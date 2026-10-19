package local.hackrf.controller;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.time.OffsetDateTime;
import org.json.JSONObject;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** USB bring-up uses only the upstream HackRF read-only vendor requests. */
public class MainActivity extends Activity {
    private static final String PERMISSION = "local.hackrf.controller.USB_PERMISSION";
    static volatile boolean visible;
    private TextView backgroundStatus;
    private UsbManager usb;
    private TextView status;
    private Button connect, importButton, send, stop, localGenerate, localStart;
    private LinearLayout nativeForm, actionBar;
    private String currentCity="";
    private boolean cityAttempted;
    private com.amap.api.location.AMapLocationClient cityLocator;
    private Runnable afterCity;
    private Spinner places, motionMode, coordinate;
    private EditText placeQuery;
    private TextView placeSearchStatus;
    private Button placeSearchButton;
    private int placeSearchGeneration;
    private CheckBox randomHeading;
    private Button restoreLocation;
    private final java.util.LinkedHashMap<String,EditText> extra = new java.util.LinkedHashMap<>();
    private final String[] routes={"stroll","office","roadside","wander","circle"};
    private String lastRoute;
    private TextView signalEnvironment;
    private TextView behaviorDescription, traceSummary;
    private TextView homePlace, homeCoordinates, deviceSummary, runSummary, deviceDetails;
    private LocationMap locationMap, monitorMap;
    private String sceneSatelliteText="场景卫星：准备完成后显示（非手机实测）";
    private final java.util.IdentityHashMap<LinearLayout,LinearLayout> numberRows=new java.util.IdentityHashMap<>();
    private LinearLayout pages, homePage, placesPage, settingsPage, devicePage, diagnostics;
    private Button[] navigation;
    private PathPreview preview;
    private SpeedPreview speedPreview;
    private static final int INK=0xff142c3a, MUTED=0xff617581, TEAL=0xff087f79;
    private int currentPage;
    private ToggleButton[] signalButtons;
    private TextView systemCapability;
    private boolean mixedSelected(){return signalButtons[0].isChecked()&&signalButtons[1].isChecked();}
    private boolean supportedSelection(){return singleSystem()||(mixedSelected()&&SignalProfile.MIXED_ENABLED);}
    private String selectedSystemLabel(){return mixedSelected()?"GPS＋北斗":SYSTEM_LABELS[signalMode];}
    private String selectedSystem(){return mixedSelected()?SignalProfile.MIXED_SYSTEM:SYSTEMS[signalMode];}
    private boolean singleSystem(){return signalButtons[0].isChecked()!=signalButtons[1].isChecked();}
    private void selectSystem(int mode){signalButtons[0].setChecked(mode==0);signalButtons[1].setChecked(mode==1);}
    private void updateSystems(){
        signalMode=signalButtons[1].isChecked()?1:0;
        systemCapability.setText(singleSystem()?"实时流式模拟，不保存整段 I/Q；仅生成离线样本时保存文件。":signalButtons[0].isChecked()?"GPS 为主、北斗为辅；共用地点与运动参数，实时流式模拟。":"请选择卫星系统。");
        if(localStart!=null){if(signalMode==1)try{if(Double.parseDouble(duration.getText().toString())<90)duration.setText("90");}catch(NumberFormatException ignored){}refreshTx();if(preview!=null)preview.invalidate();}
    }
    private int signalMode;
    private static final String[] SYSTEMS={"gps","bds"},SYSTEM_LABELS={"GPS","北斗"};
    private ScrollView pageScroll;
    private LinearLayout movementCard,biasCard;
    private EditText alias, latitude, longitude, minimum, maximum, duration, speed, radius, heading, drift, verticalDrift;
    private final ArrayList<JSONObject> savedPlaces = new ArrayList<>();
    private int selectedPlace = -1;
    private TextView scenarioView, txView, downloadSummary, runtimeDetails, progressDetails;
    private EditText gain;
    private CheckBox amplifier;
    private ProgressBar progress;
    private boolean importing;
    private boolean locationPermissionAsked;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateMap=this::updateMapLocation;
    private final Runnable tick = new Runnable() { public void run() { refreshTx(); handler.postDelayed(this, 1000); } };
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean reading;
    private static String verifiedDeviceName="", verifiedDeviceReport="";
    private String connectionIssue="";
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (PERMISSION.equals(intent.getAction())) {
                // Check the real permission instead of trusting broadcast extras.
                ArrayList<UsbDevice> found = devices();
                if (found.size() == 1 && usb.hasPermission(found.get(0))) { read(found.get(0)); return; }
                connectionIssue="USB 授权未完成，请点击连接并允许访问。";status.setText(connectionIssue);scan();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbDevice removed=intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if(removed!=null && removed.getVendorId()==0x1d50 && removed.getProductId()==0x6089){verifiedDeviceName=verifiedDeviceReport="";connectionIssue="HackRF 已断开，请重新连接。";status.setText(connectionIssue);}
                scan();
            } else scan();
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        usb = getSystemService(UsbManager.class);
        if(!TransmitService.busy && getSharedPreferences("controller",0).getBoolean("taskInProgress",false))TransmitService.resetFlow(this,"上次任务意外退出");

