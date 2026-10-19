package local.hackrf.controller;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import org.json.*;
import java.util.*;

/** Isolated configuration checks preserve all existing preferences. No USB or RF. */
public class ConfigCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Context c=new ContextWrapper(getTargetContext()){@Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(name.equals("controller")?"controller-config-check":name,mode);}};SharedPreferences prefs=c.getSharedPreferences("controller",0);Map<String,?> before=prefs.getAll();Bundle out=new Bundle();int code=Activity.RESULT_CANCELED;
        try {
            if(TransmitService.busy)throw new IllegalStateException("Task active");
            for(String system:new String[]{"galileo","glonass"}){
                try{new NativeScene().generate(null,new JSONObject().put("gnssSystem",system),()->{},message->{});throw new AssertionError("Cancelled system accepted");}
                catch(java.io.IOException expected){if(!"不支持的卫星系统".equals(expected.getMessage()))throw expected;}
            }
            JSONObject launch=new JSONObject().put("seed",42);PhoneConfig.freshSimulationSeed(c,launch);long previousLaunch=launch.getLong("seed");PhoneConfig.freshSimulationSeed(c,launch);if(launch.getLong("seed")==previousLaunch)throw new AssertionError("Launch seed repeated");
            for(int i=0;i<100;i++){
                PhoneConfig.randomizeDrift(c,launch,new Random(i));
                for(String key:new String[]{"horizontalDrift","verticalDrift","driftSmoothing"}){double v=launch.getDouble(key);double low=key.equals("horizontalDrift")?2:key.equals("verticalDrift")?.3:8,high=key.equals("horizontalDrift")?3:key.equals("verticalDrift")?.8:12;if(v<low || v>high || Math.abs(v*100-Math.rint(v*100))>1e-8)throw new AssertionError("Drift random range");}
            }
            JSONObject defaults=PhoneConfig.defaults(c);if(defaults.length()!=23)throw new AssertionError("Mac fields changed");
            PhoneMotion m=NativeScene.parameters(defaults);if(m.speed!=.5 || !m.mode.equals("circle") || m.seed!=42 || !m.randomHeading)throw new AssertionError("Mac defaults differ");
            JSONObject edited=new JSONObject(defaults.toString()).put("route","line").put("coordinateSystem","GCJ-02").put("speedVariation",.4).put("speedSmoothing",5).put("randomHeading",false).put("headingMinimum",40).put("headingMaximum",100).put("headingHoldMinimum",4).put("headingHoldMaximum",9).put("turnSmoothing",4).put("heightSmoothing",5).put("driftSmoothing",12).put("seed",123);
            m=NativeScene.parameters(edited);if(!m.mode.equals("line") || m.speedVariation!=.4 || m.speedSmoothing!=5 || m.randomHeading || m.headingMinimum!=40 || m.headingMaximum!=100 || m.headingHoldMinimum!=4 || m.headingHoldMaximum!=9 || m.turnSmoothing!=4 || m.heightSmoothing!=5 || m.driftSmoothing!=12 || m.seed!=123)throw new AssertionError("Unmapped configuration");
            JSONObject person=new JSONObject(defaults.toString()).put("route","office").put("radius",5).put("walkMinimum",4).put("walkMaximum",10).put("pauseMinimum",20).put("pauseMaximum",90).put("reactionSeconds",.6);
            m=NativeScene.parameters(person);if(!m.personMode() || m.walkMinimum!=4 || m.walkMaximum!=10 || m.pauseMinimum!=20 || m.pauseMaximum!=90 || m.reactionSeconds!=.6)throw new AssertionError("Person timing fields not mapped");
            JSONObject old=new JSONObject().put("latitude",30.65).put("longitude",104.1).put("minimum",399).put("maximum",401).put("duration",120).put("speed",1.2).put("radius",12).put("heading",90).put("drift",2).put("verticalDrift",.1).put("mode","park").put("seed",42);
            prefs.edit().putString("phoneSettings",old.toString()).remove("motionV4").remove("presetsV4").remove("presets20261017").putString("locations","[{\"alias\":\"用户地点\",\"latitude\":30.5,\"longitude\":104.0}]").commit();
            JSONObject migrated=PhoneConfig.load(c);m=NativeScene.parameters(migrated);
            if(!m.mode.equals("stroll") || m.minimum!=399 || m.maximum!=401 || (m.drift<2 || m.drift>3) || m.duration!=120)throw new AssertionError("Legacy migration lost values");
            String[][] routes={{"park","stroll"},{"stationary","office"},{"circle","circle"},{"line","roadside"}};
            for(String[] route:routes){
                prefs.edit().putString("phoneSettings",new JSONObject(defaults.toString()).put("route",route[0]).toString()).commit();
                JSONObject converted=PhoneConfig.load(c);PhoneMotion migratedMotion=NativeScene.parameters(converted);migratedMotion.validate();
                if(!converted.getString("route").equals(route[1])||converted.getDouble("latitude")!=defaults.getDouble("latitude")||converted.getDouble("duration")!=defaults.getDouble("duration"))throw new AssertionError("Four-scene migration lost settings");
            }
            JSONArray speedRange=new JSONObject(PhoneConfig.asset(c,"signal-environments.json")).getJSONObject("startupSpeedRangesCmPerSecond").getJSONArray(m.mode);
            double lower=m.speed-m.speedVariation,upper=m.speed+m.speedVariation;
            if(lower<speedRange.getInt(0)/100.0-1e-9 || lower>speedRange.getInt(1)/100.0+1e-9 || upper<speedRange.getInt(2)/100.0-1e-9 || upper>speedRange.getInt(3)/100.0+1e-9)throw new AssertionError("Startup speed differs from shared Mac policy");
            long seed=m.seed;if(seed==prefs.getLong("lastSessionSeed",-1) && seed==42)throw new AssertionError("Seed not refreshed");
            JSONArray list=PhoneConfig.locations(c);if(list.length()!=PhoneConfig.presets(c).length()+1 || !list.getJSONObject(0).getString("alias").equals("用户地点") || list.getJSONObject(1).getDouble("referenceElevation")!=497)throw new AssertionError("Presets clobbered user locations");
            prefs.edit().putString("locations","[]").commit();if(PhoneConfig.locations(c).length()!=0)throw new AssertionError("Deleted presets reappeared");
            prefs.edit().putString("phoneSettings",edited.toString()).commit();if(PhoneConfig.load(c).getLong("seed")!=123)throw new AssertionError("Manual seed lost in same session");
            prefs.edit().putString("phoneSettings",new JSONObject(edited.toString()).put("duration",600).toString()).commit();if(PhoneConfig.load(c).getInt("duration")!=600)throw new AssertionError("600-second duration was truncated");
            try{NativeScene.parameters(new JSONObject(edited.toString()).put("duration",601));throw new AssertionError("Long duration accepted");}catch(java.io.IOException expected){}
            String retained=prefs.getString("phoneSettings","");prefs.edit().putString("scenario",java.util.UUID.randomUUID().toString()).putBoolean("taskInProgress",true).commit();
            TransmitService.completedBytes=123;TransmitService.expectedBytes=456;
            TransmitService.resetFlow(c,"test failure");
            if(TransmitService.completedBytes!=0 || TransmitService.expectedBytes!=0 || prefs.contains("scenario") || prefs.contains("taskInProgress") || !retained.equals(prefs.getString("phoneSettings","")))throw new AssertionError("Reset lost settings or retained task state");
            out.putString("stream","PASS: cancelled systems rejected before device/network access; all 23 Mac fields + person timing, legacy migration, presets with user locations, deleted preset persistence, manual seed; no USB/RF");code=Activity.RESULT_OK;
        }catch(Throwable e){out.putString("stream","FAIL: "+e);}
        finally {
            SharedPreferences.Editor edit=prefs.edit();for(String key:new String[]{"phoneSettings","heightRangesV1","motionV4","presetsV4","presets20261017","locations","lastSessionSeed"}){Object value=before.get(key);if(value==null)edit.remove(key);else if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Long)edit.putLong(key,(Long)value);}edit.commit();
        }finish(code,out);
    }
}
