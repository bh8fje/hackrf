package local.hackrf.controller;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.Spinner;
import org.json.*;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;

/** Current-data native generation with Activity kept visible; never requests RF. */
public class SampledGenerationCheck extends Instrumentation {
    private String system;
    private boolean validateOnly;
    @Override public void onCreate(Bundle args){super.onCreate(args);system=args.getString("system","bds");validateOnly=Boolean.parseBoolean(args.getString("validateOnly","false"));start();}
    @Override public void onStart(){Bundle out=new Bundle();Activity activity=null;boolean owned=false;
        try{
            validateProfiles();
            if(validateOnly){out.putString("stream","PASS: BDS/Galileo/GLONASS 90/180-second manifest bounds, 64-bit IQ sizes and invalid metadata rejection; RF disabled");finish(Activity.RESULT_OK,out);return;}
            if(!system.equals("bds"))throw new IllegalArgumentException("Unsupported system");
            boolean galileo=system.equals("galileo"),glonass=system.equals("glonass");long expected=galileo?2880000000L:glonass?1800000000L:900000000L;
            Context c=getTargetContext();if(TransmitService.busy)throw new IllegalStateException("Existing task is running");
            activity=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.lang.reflect.Method selector=MainActivity.class.getDeclaredMethod("selectSystem",int.class);selector.setAccessible(true);Activity opened=activity;
            runOnMainSync(()->{try{selector.invoke(opened,1);}catch(Exception e){throw new RuntimeException(e);}});
            JSONObject input=PhoneConfig.defaults(c).put("gnssSystem",system).put("duration",90).put("seed",42)
                .put("route","stationary").put("speed",0).put("speedVariation",0).put("heightVariation",0).put("horizontalDrift",0).put("verticalDrift",0);
            runOnMainSync(()->opened.startForegroundService(new Intent(c,TransmitService.class).setAction(TransmitService.GENERATE).putExtra("settings",input.toString()).putExtra("autoTransmit",false)));owned=true;
            for(int i=0;i<50&&!TransmitService.busy;i++)SystemClock.sleep(100);
            if(!TransmitService.busy)throw new AssertionError("Generation did not start");
            long deadline=SystemClock.elapsedRealtime()+2250000,last=0;
            while(TransmitService.busy&&SystemClock.elapsedRealtime()<deadline){
                SystemClock.sleep(250);
                if(SystemClock.elapsedRealtime()-last>10000){Bundle progress=new Bundle();progress.putString("stream",TransmitService.status+"\n");sendStatus(0,progress);last=SystemClock.elapsedRealtime();}
            }
            if(TransmitService.busy||!TransmitService.status.startsWith("本机生成完成"))throw new AssertionError(TransmitService.status+"; "+TransmitService.lastOutcome);
            owned=false;String id=c.getSharedPreferences("controller",0).getString(system+"Experiment","");
            if(!id.matches("[a-f0-9-]{36}"))throw new AssertionError("Missing experiment");
            File folder=new File(c.getFilesDir(),system+"-experiments/"+id);
            JSONObject result=new JSONObject(new String(Files.readAllBytes(new File(folder,"experiment.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
            if(!result.getString("signal").equals(galileo?"GAL-E1":glonass?"GLO-G1":"BDS-B1I")||result.getInt("sampleRate")!=(galileo?16000000:glonass?10000000:5000000)||result.getLong("frequencyHz")!=(galileo?1575420000L:glonass?1602000000L:1561098000L)
                ||result.getLong("iqBytes")!=expected||new File(folder,"signal.bin").length()!=expected
                ||result.getBoolean("hardwareOutputAllowed")||result.getBoolean("softwareReceiverVerified"))throw new AssertionError("Galileo metadata or hardware gate");
            JSONObject config=new JSONObject(new String(Files.readAllBytes(new File(folder,"scenario.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));JSONArray systems=config.getJSONObject("output").getJSONArray("systemSelect");int enabled=0;
            for(int i=0;i<systems.length();i++)if(systems.getJSONObject(i).getBoolean("enable")){enabled++;if(!systems.getJSONObject(i).getString("system").equals(galileo?"Galileo":glonass?"GLONASS":"BDS"))throw new AssertionError("Wrong constellation");}
            if(enabled!=1)throw new AssertionError("Mixed constellation output");
            String generatorLog=new String(Files.readAllBytes(new File(folder,"generator.log").toPath()),java.nio.charset.StandardCharsets.UTF_8);
            if((galileo||system.equals("bds"))&&!generatorLog.contains("PARALLEL execution (4 threads"))throw new AssertionError("Galileo parallel generator not active");
            if((galileo||system.equals("bds"))&&(result.getJSONObject("signalEnvironment").getInt("profileIndex")!=3||!generatorLog.contains("ENV RANK")))throw new AssertionError("Galileo office environment not active");
            out.putString("stream","PASS: Android "+system+" current-data 90s/"+expected+" bytes; foreground; RF disabled; folder="+id);finish(Activity.RESULT_OK,out);
        }catch(Throwable e){out.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,out);}
        finally{if(owned)getTargetContext().startService(new Intent(getTargetContext(),TransmitService.class).setAction(TransmitService.STOP));if(activity!=null){Activity opened=activity;runOnMainSync(opened::finish);}}
    }
    private static void validateProfiles() throws Exception {
        for(String route:SignalProfile.ROUTES){
            int profile=SignalProfile.forRoute(route);ProcessBuilder command=SampledScene.generationCommand(new File("generator"),"galileo",profile,42);
            java.util.List<String> args=command.command();
            if(!args.get(args.indexOf("-Q")+1).equals(Integer.toString(profile))||!args.get(args.indexOf("-S")+1).equals("42")||!args.contains("-mt")||!"4".equals(command.environment().get("OMP_NUM_THREADS"))||!"FALSE".equals(command.environment().get("OMP_DYNAMIC")))throw new AssertionError("Galileo route environment command mismatch: "+route);
        }
        for(String route:SignalProfile.ROUTES){
            int profile=SignalProfile.forRoute(route);ProcessBuilder command=SampledScene.generationCommand(new File("generator"),"bds",profile,42);
            java.util.List<String> args=command.command();
            if(!args.get(args.indexOf("-Q")+1).equals(Integer.toString(profile))||!args.get(args.indexOf("-S")+1).equals("42")||!args.contains("-mt")||!"4".equals(command.environment().get("OMP_NUM_THREADS")))throw new AssertionError("BDS route environment command mismatch: "+route);
        }

        String[] ids={"bds","galileo","glonass"};int[] rates={5000000,16000000,10000000};long[] frequencies={1561098000L,1575420000L,1602000000L};
        java.time.Instant start=java.time.Instant.now().plusSeconds(300);
        for(int i=0;i<ids.length;i++)for(int duration:(i==0?new int[]{90,180,600}:new int[]{90,180})){
            long bytes=duration*(long)rates[i]*2;
            if(i==1&&duration==180&&bytes!=5760000000L)throw new AssertionError("IQ size overflow");
            JSONObject navigation=new JSONObject().put("mode","current-broadcast").put("startUTC",start.toString()).put("broadcastProducedAtUTC",start.minusSeconds(300).toString()).put("coverageCheckedThroughUTC",start.plusSeconds(duration).toString());
            JSONObject m=new JSONObject().put("schema",2).put("type","hackrf-"+ids[i]+"-iq").put("format","s8-iq")
                .put("origin","android-local").put("validatedBy","length-sha256").put("softwareReceiverVerified",false)
                .put("generatorSourceSHA256",new String(new char[64]).replace('\0','a')).put("iqSHA256",new String(new char[64]).replace('\0','b'))
                .put("durationSeconds",duration).put("sampleRate",rates[i]).put("frequencyHz",frequencies[i]).put("iqBytes",bytes)
                .put("passed",true).put("navigation",navigation).put("hardwareOutputAllowed",true).put("plannedTransmission",true);
            ScenarioStore.validate(m);
            m.put("iqBytes",bytes-2);try{ScenarioStore.validate(m);throw new AssertionError("Truncated IQ accepted");}catch(java.io.IOException expected){}m.put("iqBytes",bytes);
            m.put("frequencyHz",frequencies[i]+1);try{ScenarioStore.validate(m);throw new AssertionError("Wrong carrier accepted");}catch(java.io.IOException expected){}
        }
    }

}
