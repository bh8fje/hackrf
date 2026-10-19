package local.hackrf.controller;

import android.app.*;
import android.os.Bundle;
import android.content.Intent;
import java.lang.reflect.Field;

/** Explicit SDK integration check; does not persist consent or start any GNSS generation. */
public class AMapCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            if(TransmitService.busy)throw new IllegalStateException("Task active");
            MainActivity activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Field field=MainActivity.class.getDeclaredField("monitorMap");field.setAccessible(true);
            LocationMap view=(LocationMap)field.get(activity);
            runOnMainSync(()->{view.showLocation(30.5728,104.0668);view.startAMap();});
            long deadline=android.os.SystemClock.elapsedRealtime()+45000;
            java.util.concurrent.atomic.AtomicBoolean loaded=new java.util.concurrent.atomic.AtomicBoolean();
            while(android.os.SystemClock.elapsedRealtime()<deadline){runOnMainSync(()->loaded.set(view.isMapLoaded()));if(loaded.get())break;Thread.sleep(500);}
            if(!loaded.get())throw new AssertionError("AMap did not load within 45 seconds; inspect auth/network logs");
            runOnMainSync(()->{
                if(view.getChildCount()!=2 || !(view.getChildAt(0) instanceof android.widget.FrameLayout) || !(view.getChildAt(1) instanceof android.widget.TextView))throw new AssertionError("Map information must be below the map");
                try{
                    Field mapField=LocationMap.class.getDeclaredField("map");mapField.setAccessible(true);
                    com.amap.api.maps.AMap nativeMap=(com.amap.api.maps.AMap)mapField.get(view);
                    if(Math.abs(nativeMap.getCameraPosition().zoom-Math.max(nativeMap.getMinZoomLevel(),nativeMap.getMaxZoomLevel()-3))>.01)throw new AssertionError("Map must start at fourth-highest supported zoom");
                    Field circleField=LocationMap.class.getDeclaredField("targetCircle");circleField.setAccessible(true);
                    Field markerField=LocationMap.class.getDeclaredField("marker");markerField.setAccessible(true);
                    com.amap.api.maps.model.Circle circle=(com.amap.api.maps.model.Circle)circleField.get(view);
                    com.amap.api.maps.model.Marker marker=(com.amap.api.maps.model.Marker)markerField.get(view);
                    if(circle==null||circle.getRadius()!=50||!circle.getCenter().equals(marker.getPosition())||android.graphics.Color.alpha(circle.getFillColor())!=51)throw new AssertionError("Target circle must be 50m and translucent, centered on marker");
                }catch(ReflectiveOperationException error){throw new AssertionError(error);}
                try{
                    Field handlerField=MainActivity.class.getDeclaredField("handler"),tickField=MainActivity.class.getDeclaredField("tick");handlerField.setAccessible(true);tickField.setAccessible(true);
                    ((android.os.Handler)handlerField.get(activity)).removeCallbacks((Runnable)tickField.get(activity));
                    GnssTrack track=new GnssTrack();track.prepare();long base=android.os.SystemClock.elapsedRealtimeNanos()-12_000_000_000L;track.begin(base);
                    if(track.accept(30,104,base+1,base+1,true,true)||track.accept(30,104,base+1,base+1,false,false)||track.accept(91,104,base+1,base+1,false,true)||track.accept(30,104,base+1,base+4_000_000_001L,false,true))throw new AssertionError("Mock/network/invalid/stale fix accepted");
                    Field latField=LocationMap.class.getDeclaredField("latitude"),lonField=LocationMap.class.getDeclaredField("longitude");latField.setAccessible(true);lonField.setAccessible(true);
                    double lat=latField.getDouble(view),lon=lonField.getDouble(view);
                    for(int i=0;i<4;i++){long time=base+(i<2?i+1:i+7)*1_000_000_000L;if(!track.accept(lat+i*.00006,lon+i*.00006,time,time,false,true))throw new AssertionError("Fresh fix rejected");}
                    if(track.accept(lat,lon,base+1_000_000_000L,base+1_000_000_000L,false,true))throw new AssertionError("Out of order fix accepted");
                    track.stop();view.showReceivedTrack(track);
                    Field lines=LocationMap.class.getDeclaredField("receivedLines");lines.setAccessible(true);
                    if(((java.util.List<?>)lines.get(view)).size()!=2)throw new AssertionError("GNSS gaps must create separate polylines");
                    track.prepare();view.showReceivedTrack(track);if(!((java.util.List<?>)lines.get(view)).isEmpty())throw new AssertionError("Previous session track retained");
                    track.begin(base);for(int i=0;i<4;i++){long time=base+(i<2?i+1:i+7)*1_000_000_000L;track.accept(lat+i*.00006,lon+i*.00006,time,time,false,true);}track.stop();view.showReceivedTrack(track);
                }catch(ReflectiveOperationException error){throw new AssertionError(error);}
                view.showSimulation("轨迹绘制测试（合成点，非实测）","绿色轨迹：验证叠加与断流分段");
                if(view.getChildAt(1).getTop()<view.getChildAt(0).getBottom())throw new AssertionError("Satellite text overlaps map");
            });
            Thread.sleep(5000);
            java.util.concurrent.CountDownLatch snapshot=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap> captured=new java.util.concurrent.atomic.AtomicReference<>();
            Field nativeMap=LocationMap.class.getDeclaredField("map");nativeMap.setAccessible(true);
            com.amap.api.maps.AMap amap=(com.amap.api.maps.AMap)nativeMap.get(view);
            runOnMainSync(()->{view.requestRectangleOnScreen(new android.graphics.Rect(0,0,view.getWidth(),view.getHeight()),true);amap.getMapScreenShot(new com.amap.api.maps.AMap.OnMapScreenShotListener(){
                public void onMapScreenShot(android.graphics.Bitmap bitmap){captured.set(bitmap);snapshot.countDown();}
                public void onMapScreenShot(android.graphics.Bitmap bitmap,int code){captured.set(bitmap);snapshot.countDown();}
            });});
            if(!snapshot.await(15,java.util.concurrent.TimeUnit.SECONDS)||captured.get()==null)throw new AssertionError("Native map screenshot unavailable");
            runOnMainSync(()->{view.recenter();view.requestRectangleOnScreen(new android.graphics.Rect(0,0,view.getWidth(),view.getHeight()),true);});
            Thread.sleep(1000);
            android.graphics.Bitmap ui=getUiAutomation().takeScreenshot();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(),"amap-layout.png"))){ui.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}ui.recycle();
            android.graphics.Bitmap frame=captured.get();
            try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(),"amap-check.png"))){frame.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}frame.recycle();
            result.putString("result","PASS: native AMap loaded, WGS84 target displayed; no RF and no persisted consent");finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("error",error.toString());finish(Activity.RESULT_CANCELED,result);}
    }
}
