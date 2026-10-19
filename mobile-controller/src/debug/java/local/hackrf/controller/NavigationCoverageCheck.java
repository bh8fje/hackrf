package local.hackrf.controller;
import android.app.*;
import android.os.*;
import java.io.*;
import java.nio.file.Files;
import java.time.*;
import org.json.*;

/** Current downloaded navigation validation only; never generates I/Q or opens USB. */
public class NavigationCoverageCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        if(TransmitService.busy)throw new IllegalStateException("Task active");
        String error="本机生成失败：没有当前北斗数据源覆盖整个场景：[{\"url\":\"https://example.invalid\",\"error\":\"coverage\"}]";
        String concise=MainActivity.friendlyStatus(error);
        if(concise.contains("https://")||concise.contains("[{" )||concise.length()>120)throw new AssertionError("Raw diagnostics leaked into user message");
        String policy=PhoneConfig.asset(getTargetContext(),"navigation-sources.json");
        if(!new JSONObject(policy).getJSONArray("bds").toString().contains("brdc_last.rnx.Z"))throw new AssertionError("Rolling BDS source missing from APK");
        File folder=new File(getTargetContext().getCacheDir(),"coverage-check");
        String models=new String(Files.readAllBytes(new File(folder,"models.rnx").toPath()),java.nio.charset.StandardCharsets.US_ASCII);Instant now=Instant.now();
        if(Math.abs(Duration.between(RinexNav.production(models),now).getSeconds())>10800)throw new AssertionError("Test models stale");
        JSONArray sources=new JSONArray();String validatedNav=null;
        for(String name:new String[]{"tarc.rnx","snapshot.rnx"}){
            String raw=new String(Files.readAllBytes(new File(folder,name).toPath()),java.nio.charset.StandardCharsets.US_ASCII);
            if(Math.abs(Duration.between(RinexNav.production(raw),now).getSeconds())>1800)throw new AssertionError("Test source stale");
            String checked=BdsNavigation.convert(raw,models,now.plusSeconds(600),18,180);
            validatedNav=checked;
            int satellites=0;for(String row:checked.split("\\R"))if(row.matches("^C\\d{2} .*"))satellites++;
            sources.put(new JSONObject().put("file",name).put("satellites",satellites));
        }
        Files.write(new File(folder,"broadcast-bds.rnx").toPath(),validatedNav.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        PhoneMotion motion=NativeScene.parameters(PhoneConfig.defaults(getTargetContext()).put("duration",180));
        File trajectory=new File(folder,"emitted.csv");motion.write(trajectory,()->{});
        String[] first=new String(Files.readAllBytes(trajectory.toPath()),java.nio.charset.StandardCharsets.US_ASCII).split("\\n",2)[0].split(",");
        double[] xyz={Double.parseDouble(first[1]),Double.parseDouble(first[2]),Double.parseDouble(first[3])};
        Files.write(new File(folder,"scenario.json").toPath(),SampledScene.configuration(motion,now.plusSeconds(600),xyz,false).toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ProcessBuilder command=new ProcessBuilder(new File(getTargetContext().getApplicationInfo().nativeLibraryDir,"libbdssim.so").toString(),"-c","scenario.json","-vo","-st");command.directory(folder);command.redirectErrorStream(true).redirectOutput(new File(folder,"visibility.log"));new NativeScene().execute(command,30,()->{});
        String log=new String(Files.readAllBytes(new File(folder,"visibility.log").toPath()),java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher channels=java.util.regex.Pattern.compile("Total channels: (\\d+)").matcher(log);
        if(!channels.find()||Integer.parseInt(channels.group(1))<4||new File(folder,"signal.bin").exists())throw new AssertionError("Native coverage failed or I/Q unexpectedly generated");
        JSONObject report=new JSONObject().put("visibleChannels",Integer.parseInt(channels.group(1))).put("sources",sources).put("startUTC",now.plusSeconds(600)).put("durationSeconds",180).put("rfEnabled",false).put("conciseError",concise);
        Files.write(new File(folder,"report.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));result.putString("result",report.toString());finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("error",error.toString());finish(Activity.RESULT_CANCELED,result);}}
}
