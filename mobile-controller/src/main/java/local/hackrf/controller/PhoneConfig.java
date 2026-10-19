package local.hackrf.controller;

import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class PhoneConfig {
    private static Long sessionSeed;
    static String asset(Context c,String name) throws IOException {
        try(InputStream in=c.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    static JSONObject defaults(Context c) throws Exception {return new JSONObject(asset(c,"mac-defaults.json"));}
    static double[] heightBounds(JSONObject place,double variation) throws Exception {
        double low,high;
        if(place.has("minimum")&&place.has("maximum")){low=place.getDouble("minimum");high=place.getDouble("maximum");}
        else if(place.has("referenceElevation")){double h=place.getDouble("referenceElevation");low=h-variation;high=h+variation;}
        else return null;
        if(!Double.isFinite(low)||!Double.isFinite(high)||low < -500||high>10000||low>high)throw new IOException("地点高度上下限无效");
        return new double[]{low,high};
    }
    static JSONArray presets(Context c) throws Exception {return new JSONArray(asset(c,"mac-locations.json"));}
    static JSONObject load(Context c) throws Exception {
        SharedPreferences p=c.getSharedPreferences("controller",0);JSONObject result=defaults(c),old;
        try {old=new JSONObject(p.getString("phoneSettings","{}"));}
        catch(JSONException e) {p.edit().putString("phoneSettingsRecovery",p.getString("phoneSettings","{}")).apply();old=new JSONObject();}
        for(String key:new String[]{"walkMinimum","walkMaximum","pauseMinimum","pauseMaximum","reactionSeconds"})result.put(key,key.equals("walkMinimum")?12:key.equals("walkMaximum")?35:key.equals("pauseMinimum")?3:key.equals("pauseMaximum")?12:.8);
        if(old.length()==0) {PhoneMotion person=PhoneMotion.profile("stroll");result.put("route","stroll").put("speed",person.speed).put("speedVariation",person.speedVariation).put("radius",person.radius);}
        boolean legacy=old.has("mode") && !old.has("route");
        for(Iterator<String> it=old.keys();it.hasNext();) {String key=it.next();result.put(key,old.get(key));}
        if(legacy) {
            result.put("route",old.getString("mode")).put("horizontalDrift",old.optDouble("drift",1.5));
            double low=old.optDouble("minimum",400),high=old.optDouble("maximum",400);
            result.put("height",(low+high)/2).put("heightVariation",(high-low)/2);
            if(!p.getBoolean("motionV4",false) && old.optDouble("speed")==1.2) result.put("speed",.5);
        }
        if(!p.getBoolean("heightRangesV1",false)){
            if(result.optDouble("heightVariation",0)==0)result.put("heightVariation",defaults(c).getDouble("heightVariation"));
            p.edit().putBoolean("heightRangesV1",true).apply();
        }
        String route=result.getString("route");
        String personRoute=route;
        switch(route){case "park":personRoute="stroll";break;case "stationary":personRoute="office";break;case "line":personRoute="roadside";break;}
        if(!personRoute.equals(route)) {
            PhoneMotion profile=PhoneMotion.profile(personRoute);
            result.put("route",personRoute).put("radius",personRoute.equals("office")?Math.min(8,result.getDouble("radius")):Math.max(10,result.getDouble("radius")))
                .put("walkMinimum",profile.walkMinimum).put("walkMaximum",profile.walkMaximum).put("pauseMinimum",profile.pauseMinimum).put("pauseMaximum",profile.pauseMaximum).put("reactionSeconds",profile.reactionSeconds);
        }
        if(result.getDouble("duration")>600)result.put("duration",600);
        boolean newSession=sessionSeed==null;
        if(newSession) {long previous=p.getLong("lastSessionSeed",-1),next;Random rng=new Random();do {next=rng.nextInt(Integer.MAX_VALUE);}while(next==previous);sessionSeed=next;p.edit().putLong("lastSessionSeed",next).apply();}
        if(newSession) {randomizeSpeed(c,result,new Random());randomizeDrift(c,result,new Random());}
        if(newSession || !old.has("seed"))result.put("seed",sessionSeed);p.edit().putBoolean("motionV4",true).apply();return result;
    }
    static void freshSimulationSeed(Context c,JSONObject settings) throws Exception {
        randomizeDrift(c,settings,new Random());
        SharedPreferences p=c.getSharedPreferences("controller",0);long previous=p.getLong("lastSimulationSeed",-1),next;Random rng=new Random();
        do{next=rng.nextInt(Integer.MAX_VALUE);}while(next==previous || next==settings.optLong("seed",-1));
        settings.put("seed",next);p.edit().putLong("lastSimulationSeed",next).apply();
    }
    static void randomizeDrift(Context c,JSONObject settings,Random rng) throws Exception {
        JSONObject ranges=new JSONObject(asset(c,"signal-environments.json")).getJSONObject("startupDriftRangesHundredths");
        for(String key:new String[]{"horizontalDrift","verticalDrift","driftSmoothing"}){
            JSONArray range=ranges.getJSONArray(key);settings.put(key,(range.getInt(0)+rng.nextInt(range.getInt(1)-range.getInt(0)+1))/100.0);
        }
    }
    static void randomizeSpeed(Context c,JSONObject settings,Random rng) throws Exception {
        JSONArray range=new JSONObject(asset(c,"signal-environments.json")).getJSONObject("startupSpeedRangesCmPerSecond").getJSONArray(settings.getString("route"));
        double previousLow=settings.getDouble("speed")-settings.getDouble("speedVariation"),previousHigh=settings.getDouble("speed")+settings.getDouble("speedVariation");
        double low,high;
        do {low=(range.getInt(0)+rng.nextInt(range.getInt(1)-range.getInt(0)+1))/100.0;
            high=(range.getInt(2)+rng.nextInt(range.getInt(3)-range.getInt(2)+1))/100.0;
        }while(high>0 && Math.abs(low-previousLow)<.0001 && Math.abs(high-previousHigh)<.0001);
        settings.put("speed",(low+high)/2).put("speedVariation",(high-low)/2);
    }
    static JSONArray locations(Context c) throws Exception {
        SharedPreferences p=c.getSharedPreferences("controller",0);JSONArray saved;
        try {saved=new JSONArray(p.getString("locations","[]"));} catch(JSONException e) {p.edit().putString("locationsRecovery",p.getString("locations","[]")).apply();throw new IOException("地点列表无法读取，已保留原始数据");}
        if(!p.contains("locations")) saved=presets(c);
        if(!p.getBoolean("presetsV4",false)) {
            JSONArray builtins=presets(c);
            for(int i=0;i<builtins.length();i++) {JSONObject builtin=builtins.getJSONObject(i);boolean exists=false;
                for(int j=0;j<saved.length();j++) {JSONObject entry=saved.getJSONObject(j);if(entry.optString("alias").equals(builtin.getString("alias")) && entry.optDouble("latitude")==builtin.getDouble("latitude") && entry.optDouble("longitude")==builtin.getDouble("longitude")) {entry.put("builtinKey",builtin.getString("builtinKey")).put("referenceElevation",builtin.getDouble("referenceElevation"));exists=true;}}
                if(!exists)saved.put(builtin);
            }
            p.edit().putString("locations",saved.toString()).putBoolean("presetsV4",true).commit();
        }
        if(!p.getBoolean("presets20261017",false)){
            JSONArray builtins=presets(c);
            for(int i=Math.max(0,builtins.length()-2);i<builtins.length();i++){
                JSONObject builtin=builtins.getJSONObject(i);boolean exists=false;
                for(int j=0;j<saved.length();j++){JSONObject entry=saved.getJSONObject(j);
                    if(entry.optString("builtinKey").equals(builtin.getString("builtinKey")) || (entry.optString("alias").equals(builtin.getString("alias")) && entry.optDouble("latitude")==builtin.getDouble("latitude") && entry.optDouble("longitude")==builtin.getDouble("longitude")))exists=true;
                }
                if(!exists)saved.put(builtin);
            }
            if(!p.edit().putString("locations",saved.toString()).putBoolean("presets20261017",true).commit())throw new IOException("无法保存新增预置地点");
        }
        return saved;
    }
}
