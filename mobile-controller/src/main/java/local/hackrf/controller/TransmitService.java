package local.hackrf.controller;

import android.app.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.*;

public class TransmitService extends Service {
    static final String GENERATE = "local.hackrf.controller.GENERATE";
    static final String START = "local.hackrf.controller.START", STOP = "local.hackrf.controller.STOP";
    static volatile boolean busy;
    static final GnssTrack receivedTrack=new GnssTrack();
    private boolean receiveDuringTx;
    static volatile String status = "尚未开始传输";
    static volatile String lastOutcome="";
    private boolean failed;
    static volatile long completedBytes, expectedBytes;
    static volatile long taskStartedElapsedMs;
    private volatile boolean stopping;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int activeRunId, lastCommandId;
    private UsbDevice selected;
    private NativeScene generator;
    private volatile MixedScene mixedScene;
    private MixedScene.Stream mixedStream;
    private SingleScene singleScene;
    private boolean streamLateBeforeRadio;
    private final byte[] streamScratch=new byte[262144];
    private volatile IOException monitorFailure;
    private volatile long lastM0Bytes, shortfalls;
    private long maxFileReadMs;
    private PowerManager.WakeLock wake;
    private final BroadcastReceiver detach = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (selected != null && device != null && selected.getDeviceName().equals(device.getDeviceName())) { status = "HackRF 已断开，正在清理"; stopping = true; if(mixedScene!=null)mixedScene.close(); }
        }
    };
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("transmit", "天书模拟运行", NotificationManager.IMPORTANCE_LOW));
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(detach, filter, RECEIVER_NOT_EXPORTED); else registerReceiver(detach, filter);
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, TransmitService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "transmit").setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("天书正在运行").setContentText(MainActivity.friendlyStatus(status)).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止任务", stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        lastCommandId=id;
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (STOP.equals(intent.getAction())) { stopping = true; if (generator != null) generator.cancel(); if(mixedScene!=null)mixedScene.close(); status = "正在停止并关闭 HackRF"; if (!busy) {resetFlow(this,status);stopSelf();} return START_NOT_STICKY; }
        boolean generating = GENERATE.equals(intent.getAction());
        if ((!START.equals(intent.getAction()) && !generating) || busy) return START_NOT_STICKY;
        taskStartedElapsedMs=SystemClock.elapsedRealtime();
        activeRunId=id;busy = true; stopping = false;failed=true;getSharedPreferences("controller",0).edit().putBoolean("taskInProgress",true).apply(); completedBytes = 0; expectedBytes = 0; status = "校验场景与 USB 设备";
        final int gain = intent.getIntExtra("gain", 47);
        final boolean amplifier = intent.getBooleanExtra("amplifier", true);
        try {
            String settings=intent.getStringExtra("settings");boolean auto=intent.getBooleanExtra("autoTransmit",false);
            if(generating && auto){
                UsbManager usb=getSystemService(UsbManager.class);ArrayList<UsbDevice> devices=new ArrayList<>();
                for(UsbDevice device:usb.getDeviceList().values())if(device.getVendorId()==0x1d50&&device.getProductId()==0x6089)devices.add(device);
                if(devices.size()!=1 || !usb.hasPermission(devices.get(0)))throw new SecurityException("需要已授权的 HackRF USB 设备");
                selected=devices.get(0);
            }
            receiveDuringTx=!generating||auto;
            if(receiveDuringTx)receivedTrack.prepare();
            foreground(!(generating && auto));
            if(receiveDuringTx)receivedTrack.listen(this);
            worker.execute(() -> {
                try { if(generating) generate(settings,auto,gain,amplifier);else transmit(gain,amplifier); }
                finally { main.post(() -> finishRun(id)); }
            });
        }
        catch (Exception e) { status = "启动失败：" + errorMessage(e); finishRun(id); }
        return START_NOT_STICKY;
    }
    private void finishRun(int id){
        if(activeRunId!=id)return;
        if(stopping || failed)resetFlow(this,status);
        getSharedPreferences("controller",0).edit().remove("taskInProgress").apply();
        receivedTrack.stop();
        selected=null;busy=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(lastCommandId);
    }
    static void resetFlow(Context context,String outcome){
        lastOutcome=outcome;completedBytes=expectedBytes=0;
        context.getSharedPreferences("controller",0).edit().remove("scenario").remove("taskInProgress").apply();
        status="尚未开始传输";
        if(outcome.contains("未确认采样停止"))status="流程已清零；上次未确认设备停止，请检查或断开设备后重试。";
    }
    private void foreground(boolean generating) {
        if(!generating){
            UsbManager usb=getSystemService(UsbManager.class);
            if(selected==null || !usb.getDeviceList().containsKey(selected.getDeviceName()) || !usb.hasPermission(selected))
                throw new SecurityException("HackRF 已断开或 USB 授权失效，请重新连接并授权");
        }
        int type=generating?android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC:android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if(receiveDuringTx&&checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED)type|=android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, notification(), type);
        else startForeground(2, notification());
    }
    private void generate(String settings, boolean auto, int gain, boolean amplifier) {
        PowerManager.WakeLock generationWake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hackrf:generate");
        generationWake.acquire(900000);
        try {
            JSONObject parameters=new JSONObject(settings);
            monitorFailure = null;
            boolean mixed=SignalProfile.MIXED_SYSTEM.equals(parameters.optString("gnssSystem"));
            if(auto){
                if(mixed)SignalProfile.requireMixedEnabled();
                for(int attempt=0;attempt<2;attempt++){
                    ensureStreamDevice();
                    try{
                        String system=parameters.optString("gnssSystem","gps");long planningBegan;
                        if(mixed){mixedScene=MixedPreparation.prepare(this,parameters,this::check,message->{status=message;getSystemService(NotificationManager.class).notify(2,notification());});planningBegan=mixedScene.planningBeganElapsedMs;mixedStream=mixedScene.start(this::check);}
                        else{generator=new NativeScene();singleScene=generator.prepareStream(this,parameters,this::check,message->{status=message;getSystemService(NotificationManager.class).notify(2,notification());});planningBegan=singleScene.planningBeganElapsedMs;mixedStream=singleScene.start(this::check);}
                        String key=system+"StreamReadyMillis";long observed=SystemClock.elapsedRealtime()-planningBegan;
                        long previous=getSharedPreferences("controller",0).getLong(key,8000);
                        getSharedPreferences("controller",0).edit().putLong(key,Preparation.nextStreamReadyMillis(previous,observed)).apply();
                        JSONObject manifest=mixed?mixedScene.manifest:singleScene.manifest;
                        manifest.put("streamReadyMillis",observed).put("streamRebuildAttempt",attempt);
                        MixedScene.write(mixed?mixedScene.folder:singleScene.folder,"experiment.json",manifest.toString(2));
                        streamDelay(manifest,true);ensureStreamDevice();transmit(gain,amplifier);
                        if(streamLateBeforeRadio)throw new NativeScene.PreparationLate();
                        return;
                    }catch(NativeScene.PreparationLate late){
                        closePreparedStream();
                        boolean connected=streamDevicePresent();
                        if(!Preparation.retryStream(attempt,stopping||Thread.currentThread().isInterrupted(),false,connected))throw late;
                        check();status="实时场景准备超过时段，已关闭旧进程；保持目标点重新安排一次";
                    }
                }
                throw new IOException("重新安排后仍未赶上实时场景时段");
            }
            if(mixed){mixedScene=MixedPreparation.prepare(this,parameters,this::check,message->status=message);failed=false;status="混合场景参数已准备；未启动射频";return;}
            generator = new NativeScene();
            File folder = generator.generate(this, parameters, this::check, message -> {
                status = message; getSystemService(NotificationManager.class).notify(2, notification());
            },auto);
            check();
            if (auto) { transmit(gain, amplifier); }
            else {failed=false;status = "本机生成完成；长度与哈希自检通过（尚未独立解码）\n" + folder.getName();}
        } catch (Exception e) { status = (stopping ? "已停止：" : "本机生成失败：") + errorMessage(e); }
        finally {
            closePreparedStream();
            if (generationWake.isHeld()) generationWake.release();
        }
    }
    private void closePreparedStream(){
        if(generator!=null)generator.cancel();generator=null;
        if(mixedScene!=null)mixedScene.close();mixedScene=null;
        if(singleScene!=null)singleScene.close();singleScene=null;mixedStream=null;
    }
    private boolean streamDevicePresent(){
        if(selected==null)return false;UsbManager usb=getSystemService(UsbManager.class);
        return usb.getDeviceList().containsKey(selected.getDeviceName())&&usb.hasPermission(selected);
    }
    private void ensureStreamDevice()throws IOException {check();if(!streamDevicePresent())throw new InterruptedIOException("HackRF已断开或授权失效，不再重新准备");}
    private long streamDelay(JSONObject manifest,boolean streamed)throws Exception{
        try{return ScenarioStore.delay(manifest,System.currentTimeMillis());}
        catch(IOException invalid){
            long start=java.time.Instant.parse(manifest.getJSONObject("navigation").getString("startUTC")).toEpochMilli();
            if(streamed&&Preparation.streamLate(start,System.currentTimeMillis()))throw new NativeScene.PreparationLate();
            throw invalid;
        }
    }
    private String errorMessage(Exception error){
        android.util.Log.e("WalkLab", "Task failed", error);
        if(error instanceof SecurityException)return "系统拒绝设备访问，请重新连接 HackRF 并允许 USB 授权，然后重试。";
        return error.getMessage();
    }
    private void check() throws IOException {
        if (stopping || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("用户停止或设备断开");
        if (monitorFailure != null) throw monitorFailure;
    }
    private static final class Slot {
        final UsbRequest request = new UsbRequest();
        final ByteBuffer buffer = ByteBuffer.allocateDirect(Build.VERSION.SDK_INT >= 28 ? 262144 : 16384);
        int expected, actual;
    }
    private boolean queue(Slot slot, FileChannel file, long[] remaining) throws Exception {
        check();
        long before = SystemClock.elapsedRealtime();
        slot.actual = file==null?IqPackets.fill(slot.buffer,mixedStream,streamScratch,remaining):IqPackets.fill(slot.buffer, file, remaining);
        maxFileReadMs = Math.max(maxFileReadMs, SystemClock.elapsedRealtime()-before);
        slot.expected = slot.buffer.remaining();
        if (slot.expected == 0) return false;
        if (!slot.request.queue(slot.buffer)) throw new IOException("USB 请求入队失败");
        return true;
    }
    private void transmit(int gain, boolean amplifier) {
        UsbDeviceConnection connection = null; UsbInterface face = null; HackrfProtocol protocol = null;
        ArrayList<Slot> slots = new ArrayList<>();
        ScheduledExecutorService monitor = null;
        long maxCompletionGapMs = 0,runStarted=taskStartedElapsedMs;
        JSONObject record = new JSONObject(); String message = ""; boolean offConfirmed = false,sampled=false,radioAttempted=false;
        long[] stoppedState=null;
        try {
            streamLateBeforeRadio=false;monitorFailure = null; lastM0Bytes = 0; shortfalls = 0; maxFileReadMs = 0;
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hackrf:usb-transmit");
            wake.acquire(900000);
            boolean mixed=mixedScene!=null,streamed=mixed||singleScene!=null;
            File folder = mixed?mixedScene.folder:singleScene!=null?singleScene.folder:ScenarioStore.current(this);
            if (folder == null) throw new IOException("请先生成场景或导入 Mac 场景包");
            JSONObject manifest = mixed?mixedScene.manifest:singleScene!=null?singleScene.manifest:ScenarioStore.manifest(folder);sampled=streamed||ScenarioStore.isSampled(manifest);String system=mixed?SignalProfile.MIXED_SYSTEM:singleScene!=null?singleScene.system:ScenarioStore.system(manifest);final boolean[] txRun={false}; expectedBytes = manifest.getLong("iqBytes");
            if(mixed)SignalProfile.requireMixedEnabled();
            if(!mixed&&!system.equals("gps")&&!system.equals("bds"))throw new IOException("已取消该导航系统，仅支持 GPS 和北斗");
            record.put("signal",mixed?"GPS-L1CA+BDS-B1I":system.equals("galileo")?"GAL-E1":system.equals("glonass")?"GLO-G1":system.equals("bds")?"BDS-B1I":"GPS-L1CA");
            streamDelay(manifest,streamed);
            File iq = new File(folder, "signal.bin");
            if(!streamed){
            if (iq.length() != expectedBytes) throw new IOException("I/Q 长度已变化");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(new FileInputStream(iq))) {
                byte[] data = new byte[262144]; int length;
                while ((length = input.read(data)) != -1) { check(); digest.update(data, 0, length); }
            }
            if (!ScenarioStore.hex(digest.digest()).equals(manifest.getString("iqSHA256"))) throw new IOException("I/Q SHA-256 已变化");
            }else record.put("storageFormat","stream-no-wideband-file").put("storedIQBytes",0);
            try{ReceiverSync.send(this,folder,manifest);record.put("receiverSceneSynced",true);}
            catch(Exception unavailable){record.put("receiverSceneSynced",false).put("receiverSyncError",errorMessage(unavailable));}
            UsbManager usb = getSystemService(UsbManager.class);
            ArrayList<UsbDevice> devices = new ArrayList<>();
            for (UsbDevice device : usb.getDeviceList().values()) if (device.getVendorId() == 0x1d50 && device.getProductId() == 0x6089) devices.add(device);
            if (devices.size() != 1 || !usb.hasPermission(devices.get(0))) throw new IOException("需要恰好一台已授权的 HackRF One");
            selected = devices.get(0);
            connection = usb.openDevice(selected); if (connection == null) throw new IOException("USB 打开失败");
            foreground(false);
            UsbEndpoint endpoint = null;
            for (int i = 0; i < selected.getInterfaceCount(); i++) {
                UsbInterface candidate = selected.getInterface(i);
                for (int j = 0; j < candidate.getEndpointCount(); j++) {
                    UsbEndpoint e = candidate.getEndpoint(j);
                    if (e.getAddress() == 0x02 && e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) { face = candidate; endpoint = e; }
                }
            }
            if (face == null || !connection.claimInterface(face, true)) throw new IOException("无法占用 Bulk OUT 接口");
            final UsbDeviceConnection link = connection;
            protocol = new HackrfProtocol((type, request, value, index, data) -> {
                if (stopping && !(request == 1 && value == 0)) throw new InterruptedIOException("已停止");
                return link.controlTransfer(type, request, value, index, data, data == null ? 0 : data.length, 1000);
            });
            int api = HackrfProtocol.apiVersion(connection.getRawDescriptors());
            protocol.configure(gain, amplifier,system); protocol.state(); int block = protocol.bufferSize(api);
            record.put("appVersion",getPackageManager().getPackageInfo(getPackageName(),0).versionName).put("batteryOptimizationExempt",getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())).put("screenInteractiveAtStart",getSystemService(PowerManager.class).isInteractive());
            record.put("usbApiVersionHex", String.format(Locale.US, "%04x", api)).put("dmaBufferBytes", block);
            long delay = streamDelay(manifest,streamed);
            long deadline = SystemClock.elapsedRealtime() + delay;
            while (SystemClock.elapsedRealtime() < deadline) {
                check(); status = "等待计划时刻，约 " + ((deadline-SystemClock.elapsedRealtime()+999)/1000) + " 秒";
                getSystemService(NotificationManager.class).notify(2, notification()); Thread.sleep(Math.max(1, Math.min(200, deadline-SystemClock.elapsedRealtime())));
            }
            streamDelay(manifest,streamed); check();
            try (InputStream input = streamed?mixedStream:new FileInputStream(iq)) {
                FileChannel file = streamed?null:((FileInputStream)input).getChannel();
                long padding = (block - expectedBytes % block) % block + block;
                long[] remaining = {expectedBytes, padding};
                for (int i = 0; i < IqPackets.queueSlots(mixed,Build.VERSION.SDK_INT); i++) {
                    Slot slot = new Slot();
                    if (!slot.request.initialize(connection, endpoint)) throw new IOException("USB 异步请求初始化失败");
                    slots.add(slot); slot.request.setClientData(slot);
                }
                check();radioAttempted=true;protocol.mode(2);
                receivedTrack.begin(SystemClock.elapsedRealtimeNanos());
                long started = SystemClock.elapsedRealtime(), lastCompletion = started;
                record.put("startedAtMs", System.currentTimeMillis()).put("startupElapsedMs",started-runStarted).put("scenario", folder.getName()).put("manifest", manifest)
                      .put("gain", gain).put("amplifier", amplifier).put("paddingBytes", padding);
                int pending = 0; for (Slot slot : slots) if (queue(slot, file, remaining)) pending++;
                final HackrfProtocol radio = protocol;
                monitor = Executors.newSingleThreadScheduledExecutor();
                final long[] progressSnapshot = {started, 0};
                // Control reads can block; never put them on the sample-feeding thread.
                monitor.scheduleWithFixedDelay(() -> {
                    try {
                        long[] state = radio.state();if(state[3]==4)txRun[0]=true; lastM0Bytes = HackrfProtocol.extendCounter(lastM0Bytes,state[0]); shortfalls = state[1];
                        if (state[2] != 0) monitorFailure = new IOException("HackRF M0 错误 " + state[2]);
                        long now = SystemClock.elapsedRealtime(), bytes = completedBytes;
                        status = String.format(Locale.US, "USB 已完成 %.1f%% · %.2f MB/s · 欠载 %d", bytes*100.0/expectedBytes,
                                (bytes-progressSnapshot[1])/1000.0/Math.max(1, now-progressSnapshot[0]), shortfalls);
                        progressSnapshot[0] = now; progressSnapshot[1] = bytes;
                        getSystemService(NotificationManager.class).notify(2, notification());
                    } catch (IOException e) { monitorFailure = e; }
                }, 1, 1, TimeUnit.SECONDS);
                while (pending > 0) {
                    check(); if (SystemClock.elapsedRealtime()-started > manifest.getInt("durationSeconds")*1000L+5000) throw new IOException("USB 传输超时");
                    UsbRequest request;
                    try { request = connection.requestWait(250); } catch (TimeoutException timeout) { continue; }
                    if (request == null) throw new IOException("USB 请求失败或设备断开");
                    long completion = SystemClock.elapsedRealtime();
                    maxCompletionGapMs = Math.max(maxCompletionGapMs, completion-lastCompletion); lastCompletion = completion;
                    Slot slot = (Slot)request.getClientData(); pending--;
                    if (slot.buffer.position() != slot.expected) throw new IOException("USB 短传输，未重发以免重复样本");
                    completedBytes += slot.actual;
                    if (queue(slot, file, remaining)) pending++;
                }
                monitor.shutdownNow(); if(!monitor.awaitTermination(1500, TimeUnit.MILLISECONDS))throw new IOException("设备状态查询未停止"); monitor = null;
                // Drain the firmware DMA buffer; USB completion alone does not mean RF consumption.
                long drainDeadline = SystemClock.elapsedRealtime()+2000;
                while (true) {
                    check(); long[] state = protocol.state();lastM0Bytes=HackrfProtocol.extendCounter(lastM0Bytes,state[0]);
                    if (lastM0Bytes >= expectedBytes) { record.put("shortfalls", state[1]).put("m0Bytes", lastM0Bytes); break; }
                    if (state[2] != 0 || SystemClock.elapsedRealtime() > drainDeadline) throw new IOException("设备未确认消费完整 I/Q");
                    Thread.sleep(10);
                }
                if(streamed){protocol.mode(0);mixedStream.verifyComplete();}
                record.put("txRunObserved",txRun[0]).put("elapsedMs", SystemClock.elapsedRealtime()-started);
                if(sampled && !txRun[0])throw new IOException("导航信号未通过TX_RUN检查");
            }
            failed=false;message = "I/Q 发送完成";
        } catch (Exception e) { if(e instanceof NativeScene.PreparationLate&&!radioAttempted&&!stopping)streamLateBeforeRadio=true;message = (stopping ? "已停止" : "发送未完成") + "：" + errorMessage(e); }
        finally {
            if (monitor != null) {
                monitor.shutdownNow();
                try { monitor.awaitTermination(1500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
            }
            try { record.put("maxCompletionGapMs", maxCompletionGapMs).put("maxFileReadMs", maxFileReadMs)
                    .put("queueBufferBytes", Build.VERSION.SDK_INT >= 28 ? 262144 : 16384).put("queueSlots", slots.size())
                    .put("m0BytesLastQuery", lastM0Bytes);
                if (!record.has("shortfalls")) record.put("shortfalls", shortfalls); } catch (Exception ignored) { }
            if (protocol != null) {
                try {
                    record.put("shortfallsBeforeStop",record.optLong("shortfalls",shortfalls)).put("m0BytesBeforeStop",lastM0Bytes);
                    protocol.mode(0);
                    // Read without the cancellation guard to verify the OFF command completed.
                    byte[] state = new byte[40];HackrfProtocol.IdleState idle=new HackrfProtocol.IdleState();
                    long stopDeadline=SystemClock.elapsedRealtime()+2000;
                    for (int i = 0; i < 50 && SystemClock.elapsedRealtime()<stopDeadline; i++) {
                        int count = connection.controlTransfer(0xc0, 41, 0, 0, state, 40, 100);
                        if (idle.observe(count==40?state:null)) {
                            offConfirmed = true;stoppedState=HackrfProtocol.decodeState(state);
                            lastM0Bytes=HackrfProtocol.extendCounter(lastM0Bytes,stoppedState[0]);
                            record.put("shortfalls",stoppedState[1]).put("m0Bytes",lastM0Bytes).put("m0ErrorAfterStop",stoppedState[2])
                                .put("stableIdleConfirmed",true).put("stableIdleReadCount",3);
                            break;
                        }
                        SystemClock.sleep(20);
                    }
                } catch (Exception ignored) { }
            }
            for (Slot slot : slots) { try { slot.request.cancel(); } catch (Exception ignored) { } finally {try {slot.request.close();}catch(Exception ignored){}} }
            boolean released=false;
            if(connection!=null){try{if(face!=null)released=connection.releaseInterface(face);}catch(RuntimeException ignored){}finally{try{connection.close();}catch(RuntimeException ignored){}}}
            try{record.put("interfaceReleased",released);}catch(Exception ignored){}
            if (wake != null && wake.isHeld()) wake.release();
            if(protocol!=null && !offConfirmed)failed=true;
            if(sampled && !failed)try{
                HackrfProtocol.requireCompleted(stoppedState,lastM0Bytes,expectedBytes);
                record.put("finalM0StateVerified",true);
            }catch(Exception e){failed=true;message="发送未完成："+errorMessage(e);}
            status = message + String.format(Locale.US, " · USB %.2f/%.2f MB", completedBytes/1e6, expectedBytes/1e6) + (record.has("shortfalls") ? " · 末次欠载 " + record.optLong("shortfalls") : "") + (offConfirmed ? "；停止命令已接受，采样已空闲" : protocol != null ? "；未确认采样停止，请检查或断开设备" : "；未启动射频");
            try {
                record.put("totalTaskElapsedMs",SystemClock.elapsedRealtime()-runStarted).put("activityVisibleAtEnd",MainActivity.visible).put("screenInteractiveAtEnd",getSystemService(PowerManager.class).isInteractive()).put("endedAtMs", System.currentTimeMillis()).put("completedBytes", completedBytes).put("expectedBytes", expectedBytes)
                      .put("offConfirmed", offConfirmed).put("status",stopping?"stopped":failed?"failed":"completed").put("message", status);
                Files.write(new File(getFilesDir(), "tx-" + System.currentTimeMillis() + ".json").toPath(), record.toString(2).getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) { }
        }
    }
    @Override public void onDestroy() {
        receivedTrack.stop();stopping = true; if (generator != null) generator.cancel();if(mixedScene!=null)mixedScene.close(); worker.shutdownNow(); unregisterReceiver(detach); super.onDestroy();
    }
}
