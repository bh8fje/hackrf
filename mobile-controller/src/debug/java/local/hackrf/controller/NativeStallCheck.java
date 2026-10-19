package local.hackrf.controller;
import android.app.*;
import android.os.*;
import java.io.*;
import java.nio.file.Files;

/** A deliberately stalled child, not a signal generator or transmitter. */
public class NativeStallCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();File folder=null;
        try{
            if(TransmitService.busy)throw new IOException("Task active");
            folder=Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),"stall-check-").toFile();
            ProcessBuilder command=new ProcessBuilder("/system/bin/sh","-c","printf x > signal.bin; exec sleep 50");command.directory(folder);
            long began=SystemClock.elapsedRealtime();
            int[] updates={0};
            try{new NativeScene().execute(command,70,()->{},1,message->{if(!message.startsWith("正在生成模拟样本")||!message.contains("MB/s"))throw new AssertionError("Generation progress hidden");updates[0]++;});throw new AssertionError("Stalled child accepted");}
            catch(IOException expected){if(!expected.getMessage().contains("停滞30秒"))throw expected;}
            long elapsed=SystemClock.elapsedRealtime()-began;if(elapsed<30000||elapsed>40000)throw new AssertionError("Watchdog time "+elapsed);
            if(updates[0]<1)throw new AssertionError("No sample-size progress");
            out.putString("stream","PASS: stalled native child rejected and terminated in "+elapsed+"ms; no RF");finish(Activity.RESULT_OK,out);
        }catch(Throwable e){out.putString("stream","FAIL: "+e);finish(Activity.RESULT_CANCELED,out);}
        finally{if(folder!=null){new File(folder,"signal.bin").delete();folder.delete();}}
    }
}
