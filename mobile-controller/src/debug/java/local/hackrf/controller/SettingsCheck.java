package local.hackrf.controller;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.*;
import java.lang.reflect.Field;
import java.util.Map;
import org.json.*;

/** Exercise location CRUD and recreation without touching USB or generating a scene. */
public class SettingsCheck extends Instrumentation {
    public static class TestActivity extends MainActivity {
        @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(name.equals("controller")?"controller-ui-check":name,mode);}
    }
    Activity activity;
    Object field(String name) throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity); }
    void edit(String name,String text) throws Exception { ((EditText)field(name)).setText(text); }
    void action(String name) throws Exception { java.lang.reflect.Method m=MainActivity.class.getDeclaredMethod("placeAction",String.class);m.setAccessible(true);m.invoke(activity,name); }
    void ui(Checked task) { java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();runOnMainSync(()->{try {task.run();}catch(Throwable e){error.set(e);}});waitForIdleSync();if(error.get()!=null)throw new RuntimeException(error.get()); }
    interface Checked {void run() throws Exception;}
    @Override public void onCreate(Bundle args) {super.onCreate(args);start();}
    @Override public void onStart() {
        SharedPreferences prefs=getTargetContext().getSharedPreferences("controller-ui-check",0);
        Map<String,?> original=prefs.getAll();Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;
        try {
            JSONObject policy=new JSONObject(PhoneConfig.asset(getTargetContext(),"signal-environments.json")).getJSONObject("startupSpeedRangesCmPerSecond");
            for(String route:SignalProfile.ROUTES){
                JSONObject sample=PhoneConfig.defaults(getTargetContext()).put("route",route);JSONArray bounds=policy.getJSONArray(route);
                for(int attempt=0;attempt<10;attempt++){
                    double oldSpeed=sample.getDouble("speed"),oldVariation=sample.getDouble("speedVariation");
                    PhoneConfig.randomizeSpeed(getTargetContext(),sample,new java.util.Random(attempt));
                    double speed=sample.getDouble("speed"),variation=sample.getDouble("speedVariation"),lo=speed-variation,hi=speed+variation;
                    if(lo<bounds.getInt(0)/100.0-1e-9 || lo>bounds.getInt(1)/100.0+1e-9 || hi<bounds.getInt(2)/100.0-1e-9 || hi>bounds.getInt(3)/100.0+1e-9)throw new AssertionError("Startup speed outside route bounds");
                    if(hi>0 && Math.abs(oldSpeed-speed)<1e-9 && Math.abs(oldVariation-variation)<1e-9)throw new AssertionError("Startup speed repeated");
                }
            }
            if (TransmitService.busy) throw new IllegalStateException("A task is active");
            prefs.edit().putString("locations","[]").putBoolean("presetsV4",true).putBoolean("presets20261017",true).putInt("selectedPlace",-1).remove("phoneSettings").commit();
            activity=startActivitySync(new Intent(getTargetContext(),TestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            ui(()->{java.lang.reflect.Method show=MainActivity.class.getDeclaredMethod("showPage",int.class);show.setAccessible(true);show.invoke(activity,1);});
            ui(()->{edit("alias","自检办公室");edit("latitude","30.65");edit("longitude","104.1");action("新增位置");});
            JSONArray places=new JSONArray(prefs.getString("locations","[]"));
            if (places.length()!=1 || !places.getJSONObject(0).getString("alias").equals("自检办公室")) throw new AssertionError("Add failed");
            ui(()->{edit("alias","自检园区");edit("latitude","30.66");action("保存修改");edit("duration","180");edit("radius","50");((Spinner)field("motionMode")).setSelection(3);});
            places=new JSONArray(prefs.getString("locations","[]"));
            if (places.getJSONObject(0).getDouble("latitude")!=30.66) throw new AssertionError("Edit failed");
            ui(()->activity.finish());
            activity=startActivitySync(new Intent(getTargetContext(),TestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            ui(()->{java.lang.reflect.Method show=MainActivity.class.getDeclaredMethod("showPage",int.class);show.setAccessible(true);show.invoke(activity,1);});
            ui(()->{if (!((EditText)field("duration")).getText().toString().equals("180") || ((Spinner)field("motionMode")).getSelectedItemPosition()!=3) throw new AssertionError("Parameters did not survive recreation");((Spinner)field("places")).setSelection(1);});
            ui(()->{if (!((EditText)field("alias")).getText().toString().equals("自检园区")) throw new AssertionError("Selection failed"); action("删除位置");});
            if (new JSONArray(prefs.getString("locations","[]")).length()!=0) throw new AssertionError("Delete failed");
            ui(()->activity.finish());
            activity=startActivitySync(new Intent(getTargetContext(),TestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            ui(()->{if (((Spinner)field("places")).getCount()!=1) throw new AssertionError("Deleted location reappeared");});
            ui(()->{
                java.lang.reflect.Method show=MainActivity.class.getDeclaredMethod("showPage",int.class);show.setAccessible(true);show.invoke(activity,1);
                @SuppressWarnings("unchecked") java.util.ArrayList<JSONObject> saved=(java.util.ArrayList<JSONObject>)field("savedPlaces");
                saved.add(PhoneConfig.presets(getTargetContext()).getJSONObject(0));
                java.lang.reflect.Method update=MainActivity.class.getDeclaredMethod("updatePlaces");update.setAccessible(true);update.invoke(activity);((Spinner)field("places")).setSelection(1);
            });
            ui(()->{edit("minimum","496");edit("maximum","498");edit("alias","改名后的内置地点");edit("latitude","30.7");action("保存修改");});
            if(new JSONArray(prefs.getString("locations","[]")).getJSONObject(0).has("referenceElevation"))throw new AssertionError("Changed coordinates retained stale elevation");
            ui(()->{java.lang.reflect.Method restore=MainActivity.class.getDeclaredMethod("restoreLocation");restore.setAccessible(true);restore.invoke(activity);});
            JSONObject restored=new JSONArray(prefs.getString("locations","[]")).getJSONObject(0);
            if(restored.getDouble("latitude")!=30.651450 || !restored.getString("alias").equals("改名后的内置地点") || restored.getDouble("referenceElevation")!=497)throw new AssertionError("Renamed preset restore failed");
            ui(()->{
                double low=Double.parseDouble(((EditText)field("minimum")).getText().toString()),high=Double.parseDouble(((EditText)field("maximum")).getText().toString());
                if(Math.abs(low-496.92)>1e-8||Math.abs(high-497.08)>1e-8)throw new AssertionError("Preset height initialization incorrect");
                edit("minimum","510.25");edit("maximum","512.75");edit("duration","120");edit("speed","0.8");
                java.lang.reflect.Method input=MainActivity.class.getDeclaredMethod("input");input.setAccessible(true);JSONObject snapshot=(JSONObject)input.invoke(activity);
                PhoneMotion actual=NativeScene.parameters(snapshot);if(actual.minimum!=510.25||actual.maximum!=512.75||actual.duration!=120||actual.speed!=.8)throw new AssertionError("Simulation ignored current form parameters");
                action("保存修改");
            });
            JSONObject ownHeight=new JSONArray(prefs.getString("locations","[]")).getJSONObject(0);
            if(ownHeight.getDouble("minimum")!=510.25||ownHeight.getDouble("maximum")!=512.75)throw new AssertionError("Per-place height not saved");
            double[] other=PhoneConfig.heightBounds(PhoneConfig.presets(getTargetContext()).getJSONObject(1),.08);
            if(Math.abs(other[0]-479.92)>1e-8||Math.abs(other[1]-480.08)>1e-8)throw new AssertionError("Different places shared height");
            if(PhoneConfig.heightBounds(new JSONObject().put("alias","unknown"),.08)!=null)throw new AssertionError("Unknown terrain height fabricated");

            ui(()->{java.lang.reflect.Method show=MainActivity.class.getDeclaredMethod("showPage",int.class);show.setAccessible(true);show.invoke(activity,0);});
            String[] behaviors={"stroll","office","roadside","wander"};
            double[] speeds={.85,.65,1.2,.65};
            for(int index=0;index<4;index++){final int choice=index;ui(()->((Spinner)field("motionMode")).setSelection(choice));
                final String expected=behaviors[index];final double expectedSpeed=speeds[index];
                ui(()->{java.lang.reflect.Method read=MainActivity.class.getDeclaredMethod("input");read.setAccessible(true);JSONObject input=(JSONObject)read.invoke(activity);if(!input.getString("route").equals(expected) || input.getDouble("speed")!=expectedSpeed)throw new AssertionError("Behavior selection did not apply profile");int environment=SignalProfile.forRoute(expected);if(!input.getString("signalProfile").equals(SignalProfile.IDS[environment]) || !((TextView)field("signalEnvironment")).getText().toString().equals(SignalProfile.LABELS[environment]))throw new AssertionError("Motion and signal environment disagree");});
            }
            ui(()->{edit("speed","0.7");@SuppressWarnings("unchecked") java.util.Map<String,EditText> extra=(java.util.Map<String,EditText>)field("extra");extra.get("pauseMinimum").setText("7");extra.get("pauseMaximum").setText("15");activity.finish();});
            activity=startActivitySync(new Intent(getTargetContext(),TestActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            ui(()->{java.lang.reflect.Method read=MainActivity.class.getDeclaredMethod("input");read.setAccessible(true);JSONObject input=(JSONObject)read.invoke(activity);if(!input.getString("route").equals("wander") || input.getDouble("speed")!=.7 || input.getDouble("pauseMinimum")!=7)throw new AssertionError("Custom person settings lost on recreation");});
            for(int index=0;index<behaviors.length;index++){
                final int choice=index;ui(()->((Spinner)field("motionMode")).setSelection(choice));
                ui(()->{java.lang.reflect.Method read=MainActivity.class.getDeclaredMethod("input");read.setAccessible(true);JSONObject input=(JSONObject)read.invoke(activity);String expected=SignalProfile.IDS[SignalProfile.forRoute(input.getString("route"))];if(!input.getString("signalProfile").equals(expected))throw new AssertionError("Person route environment mismatch");});
            }
            result.putString("stream","PASS: location add/edit/select/delete, empty-list persistence, 180s parameters after Activity recreation, renamed preset restore/elevation, four behavior presets/custom persistence; four person motion/environment mappings; isolated storage; no USB/RF");code=Activity.RESULT_OK;
        } catch (Throwable e) {result.putString("stream","FAIL: "+e);}
        finally {
            if (activity!=null) ui(()->activity.finish());
            SharedPreferences.Editor restore=prefs.edit();
            for (String key:new String[]{"locations","phoneSettings","txGain","txAmplifier","presetsV4","presets20261017","selectedPlace"}) {
                Object value=original.get(key);
                if(value==null) restore.remove(key);
                else if(value instanceof String) restore.putString(key,(String)value);
                else if(value instanceof Integer) restore.putInt(key,(Integer)value);
                else if(value instanceof Boolean) restore.putBoolean(key,(Boolean)value);
            }
            restore.commit();
        }
        finish(code,result);
    }
}