        getWindow().setStatusBarColor(INK); getWindow().setNavigationBarColor(0xfff3f6f7);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(0xfff3f6f7);setContentView(root);
        if(Build.VERSION.SDK_INT>=20) root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout header=new LinearLayout(this);header.setOrientation(1);header.setPadding(dp(22),dp(14),dp(22),dp(14));header.setBackgroundColor(INK);root.addView(header);
        TextView title=text(header,"天书",28);title.setTextColor(0xffffffff);title.setTypeface(null,1);
        TextView subtitle=text(header,"步行定位 · Android",13);subtitle.setTextColor(0xffa6cecc);
        LinearLayout tabs=new LinearLayout(this);root.addView(tabs);navigation=new Button[4];
        String[] names={"模拟","地点","设置","设备"};for(int i=0;i<names.length;i++){final int page=i;navigation[i]=button(tabs,names[i],()->showPage(page));navigation[i].setElevation(0);navigation[i].setStateListAnimator(null);navigation[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xfff3f6f7));navigation[i].setLayoutParams(new LinearLayout.LayoutParams(0,dp(52),1));}
        ScrollView scroll=new ScrollView(this);pageScroll=scroll;scroll.setFillViewport(true);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        pages=new LinearLayout(this);pages.setOrientation(1);pages.setPadding(dp(16),dp(8),dp(16),dp(16));scroll.addView(pages);
        homePage=page();placesPage=page();settingsPage=page();devicePage=page();
        LinearLayout systemCard=card(homePage,"卫星系统");
        LinearLayout systems=new LinearLayout(this);systemCard.addView(systems);signalButtons=new ToggleButton[2];
        systemCapability=text(systemCard,"实时流式模拟，不保存整段 I/Q；仅生成离线样本时保存文件。",13);
        for(int i=0;i<2;i++){ToggleButton choice=new ToggleButton(this);signalButtons[i]=choice;String label=i==0?"GPS L1 C/A":"北斗 B1I";choice.setTextOn(label);choice.setTextOff(label);choice.setText(label);choice.setContentDescription(label);choice.setAllCaps(false);choice.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{0xffffffff,INK}));choice.setBackgroundTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{TEAL,0xffe2e9ec}));systems.addView(choice,new LinearLayout.LayoutParams(0,dp(48),1));}
        signalButtons[0].setChecked(true);
        for(ToggleButton choice:signalButtons)choice.setOnCheckedChangeListener((button,checked)->updateSystems());
        LinearLayout execution=card(homePage,"运行状态");homePage.removeView(execution);homePage.addView(execution,0);
        LinearLayout stateRow=new LinearLayout(this);stateRow.setGravity(android.view.Gravity.CENTER_VERTICAL);execution.addView(stateRow);
        runSummary=text(stateRow,"准备就绪",18);runSummary.setTypeface(null,1);runSummary.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        runtimeDetails=text(execution,"连接设备后即可开始",13);
        txView=text(execution,"",15);txView.setLineSpacing(dp(5),1);txView.setPadding(0,dp(12),0,dp(12));txView.setTextIsSelectable(true);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(TEAL));execution.addView(progress);
        progressDetails=text(execution,"",13);
        button(execution,"查看／收起下载记录",()->downloadSummary.setVisibility(downloadSummary.getVisibility()==android.view.View.VISIBLE?android.view.View.GONE:android.view.View.VISIBLE));
        downloadSummary=text(execution,"尚无文件下载记录",13);downloadSummary.setLineSpacing(dp(5),1);downloadSummary.setTextIsSelectable(true);downloadSummary.setVisibility(android.view.View.GONE);
        LinearLayout device=card(devicePage,"连接设备");deviceSummary=text(device,"正在检查设备…",16);
        connect=button(device,"连接 HackRF",()->authorize());
        deviceDetails=text(device,"",12);deviceDetails.setTextIsSelectable(true);
        button(device,"重新扫描设备",()->scan());
        text(device,"通过 OTG 连接 HackRF，并允许 USB 访问。读取成功后，下方显示设备信息。",13);
        LinearLayout location=card(placesPage,"模拟地点");homePlace=text(location,"选择或添加一个地点",19);homeCoordinates=text(location,"",14);homeCoordinates.setPadding(0,dp(12),0,dp(8));homeCoordinates.setTextIsSelectable(true);
        placeQuery=new EditText(this);placeQuery.setSingleLine(true);placeQuery.setHint("地点名称 / 地址，或 经度,纬度");placeQuery.setContentDescription("地点查找");location.addView(placeQuery);
        placeSearchButton=button(location,"查找地点",this::findPlace);
        placeSearchStatus=text(location,"经纬度按下方坐标系输入；文字搜索需联网。",12);
        locationMap=new LocationMap(this,185);location.addView(locationMap,new LinearLayout.LayoutParams(-1,-2));
        locationMap.setPickListener((lat,lon)->choosePlace("地图选点",lat,lon,true));
        text(location,"点击或双击地图选点，也可长按拖动目标标记；文字搜索默认当前定位城市。",12);
        button(location,"命名并保存所选地点",this::savePickedPlace);
        text(location,"高德底图需联网加载，模拟坐标保持 WGS-84。",12);button(location,"选择地点与编辑地址",()->showPage(1));
        LinearLayout monitor=card(homePage,"模拟监控");monitorMap=new LocationMap(this,240);monitor.addView(monitorMap,new LinearLayout.LayoutParams(-1,-2));
        text(monitor,"卫星为生成场景的模型信息，不代表手机实际接收或实测信号强度。",12);
        LinearLayout environment=card(homePage,"本次信号环境");
        signalEnvironment=text(environment,"随运动方式自动加载",17);
        text(environment,"信号环境随运动方式自动切换；主要强星保持接收，弱星按场景缓慢变化，避免整体过弱。",13);
        buildNativeForm(homePage);
        LinearLayout radio=card(settingsPage,"设备输出");gain=number(radio,"TX 增益 / dB（0–47）",getSharedPreferences("controller",0).getInt("txGain",47));
        amplifier=new CheckBox(this);amplifier.setText("开启 RF 放大器");amplifier.setChecked(getSharedPreferences("controller",0).getBoolean("txAmplifier",true));radio.addView(amplifier);
        text(radio,"用于已准备的隔离实验环境。增益值不等于实测输出功率。",13);
        LinearLayout sources=card(settingsPage,"导航数据来源");
        text(sources,"优先复用有效缓存；需要更新时自动下载，并检查数据能否覆盖完整模拟时段。下载进度、速度和文件大小显示在运行状态中。",13);
        text(sources,"数据来源与下载时间保留在诊断信息中。数据过期或不完整时会提示更新。",12);
        LinearLayout background=card(settingsPage,"后台持续运行");backgroundStatus=text(background,"",14);
        text(background,"本机日志确认省电冻结会中断USB供数。若要切后台或锁屏，请允许后台运行，并在手机省电管理中设为不限制；未设置时请保持本页前台。",13);
        button(background,"允许后台持续运行",()->{
            try{Intent power=new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,android.net.Uri.parse("package:"+getPackageName()));startActivity(power);}catch(Exception e){startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())));}
        });
        button(background,"打开系统应用设置",()->startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName()))));
        LinearLayout tools=card(settingsPage,"场景与支持");localGenerate=button(tools,"仅准备场景",()->startNative(false));
        text(tools,"只下载与生成，不启动设备输出。",13);
        button(tools,"查看场景与诊断",()->diagnostics.setVisibility(diagnostics.getVisibility()==android.view.View.VISIBLE?android.view.View.GONE:android.view.View.VISIBLE));
        diagnostics=new LinearLayout(this);diagnostics.setOrientation(1);tools.addView(diagnostics);diagnostics.setVisibility(android.view.View.GONE);
        button(diagnostics,"重新扫描设备",()->scan());status=text(diagnostics,"正在检查 USB…",13);status.setTextIsSelectable(true);
        scenarioView=text(diagnostics,"尚无准备的场景",13);
        importButton=button(diagnostics,"导入 Mac 场景包",()->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),10));
        send=button(diagnostics,"发送已准备场景",()->startTx());
        if((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0){importButton.setVisibility(android.view.View.GONE);send.setVisibility(android.view.View.GONE);}
        button(diagnostics,"复制诊断信息",()->{getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("天书诊断",status.getText()+"\n"+scenarioView.getText()+"\n"+TransmitService.status+"\n上次结束："+TransmitService.lastOutcome+"\n"+transmissionDiagnostic()));Toast.makeText(this,"已复制",Toast.LENGTH_SHORT).show();});
        text(tools,"GPS／北斗导航模拟\n接收结果请在 GNSS 接收实验室查看。GCJ-02 为近似转换；预置海拔仅供参考。",12);
        LinearLayout actions=new LinearLayout(this);actionBar=actions;actions.setGravity(android.view.Gravity.CENTER_VERTICAL);stateRow.addView(actions,new LinearLayout.LayoutParams(-2,-2));
        localStart=button(actions,"开始模拟",()->startNative(true));localStart.setTextColor(0xffffffff);localStart.setBackgroundTintList(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xffa7b9ba,TEAL}));localStart.setTextSize(13);localStart.setMinWidth(0);localStart.setMinimumWidth(0);localStart.setPadding(dp(14),0,dp(14),0);localStart.setLayoutParams(new LinearLayout.LayoutParams(-2,dp(48)));
        stop=button(actions,"停止",()->startService(new Intent(this,TransmitService.class).setAction(TransmitService.STOP)));stop.setTextSize(13);stop.setMinWidth(0);stop.setMinimumWidth(0);stop.setPadding(dp(14),0,dp(14),0);stop.setLayoutParams(new LinearLayout.LayoutParams(-2,dp(48)));
        root.setFocusableInTouchMode(true);root.requestFocus();
        showPage(0);
        IntentFilter filter = new IntentFilter(PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        scan();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); scan(); }
    private JSONObject lastTransmission() throws Exception {
        File[] files=getFilesDir().listFiles((dir,name)->name.matches("tx-[0-9]+\\.json"));File latest=null;
        if(files!=null)for(File file:files)if(latest==null || file.lastModified()>latest.lastModified())latest=file;
        if(latest==null || latest.length()>200000)return null;
        return new JSONObject(new String(java.nio.file.Files.readAllBytes(latest.toPath()),StandardCharsets.UTF_8));
    }
    private String transmissionDiagnostic(){try{JSONObject record=lastTransmission();return record==null?"暂无发送记录":"最近一次发送记录（以其中时间为准）：\n"+record.toString(2);}catch(Exception e){return "发送记录读取失败："+e.getMessage();}}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout page(){LinearLayout page=new LinearLayout(this);page.setOrientation(1);pages.addView(page);return page;}
    private LinearLayout card(LinearLayout parent,String title){LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(16),dp(12),dp(16),dp(14));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xffffffff);bg.setCornerRadius(dp(18));card.setBackground(bg);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(6),0,dp(8));parent.addView(card,lp);TextView label=text(card,title,16);label.setTypeface(null,1);return card;}
    private Button button(LinearLayout root,String label,Runnable action){numberRows.remove(root);Button b=new Button(this);b.setText(label);b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xff92a1a7,TEAL}));b.setAllCaps(false);b.setMinHeight(dp(48));b.setOnClickListener(v->action.run());root.addView(b);return b;}
    private void showPage(int page){if(pageScroll!=null)pageScroll.scrollTo(0,0);currentPage=page;if(actionBar!=null)actionBar.setVisibility(page==0?0:8);homePage.setVisibility(page==0?0:8);placesPage.setVisibility(page==1?0:8);settingsPage.setVisibility(page==2?0:8);devicePage.setVisibility(page==3?0:8);
        for(int i=0;i<navigation.length;i++){navigation[i].setTextColor(i==page?TEAL:MUTED);navigation[i].setTypeface(null,i==page?1:0);}if(preview!=null)preview.invalidate();if(homePlace!=null && latitude!=null)updateSummary();}
    private TextView text(LinearLayout root,String value,int size){numberRows.remove(root);TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(size<=14?MUTED:INK);v.setPadding(0,dp(4),0,dp(6));root.addView(v);return v;}
    private ArrayList<UsbDevice> devices() {
        ArrayList<UsbDevice> found = new ArrayList<>();
        for (UsbDevice device : usb.getDeviceList().values()) if (device.getVendorId() == 0x1d50 && device.getProductId() == 0x6089) found.add(device);
        return found;
    }
    private void scan() {
        if (reading || TransmitService.busy) return;
        StringBuilder report = new StringBuilder("手机：" + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + "\n");
        report.append("USB 主机功能：").append(getPackageManager().hasSystemFeature("android.hardware.usb.host") ? "支持" : "未报告支持").append('\n');
        for (UsbDevice device : usb.getDeviceList().values()) report.append(String.format(Locale.US, "\nUSB %04x:%04x · %s\n", device.getVendorId(), device.getProductId(), device.getProductName()));
        ArrayList<UsbDevice> found=devices();int count = found.size();
        report.append("\nHackRF One 数量：").append(count);
        if (count == 0) report.append("\n尚未识别，请检查 OTG 连接、供电及 PortaPack 的 HackRF 模式。");
        else if (count > 1) report.append("\n请只连接一台 HackRF，以免选错设备。");
        else report.append("\n已识别，请点击授权并读取。");
        status.setText(report);updateConnection();
        if(count==1 && usb.hasPermission(found.get(0)) && !verifiedDeviceName.equals(found.get(0).getDeviceName()))read(found.get(0));
    }
    private void updateConnection(){
        ArrayList<UsbDevice> found=devices();
        renderConnection(found.size(),found.size()==1?found.get(0).getDeviceName():"",found.size()==1 && usb.hasPermission(found.get(0)));
    }
    private void renderConnection(int count,String name,boolean permitted){
        if(count!=1 || !permitted || !verifiedDeviceName.equals(name))verifiedDeviceName=verifiedDeviceReport="";
        boolean connected=count==1 && permitted && verifiedDeviceName.equals(name);
        connect.setText(reading?"读取中…":connected?"已连接":"连接 HackRF");
        connect.setEnabled(count==1 && !reading && !TransmitService.busy && !connected);
        deviceSummary.setText(reading?"正在读取 HackRF…":connected?(TransmitService.busy?"HackRF 已连接 · 任务运行中":"HackRF 已连接"):count>1?"请只连接一台 HackRF":count==0?"设备未连接 · 请通过 OTG 连接 HackRF":!connectionIssue.isEmpty()?"连接未完成，请重试":permitted?"USB 已授权，请点击连接读取设备":"已发现 HackRF，请连接授权");
        String details=connected?verifiedDeviceReport:connectionIssue;
        deviceDetails.setText(details);deviceDetails.setVisibility(details.isEmpty()?android.view.View.GONE:android.view.View.VISIBLE);
    }
    private void authorize() {
        ArrayList<UsbDevice> devices = devices();
        if (reading || TransmitService.busy || devices.size() != 1) { scan(); return; }
        UsbDevice device = devices.get(0);
        connectionIssue="";
        try {
            PendingIntent permission = PendingIntent.getBroadcast(this, 0, new Intent(PERMISSION).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE);
            usb.requestPermission(device, permission);
        } catch (RuntimeException e) { status.setText("无法请求 USB 授权：" + e.getMessage()); }
    }
    private void read(UsbDevice device) {
        if (reading || TransmitService.busy) return;
        reading = true; connectionIssue="";updateConnection();status.setText("正在读取 HackRF 信息…");
        worker.execute(() -> {
            String result;boolean success=false;
            try {
                UsbDeviceConnection connection = usb.openDevice(device);
                if (connection == null) throw new IllegalStateException("无法打开设备，请重新授权或检查连接");
                try {
                    byte[] board = new byte[1], version = new byte[255];
                    int boardSize = connection.controlTransfer(0xc0, 14, 0, 0, board, board.length, 1000);
                    int versionSize = connection.controlTransfer(0xc0, 15, 0, 0, version, version.length, 1000);
                    if (boardSize != 1 || versionSize <= 0) throw new IllegalStateException("读取失败：board=" + boardSize + ", version=" + versionSize);
                    StringBuilder report = new StringBuilder("HackRF USB 控制读取成功\n\n");
                    report.append("板卡 ID：").append(board[0] & 255).append("\n固件：")
                          .append(new String(version, 0, versionSize, StandardCharsets.US_ASCII).replace("\u0000", "").trim())
                          .append("\n设备版本（bcdDevice）：").append(device.getVersion());
                    boolean bulkOut = false;
                    for (int i = 0; i < device.getInterfaceCount(); i++) {
                        UsbInterface face = device.getInterface(i);
                        for (int j = 0; j < face.getEndpointCount(); j++) {
                            UsbEndpoint endpoint = face.getEndpoint(j);
                            report.append(String.format(Locale.US, "\n接口 %d / 端点 0x%02x / %s / 类型 %d / 包长 %d", face.getId(), endpoint.getAddress(), endpoint.getDirection() == UsbConstants.USB_DIR_OUT ? "OUT" : "IN", endpoint.getType(), endpoint.getMaxPacketSize()));
                            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.getDirection() == UsbConstants.USB_DIR_OUT) bulkOut = true;
                        }
                    }
                    report.append(bulkOut ? "\n\n已找到 Bulk OUT 数据端点，可以生成并发送场景。" : "\n\n未找到 Bulk OUT 端点，暂不能进入数据传输。");
                    result = report.toString();success=bulkOut;
                } finally { connection.close(); }
            } catch (Exception e) { result = "USB 读取失败：" + e.getMessage(); }
            final String report = result;final boolean valid=success;
            runOnUiThread(() -> {
                reading = false;
                if (!isDestroyed()) {
                    boolean present=usb.getDeviceList().containsKey(device.getDeviceName()) && usb.hasPermission(device);
                    if(present && valid){verifiedDeviceName=device.getDeviceName();verifiedDeviceReport=report;connectionIssue="";}
                    else {verifiedDeviceName=verifiedDeviceReport="";connectionIssue=present?report:"HackRF 已断开或授权失效，请重新连接。";}
                    status.setText(present?report:connectionIssue);updateConnection();
                }
            });
        });
    }
    private void setFormEnabled(android.view.View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) setFormEnabled(group.getChildAt(i),enabled);
        }
    }
    private EditText number(LinearLayout root, String label, double value) {
        LinearLayout row=numberRows.get(root);
        if(row==null || row.getChildCount()>=2){row=new LinearLayout(this);row.setBaselineAligned(false);root.addView(row);numberRows.put(root,row);}
        LinearLayout cell=new LinearLayout(this);cell.setOrientation(1);cell.setPadding(dp(3),0,dp(3),dp(3));
        row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));text(cell,label,12);
        EditText field=new EditText(this);field.setTextSize(16);
        field.setBackground(numberBackground());
        field.setPadding(dp(10),dp(10),dp(10),dp(10));
        field.setIncludeFontPadding(true);field.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);
        field.setContentDescription(label); field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        android.graphics.Paint.FontMetrics metrics=field.getPaint().getFontMetrics();
        field.setMinHeight(Math.max(dp(48),(int)Math.ceil(metrics.bottom-metrics.top)+field.getPaddingTop()+field.getPaddingBottom()));
        field.setText(String.format(Locale.US,"%.8f",value).replaceAll("0+$", "").replaceAll("\\.$", "")); cell.addView(field,new LinearLayout.LayoutParams(-1,-2)); return field;
    }
    private android.graphics.drawable.StateListDrawable numberBackground(){
        android.graphics.drawable.StateListDrawable states=new android.graphics.drawable.StateListDrawable();
        for(boolean focused:new boolean[]{true,false}){
            android.graphics.drawable.GradientDrawable border=new android.graphics.drawable.GradientDrawable();
            border.setColor(0xfff7fafb);border.setCornerRadius(dp(8));border.setStroke(dp(focused?2:1),focused?TEAL:0xffc4d2d7);
            states.addState(focused?new int[]{android.R.attr.state_focused}:new int[]{},border);
        }
        return states;
    }
    private double value(EditText field) { return Double.parseDouble(field.getText().toString().trim()); }
    private JSONObject input() throws Exception {
        if(minimum.getText().toString().trim().isEmpty()||maximum.getText().toString().trim().isEmpty())throw new IllegalArgumentException("请在设置中填写当前地点的高度下限与上限");
        double low=value(minimum),high=value(maximum);
        JSONObject input=new JSONObject().put("signalProfile",SignalProfile.IDS[SignalProfile.forRoute(routes[motionMode.getSelectedItemPosition()])]).put("latitude",value(latitude)).put("longitude",value(longitude)).put("height",(low+high)/2).put("heightVariation",(high-low)/2)
            .put("duration",value(duration)).put("speed",value(speed)).put("radius",value(radius)).put("heading",value(heading)).put("horizontalDrift",value(drift)).put("verticalDrift",value(verticalDrift))
            .put("route",routes[motionMode.getSelectedItemPosition()]).put("coordinateSystem",coordinate.getSelectedItemPosition()==1?"GCJ-02":"WGS-84").put("randomHeading",randomHeading.isChecked());
        for(java.util.Map.Entry<String,EditText> entry:extra.entrySet())input.put(entry.getKey(),value(entry.getValue()));
        NativeScene.parameters(input);return input;
    }
    private Spinner picker(LinearLayout parent,String label,String[] entries){text(parent,label,14);Spinner p=new Spinner(this);p.setMinimumHeight(dp(48));p.setContentDescription(label);p.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,entries));parent.addView(p);return p;}
    private void parameter(LinearLayout root,String key,String label,double value){extra.put(key,number(root,label,value));}
    private void buildNativeForm(LinearLayout root) {
        LinearLayout position=card(placesPage,"我的地点");places=picker(position,"选择地点",new String[]{"自定义位置"});
        alias=new EditText(this);alias.setHint("地点名称");alias.setContentDescription("地点名称");alias.setSingleLine(true);position.addView(alias);
        coordinate=picker(position,"坐标系",new String[]{"WGS-84（GPS / Google）","GCJ-02（高德）"});
        latitude=number(position,"纬度（北纬为正）",30+39/60.0+29/3600.0);longitude=number(position,"经度（东经为正）",104+6/60.0+13/3600.0);
        button(position,"新增地点",()->placeAction("新增位置"));button(position,"保存修改",()->placeAction("保存修改"));
        restoreLocation=button(position,"恢复默认地址",()->restoreLocation());
        button(position,"删除地点",()->{if(selectedPlace<0){Toast.makeText(this,"请先选择地点",Toast.LENGTH_SHORT).show();return;}new android.app.AlertDialog.Builder(this).setTitle("删除地点？").setMessage("只移除此地点，历史场景和记录仍保留。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->placeAction("删除位置")).show();});
        LinearLayout simulation=card(root,"运动与时长");nativeForm=new LinearLayout(this);nativeForm.setOrientation(1);simulation.addView(nativeForm);
        motionMode=picker(nativeForm,"运动方式",new String[]{"园区散步 · 看看停停","办公室 · 久坐与短距离走动","直线行走 · 左右偏离","随意走走 · 快慢与停留","绕圆行走 · 不规则与快慢"});duration=number(nativeForm,"持续时间 / 秒（60–600）",60);
        LinearLayout times=new LinearLayout(this);nativeForm.addView(times);for(int seconds:new int[]{60,180,300,600}){Button time=button(times,seconds/60+"分钟",()->duration.setText(Integer.toString(seconds)));time.setLayoutParams(new LinearLayout.LayoutParams(0,dp(48),1));}
        behaviorDescription=text(nativeForm,"",14);
        speed=number(nativeForm,"步速 / 米每秒",.5);radius=number(nativeForm,"活动范围半径 / 米",12);
        text(nativeForm,"GPS：60秒约312MB；北斗：至少90秒，约900MB。文件大小随时长增加。",13);
        LinearLayout path=card(root,"轨迹预览");preview=new PathPreview(this);path.addView(preview,new LinearLayout.LayoutParams(-1,dp(190)));LinearLayout zoomRow=new LinearLayout(this);path.addView(zoomRow);for(String label:new String[]{"缩小","全图","放大"}){Button control=button(zoomRow,label,()->{if(label.equals("全图"))preview.resetView();else preview.setZoom(preview.zoom*(label.equals("放大")?2:.5));});control.setLayoutParams(new LinearLayout.LayoutParams(0,dp(48),1));}speedPreview=new SpeedPreview(this);path.addView(speedPreview,new LinearLayout.LayoutParams(-1,dp(85)));text(path,"移动速度 · 曲线贴底时表示停留",12);traceSummary=text(path,"",13);text(path,"蓝色实线：人的移动；橙色虚线：移动模式叠加30–80米（东南至西南）起点偏移与漂移，办公室仅在偏移后起点20.00米内自然游移。绕圆橙线展示1–5秒采样连线。合成行为轨迹，不是实测轨迹或道路地图。",12);
        LinearLayout height=card(settingsPage,"高度");minimum=number(height,"高度下限 / 米",399.92);maximum=number(height,"高度上限 / 米",400.08);parameter(height,"heightSmoothing","高度平滑 / 秒",2);
        text(height,"参考海拔只作模拟近似值；垂直漂移另行叠加。",13);
        LinearLayout movement=card(settingsPage,"运动细节");movementCard=movement;
        parameter(movement,"walkMinimum","连续走动下限 / 秒",12);parameter(movement,"walkMaximum","连续走动上限 / 秒",35);
        parameter(movement,"pauseMinimum","停留时长下限 / 秒",3);parameter(movement,"pauseMaximum","停留时长上限 / 秒",12);parameter(movement,"reactionSeconds","起停过渡 / 秒（0.2–5）",.8);
        text(movement,"每次打开按场景随机选择速度区间，本次可调整。人物场景按走动与停留交替。办公室到达目的地会提前坐下；过渡越短，起步和停下越明显。",13);
        parameter(movement,"speedVariation","步速随机幅度 / 米每秒",.2);parameter(movement,"speedSmoothing","速度平滑 / 秒",2);
        LinearLayout speedRow=numberRows.remove(movement);
        for(int i=0;i<speedRow.getChildCount();i++)((TextView)((LinearLayout)speedRow.getChildAt(i)).getChildAt(0)).setMinLines(2);
        randomHeading=new CheckBox(this);randomHeading.setText("随机初始朝向");randomHeading.setChecked(true);movement.addView(randomHeading);
        heading=number(movement,"固定朝向 / 度（北0 / 东90）",0);numberRows.remove(movement);
        parameter(movement,"headingMinimum","随机朝向下限 / 度",0);parameter(movement,"headingMaximum","随机朝向上限 / 度",360);
        parameter(movement,"headingHoldMinimum","方向保持下限 / 秒",3);parameter(movement,"headingHoldMaximum","方向保持上限 / 秒",8);parameter(movement,"turnSmoothing","转向平滑 / 秒",2);
        LinearLayout bias=card(settingsPage,"定位漂移");biasCard=bias;drift=number(bias,"水平漂移峰值 / 米",2.5);verticalDrift=number(bias,"垂直漂移峰值 / 米",.5);parameter(bias,"driftSmoothing","漂移平滑 / 秒",10);
        parameter(bias,"seed","本次随机种子",42);button(bias,"更换随机种子",()->{extra.get("seed").setText(Integer.toString(new java.util.Random().nextInt(Integer.MAX_VALUE)));preview.invalidate();});
        text(bias,"启动时随机：水平2.00–3.00米、垂直0.30–0.80米、平滑8.00–12.00秒。办公室固定20.00米内自然游移，不使用这些参数。",13);
        try {
            JSONObject old=PhoneConfig.load(this);PhoneMotion m=NativeScene.parameters(old);
            latitude.setText(Double.toString(m.latitude));longitude.setText(Double.toString(m.longitude));coordinate.setSelection(m.coordinateSystem.equals("GCJ-02")?1:0);
            minimum.setText(Double.toString(m.minimum));maximum.setText(Double.toString(m.maximum));speed.setText(Double.toString(m.speed));radius.setText(Double.toString(m.radius));heading.setText(Double.toString(m.heading));drift.setText(String.format(Locale.US,"%.2f",m.drift));verticalDrift.setText(String.format(Locale.US,"%.2f",m.verticalDrift));duration.setText(Integer.toString(m.duration));randomHeading.setChecked(m.randomHeading);
            lastRoute=m.mode;for(int i=0;i<routes.length;i++)if(routes[i].equals(m.mode))motionMode.setSelection(i);
            for(String key:extra.keySet())extra.get(key).setText(key.equals("driftSmoothing")?String.format(Locale.US,"%.2f",old.getDouble(key)):old.getString(key));
            org.json.JSONArray list=PhoneConfig.locations(this);for(int i=0;i<list.length();i++)savedPlaces.add(list.getJSONObject(i));
        } catch(Exception e){Toast.makeText(this,"配置载入提示："+e.getMessage(),Toast.LENGTH_LONG).show();}
        places.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,android.view.View v,int index,long id){
            selectedPlace=index-1;getSharedPreferences("controller",0).edit().putInt("selectedPlace",selectedPlace).apply();
            if(selectedPlace>=0 && selectedPlace<savedPlaces.size()) {
                JSONObject item=savedPlaces.get(selectedPlace);alias.setText(item.optString("alias"));latitude.setText(Double.toString(item.optDouble("latitude")));longitude.setText(Double.toString(item.optDouble("longitude")));coordinate.setSelection(item.optString("coordinateSystem","WGS-84").equals("GCJ-02")?1:0);
                applyPlaceHeight(item);
            }updateSummary();
        }});
        selectedPlace=Math.min(savedPlaces.size()-1,getSharedPreferences("controller",0).getInt("selectedPlace",-1));
        updatePlaces();
        if(selectedPlace>=0){JSONObject item=savedPlaces.get(selectedPlace);if(item.optDouble("latitude")==Double.parseDouble(latitude.getText().toString()) && item.optDouble("longitude")==Double.parseDouble(longitude.getText().toString()))alias.setText(item.optString("alias"));}
        android.text.TextWatcher changed=new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){updateSummary();preview.invalidate();}public void afterTextChanged(android.text.Editable e){}};
        for(EditText field:new EditText[]{latitude,longitude,minimum,maximum,duration,speed,radius,heading,drift,verticalDrift})field.addTextChangedListener(changed);
        for(EditText field:extra.values())field.addTextChangedListener(changed);
        coordinate.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> parent,android.view.View v,int position,long id){updateSummary();preview.invalidate();}});
        randomHeading.setOnCheckedChangeListener((v,checked)->preview.invalidate());
        motionMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,android.view.View v,int i,long id){
            String route=routes[i];if(lastRoute!=null && !route.equals(lastRoute))applyProfile(route);lastRoute=route;signalEnvironment.setText(SignalProfile.LABELS[SignalProfile.forRoute(route)]);updateBehavior();updateSummary();preview.invalidate();}});
    }
    private void applyProfile(String route){PhoneMotion m=PhoneMotion.profile(route);speed.setText(Double.toString(m.speed));radius.setText(Double.toString(m.radius));randomHeading.setChecked(m.randomHeading);
        extra.get("speedVariation").setText(Double.toString(m.speedVariation));extra.get("headingHoldMinimum").setText(Double.toString(m.headingHoldMinimum));extra.get("headingHoldMaximum").setText(Double.toString(m.headingHoldMaximum));
        extra.get("walkMinimum").setText(Double.toString(m.walkMinimum));extra.get("walkMaximum").setText(Double.toString(m.walkMaximum));extra.get("pauseMinimum").setText(Double.toString(m.pauseMinimum));extra.get("pauseMaximum").setText(Double.toString(m.pauseMaximum));extra.get("reactionSeconds").setText(Double.toString(m.reactionSeconds));
    }
    private void updateBehavior(){if(behaviorDescription==null)return;String route=routes[motionMode.getSelectedItemPosition()];String description;
        if(route.equals("stroll"))description="园区内慢走、观察和短暂停留，速度有快有慢，随后自然起步。基础活动半径10–50米。";
        else if(route.equals("office"))description="多数时间坐着，偶尔平滑走动1–4米、短暂停留后回座位；叠加定位游移，在20.00米半径内小幅自然游移，方向和速度连续、缓慢回归，显示保留两位小数，保留30.00–80.00米起点偏移，无需运动参数。";
        else if(route.equals("roadside"))description="向前走与等候交替，左右偏离最多5.00米，方向连续变化；不读取实际道路。";
        else if(route.equals("wander"))description="随意看看、频繁改变方向，短距离快慢交替，也会突然停下再起步。基础活动半径10–50米。";
        else if(route.equals("circle"))description="围绕中心不规则绕行，半径与速度连续变化；橙色采样连线每1–5秒取点，快走或间隔较长时跨步更大。";
        else description="请选择人物行为场景。";
        behaviorDescription.setText(description);boolean editable=!TransmitService.busy;
        boolean office=route.equals("office");
        ((android.view.View)speed.getParent().getParent()).setVisibility(office?android.view.View.GONE:android.view.View.VISIBLE);
        movementCard.setVisibility(office?android.view.View.GONE:android.view.View.VISIBLE);biasCard.setVisibility(office?android.view.View.GONE:android.view.View.VISIBLE);
        speed.setEnabled(editable && !route.equals("stationary"));radius.setEnabled(editable && !route.equals("roadside") && !route.equals("line") && !route.equals("stationary"));
        for(String key:new String[]{"walkMinimum","walkMaximum","pauseMinimum","pauseMaximum","reactionSeconds"})extra.get(key).setEnabled(editable && motionMode.getSelectedItemPosition()<4);
    }
    private void updateSummary(){
        if(homePlace==null || latitude==null)return;
        homePlace.setText(alias.getText().length()>0?alias.getText():"自定义位置");
        homeCoordinates.setText(latitude.getText()+"°,  "+longitude.getText()+"° · "+(coordinate.getSelectedItemPosition()==1?"GCJ-02":"WGS-84"));
        handler.removeCallbacks(updateMap);handler.postDelayed(updateMap,350);
        if(restoreLocation!=null)restoreLocation.setEnabled(!TransmitService.busy && selectedPlace>=0 && savedPlaces.get(selectedPlace).has("builtinKey"));
    }

    private void updateMapLocation(){
        try{PhoneMotion m=new PhoneMotion();m.latitude=value(latitude);m.longitude=value(longitude);m.coordinateSystem=coordinate.getSelectedItemPosition()==1?"GCJ-02":"WGS-84";
            if(Double.isFinite(m.latitude)&&Double.isFinite(m.longitude)&&Math.abs(m.latitude)<=85&&Math.abs(m.longitude)<=180){boolean gcj=m.coordinateSystem.equals("GCJ-02");locationMap.showLocation(m.latitude,m.longitude,gcj);if(monitorMap!=null)monitorMap.showLocation(m.latitude,m.longitude,gcj);}else {locationMap.clearLocation();if(monitorMap!=null)monitorMap.clearLocation();}
        }catch(Exception invalid){locationMap.clearLocation();if(monitorMap!=null)monitorMap.clearLocation();}
    }

    private void restoreLocation(){try{if(selectedPlace<0)return;JSONObject saved=savedPlaces.get(selectedPlace);org.json.JSONArray presets=PhoneConfig.presets(this);for(int i=0;i<presets.length();i++){JSONObject item=presets.getJSONObject(i);if(item.getString("builtinKey").equals(saved.optString("builtinKey"))){latitude.setText(item.getString("latitude"));longitude.setText(item.getString("longitude"));coordinate.setSelection(0);applyPlaceHeight(item);placeAction("保存修改");break;}}}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}
    private final class PathPreview extends android.view.View {
        double zoom=1;
        float panX,panY,lastX,lastY;
        void resetView(){panX=panY=0;setZoom(1);}
        @Override public boolean performClick(){super.performClick();return true;}
        @Override public boolean onTouchEvent(android.view.MotionEvent event){
            switch(event.getActionMasked()){
                case android.view.MotionEvent.ACTION_DOWN:
                    lastX=event.getX();lastY=event.getY();getParent().requestDisallowInterceptTouchEvent(true);return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    panX+=event.getX()-lastX;panY+=event.getY()-lastY;lastX=event.getX();lastY=event.getY();invalidate();return true;
                case android.view.MotionEvent.ACTION_UP:
                    performClick();getParent().requestDisallowInterceptTouchEvent(false);return true;
                case android.view.MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);return true;
                default:return true;
            }
        }
        void setZoom(double value){zoom=Math.max(.25,Math.min(16,value));invalidate();}
        PathPreview(Context c){super(c);setContentDescription("模拟轨迹预览，可上下左右拖动；全图按钮恢复居中");}
        @Override protected void onDraw(android.graphics.Canvas canvas){super.onDraw(canvas);android.graphics.Paint paint=new android.graphics.Paint(3);paint.setColor(0xffe7eef0);paint.setStrokeWidth(dp(1));float w=getWidth(),h=getHeight();for(int i=1;i<4;i++){canvas.drawLine(w*i/4,0,w*i/4,h,paint);canvas.drawLine(0,h*i/4,w,h*i/4,paint);}
            try{PhoneMotion m=NativeScene.parameters(input());PhoneMotion.Trace trace=m.trace(()->{});speedPreview.trace=trace;speedPreview.invalidate();double extent=2;for(double[] p:trace.emitted)extent=Math.max(extent,Math.max(Math.abs(p[1]),Math.abs(p[2])));float scale=(float)(Math.min(w,h)*.43/extent*zoom);
                for(int layer=0;layer<2;layer++){double[][] points=layer==0?trace.emitted:trace.ideal;android.graphics.Path path=new android.graphics.Path();for(int i:(layer==0 && m.mode.equals("circle")?m.observationIndices(points.length):java.util.stream.IntStream.range(0,points.length).toArray())){float x=w/2+panX+(float)points[i][1]*scale,y=h/2+panY-(float)points[i][2]*scale;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(layer==0?3:2));paint.setColor(layer==0?0xffc65102:0xff1255bb);paint.setPathEffect(layer==0?new android.graphics.DashPathEffect(new float[]{dp(7),dp(5)},0):null);canvas.drawPath(path,paint);paint.setPathEffect(null);}
                paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor(INK);paint.setTextSize(dp(12));canvas.drawText("北 ↑  ·  "+String.format(Locale.US,"±%.1f m · %.0f%%",extent/zoom,zoom*100),dp(8),dp(18),paint);
                double[] offset=m.startupOffset();String summary=String.format(Locale.US,"本次起点偏移 %.2f 米\n移动约 %.1f 米 · 停留约 %.0f 秒\n停下 %d 次 · 起步 %d 次 · 最高约 %.2f 米/秒",Math.hypot(offset[0],offset[1]),trace.distance,trace.stoppedSeconds,trace.stops,trace.starts,trace.maximumSpeed);
                if(!summary.contentEquals(traceSummary.getText()))traceSummary.setText(summary);
            }catch(Exception e){paint.setColor(MUTED);paint.setTextSize(dp(14));canvas.drawText("填写有效参数后显示预览",dp(8),h/2,paint);if(traceSummary!=null)traceSummary.setText("请检查参数范围与上下限");speedPreview.trace=null;speedPreview.invalidate();}}
    }
    private final class SpeedPreview extends android.view.View {
        PhoneMotion.Trace trace;
        SpeedPreview(Context c){super(c);setContentDescription("移动速度时间曲线，零速度表示停留");}
        @Override protected void onDraw(android.graphics.Canvas canvas){super.onDraw(canvas);android.graphics.Paint paint=new android.graphics.Paint(3);float left=dp(34),right=getWidth()-dp(8),top=dp(10),bottom=getHeight()-dp(22);
            paint.setColor(0xffe7eef0);canvas.drawLine(left,bottom,right,bottom,paint);canvas.drawLine(left,top,left,bottom,paint);
            if(trace==null)return;double[][] p=trace.ideal;double peak=Math.max(.5,trace.maximumSpeed);android.graphics.Path path=new android.graphics.Path();
            for(int i=1;i<p.length-1;i++){double speed=Math.hypot(p[i][1]-p[i-1][1],p[i][2]-p[i-1][2])*10;float x=left+(right-left)*i/(p.length-2),y=bottom-(float)(speed/peak)*(bottom-top);if(i==1)path.moveTo(left,bottom);path.lineTo(x,y);}
            paint.setColor(TEAL);paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(dp(2));canvas.drawPath(path,paint);paint.setStyle(android.graphics.Paint.Style.FILL);paint.setColor(MUTED);paint.setTextSize(dp(10));canvas.drawText("0",dp(12),bottom,paint);canvas.drawText(String.format(Locale.US,"%.1f",peak),dp(6),top+dp(8),paint);canvas.drawText("0秒",left,bottom+dp(16),paint);canvas.drawText(String.format(Locale.US,"%.0f秒",p[p.length-2][0]),right-dp(40),bottom+dp(16),paint);
        }
    }
    private void applyPlaceHeight(JSONObject item){
        try{double[] bounds=PhoneConfig.heightBounds(item,PhoneConfig.defaults(this).getDouble("heightVariation"));
            minimum.setText(bounds==null?"":Double.toString(bounds[0]));maximum.setText(bounds==null?"":Double.toString(bounds[1]));
            if(bounds==null)Toast.makeText(this,"此地点尚无高度资料，请在设置中填写高度上下限",Toast.LENGTH_LONG).show();
        }catch(Exception error){minimum.setText("");maximum.setText("");Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void updatePlaces() {
        ArrayList<String> names=new ArrayList<>(); names.add("自定义位置");
        for (JSONObject place:savedPlaces) names.add(place.optString("alias"));
        int selection=Math.max(0,selectedPlace+1);
        places.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names)); places.setSelection(selection);
    }
    private void choosePlace(String name,double lat,double lon,boolean gcj){
        if(TransmitService.busy)return;
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85||Math.abs(lon)>180){placeSearchStatus.setText("该地点超出支持的坐标范围");return;}
        placeSearchGeneration++;placeSearchButton.setEnabled(true);selectedPlace=-1;places.setSelection(0);
        alias.setText(name);latitude.setText(Double.toString(lat));longitude.setText(Double.toString(lon));coordinate.setSelection(gcj?1:0);
        minimum.setText("");maximum.setText("");
        placeSearchStatus.setText(String.format(Locale.US,"已选：%s\n经度 %.7f · 纬度 %.7f · %s\n可编辑名称后保存为新地点；请在设置中填写此地点高度",name,lon,lat,gcj?"GCJ-02":"WGS-84"));
        updateSummary();preview.invalidate();
    }
    private void savePickedPlace(){
        if(TransmitService.busy)return;
        EditText name=new EditText(this);name.setSingleLine(true);name.setHint("地点名称");name.setText(alias.getText());
        android.app.AlertDialog dialog=new android.app.AlertDialog.Builder(this).setTitle("保存目标地点").setView(name)
            .setMessage("保存当前选定的位置与名称，不覆盖已有地点。")
            .setNegativeButton("取消",null).setPositiveButton("保存地点",null).create();
        dialog.setOnShowListener(d->dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String title=name.getText().toString().trim();if(title.isEmpty()||title.length()>80){name.setError("请填写1–80字名称");return;}
            try{double lat=value(latitude),lon=value(longitude);if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85||Math.abs(lon)>180)throw new IllegalArgumentException();}
            catch(Exception invalid){name.setError("请先选择有效位置");return;}
            alias.setText(title);placeAction("新增位置");dialog.dismiss();
        }));dialog.show();
    }
    private void resolveCity(Runnable next){
        afterCity=next;
        if(checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION},64);return;
        }
        cityAttempted=true;placeSearchButton.setEnabled(false);placeSearchStatus.setText("正在获取当前城市…");
        try{
            com.amap.api.location.AMapLocationClient.updatePrivacyShow(this,true,true);
            com.amap.api.location.AMapLocationClient.updatePrivacyAgree(this,true);
            cityLocator=new com.amap.api.location.AMapLocationClient(this);
            com.amap.api.location.AMapLocationClientOption option=new com.amap.api.location.AMapLocationClientOption();
            option.setLocationMode(com.amap.api.location.AMapLocationClientOption.AMapLocationMode.Battery_Saving);
            option.setOnceLocation(true);option.setNeedAddress(true);option.setMockEnable(false);option.setLocationCacheEnable(false);option.setHttpTimeOut(8000);
            cityLocator.setLocationOption(option);
            cityLocator.setLocationListener(fix->{
                if(isDestroyed())return;
                if(fix!=null&&fix.getErrorCode()==0&&fix.getCity()!=null)currentCity=fix.getCity();
                finishCity();
            });
            cityLocator.startLocation();handler.postDelayed(()->{if(cityLocator!=null)finishCity();},10000);
        }catch(Exception error){finishCity();}
    }
    private void finishCity(){
        placeSearchButton.setEnabled(true);
        if(cityLocator!=null){cityLocator.stopLocation();cityLocator.onDestroy();cityLocator=null;}
        placeSearchStatus.setText(currentCity.isEmpty()?"当前城市不可用，可输入完整城市地址查找":"当前定位城市："+currentCity);
        Runnable next=afterCity;afterCity=null;if(next!=null)next.run();
    }
    private void findPlace(){
        if(TransmitService.busy)return;
        String query=placeQuery.getText().toString().trim();if(query.isEmpty()||query.length()>120){placeSearchStatus.setText("请输入1–120字地点名称、地址或经纬度");return;}
        try{
            double[] point=PlaceCoordinates.parse(query);
            if(point!=null){choosePlace("经纬度选点",point[0],point[1],coordinate.getSelectedItemPosition()==1);return;}
            if(!getSharedPreferences("map",0).getBoolean("amapConsent",false)){locationMap.withConsent(this::findPlace);return;}
            if(!cityAttempted){resolveCity(this::findPlace);return;}
            com.amap.api.services.core.ServiceSettings.updatePrivacyShow(this,true,true);
            com.amap.api.services.core.ServiceSettings.updatePrivacyAgree(this,true);
            com.amap.api.services.core.ServiceSettings.getInstance().setProtocol(com.amap.api.services.core.ServiceSettings.HTTPS);
            com.amap.api.services.poisearch.PoiSearch.Query request=new com.amap.api.services.poisearch.PoiSearch.Query(query,"",currentCity);request.setPageSize(10);
            com.amap.api.services.poisearch.PoiSearch search=new com.amap.api.services.poisearch.PoiSearch(this,request);
            int generation=++placeSearchGeneration;placeSearchButton.setEnabled(false);placeSearchStatus.setText(currentCity.isEmpty()?"当前城市不可用，正在全国查找…":"正在查找 · "+currentCity);
            handler.postDelayed(()->{if(generation==placeSearchGeneration&&!isDestroyed()){placeSearchGeneration++;placeSearchButton.setEnabled(true);placeSearchStatus.setText("地点查询超时，请检查网络后重试");}},20000);
            search.setOnPoiSearchListener(new com.amap.api.services.poisearch.PoiSearch.OnPoiSearchListener(){
                public void onPoiItemSearched(com.amap.api.services.core.PoiItem item,int code){}
                public void onPoiSearched(com.amap.api.services.poisearch.PoiResult result,int code){
                    if(isDestroyed()||generation!=placeSearchGeneration)return;
                    placeSearchGeneration++;placeSearchButton.setEnabled(true);
                    if(TransmitService.busy)return;
                    if(code!=1000||result==null){placeSearchStatus.setText("地点查询失败（"+code+"），可重试或输入经纬度选点");return;}
                    java.util.ArrayList<com.amap.api.services.core.PoiItem> found=new java.util.ArrayList<>();java.util.ArrayList<String> labels=new java.util.ArrayList<>();
                    if(result.getPois()!=null)for(com.amap.api.services.core.PoiItem item:result.getPois())if(item.getLatLonPoint()!=null){found.add(item);labels.add(item.getTitle()+"\n"+item.getCityName()+item.getAdName()+" "+item.getSnippet());}
                    if(found.isEmpty()){placeSearchStatus.setText("没有找到地点，请补充城市或更具体的地址");return;}
                    placeSearchStatus.setText("找到 "+found.size()+" 个地点，请选择");
                    new android.app.AlertDialog.Builder(MainActivity.this).setTitle("选择目标地点").setItems(labels.toArray(new String[0]),(d,index)->{
                        com.amap.api.services.core.PoiItem item=found.get(index);choosePlace(item.getTitle(),item.getLatLonPoint().getLatitude(),item.getLatLonPoint().getLongitude(),true);
                    }).setNegativeButton("取消",null).show();
                }
            });search.searchPOIAsyn();
        }catch(Exception error){placeSearchButton.setEnabled(true);placeSearchStatus.setText("无法查找："+error.getMessage());}
    }
    private void placeAction(String action) {
        try {
            if (action.equals("删除位置")) {
                if (selectedPlace<0 || selectedPlace>=savedPlaces.size()) throw new IllegalArgumentException("请先选择位置");
                savedPlaces.remove(selectedPlace); selectedPlace=-1; alias.setText("");
            } else {
                String name=alias.getText().toString().trim(); double lat=value(latitude),lon=value(longitude);
                if (name.isEmpty() || name.length()>80 || !Double.isFinite(lat) || !Double.isFinite(lon) || lat < -85 || lat>85 || lon < -180 || lon>180) throw new IllegalArgumentException("请填写1–80字别名及有效经纬度");
                JSONObject place=new JSONObject().put("alias",name).put("latitude",lat).put("longitude",lon).put("coordinateSystem",coordinate.getSelectedItemPosition()==1?"GCJ-02":"WGS-84");
                if(minimum.getText().length()>0&&maximum.getText().length()>0){double low=value(minimum),high=value(maximum);PhoneConfig.heightBounds(new JSONObject().put("minimum",low).put("maximum",high),0);place.put("minimum",low).put("maximum",high);}
                if(action.equals("保存修改") && selectedPlace>=0 && selectedPlace<savedPlaces.size()){JSONObject previous=savedPlaces.get(selectedPlace);
                    if(previous.has("builtinKey"))place.put("builtinKey",previous.getString("builtinKey"));
                    if(previous.optDouble("latitude")==lat && previous.optDouble("longitude")==lon && previous.optString("coordinateSystem","WGS-84").equals(place.getString("coordinateSystem")) && previous.has("referenceElevation"))place.put("referenceElevation",previous.getDouble("referenceElevation"));}
                org.json.JSONArray presets=PhoneConfig.presets(this);for(int i=0;i<presets.length();i++){JSONObject builtin=presets.getJSONObject(i);if(builtin.getDouble("latitude")==lat && builtin.getDouble("longitude")==lon && builtin.getString("coordinateSystem").equals(place.getString("coordinateSystem")))place.put("referenceElevation",builtin.getDouble("referenceElevation"));}
                if (action.equals("保存修改")) { if (selectedPlace<0 || selectedPlace>=savedPlaces.size()) throw new IllegalArgumentException("请先选择位置"); savedPlaces.set(selectedPlace,place); }
                else { savedPlaces.add(place); selectedPlace=savedPlaces.size()-1; }
            }
            org.json.JSONArray list=new org.json.JSONArray(); for (JSONObject place:savedPlaces) list.put(place);
            getSharedPreferences("controller",0).edit().putString("locations",list.toString()).putInt("selectedPlace",selectedPlace).apply(); updatePlaces();
            Toast.makeText(this,"位置已保存",Toast.LENGTH_SHORT).show();
        } catch (Exception e) { Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show(); }
    }
    private void savePlaceHeight() throws Exception {
        if(selectedPlace<0||selectedPlace>=savedPlaces.size())return;
        JSONObject place=savedPlaces.get(selectedPlace);
        if(place.getDouble("latitude")!=value(latitude)||place.getDouble("longitude")!=value(longitude)||!place.optString("coordinateSystem","WGS-84").equals(coordinate.getSelectedItemPosition()==1?"GCJ-02":"WGS-84"))return;
        double low=value(minimum),high=value(maximum);PhoneConfig.heightBounds(new JSONObject().put("minimum",low).put("maximum",high),0);
        place.put("minimum",low).put("maximum",high);org.json.JSONArray list=new org.json.JSONArray();for(JSONObject item:savedPlaces)list.put(item);
        getSharedPreferences("controller",0).edit().putString("locations",list.toString()).apply();
    }
    private void startNative(boolean auto) {
        if(!supportedSelection()){Toast.makeText(this,"请选择卫星系统",Toast.LENGTH_LONG).show();return;}
        if (TransmitService.busy || importing || reading) return;
        if(auto&&!ensureTrackPermission(63))return;
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},auto?53:52); return;
        }
        try {
            JSONObject input=input().put("gnssSystem",selectedSystem()); int txGain=Integer.parseInt(gain.getText().toString());
            if (txGain<0 || txGain>47) throw new IllegalArgumentException("增益必须为0–47");
            ArrayList<UsbDevice> devices=devices();
            if (auto && (devices.size()!=1 || !usb.hasPermission(devices.get(0)))) throw new IllegalArgumentException("请先连接并授权HackRF");
            savePlaceHeight();
            PhoneConfig.freshSimulationSeed(this,input);
            drift.setText(String.format(Locale.US,"%.2f",input.getDouble("horizontalDrift")));
            verticalDrift.setText(String.format(Locale.US,"%.2f",input.getDouble("verticalDrift")));
            extra.get("driftSmoothing").setText(String.format(Locale.US,"%.2f",input.getDouble("driftSmoothing")));
            extra.get("seed").setText(input.getString("seed"));preview.invalidate();
            getSharedPreferences("controller",0).edit().putString("phoneSettings",input.toString()).apply();
            startForegroundService(new Intent(this,TransmitService.class).setAction(TransmitService.GENERATE).putExtra("settings",input.toString()).putExtra("autoTransmit",auto).putExtra("gain",txGain).putExtra("amplifier",amplifier.isChecked()));
        } catch (Exception e) {TransmitService.resetFlow(this,"无法开始："+e.getMessage());Toast.makeText(this,"无法开始："+e.getMessage(),Toast.LENGTH_LONG).show();refreshTx();}
    }
    private void refreshTx() {
        boolean busy = TransmitService.busy;
        File folder = ScenarioStore.current(this); boolean fresh = false;boolean matching=false;
        try {
            if (folder == null){scenarioView.setText("尚无准备的场景");sceneSatelliteText="场景卫星：准备完成后显示（非手机实测）";}
            if (folder != null) {
                JSONObject m = ScenarioStore.manifest(folder);matching=ScenarioStore.system(m).equals(SYSTEMS[signalMode]);
                org.json.JSONArray satellites=m.optJSONArray("sceneSatellites");sceneSatelliteText=satellites==null?"旧场景未包含卫星几何数据":"场景卫星 "+satellites.length()+" 颗（非手机实测）\n"+satellites.join(" · ").replace("\"","");
                String timing;
                try { ScenarioStore.delay(m, System.currentTimeMillis()); timing = "时间有效"; fresh = true; }
                catch (Exception e) { timing = "已过期，请重新生成导入"; }
                scenarioView.setText((m.has("signalEnvironment")?m.getJSONObject("signalEnvironment").optString("label")+"\n":"")+"场景 " + folder.getName() + "\n" + m.getInt("durationSeconds") + " 秒 / " + m.getLong("iqBytes")/1000000 + " MB\n计划 UTC " + m.getJSONObject("navigation").getString("startUTC") + "\n" + timing + (m.optInt("schema") == 2 ? "\n场景文件校验通过" : "\nMac 独立解码验证包"));
            }
        } catch (Exception e) {if(!busy)TransmitService.resetFlow(this,"场景读取失败："+e.getMessage());scenarioView.setText("尚无准备的场景");}
        ArrayList<UsbDevice> devices = devices();
        updateConnection();
        for(ToggleButton choice:signalButtons)choice.setEnabled(!busy);
        stop.setEnabled(busy);
        setFormEnabled(placesPage,!busy);setFormEnabled(settingsPage,!busy);setFormEnabled(nativeForm, !busy); localGenerate.setEnabled(supportedSelection() && !busy && !importing && !reading);
        importButton.setEnabled(signalMode==0 && !busy && !importing);
        send.setEnabled(singleSystem() && matching && !busy && !reading && !importing && fresh && devices.size() == 1 && usb.hasPermission(devices.get(0)));
        localStart.setEnabled(supportedSelection() && !busy && !importing && !reading && (devices.size() == 1 && usb.hasPermission(devices.get(0))));
        gain.setEnabled(!busy);amplifier.setEnabled(!busy);
        localStart.setText("开始"+(singleSystem()?SYSTEM_LABELS[signalMode]:signalButtons[0].isChecked()?"混合":"")+"模拟");
        localGenerate.setText(mixedSelected()?"仅准备混合参数":"仅生成"+SYSTEM_LABELS[signalMode]+"离线样本");
        if(signalMode>0){signalEnvironment.setText("运动轨迹 · 固定水平偏移 · 缓慢漂移 · 共享相对信号环境（未标定绝对信号强度）");
            try{String id=matching?"":getSharedPreferences("controller",0).getString(SYSTEMS[signalMode]+"Experiment","");if(id.matches("[a-f0-9-]{36}")){File f=new File(getFilesDir(),SYSTEMS[signalMode]+"-experiments/"+id);JSONObject r=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(f,"experiment.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));scenarioView.setText(SYSTEM_LABELS[signalMode]+"场景 "+id+"\n"+r.getLong("iqBytes")/1000000+" MB · 场景文件校验通过\n场景起始 UTC "+r.getJSONObject("navigation").getString("startUTC"));}}catch(Exception e){scenarioView.setText("尚无准备好的"+SYSTEM_LABELS[signalMode]+"场景");}}
        runSummary.setText(busy?(TransmitService.completedBytes>0?"正在模拟":TransmitService.expectedBytes>0?"等待计划时刻":"正在准备场景"):TransmitService.status.startsWith("I/Q 发送完成")?"模拟已完成":TransmitService.status.contains("失败") || TransmitService.status.startsWith("发送未完成")?"运行未完成":TransmitService.status.startsWith("已停止")?"已停止":"准备就绪");
        runSummary.setTextColor(busy?TEAL:runSummary.getText().toString().equals("运行未完成")?0xffb42318:INK);
        runtimeDetails.setText(selectedSystemLabel()+(busy?"  ·  已用 "+((android.os.SystemClock.elapsedRealtime()-TransmitService.taskStartedElapsedMs)/1000)+" 秒":"  ·  "+(devices.size()==1&&usb.hasPermission(devices.get(0))?"设备已连接":"等待设备连接")));
        progressDetails.setText(TransmitService.expectedBytes>0?String.format(Locale.US,"%.1f%%  ·  %.1f / %.1f MB",Math.min(100,TransmitService.completedBytes*100.0/TransmitService.expectedBytes),TransmitService.completedBytes/1e6,TransmitService.expectedBytes/1e6):busy?"正在准备，完成后自动开始":"");
        progress.setVisibility(busy||TransmitService.expectedBytes>0?android.view.View.VISIBLE:android.view.View.GONE);
        if(busy)getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(backgroundStatus!=null)backgroundStatus.setText(getSystemService(android.os.PowerManager.class).isIgnoringBatteryOptimizations(getPackageName())?"系统电池优化已豁免；仍需留意厂商后台管理。":"后台保护未设置：请保持前台，或允许后台持续运行。");
        updateSummary();updateBehavior();
        downloadSummary.setText(getSharedPreferences("controller",0).getString("downloadSummary","尚无文件下载记录"));
        txView.setText(importing ? "正在导入场景，请保持页面打开…" : friendlyStatus(TransmitService.status)+(!busy && TransmitService.status.equals("尚未开始传输") && !TransmitService.lastOutcome.isEmpty()?"\n上次结束："+friendlyStatus(TransmitService.lastOutcome):""));
        if(monitorMap!=null)monitorMap.showSimulation(runSummary.getText().toString()+" · "+(TransmitService.expectedBytes>0?Math.round(TransmitService.completedBytes*100.0/TransmitService.expectedBytes)+"%":busy?"准备中":"待开始"),sceneSatelliteText);
        if(monitorMap!=null)monitorMap.showReceivedTrack(TransmitService.receivedTrack);
        progress.setIndeterminate(busy && TransmitService.expectedBytes==0);
        progress.setProgress(TransmitService.expectedBytes > 0 ? (int)Math.min(1000, TransmitService.completedBytes*1000/TransmitService.expectedBytes) : 0);
    }
    static String friendlyStatus(String value){
        if(value.contains("没有当前")&&value.contains("数据源覆盖整个场景"))return "本次准备失败：当前卫星星历无法覆盖完整模拟时段，请稍后重新准备。详细原因见设备页诊断信息。";
        if(value.contains("没有时效合格的")&&value.contains("轨道与导航模型"))return "本次准备失败：暂未获取完整、有效的轨道与导航模型，请检查网络后重试。详细原因见设备页诊断信息。";
        int details=value.indexOf("[{\"url\"");
        if(details>=0)return value.substring(0,details)+"详细原因见设备页诊断信息。";
        if(value.contains("失败") || value.startsWith("已停止"))return value;
        if(value.startsWith("正在发送"))return "模拟进行中，请保持设备连接。";
        if(value.startsWith("I/Q 发送完成"))return "本次模拟已完成，设备已停止。";
        if(value.contains("正在下载"))return value;
        if(value.contains("原生生成器") || value.contains("运动轨迹"))return "正在准备模拟场景…";
        if(value.contains("SHA-256") && !value.contains("失败"))return "正在检查场景文件…";
        if(value.startsWith("本机生成完成"))return "场景已准备好，尚未开始设备输出。";
        if(value.equals("尚未开始传输"))return "选择地点和运动方式，连接设备后开始。";
        return value;
    }
    private boolean ensureTrackPermission(int code){
        if(checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED||locationPermissionAsked)return true;
        locationPermissionAsked=true;requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION},code);return false;
    }
    private void startTx() {
        if(!singleSystem())return;
        if (TransmitService.busy || reading || importing) return;
        if(!ensureTrackPermission(61))return;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 51); return;
        }
        try {
            int value = Integer.parseInt(gain.getText().toString());
            if (value < 0 || value > 47) throw new IllegalArgumentException("增益必须为 0–47");
            ArrayList<UsbDevice> devices = devices();
            if (devices.size() != 1 || !usb.hasPermission(devices.get(0))) throw new IllegalStateException("请先授权 HackRF USB 访问");
            File folder = ScenarioStore.current(this); if (folder == null) throw new IllegalStateException("请先导入场景包");
            JSONObject manifest=ScenarioStore.manifest(folder);
            if(!ScenarioStore.system(manifest).equals(SYSTEMS[signalMode]))throw new IllegalStateException("场景与所选系统不一致");
            ScenarioStore.delay(manifest, System.currentTimeMillis());
            startForegroundService(new Intent(this, TransmitService.class).setAction(TransmitService.START).putExtra("gain", value).putExtra("amplifier", amplifier.isChecked()));
        } catch (Exception e) {TransmitService.resetFlow(this,"无法启动："+e.getMessage());Toast.makeText(this,"无法启动："+e.getMessage(),Toast.LENGTH_LONG).show();refreshTx();}
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if(code==64){if(checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED)resolveCity(afterCity);else{cityAttempted=true;finishCity();}return;}
        if(code==61||code==63){if(code==61)startTx();else startNative(true);return;}
        if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (code == 51) startTx(); else if (code == 52) startNative(false); else if (code == 53) startNative(true);
        } else TransmitService.status = "请允许常驻通知后启动";
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 10 || result != RESULT_OK || data == null || data.getData() == null || TransmitService.busy) return;
        importing = true; refreshTx();
        worker.execute(() -> {
            String message;
            try { ScenarioStore.importArchive(this, data.getData()); message = "场景导入及 SHA-256 校验通过；点击发送才会启动"; }
            catch (Exception e) { message = "导入失败：" + e.getMessage(); }
            final String report = message;
            runOnUiThread(() -> { importing = false;if(report.startsWith("导入失败")){TransmitService.resetFlow(this,report);Toast.makeText(this,report,Toast.LENGTH_LONG).show();}else TransmitService.status=report; if (!isDestroyed()) refreshTx(); });
        });
    }
    @Override protected void onResume() { super.onResume(); if(locationMap!=null)locationMap.resumeMap();if(monitorMap!=null)monitorMap.resumeMap();visible=true;scan();handler.post(tick); }
    @Override protected void onPause() {
        if(locationMap!=null)locationMap.pauseMap();if(monitorMap!=null)monitorMap.pauseMap();
        visible=false;
        handler.removeCallbacks(tick);
        try {
            savePlaceHeight();
            int savedGain=Integer.parseInt(gain.getText().toString());
            android.content.SharedPreferences.Editor editor=getSharedPreferences("controller",0).edit().putString("phoneSettings",input().toString());
            if (savedGain>=0 && savedGain<=47) editor.putInt("txGain",savedGain).putBoolean("txAmplifier",amplifier.isChecked());
            editor.apply();
        } catch (Exception ignored) { }
        super.onPause();
    }
    @Override public void onDestroy() { unregisterReceiver(receiver);if(cityLocator!=null){cityLocator.stopLocation();cityLocator.onDestroy();cityLocator=null;} worker.shutdownNow();handler.removeCallbacks(updateMap);if(locationMap!=null){locationMap.destroy();}if(monitorMap!=null){monitorMap.destroy();} super.onDestroy(); }
}
