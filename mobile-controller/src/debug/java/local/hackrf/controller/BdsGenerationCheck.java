package local.hackrf.controller;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import org.json.*;

/** Current BDS data + native generation; never opens USB or calls transmit. */
public class BdsGenerationCheck extends Instrumentation {
    private String route="line";
    @Override public void onCreate(Bundle args){super.onCreate(args);route=args.getString("route","line");start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        if(TransmitService.busy)throw new IllegalStateException("Task already active");
        Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        Method selector=MainActivity.class.getDeclaredMethod("selectSystem",int.class);selector.setAccessible(true);
        runOnMainSync(()->{try{selector.invoke(activity,1);
            android.view.View root=activity.getWindow().getDecorView();root.measure(android.view.View.MeasureSpec.makeMeasureSpec(1264,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(2600,android.view.View.MeasureSpec.EXACTLY));root.layout(0,0,1264,2600);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        Field send=MainActivity.class.getDeclaredField("send"),start=MainActivity.class.getDeclaredField("localGenerate"),mode=MainActivity.class.getDeclaredField("signalMode");for(Field f:new Field[]{send,start,mode})f.setAccessible(true);
        for(int i=0;i<30 && (mode.getInt(activity)!=1 || !((android.view.View)start.get(activity)).isEnabled());i++){SystemClock.sleep(100);waitForIdleSync();}
        if(mode.getInt(activity)!=1||((android.view.View)send.get(activity)).isEnabled()||!((android.view.View)start.get(activity)).isEnabled())throw new AssertionError("BDS gate: mode="+mode.getInt(activity)+" send="+((android.view.View)send.get(activity)).isEnabled()+" start="+((android.view.View)start.get(activity)).isEnabled());
        JSONObject input=PhoneConfig.defaults(getTargetContext()).put("gnssSystem","bds").put("duration",90).put("seed",42).put("route","line").put("speed",1.0).put("speedVariation",0);
        if(!route.equals("line")) {
            if(!route.equals("stroll")&&!route.equals("office"))throw new IllegalArgumentException("Unsupported BDS check route");
            PhoneMotion profile=PhoneMotion.profile(route);
            input.put("route",route).put("speed",profile.speed).put("speedVariation",profile.speedVariation).put("radius",profile.radius)
                .put("walkMinimum",profile.walkMinimum).put("walkMaximum",profile.walkMaximum).put("pauseMinimum",profile.pauseMinimum).put("pauseMaximum",profile.pauseMaximum).put("reactionSeconds",profile.reactionSeconds);
        }
        PhoneMotion expected=NativeScene.parameters(input);PhoneMotion.Trace expectedTrace=expected.trace(()->{});
        if(!route.equals("line") && (expectedTrace.stops<1||expectedTrace.starts<1||expectedTrace.stoppedSeconds<1))throw new AssertionError("No walking/stopping behavior");
        runOnMainSync(()->activity.startForegroundService(new Intent(activity,TransmitService.class).setAction(TransmitService.GENERATE).putExtra("settings",input.toString()).putExtra("autoTransmit",false)));
        for(int i=0;i<50&&!TransmitService.busy;i++)SystemClock.sleep(100);
        if(!TransmitService.busy)throw new AssertionError("No BDS generation task");
        runOnMainSync(activity::finish);waitForIdleSync();
        long deadline=SystemClock.elapsedRealtime()+850000,last=0;
        while(TransmitService.busy&&SystemClock.elapsedRealtime()<deadline){SystemClock.sleep(250);if(SystemClock.elapsedRealtime()-last>10000){Bundle progress=new Bundle();progress.putString("stream",TransmitService.status+"\n");sendStatus(0,progress);last=SystemClock.elapsedRealtime();}}
        if(TransmitService.busy||!TransmitService.status.startsWith("本机生成完成"))throw new AssertionError(TransmitService.status+" / "+TransmitService.lastOutcome);
        String id=getTargetContext().getSharedPreferences("controller",0).getString("bdsExperiment","");
        File folder=new File(getTargetContext().getFilesDir(),"bds-experiments/"+id);
        JSONObject m=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(folder,"experiment.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        JSONObject navigation=m.getJSONObject("navigation");
        if(!java.time.Instant.parse(navigation.getString("startUTC")).equals(java.time.Instant.parse(navigation.getString("validatedAtUTC")).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)))throw new AssertionError("Offline BDS should start at validation time");
        if(m.getInt("schema")!=2||m.getJSONObject("trajectorySHA256").length()!=2||!id.matches("[a-f0-9-]{36}")||m.getLong("iqBytes")!=900000000||new File(folder,"signal.bin").length()!=900000000||m.getBoolean("softwareReceiverVerified")||m.getBoolean("hardwareOutputAllowed"))throw new AssertionError("BDS metadata / sample count / hardware gate");
        String[] rows=new String(java.nio.file.Files.readAllBytes(new File(folder,"ideal.csv").toPath()),java.nio.charset.StandardCharsets.US_ASCII).split("\\n");
        String[] first=rows[0].split(","),lastRow=rows[900].split(",");double distance=0;
        for(int i=1;i<4;i++)distance+=Math.pow(Double.parseDouble(lastRow[i])-Double.parseDouble(first[i]),2);
        if(route.equals("line") && Math.sqrt(distance)<89.9)throw new AssertionError("Dynamic path not applied");
        if(rows.length!=902)throw new AssertionError("Trajectory length");
        for(int i=0;i<rows.length;i++) {
            String[] point=rows[i].split(",");if(Math.abs(Double.parseDouble(point[0])-expectedTrace.ideal[i][0])>1e-7)throw new AssertionError("Trajectory epoch mismatch");
        }
        result.putString("stream","PASS: BDS current network + Android native 90s/900000000 bytes; Activity finished; RF disabled; route="+route+"; distance="+expectedTrace.distance+"; stoppedSeconds="+expectedTrace.stoppedSeconds+"; folder="+id);finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,result);}}
}
