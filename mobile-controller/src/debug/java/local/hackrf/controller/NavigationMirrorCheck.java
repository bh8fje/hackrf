package local.hackrf.controller;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.WindowManager;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Real archive transfer and native visibility preflight; never opens USB or writes I/Q. */
public class NavigationMirrorCheck extends Instrumentation {
    private boolean bds;
    @Override public void onCreate(Bundle args){super.onCreate(args);bds="bds".equals(args.getString("signal"));start();}
    @Override public void onStart(){Bundle out=new Bundle();Activity activity=null;
        try{
            Context context=getTargetContext();if(TransmitService.busy)throw new IOException("Task active");
            if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())throw new IOException("请先解锁手机");
            activity=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Activity opened=activity;
            runOnMainSync(()->opened.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            try{NativeScene.cachedFetch(context,"https://data.gnss.ga.gov.au/api/rinexFiles/bad-name",10800,true,()->{});throw new AssertionError("Invalid archive name accepted");}catch(IOException expected){}
            JSONArray policy=new JSONObject(PhoneConfig.asset(context,"navigation-sources.json")).getJSONArray(bds?"bds":"broadcast");
            JSONArray templates=new JSONArray();for(int i=0;i<policy.length();i++)if((bds&&!policy.getString(i).contains("/NTRIP/"))||policy.getString(i).startsWith("https://data.gnss.ga.gov.au/api/rinexFiles/"))templates.put(policy.getString(i));
            if(templates.length()!=2)throw new AssertionError("Expected two archive mirrors");
            NativeScene.Download[] files=new NativeScene.Download[2];long began=SystemClock.elapsedRealtime(),bytes=0;
            for(int i=0;i<2;i++){
                String url=NativeScene.expandSource(templates.getString(i),Instant.now().atZone(ZoneOffset.UTC));
                files[i]=NativeScene.cachedFetch(context,url,10800,!bds||i==0,()->{},message->{Bundle progress=new Bundle();progress.putString("stream",message+"\n");sendStatus(0,progress);});if(!files[i].cached)bytes+=files[i].downloadBytes;
                long age=Duration.between(files[i].produced(),Instant.now()).getSeconds();
                if(age< -60||age>(i==0?1800:10800))throw new IOException("Navigation publication is outside current window");
            }
            long elapsed=SystemClock.elapsedRealtime()-began;Instant start=Instant.now().plusSeconds(60);int offset=RinexNav.gpsMinusUTC(PhoneConfig.asset(context,"Leap_Second.dat"),start);
            if(!bds)RinexNav.convert(RinexNav.broadcast3(files[0].text),files[1].text,start.plusSeconds(offset),offset,90);
            String navigation=bds?BdsNavigation.convert(files[0].text,files[1].text,start,offset,90):GalileoNavigation.convert(files[0].text,files[1].text,start,offset,90);
            File folder=new File(context.getFilesDir(),"navigation-mirror-checks/"+UUID.randomUUID());if(!folder.mkdirs())throw new IOException("Cannot save check");
            Files.write(new File(folder,bds?"broadcast-bds.rnx":"broadcast-galileo.rnx").toPath(),navigation.getBytes(StandardCharsets.US_ASCII));
            PhoneMotion motion=NativeScene.parameters(PhoneConfig.defaults(context).put("duration",90));
            File trajectory=new File(folder,"emitted.csv");motion.write(trajectory,()->{});
            String[] first=new String(Files.readAllBytes(trajectory.toPath()),StandardCharsets.US_ASCII).split("\\n",2)[0].split(",");
            double[] xyz={Double.parseDouble(first[1]),Double.parseDouble(first[2]),Double.parseDouble(first[3])};
            double radius=Math.sqrt(xyz[0]*xyz[0]+xyz[1]*xyz[1]+xyz[2]*xyz[2]);if(radius<6000000||radius>7000000)throw new AssertionError("Expected ECEF position");
            Files.write(new File(folder,"scenario.json").toPath(),SampledScene.configuration(motion,start,xyz,!bds).toString(2).getBytes(StandardCharsets.UTF_8));
            ProcessBuilder command=new ProcessBuilder(new File(context.getApplicationInfo().nativeLibraryDir,"libbdssim.so").toString(),"-c","scenario.json","-vo","-st");command.directory(folder);command.redirectErrorStream(true).redirectOutput(new File(folder,"visibility.log"));new NativeScene().execute(command,30,()->{});
            String log=new String(Files.readAllBytes(new File(folder,"visibility.log").toPath()),StandardCharsets.UTF_8);Matcher count=Pattern.compile("Total channels: (\\d+)").matcher(log);
            if(!count.find()||Integer.parseInt(count.group(1))<4||new File(folder,"signal.bin").exists())throw new AssertionError("Insufficient visible satellites or unexpected samples");
            JSONObject report=new JSONObject().put("downloadBytes",bytes).put("downloadMillis",elapsed).put("signal",bds?"BDS-B1I":"GAL-E1").put("visibleSatellites",Integer.parseInt(count.group(1))).put("gpsCoverageVerified",!bds).put("galileoCoverageVerified",!bds).put("bdsCoverageVerified",bds).put("rfEnabled",false).put("sources",new JSONArray().put(NativeScene.source(files[0])).put(NativeScene.source(files[1])));
            Files.write(new File(folder,"report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
            out.putString("stream","PASS: phone "+(bds?"TARC BDS":"GA GPS/Galileo")+" "+bytes+" bytes / "+elapsed+" ms; "+count.group(1)+" visible; no I/Q or RF; folder="+folder.getName());finish(Activity.RESULT_OK,out);
        }catch(Throwable e){out.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,out);}
        finally{if(activity!=null){Activity opened=activity;runOnMainSync(()->{opened.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);opened.finish();});}}
    }
}
