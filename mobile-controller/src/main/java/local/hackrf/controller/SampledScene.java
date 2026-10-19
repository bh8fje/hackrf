package local.hackrf.controller;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Shared sampled-motion BDS B1I, Galileo E1 and GLONASS G1 preparation. */
final class SampledScene {
    private final NativeScene runner;
    private final boolean galileo,glonass,bds;
    private final String id,label,signal,navName,rateKey;
    private final int rate;
    private final long frequency;
    SampledScene(NativeScene runner,String system){
        this.runner=runner;id=system;galileo=id.equals("galileo");glonass=id.equals("glonass");bds=id.equals("bds");
        if(!galileo&&!glonass&&!bds)throw new IllegalArgumentException("不支持的导航系统");label=galileo?"伽利略":glonass?"格洛纳斯":"北斗";
        signal=galileo?"GAL-E1":glonass?"GLO-G1":"BDS-B1I";navName="broadcast-"+id+".rnx";rateKey=galileo||bds?id+"ParallelPreparationRate":id+"PreparationRate";
        rate=galileo?16000000:glonass?10000000:5000000;frequency=galileo?1575420000L:glonass?1602000000L:1561098000L;
    }
    private String convert(String broadcast,String models,Instant start,int offset,int duration)throws IOException {
        return galileo?GalileoNavigation.convert(broadcast,models,start,offset,duration):glonass?GlonassNavigation.convert(broadcast,models,start,offset,duration):BdsNavigation.convert(broadcast,models,start,offset,duration);
    }
    private static JSONObject source(NativeScene.Download d) throws Exception {
        return NativeScene.source(d);
    }
    static JSONObject configuration(PhoneMotion m,Instant start,double[] xyz,boolean galileo) throws Exception {
        return configuration(m,start,xyz,galileo?"galileo":"bds");
    }
    static ProcessBuilder generationCommand(File binary,String system,int profile,long seed){
        boolean galileo=system.equals("galileo"),parallel=galileo||system.equals("bds");
        ProcessBuilder command=new ProcessBuilder(binary.getAbsolutePath(),"-c","scenario.json","-u","emitted.csv",parallel?"-mt":"-st");
        if(galileo||system.equals("bds"))command.command().addAll(Arrays.asList("-Q",Integer.toString(profile),"-S",Long.toString(seed)));
        if(parallel){command.environment().put("OMP_NUM_THREADS","4");command.environment().put("OMP_WAIT_POLICY","PASSIVE");command.environment().put("OMP_DYNAMIC","FALSE");}
        command.environment().put("LC_ALL","C");return command;
    }
    static JSONObject configuration(PhoneMotion m,Instant start,double[] xyz,String id) throws Exception {
        boolean galileo=id.equals("galileo"),glonass=id.equals("glonass");
        if(!galileo&&!glonass&&!id.equals("bds"))throw new IllegalArgumentException("不支持的导航系统");
        ZonedDateTime utc=start.atZone(ZoneOffset.UTC);
        JSONObject time=new JSONObject().put("type","UTC").put("year",utc.getYear()).put("month",utc.getMonthValue()).put("day",utc.getDayOfMonth()).put("hour",utc.getHour()).put("minute",utc.getMinute()).put("second",utc.getSecond());
        JSONObject position=new JSONObject().put("type","ECEF").put("x",xyz[0]).put("y",xyz[1]).put("z",xyz[2]);
        JSONObject trajectory=new JSONObject().put("name","sampled current GNSS trajectory").put("initPosition",position)
                .put("initVelocity",new JSONObject().put("type","SCU").put("speed",0).put("course",0))
                .put("trajectoryList",new JSONArray().put(new JSONObject().put("type","Const").put("time",m.duration)));
        JSONArray systems=new JSONArray();for(String s:new String[]{"GPS","Galileo","GLONASS","BDS"}){
            boolean enabled=s.equals(galileo?"Galileo":glonass?"GLONASS":"BDS");JSONObject entry=new JSONObject().put("system",s).put("enable",enabled);
            if(enabled)entry.put("signal",galileo?"E1":glonass?"G1":"B1I");systems.put(entry);
        }
        return new JSONObject().put("version",1.0).put("time",time).put("trajectory",trajectory)
                .put("ephemeris",new JSONObject().put("type","RINEX").put("name","broadcast-"+id+".rnx"))
                .put("output",new JSONObject().put("type","IFdata").put("format","IQ8").put("sampleFreq",galileo?16:glonass?10:5).put("centerFreq",galileo?1575.42:glonass?1602.0:1561.098).put("name","signal.bin").put("config",new JSONObject().put("elevationMask",5)).put("systemSelect",systems))
                .put("power",new JSONObject().put("noiseFloor",-172).put("initPower",new JSONObject().put("unit","dBHz").put("value",47)).put("elevationAdjust",false));
    }
    File generate(Context context,JSONObject input,PhoneMotion.Check check,Consumer<String> status,boolean planned) throws Exception {
        PhoneMotion motion=NativeScene.parameters(input);
        int profile=SignalProfile.forRoute(motion.mode);
        if(galileo||bds)input=new JSONObject(input.toString()).put("signalProfile",SignalProfile.IDS[profile]);
        String preparationKey=rateKey+(galileo||bds?"-"+SignalProfile.IDS[profile]:"");
        final String profileKey=preparationKey;
        if(motion.duration<90)throw new IOException((""+label+"冷启动基准请使用90–600秒"));
        File folder=new File(context.getFilesDir(),(planned?"scenarios/":id+"-experiments/")+UUID.randomUUID());if(!folder.mkdirs())throw new IOException(("无法创建"+label+"场景目录"));
        if(folder.getUsableSpace()<(runner.streamingPreparation?0:motion.duration*(long)rate*2)+500000000L)throw new IOException((""+label+"I/Q所需空间不足"));
        status.accept(("正在获取最新"+label+"轨道与导航模型，不使用历史星历"));
        ZonedDateTime now=Instant.now().atZone(ZoneOffset.UTC),prior=now.minusDays(1);
        JSONObject sourcePolicy=new JSONObject(PhoneConfig.asset(context,"navigation-sources.json"));
        LinkedHashSet<String> ordered=new LinkedHashSet<>();
        if(bds){JSONArray domestic=sourcePolicy.optJSONArray("bds");if(domestic!=null)for(int i=0;i<domestic.length();i++)ordered.add(domestic.getString(i));}
        JSONArray shared=sourcePolicy.getJSONArray("broadcast");for(int i=0;i<shared.length();i++){
            if(glonass&&shared.getString(i).contains("BRDC00WRD_S_"))ordered.add("https://igs.bkg.bund.de/root_ftp/NTRIP/BRDC/brdc_last.rnx.Z");
            ordered.add(shared.getString(i));
        }
        JSONArray templates=new JSONArray(new ArrayList<>(ordered)),failures=new JSONArray();
        NativeScene.Download broadcast=null,models=null;List<NativeScene.Download> candidates=new ArrayList<>();
        android.content.SharedPreferences prefs=context.getSharedPreferences("controller",0);
        int lead=runner.streamingPreparation?SingleScene.leadSeconds(context,id):planned?Preparation.bdsLeadSeconds(motion.duration,prefs.getFloat(preparationKey,galileo?8f:glonass?6f:2.4f)):0;
        sourceLoop:for(int i=0;i<templates.length();i++){
            if(templates.getString(i).contains("{previous") && models!=null && !candidates.isEmpty())continue;
            String url=NativeScene.expandSource(templates.getString(i),now);
            if(!url.startsWith("https://igs.bkg.bund.de/")&&!url.startsWith("https://data.gnss.ga.gov.au/api/rinexFiles/")&&!( bds&&url.startsWith("https://www.csno-tarc.cn/data/upload/brdc/")))continue;
            try{
                NativeScene.Download d=NativeScene.cachedFetch(context,url,10800,candidates.isEmpty(),check,status);
                long age=Duration.between(d.produced(),Instant.now()).getSeconds();
                if((galileo?(d.text.contains("GAL ")&&d.text.contains("GAUT")&&d.text.contains("GPSA")&&d.text.contains("GPSB")):glonass?(d.text.contains("GLUT")&&d.text.contains("GLGP")&&d.text.contains("GPSA")&&d.text.contains("GPSB")):(d.text.contains("BDSB")&&d.text.contains("BDUT")))&&(models==null||d.produced().isAfter(models.produced())))models=d;
                if(age>=-60&&age<=1800){
                    if(glonass&&!url.contains("BRDC00WRD_R_"))candidates.add(0,d);else candidates.add(d);
                }
                if(models!=null){
                    Instant trial=Instant.now().plusSeconds(lead);
                    int trialOffset=RinexNav.gpsMinusUTC(PhoneConfig.asset(context,"Leap_Second.dat"),trial);
                    for(NativeScene.Download candidate:candidates)try{
                        // R supplies models first; inspect rolling GLO orbits before accepting its partial live archive.
                        if(glonass&&candidate.url.contains("BRDC00WRD_R_"))continue;
                        convert(candidate.text,models.text,trial,trialOffset,motion.duration);break sourceLoop;
                    }catch(IOException incomplete){check.run();}
                }
            }catch(Exception e){check.run();failures.put(new JSONObject().put("url",url).put("error",e.toString()));}
        }
        Files.write(new File(folder,"navigation-source-audit.json").toPath(),failures.toString(2).getBytes(StandardCharsets.UTF_8));
        if(candidates.isEmpty()||models==null)throw new IOException(("没有时效合格的"+label+"轨道与导航模型：")+failures);
        long preparationBegan=android.os.SystemClock.elapsedRealtime();
        Instant validated=Instant.now(),start=validated.plusSeconds(lead).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        long modelAge=Duration.between(models.produced(),validated).getSeconds();
        if(modelAge < -60||modelAge>10800)throw new IOException((""+label+"数据在准备期间过期"));
        NativeScene.Download leaps=NativeScene.leapTable(context,start.plusSeconds(motion.duration),check);
        int offset=RinexNav.gpsMinusUTC(leaps.text,start);if(RinexNav.gpsMinusUTC(leaps.text,start.plusSeconds(motion.duration))!=offset)throw new IOException((""+label+"场景跨越闰秒"));
        String navigation=null;
        for(NativeScene.Download candidate:candidates)try {
            long age=Duration.between(candidate.produced(),validated).getSeconds();
            if(age < -60||age>1800)throw new IOException((""+label+"轨道在准备期间过期"));
            navigation=convert(candidate.text,models.text,start,offset,motion.duration);broadcast=candidate;break;
        }catch(Exception e){check.run();failures.put(new JSONObject().put("url",candidate.url).put("error",e.toString()));}
        Files.write(new File(folder,"navigation-source-audit.json").toPath(),failures.toString(2).getBytes(StandardCharsets.UTF_8));
        if(broadcast==null)throw new IOException(("没有当前"+label+"数据源覆盖整个场景：")+failures);
        for(String name:new String[]{"source-broadcast.rnx","source-models.rnx","source-iers.dat",navName}){
            String text=name.equals("source-broadcast.rnx")?broadcast.text:name.equals("source-models.rnx")?models.text:name.equals("source-iers.dat")?leaps.text:navigation;
            Files.write(new File(folder,name).toPath(),text.getBytes(StandardCharsets.US_ASCII));
        }
        JSONObject timing=new JSONObject().put("mode","current-broadcast").put("signal",signal).put("startUTC",start.toString()).put("validatedAtUTC",validated.toString())
                .put("broadcastProducedAtUTC",broadcast.produced().toString()).put("modelProducedAtUTC",models.produced().toString())
                .put("gpsMinusUTCSeconds",offset).put("coverageCheckedThroughUTC",start.plusSeconds(motion.duration).toString()).put("preparationLeadSeconds",lead).put("sources",new JSONArray().put(source(broadcast)).put(source(models)));
        if(bds)timing.put("bdtMinusUTCSeconds",offset-14);
        PhoneMotion.Trace trace=motion.trace(check);
        JSONObject trajectoryHashes=new JSONObject();
        for(String name:new String[]{"ideal.csv","emitted.csv"}) {
            File file=new File(folder,name);motion.writePoints(file,name.equals("ideal.csv")?trace.ideal:trace.emitted,check);
            trajectoryHashes.put(name,ScenarioStore.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()))));
        }
        String[] first=new String(Files.readAllBytes(new File(folder,"emitted.csv").toPath()),StandardCharsets.US_ASCII).split("\\n",2)[0].split(",");
        double[] emitted={Double.parseDouble(first[1]),Double.parseDouble(first[2]),Double.parseDouble(first[3])};
        JSONObject experiment=new JSONObject().put("schema",2).put("type",id+"-research-iq").put("signal",signal).put("mode","current-broadcast").put("hardwareOutputAllowed",false)
                .put("softwareReceiverVerified",false).put("navigation",timing).put("durationSeconds",motion.duration).put("sampleRate",rate).put("frequencyHz",frequency)
                .put("trajectorySHA256",trajectoryHashes).put("settings",input)
                .put("generator",new JSONObject(PhoneConfig.asset(context,"bds-generator-provenance.json")));
        if(galileo||bds)experiment.put("signalEnvironment",new JSONObject().put("profile",SignalProfile.IDS[profile]).put("profileIndex",profile).put("label",SignalProfile.LABELS[profile]).put("motionRoute",motion.mode).put("seed",motion.seed).put("absoluteCN0Calibrated",false));
        Files.write(new File(folder,"navigation.json").toPath(),timing.toString(2).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(folder,"scenario.json").toPath(),configuration(motion,start,emitted,id).toString(2).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(folder,"experiment.json").toPath(),experiment.toString(2).getBytes(StandardCharsets.UTF_8));
        File binary=new File(context.getApplicationInfo().nativeLibraryDir,"libbdssim.so");if(!binary.canExecute())throw new IOException((""+label+"原生生成器未安装"));
        status.accept("正在核验"+label+"可见卫星与原生信号通道");
        ProcessBuilder preflight=new ProcessBuilder(binary.getAbsolutePath(),"-c","scenario.json","-u","emitted.csv","-vo","-st");preflight.directory(folder);preflight.redirectErrorStream(true).redirectOutput(new File(folder,"visibility.log"));runner.execute(preflight,30,check);
        int[] visible=SampledSatellites.ids(new String(Files.readAllBytes(new File(folder,"visibility.log").toPath()),StandardCharsets.UTF_8),id);
        if(new File(folder,"signal.bin").exists())throw new IOException("原生预检意外写入模拟样本");
        if(bds){
            float priorRate=prefs.getFloat(profileKey,2.4f);
            preparationKey=profileKey+"-n"+visible.length;
            double channelRate=prefs.getFloat(preparationKey,(float)Preparation.channelRate(priorRate,prefs.getInt(profileKey+"Channels",0),visible.length));
            int channelLead=runner.streamingPreparation?lead:planned?Preparation.bdsLeadSeconds(motion.duration,channelRate):0;
            if(planned&&channelLead!=lead){
                start=Instant.now().plusSeconds(channelLead).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);lead=channelLead;
                if(RinexNav.gpsMinusUTC(leaps.text,start)!=offset||RinexNav.gpsMinusUTC(leaps.text,start.plusSeconds(motion.duration))!=offset)throw new IOException("重新规划的场景跨越闰秒");
                navigation=convert(broadcast.text,models.text,start,offset,motion.duration);
                Files.write(new File(folder,navName).toPath(),navigation.getBytes(StandardCharsets.US_ASCII));
                timing.put("startUTC",start.toString()).put("coverageCheckedThroughUTC",start.plusSeconds(motion.duration).toString()).put("preparationLeadSeconds",lead);
                Files.write(new File(folder,"scenario.json").toPath(),configuration(motion,start,emitted,id).toString(2).getBytes(StandardCharsets.UTF_8));
                Files.write(new File(folder,"navigation.json").toPath(),timing.toString(2).getBytes(StandardCharsets.UTF_8));
                Files.write(new File(folder,"experiment.json").toPath(),experiment.toString(2).getBytes(StandardCharsets.UTF_8));
                runner.execute(preflight,30,check);
                visible=SampledSatellites.ids(new String(Files.readAllBytes(new File(folder,"visibility.log").toPath()),StandardCharsets.UTF_8),id);
                preparationKey=profileKey+"-n"+visible.length;
                status.accept("已按 "+visible.length+" 颗可见北斗卫星更新准备计划");
            }
            timing.put("preparationChannels",visible.length);
        }

        JSONArray satellites=new JSONArray();for(int svid:visible)satellites.put(String.format(Locale.US,galileo?"E%02d":glonass?"R%02d":"C%02d",svid));experiment.put("sceneSatellites",satellites);
        status.accept(planned?("Android原生生成器正在准备"+label+"I/Q，通过自检后计划发送"):("Android原生生成器正在生成"+label+"运动轨迹I/Q；无USB或射频输出"));
        ProcessBuilder builder=generationCommand(binary,id,profile,motion.seed);builder.directory(folder);builder.redirectErrorStream(true).redirectOutput(new File(folder,"generator.log"));
        if(runner.streamingPreparation){
            long planningBegan=android.os.SystemClock.elapsedRealtime();
            start=Instant.now().plusSeconds(SingleScene.leadSeconds(context,id)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            int streamOffset=RinexNav.gpsMinusUTC(leaps.text,start);
            if(RinexNav.gpsMinusUTC(leaps.text,start.plusSeconds(motion.duration))!=streamOffset)throw new IOException("单系统实时流跨越闰秒");
            long streamAge=java.time.Duration.between(broadcast.produced(),Instant.now()).getSeconds(),modelAgeNow=java.time.Duration.between(models.produced(),Instant.now()).getSeconds();
            if(streamAge < -60||streamAge>1800||modelAgeNow < -60||modelAgeNow>10800)throw new IOException("单系统实时流导航来源已过期");
            navigation=convert(broadcast.text,models.text,start,streamOffset,motion.duration);
            Files.write(new File(folder,navName).toPath(),navigation.getBytes(StandardCharsets.US_ASCII));
            timing.put("startUTC",start.toString()).put("coverageCheckedThroughUTC",start.plusSeconds(motion.duration).toString()).put("gpsMinusUTCSeconds",streamOffset).put("bdtMinusUTCSeconds",streamOffset-14).put("streamTimingOriginElapsedMs",planningBegan);
            Files.write(new File(folder,"navigation.json").toPath(),timing.toString(2).getBytes(StandardCharsets.UTF_8));
            JSONObject streamConfig=configuration(motion,start,emitted,id);streamConfig.getJSONObject("output").put("name","signal.fifo");
            Files.write(new File(folder,"scenario.json").toPath(),streamConfig.toString(2).getBytes(StandardCharsets.UTF_8));
            runner.preparedStream=new SingleScene(folder,input,timing,id,builder,experiment.optJSONObject("signalEnvironment"));return folder;
        }
        runner.execute(builder,Math.max(300,motion.duration*(galileo?24:8)),check,motion.duration*(long)rate*2,status);
        if(!Arrays.equals(visible,SampledSatellites.ids(new String(Files.readAllBytes(new File(folder,"generator.log").toPath()),StandardCharsets.UTF_8),id)))throw new IOException("生成通道与预检卫星不一致");
        File iq=new File(folder,"signal.bin");long expected=motion.duration*(long)rate*2;if(iq.length()!=expected)throw new IOException((""+label+"样本长度不符：")+iq.length()+" / "+expected);
        status.accept(("正在校验"+label+"样本和SHA-256（手机未独立解码）"));
        MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream stream=new BufferedInputStream(new FileInputStream(iq))){byte[] buffer=new byte[262144];int n;while((n=stream.read(buffer))!=-1){check.run();hash.update(buffer,0,n);}}
        experiment.put("iqBytes",expected).put("iqSHA256",ScenarioStore.hex(hash.digest()));
        long elapsed=android.os.SystemClock.elapsedRealtime()-preparationBegan;
        float measuredRate=(float)Preparation.nextRate(motion.duration,elapsed,prefs.getFloat(preparationKey,galileo?8f:glonass?6f:2.4f));
        android.content.SharedPreferences.Editor calibration=prefs.edit().putFloat(preparationKey,measuredRate);
        if(bds)calibration.putFloat(profileKey,measuredRate).putInt(profileKey+"Channels",visible.length);
        calibration.commit();
        timing.put("preparationMillis",elapsed);
        Files.write(new File(folder,"navigation.json").toPath(),timing.toString(2).getBytes(StandardCharsets.UTF_8));
        Files.write(new File(folder,"experiment.json").toPath(),experiment.toString(2).getBytes(StandardCharsets.UTF_8));
        if(planned) {
            if(Duration.between(Instant.now(),start).toMillis()<3000)throw new NativeScene.PreparationLate();
            JSONObject hashes=new JSONObject();for(String name:new String[]{"scenario.json",navName,"emitted.csv"})hashes.put(name,ScenarioStore.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(new File(folder,name).toPath()))));
            JSONObject manifest=new JSONObject().put("schema",2).put("type","hackrf-"+id+"-iq").put("format","s8-iq").put("sampleRate",rate).put("frequencyHz",frequency)
                .put("durationSeconds",motion.duration).put("iqBytes",expected).put("iqSHA256",experiment.getString("iqSHA256")).put("passed",true).put("origin","android-local").put("validatedBy","length-sha256")
                .put("softwareReceiverVerified",false).put("generatorSourceSHA256",experiment.getJSONObject("generator").getString("patchedSourceSHA256")).put("navigation",timing).put("settings",input)
                .put("hardwareOutputAllowed",true).put("plannedTransmission",true).put("preparedFilesSHA256",hashes).put("sceneSatellites",satellites);
            if(galileo||bds)manifest.put("signalEnvironment",experiment.getJSONObject("signalEnvironment"));
            Files.write(new File(folder,"manifest.json").toPath(),manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            ScenarioStore.manifest(folder);ScenarioStore.delay(manifest,System.currentTimeMillis());
            if(!prefs.edit().putString("scenario",folder.getName()).commit())throw new IOException(("无法保存"+label+"场景"));
        } else prefs.edit().putString(id+"Experiment",folder.getName()).apply();
        return folder;
    }
}
