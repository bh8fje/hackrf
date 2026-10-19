package local.hackrf.receiver;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView state, fixView, networkView, satelliteView, clockView, simulationView;
    private EditText latitude, longitude;
    private Button start, stop, export;
    private TraceView trace;
    private File recording;
    private RecordingService service;
    private boolean bound;
    private int targetRevision = -1;
    private final android.content.ServiceConnection connection = new android.content.ServiceConnection() {
        public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
            service = ((RecordingService.LocalBinder) binder).getService(); targetRevision = -1; render();
        }
        public void onServiceDisconnected(android.content.ComponentName name) { service = null; }
    };
    private final Runnable tick = new Runnable() { public void run() { render(); handler.postDelayed(this, 1000); } };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(24));
        root.setBackgroundColor(Color.rgb(244, 247, 250));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        TextView title = text(root, "GNSS 接收实验室", 26);
        title.setTextColor(Color.rgb(0, 90, 100));
        text(root, "直接观察系统 GNSS，不修改手机位置。\n目标为 WGS-84；到中心距离不是轨迹解算误差。", 14);
        latitude = field(root, "目标纬度 WGS-84", Double.NaN);
        longitude = field(root, "目标经度 WGS-84", Double.NaN);
        text(root, "目标位置默认为空；请手动填写 WGS-84 经纬度，或等待控制台同步当前模拟场景。", 12);
        simulationView = text(root, "未同步模拟场景 · 在 HackRF 控制台开始模拟后自动同步", 17);
        text(root, "状态依据时间、轨迹和目标星座 SVID 连续匹配；不能单凭这些数据鉴别无线信号来源。", 12);
        LinearLayout buttons = new LinearLayout(this);
        root.addView(buttons);
        start = button(buttons, "开始记录", this::begin);
        stop = button(buttons, "停止", () -> finishRecording("已停止记录"));
        stop.setEnabled(false);
        export = button(buttons, "导出", this::export);
        export.setEnabled(false);
        button(root, "打开系统定位设置", () -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        state = text(root, "等待开始 · 通知显示期间持续记录", 16);
        clockView = text(root, "尚无记录", 13);
        fixView = text(root, "GNSS：等待定位", 16);
        networkView = text(root, "网络定位：尚无数据（单独展示，不计入 GNSS 轨迹）", 13);
        trace = new TraceView();
        root.addView(trace, new LinearLayout.LayoutParams(-1, dp(230)));
        satelliteView = text(root, "等待卫星状态；需允许精确位置并开启系统定位", 14);
        satelliteView.setTypeface(android.graphics.Typeface.MONOSPACE);
        text(root, "记录只保存在本机，不申请联网权限。导出由你选择保存位置。\nMock 标志只能反映系统标注，不能证明无线信号真实；星座状态由手机系统报告。", 12);

    }

    private void begin() {
        if (service == null || service.running) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1); return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3); return;
        }
        startRecording();
    }
    private void startRecording() {
        try {
            if (latitude.getText().toString().trim().isEmpty() || longitude.getText().toString().trim().isEmpty()) throw new IllegalArgumentException("请填写目标经纬度，或等待控制台同步场景");
            double lat = Double.parseDouble(latitude.getText().toString()), lon = Double.parseDouble(longitude.getText().toString());
            if (!Geometry.validTarget(lat, lon)) throw new IllegalArgumentException("请填写有效 WGS-84 经纬度");
            startForegroundService(new Intent(this, RecordingService.class).setAction(RecordingService.START)
                    .putExtra("latitude", lat).putExtra("longitude", lon));
        } catch (Exception e) { state.setText("启动失败：" + e.getMessage()); }
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) begin();
        else if (code == 3 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) startRecording();
        else state.setText("请允许精确位置和通知，以便持续记录并显示常驻通知");
    }
    private void finishRecording(String message) { if (service != null) { service.finishRecording(message); render(); } }
    private void render() {
        if (service == null) return;
        if(service.running)getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        state.setText(service.state); fixView.setText(service.fixView); networkView.setText(service.networkView);
        satelliteView.setText(service.satelliteView); clockView.setText(service.clockView); simulationView.setText(service.simulationView);
        simulationView.setTextColor(service.simulationMatched ? Color.rgb(0,115,85) : Color.rgb(155,95,0));
        start.setEnabled(!service.running); stop.setEnabled(service.running); export.setEnabled(!service.running && service.recording != null);
        latitude.setEnabled(!service.running); longitude.setEnabled(!service.running);
        if (targetRevision != service.targetRevision) {
            latitude.setText(Geometry.targetText(service.targetLat)); longitude.setText(Geometry.targetText(service.targetLon));
            targetRevision = service.targetRevision;
        }
        trace.points.clear(); trace.points.addAll(service.points); trace.invalidate();
    }
    @Override protected void onStart() {
        super.onStart(); bound = bindService(new Intent(this, RecordingService.class), connection, BIND_AUTO_CREATE);
        handler.post(tick);
    }
    @Override protected void onStop() {
        handler.removeCallbacks(tick);
        if (bound) { unbindService(connection); bound = false; }
        service = null;
        super.onStop();
    }
    private void export() {
        if (service == null || service.running || service.recording == null) return;
        recording = service.recording;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/x-ndjson").putExtra(Intent.EXTRA_TITLE, recording.getName());
        startActivityForResult(intent, 2);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 2 || result != RESULT_OK || data == null || data.getData() == null) return;
        try (FileInputStream input = new FileInputStream(recording); OutputStream output = getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (output == null) throw new IllegalStateException("无法打开保存位置");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            state.setText("记录已导出");
        } catch (Exception e) { state.setText("导出失败：" + e.getMessage()); }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(LinearLayout root, String label, int size) {
        TextView view = new TextView(this); view.setText(label); view.setTextSize(size);
        view.setTextColor(Color.rgb(25, 45, 60)); view.setPadding(0, dp(8), 0, dp(8));
        view.setTextIsSelectable(true); root.addView(view); return view;
    }
    private EditText field(LinearLayout root, String label, double value) {
        text(root, label, 13);
        EditText view = new EditText(this);
        view.setContentDescription(label);
        view.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        view.setSingleLine(true); view.setText(Geometry.targetText(value));
        root.addView(view); return view;
    }
    private Button button(LinearLayout root, String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setOnClickListener(v -> action.run());
        root.addView(button); return button;
    }

    private final class TraceView extends View {
        final ArrayList<double[]> points = new ArrayList<>();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        TraceView() { super(MainActivity.this); setContentDescription("GNSS 实测轨迹，圆点是目标中心，东向右、北向上"); }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(8, 25, 42));
            double range = 20;
            for (double[] p : points) range = Math.max(range, Math.max(Math.abs(p[0]), Math.abs(p[1])) * 1.15);
            float scale = (float) (Math.min(getWidth(), getHeight()) / 2.2 / range);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            paint.setStrokeWidth(dp(1)); paint.setColor(Color.rgb(55, 75, 90));
            canvas.drawLine(0, cy, getWidth(), cy, paint); canvas.drawLine(cx, 0, cx, getHeight(), paint);
            paint.setColor(Color.CYAN); paint.setStrokeWidth(dp(2));
            for (int i = 1; i < points.size(); i++) {
                double[] a = points.get(i - 1), b = points.get(i);
                canvas.drawLine(cx + (float)a[0] * scale, cy - (float)a[1] * scale,
                        cx + (float)b[0] * scale, cy - (float)b[1] * scale, paint);
            }
            paint.setColor(Color.rgb(255, 190, 80)); canvas.drawCircle(cx, cy, dp(4), paint);
            paint.setColor(Color.WHITE); paint.setTextSize(dp(12));
            canvas.drawText(String.format(Locale.US, "东 → / 北 ↑ · 范围 ±%.0f m · 最近 %d 点", range, points.size()), dp(8), dp(20), paint);
        }
    }
}
