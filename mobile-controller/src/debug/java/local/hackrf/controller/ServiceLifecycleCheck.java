package local.hackrf.controller;

import android.app.*;
import android.content.*;
import android.os.*;

/** Invalid generation parameters fail before any download/USB/RF operation. */
public class ServiceLifecycleCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();
        android.content.SharedPreferences prefs=getTargetContext().getSharedPreferences("controller",0);
        String priorScene=prefs.getString("scenario",null);
        try{
        if(TransmitService.busy)throw new IllegalStateException("A task is active");
        Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
        for(int iteration=0;iteration<20;iteration++){
            long earliest=SystemClock.elapsedRealtime();
            runOnMainSync(()->{
                activity.startForegroundService(new Intent(activity,TransmitService.class).setAction(TransmitService.GENERATE).putExtra("settings","{}").putExtra("autoTransmit",false));
                activity.startService(new Intent(activity,TransmitService.class).setAction(TransmitService.STOP));
                activity.startForegroundService(new Intent(activity,TransmitService.class).setAction(TransmitService.GENERATE).putExtra("settings","{}").putExtra("autoTransmit",false));
            });waitForIdleSync();SystemClock.sleep(100);
            long deadline=SystemClock.elapsedRealtime()+4000;
            while(TransmitService.busy && SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(20);
            waitForIdleSync();if(TransmitService.busy)throw new AssertionError("Busy flag stuck at iteration "+iteration);
            if(TransmitService.taskStartedElapsedMs<earliest||TransmitService.taskStartedElapsedMs>SystemClock.elapsedRealtime())throw new AssertionError("Task clock was not reset for new run");
            if(TransmitService.completedBytes!=0 || TransmitService.expectedBytes!=0 || !TransmitService.status.equals("尚未开始传输") || ScenarioStore.current(getTargetContext())!=null || getTargetContext().getSharedPreferences("controller",0).getBoolean("taskInProgress",false))throw new AssertionError("Flow not reset after stop/failure at "+iteration);
            SystemClock.sleep(60);
            for(android.service.notification.StatusBarNotification n:getTargetContext().getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==2)throw new AssertionError("Orphan foreground notification at "+iteration);
        }
        java.lang.reflect.Method refresh=MainActivity.class.getDeclaredMethod("refreshTx");refresh.setAccessible(true);
        runOnMainSync(()->{try{refresh.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        java.lang.reflect.Field tx=MainActivity.class.getDeclaredField("txView");tx.setAccessible(true);
        if(TransmitService.lastOutcome.isEmpty()||!((android.widget.TextView)tx.get(activity)).getText().toString().contains(MainActivity.friendlyStatus(TransmitService.lastOutcome)))throw new AssertionError("Failure reason hidden after cleanup");
        String failure="本机生成失败：运动轨迹文件无效";
        if(!MainActivity.friendlyStatus(failure).equals(failure))throw new AssertionError("Failure masked as progress");
        out.putString("stream","PASS: 20 repeated invalid-generation/stop/duplicate-start cycles clean busy state and notification; failure reason visible and retry allowed; no downloads or RF");finish(Activity.RESULT_OK,out);
    }catch(Throwable e){out.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,out);}
        finally {if(priorScene==null)prefs.edit().remove("scenario").commit();else prefs.edit().putString("scenario",priorScene).commit();}
    }
}
