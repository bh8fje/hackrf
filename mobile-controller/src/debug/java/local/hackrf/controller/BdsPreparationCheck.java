package local.hackrf.controller;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.file.Files;
import org.json.*;

/** Planned BDS generation and preflight only; never calls transmit or opens USB. */
public class BdsPreparationCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();Context c=getTargetContext();
        android.content.SharedPreferences prefs=c.getSharedPreferences("controller",0);String prior=prefs.getString("scenario",null);
        PowerManager.WakeLock wake=c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"walklab:bds-preflight-check");
        Activity activity=null;
        try{
            if(TransmitService.busy)throw new IllegalStateException("Task active");wake.acquire(900000);
            activity=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            JSONObject input=PhoneConfig.defaults(c).put("gnssSystem","bds").put("duration",90).put("seed",42).put("route","stationary").put("speed",0).put("heightVariation",0).put("horizontalDrift",0).put("verticalDrift",0);
            File folder=new NativeScene().generate(c,input,()->{},message->{Bundle p=new Bundle();p.putString("stream",message+"\n");sendStatus(0,p);},true);
            JSONObject m=ScenarioStore.manifest(folder);ScenarioStore.delay(m,System.currentTimeMillis());
            if(!ScenarioStore.isBds(m)||m.getLong("iqBytes")!=900000000||new File(folder,"signal.bin").length()!=900000000||m.getBoolean("softwareReceiverVerified"))throw new AssertionError("BDS planned manifest");
            for(String key:new String[]{"sampleRate","frequencyHz","iqBytes","hardwareOutputAllowed","plannedTransmission","generatorSourceSHA256"}){
                JSONObject bad=new JSONObject(m.toString());bad.put(key,key.equals("sampleRate")?2600000:key.equals("frequencyHz")?1575420000L:key.equals("iqBytes")?1:key.endsWith("Allowed")||key.equals("plannedTransmission")?false:"bad");
                try{ScenarioStore.validate(bad);throw new AssertionError("Accepted invalid "+key);}catch(IOException|JSONException expected){}
            }
            try{ScenarioStore.delay(m,java.time.Instant.parse(m.getJSONObject("navigation").getString("startUTC")).toEpochMilli()+2000);throw new AssertionError("Stale scene accepted");}catch(IOException expected){}
            File trajectory=new File(folder,"emitted.csv");byte[] original=Files.readAllBytes(trajectory.toPath());
            try{Files.write(trajectory.toPath(),"modified".getBytes(java.nio.charset.StandardCharsets.US_ASCII));try{ScenarioStore.manifest(folder);throw new AssertionError("Modified trajectory accepted");}catch(IOException expected){}}
            finally{Files.write(trajectory.toPath(),original);}
            ScenarioStore.manifest(folder);
            out.putString("stream","PASS: planned BDS 90s/900000000 bytes; manifest, navigation, hashes, expired/wrong-profile/tamper rejection; no RF; folder="+folder.getName());finish(Activity.RESULT_OK,out);
        }catch(Throwable e){out.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,out);}
        finally{if(wake.isHeld())wake.release();if(prior==null)prefs.edit().remove("scenario").commit();else prefs.edit().putString("scenario",prior).commit();if(activity!=null){Activity opened=activity;runOnMainSync(opened::finish);}}
    }
}
