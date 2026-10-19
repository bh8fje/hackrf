package local.hackrf.receiver;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.*;
import android.os.*;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Device lifecycle check; never injects locations or starts radio transmission. */
public class RecordingServiceCheck extends Instrumentation {
    private RecordingService service;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        Context context = getTargetContext();
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            public void onServiceConnected(ComponentName name, IBinder binder) {
                service = ((RecordingService.LocalBinder) binder).getService(); connected.countDown();
            }
            public void onServiceDisconnected(ComponentName name) { }
        };
        boolean bound = false;
        try {
            Activity activity = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            bound = context.bindService(new Intent(context, RecordingService.class), connection, Context.BIND_AUTO_CREATE);
            require(connected.await(5, TimeUnit.SECONDS), "Service did not bind");
            runOnMainSync(() -> activity.startForegroundService(new Intent(context, RecordingService.class)
                    .setAction(RecordingService.START).putExtra("latitude",0.0).putExtra("longitude",0.0)));
            waitForIdleSync();
            for (int i = 0; i < 50 && !service.running; i++) { SystemClock.sleep(100); waitForIdleSync(); }
            require(service.running, "Recording did not start: " + service.state);
            require(context.getSystemService(NotificationManager.class).getActiveNotifications().length == 1, "Missing recording notification");
            runOnMainSync(activity::finish);
            waitForIdleSync();
            String before = service.clockView;
            SystemClock.sleep(12000);
            waitForIdleSync();
            require(service.running, "Recording stopped after Activity finish");
            require(!before.equals(service.clockView), "Background timer stopped");
            String data = new String(Files.readAllBytes(service.recording.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            require(!data.contains("\"type\":\"session_end\""), "Session ended in background");
            require(new org.json.JSONObject(data.split("\n",2)[0]).getString("appVersion").equals(context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName), "Session version differs from installed package");
            Activity reopened = startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            require(service.running, "Reopening stopped recording");
            context.getSystemService(NotificationManager.class).getActiveNotifications()[0].getNotification().actions[0].actionIntent.send();
            for (int i = 0; i < 50 && service.running; i++) { SystemClock.sleep(100); waitForIdleSync(); }
            require(!service.running, "Notification stop action failed");
            data = new String(Files.readAllBytes(service.recording.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            require(data.contains("\"type\":\"session_end\""), "Missing session_end");
            require(context.getSystemService(NotificationManager.class).getActiveNotifications().length == 0, "Notification remained after stop");
            result.putString("stream", "PASS: foreground notification, Activity finish, 12-second background timer, reopen, notification stop and file close; bytes=" + data.length());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "FAIL: " + error); finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (service != null) runOnMainSync(() -> { if (service.running) service.finishRecording("自检结束"); });
            if (bound) context.unbindService(connection);
        }
    }
}
