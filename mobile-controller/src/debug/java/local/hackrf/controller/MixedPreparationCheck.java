package local.hackrf.controller;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.time.Instant;
import java.util.*;

/** Repeated current-data preparation and pre-RF late rebuild; never opens USB. */
public class MixedPreparationCheck extends Instrumentation {
    private Bundle args;private volatile boolean stopped;private volatile MixedScene active;
    @Override public void onCreate(Bundle values){super.onCreate(values);args=values==null?new Bundle():values;start();}
    @Override public void onDestroy(){stopped=true;if(active!=null)active.close();super.onDestroy();}
    private void closeAndCheck(MixedScene scene)throws Exception {
        scene.close();java.lang.reflect.Field field=MixedScene.class.getDeclaredField("processes");field.setAccessible(true);
        for(java.lang.Process process:(List<java.lang.Process>)field.get(scene))if(process.isAlive())throw new AssertionError("Native producer survived close");
        for(String name:new String[]{"gps.fifo","bds.fifo","signal.bin"})if(new File(scene.folder,name).exists())throw new AssertionError("Unexpected remaining "+name);
    }
    @Override public void onStart(){
        Bundle output=new Bundle();JSONArray rounds=new JSONArray();long began=SystemClock.elapsedRealtime();
        android.content.SharedPreferences prefs=getTargetContext().getSharedPreferences("controller",0);boolean hadTiming=prefs.contains("mixedStreamReadyMillis");long oldTiming=prefs.getLong("mixedStreamReadyMillis",8000);
        try{
            Context context=getTargetContext();if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())throw new IOException("请先解锁手机");
            startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            JSONObject settings=PhoneConfig.defaults(context).put("duration",90).put("gnssSystem","mixed").put("route","stroll").put("seed",42);
            PhoneMotion.Check check=()->{if(stopped||Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()-began>480000)throw new InterruptedIOException("Preparation check cancelled or timed out");};
            boolean forceLate=Boolean.parseBoolean(args.getString("forceLate","true"));String origin=null;int rebuilds=0;
            for(int round=0;round<3;round++)for(int attempt=0;attempt<2;attempt++){
                active=MixedPreparation.prepare(context,settings,check,message->{});MixedScene scene=active;
                try{
                    MixedScene.Stream stream=scene.start(check);long epoch=Instant.parse(scene.manifest.getJSONObject("navigation").getString("startUTC")).toEpochMilli();
                    if(forceLate&&round==0&&attempt==0){while(System.currentTimeMillis()<=epoch+1100){check.run();SystemClock.sleep(50);}}
                    long observed=SystemClock.elapsedRealtime()-scene.planningBeganElapsedMs;
                    prefs.edit().putLong("mixedStreamReadyMillis",Preparation.nextStreamReadyMillis(prefs.getLong("mixedStreamReadyMillis",8000),observed)).commit();
                    boolean late=Preparation.streamLate(epoch,System.currentTimeMillis());
                    String first=java.nio.file.Files.readAllLines(new File(scene.folder,"emitted.csv").toPath()).get(0);
                    if(origin==null)origin=first;else if(!origin.equals(first))throw new AssertionError("Target changed during identical-seed rebuild");
                    JSONObject row=new JSONObject().put("round",round).put("attempt",attempt).put("readyMillis",observed).put("late",late).put("folder",scene.folder.getAbsolutePath());rounds.put(row);
                    if(late){if(Preparation.retryStream(attempt,false,false,true)){rebuilds++;continue;}throw new AssertionError("Rebuilt scene still late");}
                    row.put("remainingMillis",ScenarioStore.delay(scene.manifest,System.currentTimeMillis()));
                    byte[] samples=new byte[262144];if(stream.read(samples)<=0)throw new AssertionError("No mixed samples");break;
                }finally{closeAndCheck(scene);active=null;}
            }
            if(forceLate&&rebuilds<1)throw new AssertionError("Forced pre-RF late case not exercised");
            JSONObject report=new JSONObject().put("passed",true).put("rfEnabled",false).put("rounds",rounds).put("rebuilds",rebuilds).put("elapsedMillis",SystemClock.elapsedRealtime()-began);
            MixedScene.write(context.getFilesDir(),"mixed-preparation-check.json",report.toString(2));output.putString("stream","PASS: "+report);finish(Activity.RESULT_OK,output);
        }catch(Throwable error){output.putString("stream","FAIL: "+error+" "+rounds);finish(Activity.RESULT_CANCELED,output);}
        finally{if(active!=null)active.close();if(hadTiming)prefs.edit().putLong("mixedStreamReadyMillis",oldTiming).commit();else prefs.edit().remove("mixedStreamReadyMillis").commit();}
    }
}
