package local.hackrf.controller;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.zip.GZIPInputStream;

final class NativeScene {
    private volatile Process process;
    boolean streamingPreparation;SingleScene preparedStream;
    SingleScene prepareStream(Context context,JSONObject input,PhoneMotion.Check check,Consumer<String> status)throws Exception{streamingPreparation=true;generate(context,input,check,status,true);if(preparedStream==null)throw new IOException("未准备单系统实时流");return preparedStream;}
    void cancel() { if(preparedStream!=null)preparedStream.close();Process active=process; if (active!=null) active.destroy(); }
    static PhoneMotion parameters(JSONObject input) throws Exception {
        java.util.Set<String> known=new java.util.HashSet<>(java.util.Arrays.asList("latitude","longitude","coordinateSystem","height","heightVariation","duration","speed","radius","route","heading","horizontalDrift","verticalDrift","seed","speedVariation","speedSmoothing","randomHeading","headingMinimum","headingMaximum","headingHoldMinimum","headingHoldMaximum","turnSmoothing","heightSmoothing","driftSmoothing","minimum","maximum","mode","drift","walkMinimum","walkMaximum","pauseMinimum","pauseMaximum","reactionSeconds","signalProfile","gnssSystem"));
        for(java.util.Iterator<String> keys=input.keys();keys.hasNext();)if(!known.contains(keys.next()))throw new IOException("包含不支持的配置字段，请更新应用");
        SignalProfile.index(input.optString("signalProfile","open"));
        PhoneMotion m=new PhoneMotion();
        m.latitude=input.getDouble("latitude"); m.longitude=input.getDouble("longitude"); m.minimum=input.optDouble("minimum",400); m.maximum=input.optDouble("maximum",400);
        m.speed=input.getDouble("speed"); m.radius=input.getDouble("radius"); m.heading=input.getDouble("heading"); m.drift=input.optDouble("horizontalDrift",input.optDouble("drift",1.5)); m.verticalDrift=input.getDouble("verticalDrift");
        if(input.has("height")) {double h=input.getDouble("height"),v=input.getDouble("heightVariation");m.minimum=h-v;m.maximum=h+v;}
        m.duration=input.getInt("duration"); m.mode=input.optString("route",input.optString("mode","circle")); m.coordinateSystem=input.optString("coordinateSystem","WGS-84");
        m.seed=input.optLong("seed",42);m.randomHeading=input.optBoolean("randomHeading",true);
        m.speedVariation=input.optDouble("speedVariation",.2);m.speedSmoothing=input.optDouble("speedSmoothing",2);
        m.headingMinimum=input.optDouble("headingMinimum",0);m.headingMaximum=input.optDouble("headingMaximum",360);
        m.headingHoldMinimum=input.optDouble("headingHoldMinimum",3);m.headingHoldMaximum=input.optDouble("headingHoldMaximum",8);
        m.turnSmoothing=input.optDouble("turnSmoothing",2);m.heightSmoothing=input.optDouble("heightSmoothing",2);m.driftSmoothing=input.optDouble("driftSmoothing",10);
        if (input.getDouble("duration")!=m.duration || input.optDouble("seed",42)!=m.seed) throw new IOException("时长和随机种子必须为整数");
        m.walkMinimum=input.optDouble("walkMinimum",12);m.walkMaximum=input.optDouble("walkMaximum",35);m.pauseMinimum=input.optDouble("pauseMinimum",3);m.pauseMaximum=input.optDouble("pauseMaximum",12);m.reactionSeconds=input.optDouble("reactionSeconds",.8);
        m.validate(); return m;
    }
    static final class Download {
        String text,modified,hash,url,fetchedUTC,publishedUTC,resolvedURL,indexSHA256;boolean cached;long downloadBytes,downloadMillis,indexBytes,resumedBytes,transferredBytes;int parts=1;
        Instant produced()throws IOException{return publishedUTC==null?RinexNav.production(text):Instant.parse(publishedUTC);}
    }
    static JSONObject source(Download value)throws Exception {
        JSONObject result=new JSONObject().put("url",value.url).put("lastModified",value.modified).put("downloadSHA256",value.hash).put("cached",value.cached).put("fetchedUTC",value.fetchedUTC).put("downloadBytes",value.downloadBytes).put("downloadMillis",value.downloadMillis).put("parallelParts",value.parts).put("resumedBytes",value.resumedBytes).put("transferredBytes",value.transferredBytes);
        if(value.publishedUTC!=null)result.put("provider","Geoscience Australia").put("publishedAtUTC",value.publishedUTC).put("resolvedURL",value.resolvedURL).put("indexSHA256",value.indexSHA256).put("indexBytes",value.indexBytes);
        return result;
    }
    static String expandSource(String template,ZonedDateTime now){
        ZonedDateTime prior=now.minusDays(1),hour=now.minusHours(1),olderHour=now.minusHours(2);
        return template.replace("{year}",Integer.toString(now.getYear())).replace("{doy}",String.format(Locale.US,"%03d",now.getDayOfYear()))
            .replace("{yy}",String.format(Locale.US,"%02d",now.getYear()%100)).replace("{previousYear}",Integer.toString(prior.getYear())).replace("{previousDoy}",String.format(Locale.US,"%03d",prior.getDayOfYear()))
            .replace("{previousHourYear}",Integer.toString(hour.getYear())).replace("{previousHourDoy}",String.format(Locale.US,"%03d",hour.getDayOfYear())).replace("{previousHour}",String.format(Locale.US,"%02d",hour.getHour()))
            .replace("{olderHourYear}",Integer.toString(olderHour.getYear())).replace("{olderHourDoy}",String.format(Locale.US,"%03d",olderHour.getDayOfYear())).replace("{olderHour}",String.format(Locale.US,"%02d",olderHour.getHour()));
    }
    static byte[] read(InputStream stream, int limit, PhoneMotion.Check check) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[32768]; int n;
        while ((n=stream.read(buffer))!=-1) { check.run(); if (out.size()+n>limit) throw new IOException("下载或解压超出大小限制"); out.write(buffer,0,n); }
        return out.toByteArray();
    }
    private static Download fetch(String url, PhoneMotion.Check check) throws Exception {
        return fetch(url,check,message->{});
    }
    private static Download fetch(String url, PhoneMotion.Check check,Consumer<String> status) throws Exception {
        return fetch(url,check,status,null);
    }
    private static Download fetch(String url, PhoneMotion.Check check,Consumer<String> status,File chunkCache) throws Exception {
        long began=System.nanoTime();check.run();byte[] raw;String modified=null,requested=url;Download result=new Download();URL publicFile=null;
        if(url.startsWith("https://data.gnss.ga.gov.au/api/rinexFiles/")){
            String name=new URI(url).getPath();name=name.substring(name.lastIndexOf('/')+1);
            if(!name.matches("[A-Z0-9]{9}_R_[0-9]{11}_01H_MN\\.rnx\\.gz"))throw new IOException("澳大利亚导航请求无效");
            NavigationHttp.Result index=NavigationHttp.fetch(new URL(url),check,status);
            if(index.bytes.length>65536)throw new IOException("导航索引过大");JSONArray entries=new JSONArray(new String(index.bytes,StandardCharsets.UTF_8));
            if(entries.length()!=1)throw new IOException("没有唯一的最新小时导航文件");JSONObject entry=entries.getJSONObject(0);URI target=new URI(entry.getString("fileLocation"));
            if(!"valid".equals(entry.optString("metadataStatus"))||!"nav".equals(entry.optString("fileType"))||!"01H".equals(entry.optString("filePeriod"))||!"3".equals(entry.optString("rinexVersion"))
                ||!"https".equals(target.getScheme())||!"geodesy-archive-permanent-storage-prod.s3.ap-southeast-2.amazonaws.com".equals(target.getHost())||target.getUserInfo()!=null||(target.getPort()!=-1&&target.getPort()!=443)
                ||!target.getPath().endsWith("/"+name))throw new IOException("导航索引返回了不受信任的文件");
            Instant published=Instant.parse(entry.getString("createdAt"));if(published.isAfter(Instant.now().plusSeconds(60)))throw new IOException("导航发布时间无效");
            result.publishedUTC=published.toString();result.resolvedURL=new URI(target.getScheme(),target.getAuthority(),target.getPath(),null,null).toString();
            result.indexSHA256=ScenarioStore.hex(MessageDigest.getInstance("SHA-256").digest(index.bytes));result.indexBytes=index.bytes.length;url=target.toString();publicFile=NavigationHttp.gaPublicFile(name);
        }
        if(!url.startsWith("https://"))throw new IOException("导航数据源必须使用HTTPS");
        NavigationHttp.Result downloaded=null;
        if(publicFile!=null)try{downloaded=chunkCache==null?NavigationHttp.fetch(publicFile,check,status):NavigationChunks.fetch(publicFile,chunkCache,check,status);result.resolvedURL=publicFile.toString();}catch(IOException unavailable){check.run();status.accept("GA 公共文件暂不可用，尝试索引下载地址");}
        if(downloaded==null)downloaded=chunkCache==null?NavigationHttp.fetch(new URL(url),check,status):NavigationChunks.fetch(new URL(url),chunkCache,check,status);
        raw=downloaded.bytes;modified=downloaded.modified;
        result.parts=downloaded.parts;result.resumedBytes=downloaded.resumedBytes;result.transferredBytes=downloaded.transferredBytes+result.indexBytes;
        byte[] decoded=raw;
        if(raw.length>=2 && (raw[0]&255)==31 && (raw[1]&255)==139)try(GZIPInputStream gzip=new GZIPInputStream(new ByteArrayInputStream(raw))){decoded=read(gzip,20000000,check);}
        else if(raw.length>=2 && (raw[0]&255)==31 && (raw[1]&255)==157)decoded=UnixCompress.decode(raw,20000000,check);
        result.downloadBytes=raw.length+result.indexBytes;result.downloadMillis=(System.nanoTime()-began)/1000000;result.url=requested;result.text=new String(decoded,StandardCharsets.US_ASCII);result.modified=modified;result.fetchedUTC=Instant.now().toString();result.hash=ScenarioStore.hex(MessageDigest.getInstance("SHA-256").digest(raw));return result;
    }
    static Download cachedFetch(Context context,String url,int maximumAge,boolean force,PhoneMotion.Check check) throws Exception {
        return cachedFetch(context,url,maximumAge,force,check,false,message->{});
    }
    static Download cachedFetch(Context context,String url,int maximumAge,boolean force,PhoneMotion.Check check,Consumer<String> status) throws Exception {
        return cachedFetch(context,url,maximumAge,force,check,false,status);
    }
    private static Download cachedFetch(Context context,String url,int maximumAge,boolean force,PhoneMotion.Check check,boolean cacheOnly,Consumer<String> status) throws Exception {
        String key=ScenarioStore.hex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.US_ASCII)));
        File directory=new File(context.getFilesDir(),"navigation-cache");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建导航缓存");
        android.util.AtomicFile cache=new android.util.AtomicFile(new File(directory,key+".json"));
        if(!force)try(InputStream in=cache.openRead()){
            JSONObject stored=new JSONObject(new String(read(in,40000000,check),StandardCharsets.UTF_8));
            Download value=new Download();value.text=stored.getString("text");value.url=stored.getString("url");value.hash=stored.getString("downloadSHA256");value.modified=stored.optString("modified",null);value.fetchedUTC=stored.getString("fetchedUTC");value.downloadBytes=stored.optLong("downloadBytes");value.downloadMillis=stored.optLong("downloadMillis");
            value.parts=stored.optInt("parallelParts",1);value.resumedBytes=stored.optLong("resumedBytes");value.transferredBytes=stored.optLong("transferredBytes",value.downloadBytes);
            if(value.url.startsWith("https://data.gnss.ga.gov.au/api/rinexFiles/")){value.publishedUTC=stored.getString("publishedAtUTC");value.resolvedURL=stored.getString("resolvedURL");value.indexSHA256=stored.getString("indexSHA256");value.indexBytes=stored.getLong("indexBytes");}
            long age=Duration.between(value.produced(),Instant.now()).getSeconds();
            long fetchedAge=Duration.between(Instant.parse(stored.getString("fetchedUTC")),Instant.now()).getSeconds();
            String hash=ScenarioStore.hex(MessageDigest.getInstance("SHA-256").digest(value.text.getBytes(StandardCharsets.US_ASCII)));
            if(url.equals(value.url)&&age>=-60&&age<=maximumAge&&fetchedAge>=0&&fetchedAge<=900&&hash.equals(stored.getString("textSHA256"))){value.cached=true;value.transferredBytes=0;value.resumedBytes=value.downloadBytes;status.accept("导航数据校验通过，复用有效缓存");return value;}
        }catch(Exception missingOrInvalid){check.run();}
        if(cacheOnly)return null;
        Download value=fetch(url,check,status,new File(directory,"parts"));
        JSONArray recent=new JSONArray(context.getSharedPreferences("controller",0).getString("downloadHistory","[]"));
        for(int i=recent.length()-1;i>=0;i--)if(url.equals(recent.getJSONObject(i).optString("url")))recent.remove(i);
        recent.put(new JSONObject().put("url",url).put("bytes",value.downloadBytes).put("millis",value.downloadMillis).put("transferredBytes",value.transferredBytes).put("parts",value.parts));while(recent.length()>3)recent.remove(0);
        StringBuilder summary=new StringBuilder("最近文件下载（不含模拟样本）");
        for(int i=0;i<recent.length();i++){JSONObject row=recent.getJSONObject(i);String address=row.getString("url");summary.append(String.format(Locale.US,"\n\n%s\n文件 %.2f MB  ·  下载 %.2f MB\n%d 个下载连接  ·  用时 %.1f 秒",address.substring(address.lastIndexOf('/')+1),row.getLong("bytes")/1e6,row.optLong("transferredBytes",row.getLong("bytes"))/1e6,row.optInt("parts",1),row.getLong("millis")/1000.0));}
        context.getSharedPreferences("controller",0).edit().putString("downloadHistory",recent.toString()).putString("downloadSummary",summary.toString()).apply();
        long age=Duration.between(value.produced(),Instant.now()).getSeconds();
        if(age < -60 || age>maximumAge)throw new IOException("导航源发布时效不满足要求："+age+"秒");
        JSONObject stored=new JSONObject().put("url",url).put("text",value.text).put("modified",value.modified).put("downloadSHA256",value.hash).put("fetchedUTC",value.fetchedUTC)
            .put("downloadBytes",value.downloadBytes).put("downloadMillis",value.downloadMillis).put("textSHA256",ScenarioStore.hex(MessageDigest.getInstance("SHA-256").digest(value.text.getBytes(StandardCharsets.US_ASCII))));
        stored.put("parallelParts",value.parts).put("resumedBytes",value.resumedBytes).put("transferredBytes",value.transferredBytes);
        if(value.publishedUTC!=null)stored.put("publishedAtUTC",value.publishedUTC).put("resolvedURL",value.resolvedURL).put("indexSHA256",value.indexSHA256).put("indexBytes",value.indexBytes);
        FileOutputStream output=null;
        try{output=cache.startWrite();output.write(stored.toString().getBytes(StandardCharsets.UTF_8));cache.finishWrite(output);}
        catch(Exception failure){if(output!=null)cache.failWrite(output);throw failure;}
        return value;
    }
    void execute(ProcessBuilder builder,int timeoutSeconds,PhoneMotion.Check check) throws Exception {
        execute(builder,timeoutSeconds,check,0,message->{});
    }
    void execute(ProcessBuilder builder,int timeoutSeconds,PhoneMotion.Check check,long expectedBytes,Consumer<String> status) throws Exception {
        process=builder.start(); long began=android.os.SystemClock.elapsedRealtime(),deadline=began+timeoutSeconds*1000L,lastStatus=0;
        File output=new File(builder.directory(),"signal.bin");long previous=-1,lastProgress=android.os.SystemClock.elapsedRealtime();
        try {
            while (!process.waitFor(200,java.util.concurrent.TimeUnit.MILLISECONDS)) {
                check.run();long now=android.os.SystemClock.elapsedRealtime(),written=output.length();
                if(written!=previous){previous=written;lastProgress=now;}
                if(expectedBytes>0){
                    if(written>expectedBytes)throw new IOException("生成样本超过预期长度");
                    if(now-lastStatus>=1000){status.accept(NavigationHttp.progress("模拟样本",written,expectedBytes,now-began).replace("正在下载 ","正在生成"));lastStatus=now;}
                }
                if(written>0 && now-lastProgress>30000)throw new IOException("生成已停滞30秒；请保持应用前台或允许后台运行，检查省电限制后重试");
                if(now>deadline)throw new IOException("本机生成超时");
            }
            if (process.exitValue()!=0) throw new IOException("生成器失败，退出码 "+process.exitValue());
        } finally { if (process.isAlive()) { process.destroy(); if (!process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); } process=null; }
    }
    static final class PreparationLate extends IOException { PreparationLate(){super("需要更新数据或增加准备余量，正在重新安排");} }
    static org.json.JSONArray sceneSatellites(String log){
        java.util.TreeMap<Integer,String> rows=new java.util.TreeMap<>();java.util.regex.Matcher row=java.util.regex.Pattern.compile("^\\s*(\\d{2})\\s+(-?\\d+\\.\\d+)\\s+(-?\\d+\\.\\d+)\\s+\\d+\\.\\d+\\s+\\d+\\.\\d+\\s*$",java.util.regex.Pattern.MULTILINE).matcher(log);
        while(row.find()){int p=Integer.parseInt(row.group(1));double az=Double.parseDouble(row.group(2)),el=Double.parseDouble(row.group(3));if(p>=1 && p<=32 && az>=0 && az<=360 && el>=0 && el<=90)rows.put(p,String.format(Locale.US,"G%02d 方位%.1f° 仰角%.1f°",p,az,el));}
        return new org.json.JSONArray(rows.values());
    }
    File generate(Context context, JSONObject input, PhoneMotion.Check check, Consumer<String> status) throws Exception {return generate(context,input,check,status,false);}
    File generate(Context context, JSONObject input, PhoneMotion.Check check, Consumer<String> status,boolean planned) throws Exception {
        String system=input.optString("gnssSystem","gps");
        if(!system.equals("gps")&&!system.equals("bds"))throw new IOException("不支持的卫星系统");
        if(context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked())throw new IOException("请先解锁手机并保持控制台前台，再开始准备场景");
        for(int attempt=0;attempt<2;attempt++)try{
            return system.equals("gps")?generateAttempt(context,input,check,status,attempt>0):new SampledScene(this,system).generate(context,input,check,status,planned);
        }
        catch(PreparationLate late){check.run();if(attempt==1)throw new IOException("手机准备耗时超过安全余量，请稍后重试",late);status.accept(late.getMessage());}
        throw new IOException("无法准备场景");
    }
    static Download leapTable(Context context,Instant planned,PhoneMotion.Check check) throws Exception {
        String text=PhoneConfig.asset(context,"Leap_Second.dat");
        try{RinexNav.gpsMinusUTC(text,planned);Download cached=new Download();cached.text=text;cached.url="https://hpiers.obspm.fr/iers/bul/bulc/Leap_Second.dat";cached.cached=true;cached.hash=ScenarioStore.hex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII)));return cached;}
        catch(IOException expired){return fetch("https://hpiers.obspm.fr/iers/bul/bulc/Leap_Second.dat",check);}
    }
    private File generateAttempt(Context context, JSONObject input, PhoneMotion.Check check, Consumer<String> status,boolean retry) throws Exception {
        PhoneMotion motion=parameters(input);
        input=new JSONObject(input.toString()).put("signalProfile",SignalProfile.IDS[SignalProfile.forRoute(motion.mode)]);
        File folder=new File(context.getFilesDir(),"scenarios/"+UUID.randomUUID()); if (!folder.mkdirs()) throw new IOException("无法创建本机场景目录");
        if (folder.getUsableSpace()<(streamingPreparation?0:motion.duration*5200000L)+100000000) throw new IOException("手机空间不足");
        status.accept("正在检查有效导航缓存，必要时更新数据");
        android.content.SharedPreferences preferences=context.getSharedPreferences("controller",0);
        int lead=streamingPreparation?SingleScene.leadSeconds(context,"gps"):Preparation.leadSeconds(motion.duration,preferences.getFloat("preparationRate",.18f));
        ZonedDateTime now=Instant.now().atZone(ZoneOffset.UTC); int year=now.getYear(),day=now.getDayOfYear();
        Download local=null,broadcast=null,models;
        JSONObject policy=new JSONObject(PhoneConfig.asset(context,"navigation-sources.json"));
        JSONArray templates=new JSONArray(policy.getJSONArray("gps").toString()),shared=policy.getJSONArray("broadcast"),sourceFailures=new JSONArray();
        for(int i=0;i<shared.length();i++)templates.put(shared.getString(i));
        Set<String> modelAttempts=new HashSet<>(),failedSources=new HashSet<>();
        if(broadcast==null)for(int source=0;source<templates.length();source++){
            String url=expandSource(templates.getString(source),now);
            if(failedSources.contains(url))continue;
            try{
                status.accept("正在检查导航源 "+(source+1)+"/"+templates.length());
                Download candidate=cachedFetch(context,url,10800,retry,check,status);
                if(RinexNav.hasModels(candidate.text) && (local==null || candidate.produced().isAfter(local.produced())))local=candidate;
                long age=Duration.between(candidate.produced(),Instant.now()).getSeconds();
                if(age>1800 && candidate.cached){candidate=cachedFetch(context,url,10800,true,check,status);age=Duration.between(candidate.produced(),Instant.now()).getSeconds();if(RinexNav.hasModels(candidate.text))local=candidate;}
                if(age < -60 || age>1800)throw new IOException("发布时间超出30分钟："+age+"秒");
                Download model=RinexNav.hasModels(candidate.text)?candidate:local;if(model==null){for(int mi=0;mi<templates.length();mi++){String modelUrl=expandSource(templates.getString(mi),now);if(!modelAttempts.add(modelUrl))continue;try{Download possible=cachedFetch(context,modelUrl,10800,retry,check,status);long modelAge=Duration.between(possible.produced(),Instant.now()).getSeconds();if(RinexNav.hasModels(possible.text) && modelAge>=-60 && modelAge<=10800){model=possible;local=model;break;}}catch(Exception invalid){check.run();failedSources.add(modelUrl);sourceFailures.put(new JSONObject().put("url",modelUrl).put("error",invalid.toString()));Files.write(new File(folder,"navigation-source-audit.json").toPath(),sourceFailures.toString(2).getBytes(StandardCharsets.UTF_8));}}}if(model==null)throw new IOException("缺少完整UTC/电离层模型");
                int leap=RinexNav.gpsMinusUTC(PhoneConfig.asset(context,"Leap_Second.dat"),Instant.now().plusSeconds(810));
                RinexNav.convert(RinexNav.broadcast3(candidate.text),model.text,Instant.now().plusSeconds(lead+leap),leap,motion.duration);
                broadcast=candidate;break;
            }catch(Exception unavailable){check.run();sourceFailures.put(new JSONObject().put("url",url).put("error",unavailable.toString()));}
        }
        Files.write(new File(folder,"navigation-source-audit.json").toPath(),sourceFailures.toString(2).getBytes(StandardCharsets.UTF_8));
        if(broadcast==null)throw new IOException("已配置导航源均未满足当前时效或覆盖要求："+sourceFailures);
        models=local;
        if(models==null)throw new IOException("已配置导航源缺少完整且有效的UTC/电离层模型");
        Download leaps=leapTable(context,Instant.now().plusSeconds(810),check);
        Files.write(new File(folder,"source-broadcast.rnx").toPath(),broadcast.text.getBytes(StandardCharsets.US_ASCII));
        Files.write(new File(folder,"source-models.rnx").toPath(),models.text.getBytes(StandardCharsets.US_ASCII));
        Files.write(new File(folder,"source-iers.dat").toPath(),leaps.text.getBytes(StandardCharsets.US_ASCII));
        Instant produced=broadcast.produced();
        Instant fetched=Instant.now();long age=Duration.between(produced,fetched).getSeconds();
        if(age < -60 || age>1800)throw new IOException("广播星历来源过期："+age+"秒");
        Instant modelProduced=models.produced();
        long modelAge=Duration.between(modelProduced,fetched).getSeconds();
        if(modelAge < -60 || modelAge>10800)throw new IOException("UTC模型来源过期");
        long preparationBegan=android.os.SystemClock.elapsedRealtime();
        Instant start=fetched.plusSeconds(lead).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        int offset=RinexNav.gpsMinusUTC(leaps.text,start);
        if (RinexNav.gpsMinusUTC(leaps.text,start.plusSeconds(motion.duration))!=offset) throw new IOException("场景跨越闰秒转换");
        Instant gps=start.plusSeconds(offset);
        String navigation;
        try{navigation=RinexNav.convert(RinexNav.broadcast3(broadcast.text),models.text,gps,offset,motion.duration);}
        catch(IOException invalid){
            if(broadcast.cached || models.cached){
                // Invalid cached coverage must never be accepted just because the file is recent.
                throw new PreparationLate();
            }
            throw invalid;
        }
        Files.write(new File(folder,"broadcast-gps.rnx").toPath(),navigation.getBytes(StandardCharsets.US_ASCII));
        Files.write(new File(folder,"settings.json").toPath(),input.toString(2).getBytes(StandardCharsets.UTF_8));
        JSONArray sources=new JSONArray(); String[] urls={broadcast.url,models.url,leaps.url}; Download[] downloads={broadcast,models,leaps};
        for (int i=0;i<3;i++) sources.put(source(downloads[i]));
        JSONObject metadata=new JSONObject().put("mode","current-broadcast").put("startUTC",start.toString()).put("startGPS",DateTimeFormatter.ofPattern("uuuu/MM/dd,HH:mm:ss").withZone(ZoneOffset.UTC).format(gps))
                .put("gpsMinusUTCSeconds",offset).put("broadcastProducedAtUTC",produced.toString()).put("downloadedAtUTC",broadcast.fetchedUTC).put("validatedAtUTC",fetched.toString()).put("sources",sources)
                .put("coverageCheckedThroughUTC",start.plusSeconds(motion.duration).toString()).put("preparationLeadSeconds",lead).put("cacheRefreshSeconds",900).put("broadcastAgeSeconds",age)
                .put("modelProducedAtUTC",modelProduced.toString()).put("navigationSHA256",ScenarioStore.hex(MessageDigest.getInstance("SHA-256").digest(navigation.getBytes(StandardCharsets.US_ASCII))));
        if(streamingPreparation)metadata.put("streamTimingOriginElapsedMs",preparationBegan);
        Files.write(new File(folder,"navigation.json").toPath(),metadata.toString(2).getBytes(StandardCharsets.UTF_8));
        status.accept("星历已覆盖完整时段（"+(broadcast.cached?"使用有效缓存":"本轮下载")+"），正在生成轨迹；准备余量 "+lead+" 秒"); PhoneMotion.Trace track=motion.write(new File(folder,"emitted.csv"),check);
        JSONObject behavior=new JSONObject().put("route",motion.mode).put("distanceMeters",track.distance).put("stoppedSeconds",track.stoppedSeconds).put("stopCount",track.stops).put("startCount",track.starts).put("maximumSpeed",track.maximumSpeed).put("syntheticBehavior",true);
        Files.write(new File(folder,"motion-summary.json").toPath(),behavior.toString(2).getBytes(StandardCharsets.UTF_8));
        File binary=new File(context.getApplicationInfo().nativeLibraryDir,"libgpssim.so");
        if (!binary.isFile() || !binary.canExecute()) throw new IOException("Android原生生成器未正确安装");
        status.accept("Android原生生成器正在生成 I/Q");
        int profile=SignalProfile.index(input.optString("signalProfile","open"));
        ProcessBuilder builder=new ProcessBuilder(binary.getAbsolutePath(),"-Q",Integer.toString(profile),"-S",Long.toString(motion.seed),"-e","broadcast-gps.rnx","-u","emitted.csv","-t",metadata.getString("startGPS"),"-d",Double.toString(motion.duration+.1),"-s","2600000","-b","8","-o","signal.bin");
        builder.directory(folder); builder.redirectErrorStream(true).redirectOutput(new File(folder,"generator.log")); builder.environment().put("LC_ALL","C");
        if(streamingPreparation){
            preparedStream=new SingleScene(folder,input,metadata,"gps",builder,new JSONObject().put("profile",SignalProfile.IDS[profile]).put("label",SignalProfile.LABELS[profile]));return folder;
        }
        execute(builder,Math.max(120,motion.duration),check,motion.duration*5200000L,status);
        File iq=new File(folder,"signal.bin"); long size=motion.duration*5200000L;
        if (iq.length()!=size) throw new IOException("本机生成的样本长度不符");
        status.accept("生成完成，正在校验样本和SHA-256（本机未独立解码）");
        MessageDigest digest=MessageDigest.getInstance("SHA-256"); try (InputStream stream=new BufferedInputStream(new FileInputStream(iq))) {
            byte[] buffer=new byte[262144]; int n; while ((n=stream.read(buffer))!=-1) { check.run(); digest.update(buffer,0,n); }
        }
        long preparationMillis=android.os.SystemClock.elapsedRealtime()-preparationBegan;
        preferences.edit().putFloat("preparationRate",(float)Preparation.nextRate(motion.duration,preparationMillis,preferences.getFloat("preparationRate",.18f))).apply();
        if(Duration.between(Instant.now(),start).toMillis()<3000){if(!iq.delete())throw new IOException("准备超时且无法清理本轮未完成样本");throw new PreparationLate();}
        metadata.put("preparationMillis",preparationMillis);
        Files.write(new File(folder,"navigation.json").toPath(),metadata.toString(2).getBytes(StandardCharsets.UTF_8));
        String sourceHash; try (InputStream stream=context.getAssets().open("generator-source.sha256")) { sourceHash=new String(read(stream,100,check),StandardCharsets.US_ASCII).trim(); }
        JSONObject manifest=new JSONObject().put("schema",2).put("type","hackrf-gps-iq").put("format","s8-iq").put("sampleRate",2600000).put("frequencyHz",1575420000L)
                .put("durationSeconds",motion.duration).put("iqBytes",size).put("iqSHA256",ScenarioStore.hex(digest.digest())).put("passed",true)
                .put("origin","android-local").put("validatedBy","length-sha256").put("softwareReceiverVerified",false).put("generatorSourceSHA256",sourceHash)
                .put("sceneSatellites",sceneSatellites(new String(Files.readAllBytes(new File(folder,"generator.log").toPath()),StandardCharsets.UTF_8))).put("navigation",metadata).put("settings",input).put("signalEnvironment",new JSONObject().put("profile",SignalProfile.IDS[profile]).put("motionRoute",motion.mode).put("label",SignalProfile.LABELS[profile]).put("seed",motion.seed).put("absoluteCN0Calibrated",false));
        ScenarioStore.validate(manifest); ScenarioStore.delay(manifest,System.currentTimeMillis());
        Files.write(new File(folder,"manifest.json").toPath(),manifest.toString(2).getBytes(StandardCharsets.UTF_8));
        if (!context.getSharedPreferences("controller",0).edit().putString("scenario",folder.getName()).commit()) throw new IOException("无法保存场景选择");
        return folder;
    }
}
