package local.hackrf.controller;
import android.app.*;
import android.content.*;
import android.net.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.nio.file.Files;
import org.json.*;

/** Full production downloads and network evidence, without generation, GNSS recording or RF. */
public class NavigationDownloadCheck extends Instrumentation {
    private boolean snapshot,ga;
    @Override public void onCreate(Bundle args){super.onCreate(args);snapshot=Boolean.parseBoolean(args.getString("snapshot","false"));ga=Boolean.parseBoolean(args.getString("ga","false"));start();}
    @Override public void onStart(){Bundle out=new Bundle();JSONObject report=new JSONObject();JSONArray files=new JSONArray();Context c=getTargetContext();
        try {
            if(TransmitService.busy)throw new IOException("Task active");
            Activity activity=startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            ConnectivityManager manager=c.getSystemService(ConnectivityManager.class);Network active=manager.getActiveNetwork();NetworkCapabilities caps=manager.getNetworkCapabilities(active);
            report.put("network",caps==null?"unavailable":caps.toString());
            long dns=SystemClock.elapsedRealtime();report.put("dns",Arrays.toString(InetAddress.getAllByName("igs.bkg.bund.de"))).put("dnsMillis",SystemClock.elapsedRealtime()-dns);
            Bundle network=new Bundle();network.putString("stream",report.toString(2)+"\n");sendStatus(0,network);
            ZonedDateTime now=Instant.now().atZone(ZoneOffset.UTC);
            for(String product:ga?new String[]{"ALIC00AUS","PERT00AUS"}:snapshot?new String[]{"snapshot"}:new String[]{"S","R"}) {
                ZonedDateTime hour=now.minusHours(1);
                String url=ga?String.format(Locale.US,"https://data.gnss.ga.gov.au/api/rinexFiles/%s_R_%d%03d%02d00_01H_MN.rnx.gz?rinexVersion=3",product,hour.getYear(),hour.getDayOfYear(),hour.getHour()):snapshot?"https://igs.bkg.bund.de/root_ftp/NTRIP/BRDC/brdc_last.rnx.Z":String.format(Locale.US,"https://igs.bkg.bund.de/root_ftp/IGS/BRDC/%d/%03d/BRDC00WRD_%s_%d%03d0000_01D_MN.rnx.gz",now.getYear(),now.getDayOfYear(),product,now.getYear(),now.getDayOfYear());
                JSONObject row=new JSONObject().put("url",url);long began=SystemClock.elapsedRealtime();
                try {
                    NativeScene.Download d=NativeScene.cachedFetch(c,url,10800,true,()->{},message->{Bundle b=new Bundle();b.putString("stream",message+"\n");sendStatus(0,b);if(message.startsWith("正在下载")&&!MainActivity.friendlyStatus(message).equals(message))throw new AssertionError("Download progress hidden");});
                    long age=Duration.between(d.produced(),Instant.now()).getSeconds();if(snapshot&&(age< -60||age>1800))throw new IOException("Snapshot publication is stale");
                    row.put("downloadBytes",d.downloadBytes).put("downloadMillis",d.downloadMillis).put("expandedBytes",d.text.length()).put("producedAtUTC",RinexNav.production(d.text).toString()).put("hasBdsModels",d.text.contains("BDSB")&&d.text.contains("BDUT"))
                       .put("parallelParts",d.parts).put("resumedBytes",d.resumedBytes).put("transferredBytes",d.transferredBytes).put("downloadSHA256",d.hash).put("source",NativeScene.source(d)).put("hasGpsModels",RinexNav.hasModels(d.text));
                }catch(Exception e){row.put("error",e.toString());}
                row.put("elapsedMillis",SystemClock.elapsedRealtime()-began);files.put(row);
            }
            report.put("files",files);Files.write(new File(c.getFilesDir(),"navigation-download-check.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(snapshot&&files.getJSONObject(0).has("error"))throw new IOException(files.getJSONObject(0).getString("error"));
            if(ga)for(int i=0;i<files.length();i++)if(files.getJSONObject(i).has("error"))throw new IOException(files.getJSONObject(i).getString("error"));
            out.putString("stream",report.toString(2));finish(Activity.RESULT_OK,out);
        }catch(Throwable e){out.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,out);}
    }
}
