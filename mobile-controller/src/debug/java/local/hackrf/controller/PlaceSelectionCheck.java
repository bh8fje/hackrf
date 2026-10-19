package local.hackrf.controller;
import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Isolated selection and saved-coordinate checks; no USB, generation or output. */
public class PlaceSelectionCheck extends Instrumentation {
    private MainActivity activity;
    private String liveQuery;
    private Object field(String name)throws Exception{Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void invoke(String name,Class<?>[] types,Object... args)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);}
    interface Step{void run()throws Exception;}
    private void ui(Step step){java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();runOnMainSync(()->{try{step.run();}catch(Throwable e){error.set(e);}});waitForIdleSync();if(error.get()!=null)throw new AssertionError(error.get());}
    @Override public void onCreate(Bundle args){super.onCreate(args);liveQuery=args.getString("query");start();}
    @Override public void onStart(){Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;SharedPreferences p=getTargetContext().getSharedPreferences("controller-ui-check",0);Map<String,?> prior=p.getAll();
        try{
            if(TransmitService.busy)throw new IllegalStateException("Task active");
            p.edit().clear().putString("locations","[]").putBoolean("presetsV4",true).putBoolean("presets20261017",true).commit();
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),SettingsCheck.TestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            ui(()->{((Spinner)field("coordinate")).setSelection(0);((EditText)field("placeQuery")).setText("104.0668,30.5728");invoke("findPlace",new Class<?>[]{});});
            double[] picked={30.66,104.1};
            ui(()->{if(Double.parseDouble(((EditText)field("latitude")).getText().toString())!=30.5728||Double.parseDouble(((EditText)field("longitude")).getText().toString())!=104.0668||((Spinner)field("coordinate")).getSelectedItemPosition()!=0)throw new AssertionError("Coordinate lookup reordered or shifted input");
                invoke("showPage",new Class<?>[]{int.class},1);((LocationMap)field("locationMap")).startAMap();});
            java.util.concurrent.atomic.AtomicBoolean loaded=new java.util.concurrent.atomic.AtomicBoolean();long mapDeadline=SystemClock.elapsedRealtime()+45000;
            do{ui(()->loaded.set(((LocationMap)field("locationMap")).isMapLoaded()));if(loaded.get())break;Thread.sleep(100);}while(SystemClock.elapsedRealtime()<mapDeadline);
            if(!loaded.get())throw new AssertionError("Map did not load for native touch test");
            Thread.sleep(600);waitForIdleSync();
            int[] tap=new int[2];ui(()->{
                if(!activity.hasWindowFocus())throw new AssertionError("Selection test is not foreground");
                LocationMap view=(LocationMap)field("locationMap");Field markerField=LocationMap.class.getDeclaredField("marker");markerField.setAccessible(true);if(!((com.amap.api.maps.model.Marker)markerField.get(view)).isDraggable())throw new AssertionError("Target must be draggable");Field nativeField=LocationMap.class.getDeclaredField("nativeView");nativeField.setAccessible(true);android.view.View nativeView=(android.view.View)nativeField.get(view);
                android.graphics.Rect visible=new android.graphics.Rect();if(!nativeView.getGlobalVisibleRect(visible)||visible.height()<40)throw new AssertionError("Map is not visible");
                tap[0]=visible.left+visible.width()/3;tap[1]=visible.top+visible.height()/2;
                int[] origin=new int[2];nativeView.getLocationOnScreen(origin);Field mapField=LocationMap.class.getDeclaredField("map");mapField.setAccessible(true);
                com.amap.api.maps.model.LatLng point=((com.amap.api.maps.AMap)mapField.get(view)).getProjection().fromScreenLocation(new android.graphics.Point(tap[0]-origin[0],tap[1]-origin[1]));picked[0]=point.latitude;picked[1]=point.longitude;
            });
            for(int repeat=0;repeat<2;repeat++){long now=SystemClock.uptimeMillis();for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP}){android.view.MotionEvent touch=android.view.MotionEvent.obtain(now,SystemClock.uptimeMillis(),action,tap[0],tap[1],0);try{getUiAutomation().injectInputEvent(touch,true);}finally{touch.recycle();}Thread.sleep(30);}Thread.sleep(40);}
            Thread.sleep(800);waitForIdleSync();
            ui(()->{if(Math.abs(Double.parseDouble(((EditText)field("latitude")).getText().toString())-picked[0])>1e-6||Math.abs(Double.parseDouble(((EditText)field("longitude")).getText().toString())-picked[1])>1e-6)throw new AssertionError("Double tap selected wrong coordinate");});
            ui(()->{
                LocationMap view=(LocationMap)field("locationMap");Field mf=LocationMap.class.getDeclaredField("map"),vf=LocationMap.class.getDeclaredField("nativeView"),marker=LocationMap.class.getDeclaredField("marker");for(Field f:new Field[]{mf,vf,marker})f.setAccessible(true);
                com.amap.api.maps.AMap map=(com.amap.api.maps.AMap)mf.get(view);android.view.View nativeView=(android.view.View)vf.get(view);int[] origin=new int[2];nativeView.getLocationOnScreen(origin);
                android.graphics.Point start=map.getProjection().toScreenLocation(((com.amap.api.maps.model.Marker)marker.get(view)).getPosition());tap[0]=origin[0]+start.x;tap[1]=origin[1]+start.y-40;
                com.amap.api.maps.model.LatLng target=map.getProjection().fromScreenLocation(new android.graphics.Point(start.x+80,start.y+40));picked[0]=target.latitude;picked[1]=target.longitude;
            });
            long down=SystemClock.uptimeMillis();android.view.MotionEvent event=android.view.MotionEvent.obtain(down,down,0,tap[0],tap[1],0);getUiAutomation().injectInputEvent(event,true);event.recycle();Thread.sleep(800);
            for(int i=1;i<=10;i++){event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),2,tap[0]+8*i,tap[1]+4*i,0);getUiAutomation().injectInputEvent(event,true);event.recycle();Thread.sleep(30);}
            event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),1,tap[0]+80,tap[1]+40,0);getUiAutomation().injectInputEvent(event,true);event.recycle();Thread.sleep(600);waitForIdleSync();
            ui(()->{double lat=Double.parseDouble(((EditText)field("latitude")).getText().toString()),lon=Double.parseDouble(((EditText)field("longitude")).getText().toString());LocationMap view=(LocationMap)field("locationMap");Field marker=LocationMap.class.getDeclaredField("marker");marker.setAccessible(true);com.amap.api.maps.model.LatLng position=((com.amap.api.maps.model.Marker)marker.get(view)).getPosition();if(Math.abs(lon-picked[1])>0.00001||Math.abs(position.latitude-lat)>0.000001||Math.abs(position.longitude-lon)>0.000001)throw new AssertionError("Dragged marker="+position+" target="+lat+","+lon+" expectedLongitude="+picked[1]);picked[0]=lat;picked[1]=lon;});
            ui(()->{if(((Spinner)field("coordinate")).getSelectedItemPosition()!=1)throw new AssertionError("Map pick must retain GCJ-02 identity");
                invoke("savePickedPlace",new Class<?>[]{});});
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow(),name=null,save=null;
            for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("地图选点"))if("android.widget.EditText".contentEquals(node.getClassName()))name=node;
            for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("保存地点"))if("保存地点".contentEquals(node.getText()))save=node;
            if(name==null||save==null)throw new AssertionError("Named save dialog missing");
            Bundle text=new Bundle();text.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"选点持久化检查");
            if(!name.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,text)||!save.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Named save dialog action failed");
            Thread.sleep(250);waitForIdleSync();
            JSONArray saved=new JSONArray(p.getString("locations","[]"));JSONObject item=saved.getJSONObject(0);
            if(saved.length()!=1||!item.getString("alias").equals("选点持久化检查")||Math.abs(item.getDouble("latitude")-picked[0])>1e-6||Math.abs(item.getDouble("longitude")-picked[1])>1e-6||!item.getString("coordinateSystem").equals("GCJ-02"))throw new AssertionError("Selected name/position not saved: "+item+" expected "+picked[0]+","+picked[1]);
            ui(()->activity.finish());activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),SettingsCheck.TestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            ui(()->{if(!((EditText)field("alias")).getText().toString().equals("选点持久化检查")||((Spinner)field("coordinate")).getSelectedItemPosition()!=1)throw new AssertionError("Saved selection lost after reopening");});
            if(liveQuery!=null){
                if(!getTargetContext().getSharedPreferences("map",0).getBoolean("amapConsent",false))throw new IllegalStateException("Map consent required for live search");
                ui(()->{((EditText)field("placeQuery")).setText(liveQuery);invoke("findPlace",new Class<?>[]{});});
                java.util.concurrent.atomic.AtomicReference<String> status=new java.util.concurrent.atomic.AtomicReference<>();long deadline=SystemClock.elapsedRealtime()+23000;
                do{ui(()->status.set(((TextView)field("placeSearchStatus")).getText().toString()));if(!status.get().startsWith("正在"))break;Thread.sleep(100);}while(SystemClock.elapsedRealtime()<deadline);
                if(!status.get().startsWith("找到"))throw new AssertionError("Live place search: "+status.get());
                result.putString("currentCity",(String)field("currentCity"));result.putString("liveSearch",status.get());Thread.sleep(400);waitForIdleSync();
                android.graphics.Bitmap screen=getUiAutomation().takeScreenshot();if(screen!=null){try(java.io.OutputStream image=new java.io.FileOutputStream(new java.io.File(getTargetContext().getCacheDir(),"place-search-live.png"))){screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image);}screen.recycle();}
            }
            result.putString("stream","PASS: coordinate search, native GCJ-02 double tap and marker drag, named save and activity-recreation persistence; isolated preferences; no RF");code=Activity.RESULT_OK;
        }catch(Throwable e){result.putString("stream","FAIL: "+e);}
        finally{if(activity!=null)ui(()->activity.finish());SharedPreferences.Editor editor=p.edit().clear();for(Map.Entry<String,?> entry:prior.entrySet()){Object v=entry.getValue();String k=entry.getKey();if(v instanceof String)editor.putString(k,(String)v);else if(v instanceof Boolean)editor.putBoolean(k,(Boolean)v);else if(v instanceof Integer)editor.putInt(k,(Integer)v);else if(v instanceof Long)editor.putLong(k,(Long)v);else if(v instanceof Float)editor.putFloat(k,(Float)v);}editor.commit();}
        finish(code,result);
    }
}
