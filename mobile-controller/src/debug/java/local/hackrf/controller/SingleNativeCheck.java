package local.hackrf.controller;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;
import java.io.*;

/** Current-data single native producer to bounded null sink; never opens USB or starts RF. */
public class SingleNativeCheck extends Instrumentation {
    private Bundle args;private volatile boolean stopped;private final NativeScene runner=new NativeScene();
    @Override public void onCreate(Bundle values){super.onCreate(values);args=values==null?new Bundle():values;start();}
    @Override public void onDestroy(){stopped=true;runner.cancel();super.onDestroy();}
    @Override public void onStart(){
        Bundle output=new Bundle();JSONObject report=new JSONObject();SingleScene scene=null;
        long began=SystemClock.elapsedRealtime();boolean cancel=Boolean.parseBoolean(args.getString("cancel","false"));
        try{
            String system=args.getString("system","gps");if(!system.equals("gps")&&!system.equals("bds"))throw new IOException("Only GPS or BDS single-stream check");
            Context context=getTargetContext();startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            JSONObject settings=PhoneConfig.defaults(context).put("duration",90).put("gnssSystem",system).put("route","stroll").put("seed",42);
            PhoneMotion.Check check=()->{if(stopped||Thread.currentThread().isInterrupted()||SystemClock.elapsedRealtime()-began>240000)throw new InterruptedIOException("Single-stream check stopped or timed out");};
            scene=runner.prepareStream(context,settings,check,message->{});long prepared=SystemClock.elapsedRealtime();
            MixedScene.Stream stream=scene.start(check);long ready=SystemClock.elapsedRealtime();long count=0;
            try{
                if(scene.manifest.getJSONArray("sceneSatellites").length()<4)throw new AssertionError("Insufficient actual native satellites");
                JSONObject receiver=ReceiverSync.scenario(scene.folder,scene.manifest);
                if(receiver.getInt("constellationId")!=(system.equals("gps")?1:5))throw new AssertionError("Receiver constellation mismatch");
                byte[] block=new byte[262144];int n;
                if(cancel){n=stream.read(block);if(n<=0)throw new AssertionError("No samples before cancel");count=n;scene.close();}
                else{while((n=stream.read(block))!=-1){check.run();count+=n;}stream.verifyComplete();if(count!=scene.manifest.getLong("iqBytes"))throw new AssertionError("Single stream length mismatch");}
                if(new File(scene.folder,"signal.bin").exists())throw new AssertionError("Single auto stream saved full IQ");
                report.put("system",system).put("bytes",count).put("expectedBytes",scene.manifest.getLong("iqBytes")).put("prefillMillis",ready-prepared)
                    .put("navigationMillis",prepared-began).put("elapsedMillis",SystemClock.elapsedRealtime()-began).put("storedIQBytes",0).put("rfEnabled",false)
                    .put("cancelRequested",cancel).put("transportComplete",!cancel&&count==scene.manifest.getLong("iqBytes")).put("passed",true).put("folder",scene.folder.getAbsolutePath());
            }finally{stream.close();}
            if(!scene.isClosed()||new File(scene.folder,"signal.fifo").exists())throw new AssertionError("Single stream cleanup incomplete");
            MixedScene.write(scene.folder,"single-stream-check.json",report.toString(2));
            output.putString("stream","PASS: "+report);finish(Activity.RESULT_OK,output);
        }catch(Throwable error){try{report.put("passed",false).put("error",error.toString());}catch(JSONException impossible){throw new RuntimeException(impossible);}output.putString("stream","FAIL: "+report);finish(Activity.RESULT_CANCELED,output);}
        finally{if(scene!=null)scene.close();runner.cancel();}
    }
}
