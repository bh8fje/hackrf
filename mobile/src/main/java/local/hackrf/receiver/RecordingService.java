package local.hackrf.receiver;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.time.OffsetDateTime;

public class RecordingService extends android.app.Service implements LocationListener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationManager manager;
    private android.os.PowerManager.WakeLock wake;
    String state = "等待开始 · 点击开始记录", fixView = "GNSS：尚无定位", networkView = "网络定位：尚无数据", satelliteView = "等待卫星状态", clockView = "尚无记录", simulationView = "未同步模拟场景";
    final ArrayList<double[]> points = new ArrayList<>();
    boolean simulationMatched;
    int targetRevision;
    boolean running;
    private long started, bytes, latestFixNanos;
    private int fixes;
    double targetLat = Double.NaN, targetLon = Double.NaN;
    private JSONObject scenario;
    private long scenarioModified, scenarioStartMs, satelliteNanos, lastScenarioFixTime;
    private int matchingFixes, matchingSvids;
    private double scenarioError = Double.NaN;
    private boolean previouslyMatched;
    private int scenarioConstellation=GnssStatus.CONSTELLATION_GPS;
    private JSONArray scenarioSvids=new JSONArray();
    File recording;
    private BufferedWriter writer;
    private final SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS 'UTC'", Locale.US);

    private final GnssStatus.Callback satellites = new GnssStatus.Callback() {
        @Override public void onStarted() { if (running) state = "GNSS 已启动，等待卫星和定位"; }
        @Override public void onStopped() { if (running) state = "系统 GNSS 已停止，请检查定位开关"; }
        @Override public void onFirstFix(int ttffMillis) {
            if (!running) return;
            state = "首次定位耗时 " + ttffMillis + " ms";
            try { write(new JSONObject().put("type", "first_fix").put("ttffMs", ttffMillis)); }
            catch (Exception e) { fail(e); }
        }
        @Override public void onSatelliteStatusChanged(GnssStatus status) {
            if (!running) return;
            int used = 0, gps = 0, beidou = 0;
            JSONArray rows = new JSONArray();
            StringBuilder table = new StringBuilder("星座 / 编号   C/N₀ dB-Hz   参与定位\n");
            try {
                for (int i = 0; i < status.getSatelliteCount(); i++) {
                    boolean inFix = status.usedInFix(i);
                    if (inFix) used++;
                    int constellation = status.getConstellationType(i);
                    if (inFix && constellation == GnssStatus.CONSTELLATION_GPS) gps++;
                    if (inFix && constellation == GnssStatus.CONSTELLATION_BEIDOU) beidou++;
                    String name = constellation(constellation);
                    table.append(String.format(Locale.US, "%s %3d   %5.1f      %s\n", name,
                            status.getSvid(i), status.getCn0DbHz(i), inFix ? "是" : "—"));
                    JSONObject row = new JSONObject().put("constellation", name).put("constellationId", constellation)
                            .put("svid", status.getSvid(i)).put("cn0DbHz", status.getCn0DbHz(i))
                            .put("usedInFix", inFix).put("elevationDegrees", status.getElevationDegrees(i))
                            .put("azimuthDegrees", status.getAzimuthDegrees(i));
                    if (status.hasCarrierFrequencyHz(i)) row.put("carrierFrequencyHz", status.getCarrierFrequencyHz(i));
                    rows.put(row);
                }
                satelliteView = "可见 " + status.getSatelliteCount() + " · 参与定位 " + used
                        + "（GPS " + gps + " / 北斗 " + beidou + "）\n\n" + table;
                write(new JSONObject().put("type", "satellites").put("satellites", rows));
                java.util.HashSet<Integer> matched = new java.util.HashSet<>();
                if (scenario != null) {
                    JSONArray expected = scenarioSvids;
                    for (int i = 0; i < status.getSatelliteCount(); i++) {
                        if (!Geometry.usedWithSignal(status.usedInFix(i),status.getCn0DbHz(i)) || status.getConstellationType(i) != scenarioConstellation) continue;
                        for (int j = 0; j < expected.length(); j++) if (expected.optInt(j) == status.getSvid(i)) matched.add(status.getSvid(i));
                    }
                }
                matchingSvids = matched.size(); satelliteNanos = SystemClock.elapsedRealtimeNanos();
            } catch (Exception e) { fail(e); }
        }
    };

    private final OnNmeaMessageListener nmea = (message, timestamp) -> {
        try { write(new JSONObject().put("type", "nmea").put("timestampMs", timestamp).put("sentence", message)); }
        catch (Exception e) { fail(e); }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            loadScenario();
            renderSimulation();
            if (!running) { handler.postDelayed(this, 1000); return; }
            double age = latestFixNanos == 0 ? -1 : (SystemClock.elapsedRealtimeNanos() - latestFixNanos) / 1e9;
            clockView = String.format(Locale.US, "运行 %.0f 秒 · GNSS 点 %d · 最近 GNSS 数据 %s\n记录 %.1f KB",
                    (SystemClock.elapsedRealtime() - started) / 1000.0, fixes,
                    age < 0 ? "尚无" : String.format(Locale.US, "%.1f 秒前%s", age, age > 3 ? "（已陈旧）" : ""), bytes / 1024.0);
            try { if (writer != null) writer.flush(); } catch (Exception e) { fail(e); return; }
            handler.postDelayed(this, 1000);
        }
    };

    static final String START = "local.hackrf.receiver.START", STOP = "local.hackrf.receiver.STOP";
    private final android.os.Binder binder = new LocalBinder();
    public final class LocalBinder extends android.os.Binder { RecordingService getService() { return RecordingService.this; } }
    @Override public android.os.IBinder onBind(Intent intent) { return binder; }
    @Override public void onCreate() {
        super.onCreate();
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        android.app.NotificationManager notifications = getSystemService(android.app.NotificationManager.class);
        notifications.createNotificationChannel(new android.app.NotificationChannel("recording", "GNSS 持续记录", android.app.NotificationManager.IMPORTANCE_LOW));
        File[] previous = getFilesDir().listFiles((dir, name) -> name.startsWith("gnss-") && name.endsWith(".jsonl"));
        if (previous != null) for (File file : previous) if (recording == null || file.lastModified() > recording.lastModified()) recording = file;
        handler.post(tick);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) finishRecording("已停止记录");
        else if (intent != null && START.equals(intent.getAction()) && !running) {
            targetLat = intent.getDoubleExtra("latitude", targetLat);
            targetLon = intent.getDoubleExtra("longitude", targetLon);
            android.app.PendingIntent open = android.app.PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), android.app.PendingIntent.FLAG_IMMUTABLE);
            android.app.PendingIntent stop = android.app.PendingIntent.getService(this, 1,
                    new Intent(this, RecordingService.class).setAction(STOP), android.app.PendingIntent.FLAG_IMMUTABLE);
            startForeground(1, new android.app.Notification.Builder(this, "recording")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("GNSS 正在持续记录")
                    .setContentText("切换地图或锁屏后继续；点此返回实验室")
                    .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                    .addAction(new android.app.Notification.Action.Builder(null, "停止记录", stop).build()).build());
            begin();
        }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        finishRecording("采集服务已结束");
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void begin() {
        if (running) return;
        try {
            if (!Geometry.validTarget(targetLat, targetLon)) throw new IllegalArgumentException("请填写有效 WGS-84 经纬度");
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) throw new IllegalStateException("请先开启系统定位");
            recording = new File(getFilesDir(), "gnss-" + System.currentTimeMillis() + ".jsonl");
            writer = Files.newBufferedWriter(recording.toPath(), StandardCharsets.UTF_8);
            bytes = 0; fixes = 0; latestFixNanos = 0;
            started = SystemClock.elapsedRealtime(); running = true;
            wake=getSystemService(android.os.PowerManager.class).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"ReceiverLab:recording");wake.acquire();
            matchingFixes = 0; previouslyMatched = false;
            points.clear();
            fixView = "GNSS：等待本次新定位";
            networkView = "网络定位：等待数据";
            satelliteView = "等待卫星状态";
            write(new JSONObject().put("type", "session").put("targetLatitudeWgs84", targetLat)
                    .put("targetLongitudeWgs84", targetLon).put("manufacturer", android.os.Build.MANUFACTURER)
                    .put("model", android.os.Build.MODEL).put("sdk", android.os.Build.VERSION.SDK_INT)
                    .put("appVersion", getPackageManager().getPackageInfo(getPackageName(),0).versionName));
            boolean satellitesRegistered = manager.registerGnssStatusCallback(satellites, handler);
            boolean nmeaRegistered = manager.addNmeaListener(nmea, handler);
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
            if (manager.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)) {
                try { manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, this, Looper.getMainLooper()); }
                catch (RuntimeException ignored) { networkView = "网络定位：设备未提供此通道"; }
            }
            write(new JSONObject().put("type", "registration").put("satelliteStatus", satellitesRegistered).put("nmea", nmeaRegistered));
            state = "正在记录 GNSS；卫星状态 " + (satellitesRegistered ? "已注册" : "不可用") + "；NMEA " + (nmeaRegistered ? "已注册" : "不可用");
            handler.removeCallbacks(tick); handler.post(tick);
        } catch (Exception e) { fail(e); }
    }

    @Override public void onLocationChanged(Location location) {
        if (!running) return;
        boolean gnss = LocationManager.GPS_PROVIDER.equals(location.getProvider());
        boolean mock = android.os.Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider();
        double distance = Geometry.distance(location.getLatitude(), location.getLongitude(), targetLat, targetLon);
        String summary = String.format(Locale.US, "%s\n纬度 %.8f · 经度 %.8f\n水平精度 %s · 到目标中心 %.2f m\n椭球高 %s · 垂直精度 %s\n速度 %s · Mock 标记 %s\n位置时间 %s\n系统接收时间 %s",
                gnss ? "GNSS（GPS_PROVIDER，可多星座）" : "网络定位（不计入 GNSS 轨迹）",
                location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? String.format(Locale.US, "±%.1f m", location.getAccuracy()) : "未知", distance,
                location.hasAltitude() ? String.format(Locale.US, "%.2f m", location.getAltitude()) : "未知",
                location.hasVerticalAccuracy() ? String.format(Locale.US, "±%.1f m", location.getVerticalAccuracyMeters()) : "未知",
                location.hasSpeed() ? String.format(Locale.US, "%.2f m/s", location.getSpeed()) : "未知", mock,
                utc.format(new Date(location.getTime())), utc.format(new Date()));
        if (gnss) {
            fixes++; latestFixNanos = location.getElapsedRealtimeNanos();
            fixView = summary;
            points.add(Geometry.point(location.getLatitude(), location.getLongitude(), targetLat, targetLon));
            if (points.size() > 600) points.remove(0);

            evaluateScenario(location, mock);
        } else { networkView = summary; }
        try {
            JSONObject row = new JSONObject().put("type", "location").put("provider", location.getProvider())
                    .put("latitude", location.getLatitude()).put("longitude", location.getLongitude())
                    .put("locationTimeMs", location.getTime()).put("fixElapsedRealtimeNanos", location.getElapsedRealtimeNanos())
                    .put("mock", mock).put("distanceToTargetCenterM", distance);
            if (location.hasAltitude()) row.put("ellipsoidHeightM", location.getAltitude());
            if (location.hasAccuracy()) row.put("horizontalAccuracyM", location.getAccuracy());
            if (location.hasVerticalAccuracy()) row.put("verticalAccuracyM", location.getVerticalAccuracyMeters());
            if (location.hasSpeed()) row.put("speedMps", location.getSpeed());
            write(row);
        } catch (Exception e) { fail(e); }
    }

    @Override public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) finishRecording("系统 GNSS 已关闭，记录停止");
    }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

    private void write(JSONObject row) throws Exception {
        if (!running || writer == null) return;
        row.put("receivedAtMs", System.currentTimeMillis()).put("receivedElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos());
        String line = row.toString() + "\n";
        writer.write(line); bytes += line.getBytes(StandardCharsets.UTF_8).length;
        if (bytes >= 20_000_000 && !"session_end".equals(row.optString("type"))) finishRecording("记录达到 20 MB，本次已停止；可导出后重新开始");
    }

    private void fail(Exception e) { finishRecording("错误：" + e.getMessage()); }

    private void loadScenario() {
        File file = new File(getFilesDir(), "scenario.json");
        if (!file.isFile() || file.lastModified() == scenarioModified) return;
        try {
            if (file.length() > 200000) throw new IllegalArgumentException("场景信息过大");
            JSONObject next = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            long startMs=ReceiverScenario.validate(next);
            double lat=next.getDouble("targetLatitude"),lon=next.getDouble("targetLongitude");
            int duration=next.getInt("durationSeconds"),constellation=next.optInt("constellationId",GnssStatus.CONSTELLATION_GPS);
            JSONArray prns=next.has("svids")?next.getJSONArray("svids"):next.getJSONArray("gpsPRNs");
            boolean changed = scenario == null || !scenario.optString("id").equals(next.getString("id"));
            scenario = next; scenarioConstellation=constellation;scenarioSvids=prns; scenarioStartMs = startMs; scenarioModified = file.lastModified();
            android.util.Log.i("ReceiverLab", "Scenario loaded: " + next.getString("id") + ", phase=" + next.optString("phase") + ", duration=" + duration);
            if (changed) {
                targetLat = lat; targetLon = lon;
                targetRevision++;
                this.points.clear();
                matchingFixes = 0; matchingSvids = 0; lastScenarioFixTime = 0; previouslyMatched = false; scenarioError = Double.NaN;
            }
            write(new JSONObject().put("type", "scenario_sync").put("scenario", next));
        } catch (Exception e) {
            scenario = null; simulationMatched = false;
            android.util.Log.e("ReceiverLab", "Scenario rejected", e);
            simulationView = "模拟场景读取失败：" + e.getMessage();
        }
    }

    private void evaluateScenario(Location location, boolean mock) {
        if (scenario == null || location.getTime() <= lastScenarioFixTime) return;
        lastScenarioFixTime = location.getTime();
        try {
            double t = (location.getTime() - scenarioStartMs) / 1000.0;
            JSONArray points = scenario.getJSONArray("points");
            boolean inWindow = t >= 0 && t <= scenario.getInt("durationSeconds") && activeScenarioWindow();
            double age = (SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) / 1e9;
            scenarioError = Double.NaN;
            if (inWindow) {
                int i = 1;
                while (i < points.length()-1 && points.getJSONArray(i).getDouble(0) < t) i++;
                JSONArray a = points.getJSONArray(i-1), b = points.getJSONArray(i);
                double fraction = (t-a.getDouble(0))/(b.getDouble(0)-a.getDouble(0));
                double[] p = Geometry.point(location.getLatitude(), location.getLongitude(), targetLat, targetLon);
                scenarioError = Math.hypot(p[0]-(a.getDouble(1)+(b.getDouble(1)-a.getDouble(1))*fraction),
                        p[1]-(a.getDouble(2)+(b.getDouble(2)-a.getDouble(2))*fraction));
            }
            int gps = (SystemClock.elapsedRealtimeNanos()-satelliteNanos)/1e9 <= 3 ? matchingSvids : 0;
            matchingFixes = Geometry.matchesScenario(scenarioError, age, gps, inWindow, mock) ? matchingFixes+1 : 0;
            if (matchingFixes >= 3) previouslyMatched = true;
            write(new JSONObject().put("type", "scenario_match").put("scenarioId", scenario.getString("id"))
                    .put("matched", matchingFixes >= 3).put("consecutiveFixes", matchingFixes)
                    .put("matchingSvids", gps).put("constellationId",scenarioConstellation).put("horizontalErrorM", Double.isFinite(scenarioError) ? scenarioError : JSONObject.NULL));
            renderSimulation();
        } catch (Exception e) { simulationView = "场景匹配暂不可用：" + e.getMessage(); }
    }

    private boolean activeScenarioWindow() {
        if (scenario == null) return false;
        String phase = scenario.optString("phase");
        return phase.equals("armed") && System.currentTimeMillis() >= scenarioStartMs
                && System.currentTimeMillis() <= scenarioStartMs + scenario.optInt("durationSeconds")*1000L;
    }

    private void renderSimulation() {
        if (scenario == null) return;
        double age = latestFixNanos == 0 ? Double.POSITIVE_INFINITY : (SystemClock.elapsedRealtimeNanos()-latestFixNanos)/1e9;
        boolean matched = running && activeScenarioWindow() && matchingFixes >= 3 && age <= 3
                && (SystemClock.elapsedRealtimeNanos()-satelliteNanos)/1e9 <= 3 && matchingSvids >= 4;
        String message;
        if (!running) message = "场景已同步 · 点击开始记录进行匹配";
        else if (System.currentTimeMillis() < scenarioStartMs) message = "场景已同步 · 等待计划发送时刻";
        else if (!activeScenarioWindow()) message = "发送窗口已结束 · " + (previouslyMatched ? "本次曾连续匹配模拟场景" : "本次尚未确认匹配");
        else if (age > 3) message = "发送窗口内 · GNSS 数据陈旧或尚无定位";
        else if (matched) message = String.format(Locale.US, "模拟场景匹配 · 轨迹偏差 %.1f m · %s %d", scenarioError, constellation(scenarioConstellation),matchingSvids);
        else message = "发送窗口内 · 尚未连续匹配（" + matchingFixes + "/3）";
        simulationView = message;
        simulationMatched = matched;
    }

    void finishRecording(String message) {
        boolean wasRunning = running;
        if (wasRunning) {
            try { write(new JSONObject().put("type", "session_end").put("reason", message)); }
            catch (Exception ignored) { }
        }
        running = false;
        if(wake!=null&&wake.isHeld())wake.release();wake=null;
        handler.removeCallbacks(tick);
        try { manager.removeUpdates(this); } catch (RuntimeException ignored) { }
        try { manager.unregisterGnssStatusCallback(satellites); } catch (RuntimeException ignored) { }
        try { manager.removeNmeaListener(nmea); } catch (RuntimeException ignored) { }
        if (writer != null) {
            try { writer.close(); } catch (Exception e) { message += "；文件关闭失败：" + e.getMessage(); }
            writer = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        state = message;
        renderSimulation();
        handler.post(tick);
    }

    private static String constellation(int id) {
        switch (id) {
            case GnssStatus.CONSTELLATION_GPS: return "GPS";
            case GnssStatus.CONSTELLATION_BEIDOU: return "北斗";
            case GnssStatus.CONSTELLATION_GLONASS: return "GLO";
            case GnssStatus.CONSTELLATION_GALILEO: return "GAL";
            case GnssStatus.CONSTELLATION_QZSS: return "QZS";
            case GnssStatus.CONSTELLATION_SBAS: return "SBA";
            case GnssStatus.CONSTELLATION_IRNSS: return "IRN";
            default: return "未知";
        }
    }

}
