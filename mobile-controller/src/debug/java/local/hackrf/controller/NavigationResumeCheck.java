package local.hackrf.controller;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import org.json.*;

/** Deliberate download cancellation and a fresh invocation using production checkpoints; no RF. */
public class NavigationResumeCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();
        try{
            Context c=getTargetContext();if(TransmitService.busy)throw new IOException("Task active");
            startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            String url="https://igs.bkg.bund.de/root_ftp/NTRIP/BRDC/brdc_last.rnx.Z?resume-check="+UUID.randomUUID();
            File cache=new File(c.getFilesDir(),"navigation-cache/parts");AtomicBoolean paused=new AtomicBoolean();
            try{
                NavigationChunks.fetch(new URL(url),cache,()->{if(paused.get())throw new IOException("Intentional download pause");},message->{
                    Bundle p=new Bundle();p.putString("stream",message+"\n");sendStatus(0,p);
                    if(message.startsWith("3路分块续传 ")){int end=message.indexOf('%');double percent=Double.parseDouble(message.substring("3路分块续传 ".length(),end).trim());if(percent>=30&&percent<100)paused.set(true);}
                });throw new AssertionError("Download finished before cancellation test");
            }catch(IOException expected){if(!paused.get())throw expected;}
            NavigationHttp.Result resumed=NavigationChunks.fetch(new URL(url),cache,()->{},message->{Bundle p=new Bundle();p.putString("stream",message+"\n");sendStatus(0,p);});
            if(resumed.parts!=3||resumed.resumedBytes<=0||resumed.transferredBytes>=resumed.bytes.length)throw new AssertionError("Saved partial blocks not reused; file may have changed version");
            RinexNav.production(new String(UnixCompress.decode(resumed.bytes,20000000,()->{}),java.nio.charset.StandardCharsets.US_ASCII));
            NavigationHttp.Result repeat=NavigationChunks.fetch(new URL(url),cache,()->{},message->{});
            if(repeat.transferredBytes!=0||!Arrays.equals(resumed.bytes,repeat.bytes))throw new AssertionError("Completed file downloaded again");
            JSONObject report=new JSONObject().put("parallelParts",3).put("intentionalPause",true).put("fileBytes",resumed.bytes.length).put("resumedBytes",resumed.resumedBytes).put("transferredBytesAfterPause",resumed.transferredBytes).put("repeatTransferredBytes",repeat.transferredBytes).put("repeatMillis",repeat.elapsedMillis)
                .put("downloadSHA256",ScenarioStore.hex(MessageDigest.getInstance("SHA-256").digest(resumed.bytes))).put("rfEnabled",false);
            Files.write(new File(c.getFilesDir(),"navigation-resume-check.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.putString("stream","PASS: "+report.toString());finish(Activity.RESULT_OK,out);
        }catch(Throwable e){out.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,out);}
    }
}
