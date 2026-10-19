package local.hackrf.controller;

import android.content.Context;
import android.location.*;
import android.os.*;
import java.util.ArrayList;

/** Phone GNSS fixes only; never writes or injects a system location. */
final class GnssTrack implements LocationListener {
    private LocationManager manager;
    private final ArrayList<double[]> points=new ArrayList<>();
    private long startNanos,lastNanos,revision;
    private boolean transmitting;
    private String state="接收轨迹：尚未开始";
    synchronized void prepare(){points.clear();startNanos=lastNanos=0;transmitting=false;revision++;state="接收轨迹：等待发射";}
    void listen(Context context){
        try{
            if(context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=android.content.pm.PackageManager.PERMISSION_GRANTED){synchronized(this){state="接收轨迹：未授权精确定位";}return;}
            manager=context.getSystemService(LocationManager.class);
            if(!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)){synchronized(this){state="接收轨迹：请开启系统定位";}manager=null;return;}
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this,Looper.getMainLooper());
        }catch(SecurityException|IllegalArgumentException failure){synchronized(this){state="接收轨迹不可用："+failure.getMessage();}}
    }
    synchronized void begin(long nanos){startNanos=nanos;transmitting=true;if(manager!=null)state="接收轨迹：等待新鲜 GNSS 定位";}
    void stop(){if(manager!=null){try{manager.removeUpdates(this);}catch(SecurityException revoked){android.util.Log.w("WalkLabTrack","Location permission revoked",revoked);}finally{manager=null;}}synchronized(this){if(transmitting)state=points.isEmpty()?"接收轨迹：本轮未获得有效定位":"接收轨迹：本轮已结束";transmitting=false;}}
    synchronized boolean accept(double lat,double lon,long fixNanos,long nowNanos,boolean mock,boolean gps){
        if(!transmitting||!gps||mock||!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180||fixNanos<startNanos||fixNanos<=lastNanos||fixNanos>nowNanos||nowNanos-fixNanos>3_000_000_000L)return false;
        points.add(new double[]{lat,lon,fixNanos});if(points.size()>3600)points.remove(0);lastNanos=fixNanos;revision++;state="接收轨迹：手机 GNSS 实测（未鉴别信号来源）";return true;
    }
    @Override public void onLocationChanged(Location location){accept(location.getLatitude(),location.getLongitude(),location.getElapsedRealtimeNanos(),SystemClock.elapsedRealtimeNanos(),location.isFromMockProvider(),LocationManager.GPS_PROVIDER.equals(location.getProvider()));}
    synchronized ArrayList<double[]> snapshot(){return new ArrayList<>(points);}
    synchronized long revision(){return revision;}
    synchronized boolean stale(){return transmitting&&lastNanos>0&&SystemClock.elapsedRealtimeNanos()-lastNanos>3_000_000_000L;}
    synchronized String summary(){return state+(points.isEmpty()?"":" · "+points.size()+" 点")+(stale()?" · 定位已陈旧，等待恢复":"");}
    @Override public synchronized void onProviderDisabled(String provider){if(LocationManager.GPS_PROVIDER.equals(provider))state="接收轨迹：系统定位已关闭";}
    @Override public void onProviderEnabled(String provider){}
    @Override public void onStatusChanged(String provider,int status,Bundle extras){}
}
