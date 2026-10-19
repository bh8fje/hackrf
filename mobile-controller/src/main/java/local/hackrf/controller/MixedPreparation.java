package local.hackrf.controller;

import android.content.Context;
import java.io.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;

/** Reuse the single-connection resumable navigation cache; one UTC for both systems. */
final class MixedPreparation {
    static boolean ageValid(long age,int maximumAge){return age>=-60&&age<=maximumAge;}
    static NativeScene.Download selectModel(NativeScene.Download current,NativeScene.Download candidate,long age,boolean eligible){return eligible&&ageValid(age,10800)?candidate:current;}
    static MixedScene prepare(Context context,JSONObject settings,PhoneMotion.Check check,Consumer<String> status)throws Exception {
        if(context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked())throw new IOException("请先解锁手机并保持控制台前台");
        PhoneMotion motion=NativeScene.parameters(settings);
        if(motion.duration<90)throw new IOException("混合冷启动场景请使用90–600秒");
        JSONObject policy=new JSONObject(PhoneConfig.asset(context,"navigation-sources.json"));
        LinkedHashSet<String> sources=new LinkedHashSet<>();ZonedDateTime now=Instant.now().atZone(ZoneOffset.UTC);
        for(String category:new String[]{"bds","gps","broadcast"}){
            JSONArray entries=policy.optJSONArray(category);
            if(entries!=null)for(int i=0;i<entries.length();i++)sources.add(NativeScene.expandSource(entries.getString(i),now));
        }
        List<NativeScene.Download> candidates=new ArrayList<>();NativeScene.Download gpsModels=null,bdsModels=null;
        JSONArray failures=new JSONArray();long began=android.os.SystemClock.elapsedRealtime();
        for(String url:sources){
            check.run();status.accept("正在检查GPS与北斗共同导航数据；保留断点续传");
            try {
                NativeScene.Download value=NativeScene.cachedFetch(context,url,10800,false,check,status);
                long age=Duration.between(value.produced(),Instant.now()).getSeconds();
                if(value.cached && age>1800){value=NativeScene.cachedFetch(context,url,10800,true,check,status);age=Duration.between(value.produced(),Instant.now()).getSeconds();}
                gpsModels=selectModel(gpsModels,value,age,RinexNav.hasModels(value.text));
                bdsModels=selectModel(bdsModels,value,age,value.text.contains("BDSB")&&value.text.contains("BDUT"));
                if(ageValid(age,1800))candidates.add(value);
            }catch(Exception error){check.run();failures.put(new JSONObject().put("url",url).put("error",error.toString()));}
            if(gpsModels!=null&&!ageValid(Duration.between(gpsModels.produced(),Instant.now()).getSeconds(),10800))gpsModels=null;
            if(bdsModels!=null&&!ageValid(Duration.between(bdsModels.produced(),Instant.now()).getSeconds(),10800))bdsModels=null;
            if(gpsModels==null||bdsModels==null)continue;
            Instant start=Instant.now().plusSeconds(leadSeconds(context)).truncatedTo(ChronoUnit.SECONDS);
            NativeScene.Download leaps=NativeScene.leapTable(context,start.plusSeconds(motion.duration),check);
            start=Instant.now().plusSeconds(leadSeconds(context)).truncatedTo(ChronoUnit.SECONDS);
            int offset=RinexNav.gpsMinusUTC(leaps.text,start);
            NativeScene.Download gps=null,bds=null;
            for(NativeScene.Download value:candidates){
                long age=Duration.between(value.produced(),Instant.now()).getSeconds();
                if(!ageValid(age,1800))continue;
                if(gps==null)try{RinexNav.convert(RinexNav.broadcast3(value.text),gpsModels.text,start.plusSeconds(offset),offset,motion.duration);gps=value;}catch(IOException incomplete){check.run();}
                if(bds==null)try{BdsNavigation.convert(value.text,bdsModels.text,start,offset,motion.duration);bds=value;}catch(IOException incomplete){check.run();}
            }
            if(gps==null||bds==null)continue;
            long planningBegan=android.os.SystemClock.elapsedRealtime();
            start=Instant.now().plusSeconds(leadSeconds(context)).truncatedTo(ChronoUnit.SECONDS);
            offset=RinexNav.gpsMinusUTC(leaps.text,start);
            if(RinexNav.gpsMinusUTC(leaps.text,start.plusSeconds(motion.duration))!=offset)throw new IOException("混合重排时段跨越闰秒");
            if(!ageValid(Duration.between(gps.produced(),Instant.now()).getSeconds(),1800)||!ageValid(Duration.between(bds.produced(),Instant.now()).getSeconds(),1800))continue;
            if(!ageValid(Duration.between(gpsModels.produced(),Instant.now()).getSeconds(),10800)||!ageValid(Duration.between(bdsModels.produced(),Instant.now()).getSeconds(),10800))continue;
            File folder=new File(context.getFilesDir(),"mixed-experiments/"+UUID.randomUUID());
            MixedScene scene=MixedScene.prepare(folder,new File(context.getApplicationInfo().nativeLibraryDir),settings,gps.text,gpsModels.text,bds.text,bdsModels.text,leaps.text,start,check);
            scene.planningBeganElapsedMs=planningBegan;
            JSONObject navigation=new JSONObject().put("mode","current-broadcast").put("startUTC",start.toString()).put("coverageCheckedThroughUTC",start.plusSeconds(motion.duration).toString())
                .put("broadcastProducedAtUTC",(gps.produced().isBefore(bds.produced())?gps.produced():bds.produced()).toString()).put("gpsMinusUTCSeconds",offset).put("bdtMinusUTCSeconds",offset-14).put("preparedAtUTC",Instant.now().toString())
                .put("sources",new JSONArray().put(NativeScene.source(gps)).put(NativeScene.source(gpsModels)).put(NativeScene.source(bds)).put(NativeScene.source(bdsModels)).put(NativeScene.source(leaps)));
            scene.manifest.put("navigation",navigation).put("navigationPreparationMs",android.os.SystemClock.elapsedRealtime()-began);
            MixedScene.write(folder,"navigation.json",navigation.toString(2));MixedScene.write(folder,"navigation-source-audit.json",failures.toString(2));MixedScene.write(folder,"experiment.json",scene.manifest.toString(2));
            status.accept("GPS与北斗共同时段已验证；混合流不保存宽带样本");return scene;
        }
        throw new IOException("导航源尚未同时覆盖GPS与北斗当前时段："+failures);
    }
    private static long leadSeconds(Context context){
        long previous=context.getSharedPreferences("controller",0).getLong("mixedStreamReadyMillis",8000);
        return Preparation.streamLeadSeconds(previous);
    }
}
