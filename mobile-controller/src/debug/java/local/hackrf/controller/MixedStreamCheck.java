package local.hackrf.controller;

import android.app.*;
import android.os.Bundle;
import java.io.*;
import java.time.Instant;
import java.util.*;
import org.json.JSONObject;

/** Configuration and bounded stream checks only: no native generation, USB or RF. */
public class MixedStreamCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            String older=NativeScene.expandSource("{olderHourYear}/{olderHourDoy}/{olderHour}",java.time.ZonedDateTime.parse("2026-01-01T00:10:00Z"));
            if(!older.equals("2025/365/22"))throw new AssertionError("Two-hour source fallback failed across UTC year boundary: "+older);
            File binaries=new File(getTargetContext().getApplicationInfo().nativeLibraryDir);
            if(!new File(binaries,"libmixedstream.so").canExecute())throw new AssertionError("Mixer PIE missing");
            File folder=new File(getTargetContext().getCacheDir(),"mixed-check");
            if(MixedScene.RATE!=18000000 || MixedScene.FREQUENCY!=1567747500L)throw new AssertionError("Mixed 18Msps RF profile changed");
            PhoneMotion motion=PhoneMotion.profile("stroll");motion.duration=90;
            List<ProcessBuilder> commands=MixedScene.commands(binaries,folder,motion,Instant.parse("2026-10-18T00:00:00Z"),18);
            List<String> gps=commands.get(0).command(),bds=commands.get(1).command(),mixer=commands.get(2).command();
            if(!gps.get(0).endsWith("libgpssim.so") || !gps.contains("2026/10/18,00:00:18") || !gps.contains("90.1") || !gps.contains("gps.fifo"))throw new AssertionError("GPS command mismatch");
            if(!bds.get(0).endsWith("libbdssim.so") || !bds.contains("scenario-bds.json") || !mixer.contains("--duration-seconds") || !mixer.contains("90"))throw new AssertionError("BDS/mixer command mismatch");
            if(!"4".equals(commands.get(1).environment().get("OMP_NUM_THREADS")))throw new AssertionError("BDS thread default changed");
            for(ProcessBuilder command:commands)if(!command.command().contains("emitted.csv") && command!=commands.get(2))throw new AssertionError("Motion not shared");
            byte[] data=new byte[MixedScene.PREFILL+MixedScene.CHUNK+17];new Random(42).nextBytes(data);
            MixedScene owner=new MixedScene(folder,new JSONObject(),Collections.emptyList(),data.length);
            MixedScene.Stream stream=new MixedScene.Stream(new ByteArrayInputStream(data),data.length,()->{},owner);
            stream.prefill();byte[] block=new byte[65536];int n,offset=0;
            while((n=stream.read(block))!=-1){for(int i=0;i<n;i++)if(block[i]!=data[offset+i])throw new AssertionError("Stream changed bytes");offset+=n;}
            if(offset!=data.length)throw new AssertionError("Stream length mismatch");stream.verifyComplete();stream.close();
            final boolean[] complete={false},closed={false};
            MixedScene.Owner singleOwner=new MixedScene.Owner(){public boolean isClosed(){return closed[0];}public void verifyProcesses(){complete[0]=true;}public void close(){closed[0]=true;}};
            MixedScene.Stream single=new MixedScene.Stream(new ByteArrayInputStream(new byte[32]),32,()->{},singleOwner);
            single.prefill();byte[] only=new byte[32];if(single.read(only)!=32||single.read()!=-1||!complete[0])throw new AssertionError("Single owner EOF not verified");
            single.close();try{single.read();throw new AssertionError("Closed owner still readable");}catch(InterruptedIOException expected){}
            MixedScene brokenOwner=new MixedScene(folder,new JSONObject(),Collections.emptyList(),10);
            MixedScene.Stream broken=new MixedScene.Stream(new ByteArrayInputStream(new byte[4]),10,()->{},brokenOwner);
            try{broken.prefill();throw new AssertionError("Short producer accepted");}catch(EOFException expected){}finally{broken.close();}
            if(MixedScene.QUEUE_CHUNKS!=64 || MixedScene.PREFILL!=8000000)throw new AssertionError("Mixed jitter queue or startup prefill changed");
            if(MixedScene.PREFILL+(MixedScene.QUEUE_CHUNKS+2L)*MixedScene.CHUNK>=26000000)throw new AssertionError("Buffer bound exceeds 26MB");
            result.putString("stream","PASS: mixed commands, common UTC/path, bounded prefill, exact bytes and EOF; no native generation, USB or RF");finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("stream","FAIL: "+error);finish(Activity.RESULT_CANCELED,result);}
    }
}
