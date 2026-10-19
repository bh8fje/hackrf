package local.hackrf.receiver;
import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

/** Bounded real GNSS capture. Never injects location or opens a transmitter. */
public class ReceptionCaptureCheck extends Instrumentation {
    private int seconds=180;private double latitude=Double.NaN,longitude=Double.NaN;private RecordingService service;private boolean owned;
    @Override public void onCreate(Bundle args){super.onCreate(args);seconds=Integer.parseInt(args.getString("seconds","180"));latitude=Double.parseDouble(args.getString("latitude","NaN"));longitude=Double.parseDouble(args.getString("longitude","NaN"));start();}
    @Override public void onStart(){Bundle out=new Bundle();Activity activity=null;Context c=getTargetContext();boolean bound=false;
        CountDownLatch connected=new CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){service=((RecordingService.LocalBinder)b).getService();connected.countDown();}public void onServiceDisconnected(ComponentName n){}};
        try {
            if(seconds<15||seconds>600)throw new IllegalArgumentException("Capture must be 15-600s");
            activity=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            bound=c.bindService(new Intent(c,RecordingService.class),connection,Context.BIND_AUTO_CREATE);
            if(!connected.await(5,TimeUnit.SECONDS))throw new AssertionError("Service not bound");
            if(service.running)throw new IllegalStateException("Existing recording left running");
            if(!Geometry.validTarget(latitude,longitude)){latitude=service.targetLat;longitude=service.targetLon;}
            if(!Geometry.validTarget(latitude,longitude))throw new IllegalArgumentException("请提供目标经纬度或先同步场景");
            Activity opened=activity;runOnMainSync(()->opened.startForegroundService(new Intent(c,RecordingService.class).setAction(RecordingService.START).putExtra("latitude",latitude).putExtra("longitude",longitude)));owned=true;
            for(int i=0;i<50&&!service.running;i++){SystemClock.sleep(100);waitForIdleSync();}
            if(!service.running)throw new AssertionError(service.state);
            long end=SystemClock.elapsedRealtime()+seconds*1000L;
            while(SystemClock.elapsedRealtime()<end&&service.running){SystemClock.sleep(1000);Bundle progress=new Bundle();progress.putString("stream",service.clockView+" · "+service.fixView+"\n");if((end-SystemClock.elapsedRealtime())/1000%10==0)sendStatus(0,progress);}
            if(!service.running)throw new AssertionError(service.state);
            runOnMainSync(()->service.finishRecording("自动接收验证窗口结束"));owned=false;
            out.putString("stream","PASS: raw GNSS capture closed; file="+service.recording.getName()+"; "+service.clockView);finish(Activity.RESULT_OK,out);
        }catch(Throwable e){out.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,out);}
        finally{if(owned&&service!=null)runOnMainSync(()->service.finishRecording("接收验证清理"));if(bound)c.unbindService(connection);if(activity!=null){Activity opened=activity;runOnMainSync(opened::finish);}}
    }
}
