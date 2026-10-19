package local.hackrf.controller;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import java.io.*;
import java.nio.file.Files;
import org.json.*;

/** Actual 90-second contained sampled-signal RF acceptance via the production service. */
public class BdsTransmitCheck extends Instrumentation {
    private String system;private int duration;
    @Override public void onCreate(Bundle args){super.onCreate(args);system=args.getString("system","bds");duration=Integer.parseInt(args.getString("duration","90"));start();}
    @Override public void onStart(){Bundle out=new Bundle();Activity activity=null;boolean launched=false;
        Context c=getTargetContext();
        try {
            if(!system.equals("gps")&&!system.equals("bds")&&!system.equals("mixed"))throw new IllegalArgumentException("Unsupported system");
            if(duration<90||duration>180)throw new IllegalArgumentException("Duration must be 90-180 seconds");
            int rate=system.equals("gps")?2600000:system.equals("bds")?5000000:18000000;long frequency=system.equals("gps")?1575420000L:system.equals("bds")?1561098000L:1567747500L;long expected=duration*(long)rate*2;String signal=system.equals("gps")?"GPS-L1CA":system.equals("bds")?"BDS-B1I":"GPS-L1CA+BDS-B1I";
            if(TransmitService.busy)throw new IllegalStateException("Task already active");
            UsbManager usb=c.getSystemService(UsbManager.class);int count=0;
            for(UsbDevice d:usb.getDeviceList().values())if(d.getVendorId()==0x1d50&&d.getProductId()==0x6089){count++;if(!usb.hasPermission(d))throw new IOException("HackRF USB not authorized");}
            if(count!=1)throw new IOException("Exactly one HackRF required");
            activity=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            JSONObject input=PhoneConfig.defaults(c).put("gnssSystem",system).put("duration",duration).put("seed",42).put("route","stationary").put("speed",0).put("heightVariation",0).put("horizontalDrift",0).put("verticalDrift",0);
            long began=System.currentTimeMillis();Activity opened=activity;
            runOnMainSync(()->opened.startForegroundService(new Intent(c,TransmitService.class).setAction(TransmitService.GENERATE).putExtra("settings",input.toString()).putExtra("autoTransmit",true).putExtra("gain",47).putExtra("amplifier",true)));launched=true;
            for(int i=0;i<50&&!TransmitService.busy;i++){SystemClock.sleep(100);waitForIdleSync();}
            if(!TransmitService.busy)throw new AssertionError("Service did not start: "+TransmitService.lastOutcome);
            long deadline=SystemClock.elapsedRealtime()+Math.max(900000,duration*10000L),last=0;
            while(TransmitService.busy&&SystemClock.elapsedRealtime()<deadline){SystemClock.sleep(250);if(SystemClock.elapsedRealtime()-last>5000){Bundle progress=new Bundle();progress.putString("stream",TransmitService.status+"\n");sendStatus(0,progress);last=SystemClock.elapsedRealtime();}}
            if(TransmitService.busy)throw new AssertionError("Timed out");
            File latest=null;for(File f:c.getFilesDir().listFiles())if(f.getName().startsWith("tx-")&&f.getName().endsWith(".json")&&f.lastModified()>=began&&(latest==null||f.lastModified()>latest.lastModified()))latest=f;
            if(latest==null)throw new AssertionError("No transmission record: "+TransmitService.lastOutcome);
            JSONObject record=new JSONObject(new String(Files.readAllBytes(latest.toPath()),java.nio.charset.StandardCharsets.UTF_8));
            JSONObject manifest=record.getJSONObject("manifest");
            if(manifest.getInt("sampleRate")!=rate||manifest.getLong("frequencyHz")!=frequency
                ||!"completed".equals(record.optString("status"))||!record.optBoolean("finalM0StateVerified")||!signal.equals(record.optString("signal"))||record.optLong("completedBytes")!=expected||record.optLong("m0Bytes")<expected||record.optLong("shortfalls",-1)!=0||!record.optBoolean("txRunObserved")||!record.optBoolean("offConfirmed")||!record.optBoolean("interfaceReleased")||!record.optBoolean("receiverSceneSynced"))throw new AssertionError(record.toString());
            out.putString("stream","PASS: "+signal+" "+duration+"s RF; "+expected+" bytes, zero underruns, TX_RUN and OFF confirmed; record="+latest.getName()+"; folder="+record.getString("scenario"));finish(Activity.RESULT_OK,out);
        }catch(Throwable e){out.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,out);}
        finally{if(launched&&TransmitService.busy)c.startService(new Intent(c,TransmitService.class).setAction(TransmitService.STOP));if(activity!=null){Activity opened=activity;runOnMainSync(opened::finish);}}
    }
}
