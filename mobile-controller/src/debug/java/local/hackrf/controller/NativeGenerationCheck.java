package local.hackrf.controller;

import android.app.*;
import android.content.Intent;
import android.os.*;
import java.io.File;
import org.json.JSONObject;

/** Current network + native execution test. autoTransmit is always false. */
public class NativeGenerationCheck extends Instrumentation {
    private boolean foreground;
    @Override public void onCreate(Bundle args) { super.onCreate(args);foreground=Boolean.parseBoolean(args.getString("foreground","false"));start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if(getTargetContext().getSystemService(KeyguardManager.class).isKeyguardLocked())throw new IllegalStateException("请先解锁手机");
            Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            JSONObject input=PhoneConfig.defaults(getTargetContext()).put("route","stroll").put("speed",.85).put("speedVariation",.4).put("radius",30).put("seed",42).put("duration",90);
            runOnMainSync(()->activity.startForegroundService(new Intent(activity,TransmitService.class).setAction(TransmitService.GENERATE)
                    .putExtra("settings",input.toString()).putExtra("autoTransmit",false)));
            for (int i=0;i<50 && !TransmitService.busy;i++) { SystemClock.sleep(100); waitForIdleSync(); }
            if (!TransmitService.busy) throw new AssertionError("Generation service did not start: "+TransmitService.status+"; "+TransmitService.lastOutcome);
            if(!foreground)runOnMainSync(activity::finish); waitForIdleSync();
            long deadline=SystemClock.elapsedRealtime()+180000,last=0;
            while (TransmitService.busy && SystemClock.elapsedRealtime()<deadline) {
                SystemClock.sleep(250);
                if (SystemClock.elapsedRealtime()-last>5000) { Bundle progress=new Bundle(); progress.putString("stream",TransmitService.status+"\n");sendStatus(0,progress);last=SystemClock.elapsedRealtime(); }
            }
            if (TransmitService.busy || !TransmitService.status.startsWith("本机生成完成")) throw new AssertionError(TransmitService.status+"; "+TransmitService.lastOutcome);
            File folder=ScenarioStore.current(getTargetContext()); JSONObject manifest=ScenarioStore.manifest(folder);
            if (manifest.getInt("schema")!=2 || new File(folder,"signal.bin").length()!=468000000 || manifest.getBoolean("softwareReceiverVerified")) throw new AssertionError("Bad local artifact metadata");
            result.putString("stream","PASS: phone network + native generation "+(foreground?"in foreground":"continued after Activity finish")+"; folder="+folder.getName()+"; 468000000 bytes; no USB or RF started");
            finish(Activity.RESULT_OK,result);
        } catch (Throwable error) { result.putString("stream","FAIL: "+error);finish(Activity.RESULT_CANCELED,result); }
    }
}
