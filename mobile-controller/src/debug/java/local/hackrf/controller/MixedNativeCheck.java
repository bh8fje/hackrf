package local.hackrf.controller;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Real native producers into a bounded stream; never invokes USB or RF. */
public class MixedNativeCheck extends Instrumentation {
    private Bundle args;
    private volatile MixedScene scene;
    private volatile boolean stopped;
    @Override public void onCreate(Bundle input){super.onCreate(input);args=input==null?new Bundle():input;start();}
    @Override public void onDestroy(){stopped=true;if(scene!=null)scene.close();super.onDestroy();}
    private static String text(File file)throws IOException{return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);}
    private void noProcesses(MixedScene current)throws Exception {
        java.lang.reflect.Field field=MixedScene.class.getDeclaredField("processes");field.setAccessible(true);
        List<Process> children=(List<Process>)field.get(current);if(children.size()!=3)throw new AssertionError("Expected three native processes");
        for(Process process:children)if(process.isAlive())throw new AssertionError("Native producer survived cleanup");
        for(String name:new String[]{"gps.fifo","bds.fifo"})if(new File(current.folder,name).exists())throw new AssertionError("FIFO survived cleanup");
    }
    @Override public void onStart(){
        Bundle result=new Bundle();JSONObject report=new JSONObject();File folder=null;MixedScene.Stream stream=null;
        long began=SystemClock.elapsedRealtime();boolean cancel=Boolean.parseBoolean(args.getString("cancel","false"));
        boolean paced=Boolean.parseBoolean(args.getString("paced","true"));
        ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
        watchdog.schedule(()->{stopped=true;MixedScene active=scene;if(active!=null)active.close();},180,TimeUnit.SECONDS);
        try{
            Context context=getTargetContext();
            if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())throw new IllegalStateException("请先解锁手机");
            startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            File files=context.getFilesDir().getCanonicalFile();String requested=args.getString("sourceDir","mixed-source");
            File source=(requested.startsWith("/")?new File(requested):new File(files,requested)).getCanonicalFile();
            if(!source.getPath().startsWith(files.getPath()+File.separator))throw new IOException("sourceDir必须在应用files目录内");
            String nav=text(new File(source,"broadcast-mixed.rnx"));String leaps=text(new File(source,"source-iers.dat"));
            JSONObject navigation=new JSONObject(text(new File(source,"navigation.json")));
            Instant start=Instant.parse(navigation.getString("startUTC"));
            JSONObject settings=PhoneConfig.defaults(context).put("duration",90).put("route","stroll").put("seed",42)
                    .put("radius",30).put("speed",.85).put("speedVariation",.4).put("gnssSystem","mixed");
            folder=new File(files,"mixed-native-check/"+UUID.randomUUID());
            PhoneMotion.Check check=()->{if(stopped || Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime()-began>180000)throw new InterruptedIOException("混合离线检查已取消或超过180秒");};
            scene=MixedScene.prepare(folder,new File(context.getApplicationInfo().nativeLibraryDir),settings,nav,nav,nav,nav,leaps,start,check);
            report.put("sourceDir",source.getAbsolutePath()).put("folder",folder.getAbsolutePath()).put("startUTC",start.toString())
                    .put("cancelRequested",cancel).put("paced",paced).put("rfEnabled",false).put("hardwareOutputAllowed",false)
                    .put("offlineSourceOnly",true).put("note","导航时刻仅用于离线复现，不是当前RF发送授权");
            long startup=SystemClock.elapsedRealtime();stream=scene.start(check);
            long ready=SystemClock.elapsedRealtime(),count=0;byte[] block=new byte[262144];int n;
            JSONArray gps=scene.manifest.getJSONArray("sceneSatellites"),bds=scene.manifest.getJSONArray("auxiliarySceneSatellites");
            if(gps.length()<4 || bds.length()<4)throw new AssertionError("Both actual native channel lists must contain at least four satellites");
            JSONObject receiverScene=ReceiverSync.scenario(folder,scene.manifest);
            if(receiverScene.getInt("constellationId")!=1 || receiverScene.getJSONArray("svids").length()!=gps.length() || !receiverScene.has("receiverMatchingNote"))throw new AssertionError("GPS-primary receiver matching contract lost");
            report.put("prefillMillis",ready-startup).put("sceneSatellites",gps).put("auxiliarySceneSatellites",bds)
                .put("receiverMatchingNote",receiverScene.getString("receiverMatchingNote")).put("receiverMatchingConstellationId",1);
            if(!MixedPreparation.ageValid(-60,10800)||!MixedPreparation.ageValid(10800,10800)||MixedPreparation.ageValid(-61,10800)||MixedPreparation.ageValid(10801,10800))throw new AssertionError("Navigation age boundary changed");
            NativeScene.Download currentModel=new NativeScene.Download(),candidateModel=new NativeScene.Download();
            if(MixedPreparation.selectModel(currentModel,candidateModel,10801,true)!=currentModel || MixedPreparation.selectModel(currentModel,candidateModel,0,true)!=candidateModel)throw new AssertionError("Expired model prevented source fallback");
            if(cancel){
                n=stream.read(block);if(n<=0)throw new AssertionError("No mixed bytes before cancellation");count=n;
                scene.close();noProcesses(scene);report.put("cancelVerified",true);
            }else{
                while((n=stream.read(block))!=-1){
                    check.run();
                    if(paced){
                        long due=ready+count*1000/(MixedScene.RATE*2L);
                        while(SystemClock.elapsedRealtime()<due){check.run();Thread.sleep(Math.max(1,Math.min(25,due-SystemClock.elapsedRealtime())));}
                    }
                    count+=n;
                }
                stream.verifyComplete();if(count!=scene.expectedBytes)throw new AssertionError("Mixed output length mismatch");
                report.put("nativeProcessesExitZero",true);scene.close();noProcesses(scene);
            }
            long iqBytes=0;File[] outputs=folder.listFiles();if(outputs!=null)for(File file:outputs)if(file.getName().endsWith(".bin"))iqBytes+=file.length();
            if(iqBytes!=0)throw new AssertionError("Unexpected stored IQ");
            report.put("bytes",count).put("expectedBytes",scene.expectedBytes).put("storedIQBytes",iqBytes).put("fifoCleaned",true)
                    .put("elapsedMillis",SystemClock.elapsedRealtime()-began).put("passed",true);
            MixedScene.write(folder,"native-stream-check.json",report.toString(2));
            result.putString("stream","PASS: native GPS+BDS → FIFO → mixer → offline sink; "+report.toString());finish(Activity.RESULT_OK,result);
        }catch(Throwable error){
            if(scene!=null)scene.close();
            try{report.put("passed",false).put("error",error.toString()).put("elapsedMillis",SystemClock.elapsedRealtime()-began);if(folder!=null && folder.exists())MixedScene.write(folder,"native-stream-check.json",report.toString(2));}catch(Exception ignored){}
            result.putString("stream","FAIL: "+report.toString());finish(Activity.RESULT_CANCELED,result);
        }finally{watchdog.shutdownNow();}
    }
}
