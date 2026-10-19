package local.hackrf.controller;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.amap.api.maps.*;
import com.amap.api.maps.model.*;

/** Native map; simulation information stays below the map and never covers its controls. */
final class LocationMap extends LinearLayout {
    interface PickListener {void picked(double latitude,double longitude);}
    private PickListener pickListener;
    void setPickListener(PickListener listener){pickListener=listener;}
    void pick(com.amap.api.maps.model.LatLng point){if(pickListener!=null)pickListener.picked(point.latitude,point.longitude);}
    private final FrameLayout canvas;
    private final Button center;
    private final TextView status;
    private android.webkit.WebView openView;
    private MapView nativeView;
    private AMap map;
    private Marker marker;
    private Circle targetCircle;
    private final java.util.ArrayList<Polyline> receivedLines=new java.util.ArrayList<>();
    private Marker receivedMarker;
    private GnssTrack receivedTrack;
    private long trackRevision=-1;
    private boolean trackStale;
    private String receivedSummary="";
    private boolean resumed,loaded,gcjCoordinates;
    private double latitude=Double.NaN,longitude=Double.NaN;
    private String simulation="",satellites="",mapStatus="点击启用高德地图";
    LocationMap(Context context,int height){
        super(context);setOrientation(VERTICAL);
        canvas=new FrameLayout(context);canvas.setBackgroundColor(0xFFF0F4F5);
        addView(canvas,new LayoutParams(-1,Math.round(height*getResources().getDisplayMetrics().density)));
        center=new Button(context);center.setText("启用高德地图");center.setContentDescription("启用高德地图或回到当前设定地点");
        canvas.addView(center,new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.END));
        status=new TextView(context);status.setTextSize(12);status.setTextColor(0xFF455A64);
        int padding=Math.round(8*getResources().getDisplayMetrics().density);status.setPadding(0,padding,0,0);
        addView(status,new LayoutParams(-1,-2));renderStatus();
        Button open=new Button(context);open.setText("OpenStreetMap / 离线网格");addView(open);open.setOnClickListener(v->{releaseMap();showOpenMap();});
        Button key=new Button(context);key.setText("填写高德 Key");addView(key);key.setOnClickListener(v->{
            EditText input=new EditText(context);input.setSingleLine(true);input.setHint("Android SDK Key");input.setInputType(129);
            new android.app.AlertDialog.Builder(context).setTitle("高德 Android SDK Key").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
                String value=input.getText().toString().trim();if(!value.matches("[a-fA-F0-9]{32}")){Toast.makeText(context,"Key 需要32位十六进制字符",Toast.LENGTH_LONG).show();return;}
                context.getSharedPreferences("map",0).edit().putString("amapKey",value).apply();releaseMap();withConsent(this::startAMap);
            }).show();
        });
        showOpenMap();
        center.setOnClickListener(v->{if(map!=null){recenter();return;}withConsent(this::startAMap);});
        if(context.getSharedPreferences("map",0).getBoolean("amapConsent",false))post(this::startAMap);
    }
    void withConsent(Runnable action){
        Context context=getContext();
        String key=context.getSharedPreferences("map",0).getString("amapKey", "");
        if(key.isEmpty()){Toast.makeText(context,"请先填写自己的高德 Android SDK Key",Toast.LENGTH_LONG).show();return;}
        MapsInitializer.setApiKey(key);
        com.amap.api.services.core.ServiceSettings.getInstance().setApiKey(key);
        com.amap.api.location.AMapLocationClient.setApiKey(key);
        if(context.getSharedPreferences("map",0).getBoolean("amapConsent",false)){action.run();return;}
        new android.app.AlertDialog.Builder(context).setTitle("使用高德地图与地点搜索")
            .setMessage("高德SDK会联网加载所选位置的地图；文字搜索会将查询词发送给高德，并按高德隐私政策处理设备及网络信息。地点搜索将使用网络定位获取当前城市（需系统定位权限），不使用模拟目标作为当前城市。")
            .setNeutralButton("隐私政策",(d,w)->context.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://lbs.amap.com/pages/privacy/"))))
            .setNegativeButton("暂不启用",null)
            .setPositiveButton("同意并启用",(d,w)->{context.getSharedPreferences("map",0).edit().putBoolean("amapConsent",true).apply();action.run();}).show();
    }
    void startAMap(){
        if(nativeView!=null)return;
        String key=getContext().getSharedPreferences("map",0).getString("amapKey", "");if(key.isEmpty())return;
        MapsInitializer.setApiKey(key);
        if(openView!=null){canvas.removeView(openView);openView.destroy();openView=null;}
        try{
            MapsInitializer.updatePrivacyShow(getContext(),true,true);MapsInitializer.updatePrivacyAgree(getContext(),true);
            nativeView=new MapView(getContext());nativeView.onCreate(null);
            canvas.addView(nativeView,0,new FrameLayout.LayoutParams(-1,-1));map=nativeView.getMap();
            map.setOnMapClickListener(this::pick);
            map.setOnMarkerDragListener(new AMap.OnMarkerDragListener(){
                public void onMarkerDragStart(Marker value){}
                public void onMarkerDrag(Marker value){}
                public void onMarkerDragEnd(Marker value){if(value.equals(marker))pick(value.getPosition());}
            });
            android.view.GestureDetector gestures=new android.view.GestureDetector(getContext(),new android.view.GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onDoubleTap(android.view.MotionEvent event){
                    if(pickListener!=null)pick(map.getProjection().fromScreenLocation(new android.graphics.Point(Math.round(event.getX()),Math.round(event.getY()))));return true;
                }
            });
            map.getUiSettings().setZoomControlsEnabled(true);map.getUiSettings().setMyLocationButtonEnabled(false);
            map.setOnMapTouchListener(event->{gestures.onTouchEvent(event);if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(event.getActionMasked()!=android.view.MotionEvent.ACTION_UP && event.getActionMasked()!=android.view.MotionEvent.ACTION_CANCEL);});
            map.setOnMapLoadedListener(()->{loaded=true;mapStatus="高德地图 · 当前设定地点";renderStatus();});
            if(resumed)nativeView.onResume();center.setText("回到设定地点");mapStatus="高德地图加载中";renderStatus();updateMarker();trackRevision=-1;if(receivedTrack!=null)showReceivedTrack(receivedTrack);
        }catch(Exception|LinkageError error){releaseMap();mapStatus="高德地图不可用，点击重试";renderStatus();android.util.Log.e("WalkLabMap","AMap initialization failed",error);}
    }
    private void showOpenMap(){
        if(openView!=null)return;
        openView=new android.webkit.WebView(getContext());openView.getSettings().setJavaScriptEnabled(true);
        openView.addJavascriptInterface(new Object(){@android.webkit.JavascriptInterface public void picked(double lat,double lon){post(()->{if(pickListener!=null)pickListener.picked(lat,lon);});}},"PublicMap");
        openView.setWebViewClient(new android.webkit.WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(android.webkit.WebView view,android.webkit.WebResourceRequest request){return true;}
            @Override public void onPageFinished(android.webkit.WebView view,String url){updateMarker();}
        });
        try{
            String html=PhoneConfig.asset(getContext(),"map.html").replace("walklab-tile://osm/", "https://tile.openstreetmap.org/");
            html=html.replace("</script>","map.ondblclick=e=>{if(!center)return;const r=map.getBoundingClientRect(),c=project(...center),p=unproject(c[0]+e.clientX-r.left-r.width/2,c[1]+e.clientY-r.top-r.height/2);PublicMap.picked(p[0],p[1])};</script>");
            openView.loadDataWithBaseURL("https://localhost/",html,"text/html","UTF-8",null);
        }catch(Exception e){mapStatus="OpenStreetMap 页面不可用";renderStatus();}
        canvas.addView(openView,0,new FrameLayout.LayoutParams(-1,-1));mapStatus="OpenStreetMap · 点击联网加载底图";renderStatus();
    }
    private void releaseMap(){
        if(nativeView!=null){nativeView.onPause();nativeView.onDestroy();canvas.removeView(nativeView);}
        nativeView=null;map=null;marker=null;targetCircle=null;receivedMarker=null;receivedLines.clear();trackRevision=-1;loaded=false;center.setText("启用高德地图");
    }
    private void updateMarker(){
        if(openView!=null){
            if(Double.isFinite(latitude)){
                PhoneMotion point=new PhoneMotion();point.latitude=latitude;point.longitude=longitude;point.coordinateSystem=gcjCoordinates?"GCJ-02":"WGS-84";
                try{double[] wgs=point.wgs84();openView.evaluateJavascript("setLocation("+wgs[0]+","+wgs[1]+")",null);}catch(IllegalArgumentException e){mapStatus=e.getMessage();renderStatus();}
            }
            else openView.evaluateJavascript("clearLocation()",null);
        }
        if(map==null)return;
        if(!Double.isFinite(latitude)){if(marker!=null){marker.remove();marker=null;}if(targetCircle!=null){targetCircle.remove();targetCircle=null;}return;}
        // Only the map display is converted to GCJ-02; simulation coordinates remain WGS84.
        LatLng point=new LatLng(latitude,longitude);if(!gcjCoordinates)point=new CoordinateConverter(getContext()).from(CoordinateConverter.CoordType.GPS).coord(point).convert();
        if(marker==null)marker=map.addMarker(new MarkerOptions().position(point).title("模拟目标").draggable(pickListener!=null));else marker.setPosition(point);
        if(targetCircle==null)targetCircle=map.addCircle(new CircleOptions().center(point).radius(150).fillColor(0x332196F3).strokeColor(0xAA1976D2).strokeWidth(2));else targetCircle.setCenter(point);
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(point,Math.max(map.getMinZoomLevel(),map.getMaxZoomLevel()-4)));
    }
    void recenter(){if(map!=null&&marker!=null)map.animateCamera(CameraUpdateFactory.newLatLng(marker.getPosition()));}
    void showLocation(double lat,double lon){showLocation(lat,lon,false);}
    void showLocation(double lat,double lon,boolean gcj){
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85||Math.abs(lon)>180)return;
        if(lat==latitude&&lon==longitude&&gcj==gcjCoordinates)return;latitude=lat;longitude=lon;gcjCoordinates=gcj;updateMarker();
    }
    void clearLocation(){latitude=Double.NaN;longitude=Double.NaN;updateMarker();}
    void showSimulation(String state,String data){if(state.equals(simulation)&&data.equals(satellites))return;simulation=state;satellites=data;renderStatus();}
    void showReceivedTrack(GnssTrack track){
        receivedTrack=track;receivedSummary=track.summary();renderStatus();
        if(map==null)return;
        long revision=track.revision();boolean stale=track.stale();if(revision==trackRevision&&stale==trackStale)return;
        trackRevision=revision;trackStale=stale;
        for(Polyline line:receivedLines)line.remove();receivedLines.clear();
        if(receivedMarker!=null){receivedMarker.remove();receivedMarker=null;}
        java.util.ArrayList<LatLng> segment=new java.util.ArrayList<>();long previous=0;LatLng last=null;
        for(double[] fix:track.snapshot()){
            if(previous>0&&fix[2]-previous>5_000_000_000L){addReceivedSegment(segment,stale);segment.clear();}
            last=new CoordinateConverter(getContext()).from(CoordinateConverter.CoordType.GPS).coord(new LatLng(fix[0],fix[1])).convert();
            segment.add(last);previous=(long)fix[2];
        }
        addReceivedSegment(segment,stale);
        if(last!=null)receivedMarker=map.addMarker(new MarkerOptions().position(last).title("手机 GNSS 实测").icon(BitmapDescriptorFactory.defaultMarker(stale?BitmapDescriptorFactory.HUE_ORANGE:BitmapDescriptorFactory.HUE_GREEN)));
    }
    private void addReceivedSegment(java.util.ArrayList<LatLng> points,boolean stale){if(points.size()>1)receivedLines.add(map.addPolyline(new PolylineOptions().addAll(points).width(7).color(stale?0xFF888888:0xFF12A05C)));}
    private void renderStatus(){status.setText(mapStatus+(simulation.isEmpty()?"":"\n"+simulation)+(satellites.isEmpty()?"":"\n"+satellites)+(receivedSummary.isEmpty()?"":"\n"+receivedSummary));}
    void resumeMap(){resumed=true;if(nativeView!=null)nativeView.onResume();}
    void pauseMap(){resumed=false;if(nativeView!=null)nativeView.onPause();}
    void destroy(){releaseMap();if(openView!=null){openView.destroy();openView=null;}}
    boolean isMapLoaded(){return loaded;}
}
