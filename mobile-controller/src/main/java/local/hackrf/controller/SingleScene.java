package local.hackrf.controller;

import android.content.Context;
import android.system.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Original single-constellation producer feeding the same bounded USB stream pump. */
final class SingleScene implements MixedScene.Owner {
    final long planningBeganElapsedMs;final File folder;final JSONObject manifest;final String system;
    private final ProcessBuilder command;
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile Process process;private volatile FileDescriptor keeper;private volatile InputStream input;
    private MixedScene.Stream stream;private Thread watcher;
    SingleScene(File folder,JSONObject settings,JSONObject navigation,String system,ProcessBuilder command,JSONObject environment)throws Exception {
        this.folder=folder;this.command=command;this.system=system;planningBeganElapsedMs=navigation.optLong("streamTimingOriginElapsedMs",android.os.SystemClock.elapsedRealtime());
        int rate=system.equals("gps")?2600000:5000000;int duration=settings.getInt("duration");
        manifest=new JSONObject().put("schema",2).put("type","hackrf-"+system+"-iq").put("signal",system.equals("gps")?"GPS-L1CA":"BDS-B1I")
            .put("format","s8-iq").put("sampleRate",rate).put("frequencyHz",system.equals("gps")?1575420000L:1561098000L)
            .put("durationSeconds",duration).put("iqBytes",duration*(long)rate*2).put("storedIQBytes",0).put("storageFormat","stream-no-wideband-file")
            .put("softwareReceiverVerified",false).put("navigation",navigation).put("settings",settings);
        if(environment!=null)manifest.put("signalEnvironment",environment);
        MixedScene.write(folder,"experiment.json",manifest.toString(2));
    }
    static int leadSeconds(Context context,String system){
        long previous=context.getSharedPreferences("controller",0).getLong(system+"StreamReadyMillis",8000);
        return Preparation.streamLeadSeconds(previous);
    }
    MixedScene.Stream start(PhoneMotion.Check check)throws Exception {
        try {
            synchronized(this){
            if(process!=null||closed.get())throw new IOException("单系统流已启动或关闭");
            command.directory(folder);command.redirectError(new File(folder,"generator.log"));
            if(system.equals("gps")) {
                int index=command.command().indexOf("-o");command.command().set(index+1,"-");command.redirectErrorStream(false).redirectOutput(ProcessBuilder.Redirect.PIPE);
                process=command.start();input=process.getInputStream();
            }else{
                File fifo=new File(folder,"signal.fifo");Os.mkfifo(fifo.getAbsolutePath(),0600);
                keeper=Os.open(fifo.getAbsolutePath(),OsConstants.O_RDWR|OsConstants.O_NONBLOCK,0);
                input=new FileInputStream(fifo);command.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(new File(folder,"generator.log")));
                process=command.start();
                watcher=new Thread(()->{try{process.waitFor();}catch(InterruptedException error){Thread.currentThread().interrupt();}finally{closeKeeper();}},"single-fifo-exit");watcher.setDaemon(true);watcher.start();
            }
            }
            if(closed.get())throw new InterruptedIOException("单系统流已停止");
            stream=new MixedScene.Stream(input,manifest.getLong("iqBytes"),check,this);stream.prefill();check.run();
            String log=new String(Files.readAllBytes(new File(folder,"generator.log").toPath()),StandardCharsets.UTF_8);
            JSONArray satellites;
            if(system.equals("gps")){satellites=NativeScene.sceneSatellites(log);if(satellites.length()<4)throw new IOException("实际GPS生成通道不足四颗");}
            else{satellites=new JSONArray();for(int id:SampledSatellites.ids(log,"bds"))satellites.put(String.format(java.util.Locale.US,"C%02d",id));}
            manifest.put("sceneSatellites",satellites);MixedScene.write(folder,"experiment.json",manifest.toString(2));return stream;
        }catch(Exception error){close();throw error;}
    }
    private synchronized void closeKeeper(){if(keeper!=null){try{Os.close(keeper);}catch(ErrnoException error){android.util.Log.w("WalkLab","FIFO keeper close",error);}keeper=null;}}
    public boolean isClosed(){return closed.get();}
    public void verifyProcesses()throws IOException {
        try{if(process==null||!process.waitFor(3,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("单系统生成器未正常完成");}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException("单系统流中断");}
    }
    public synchronized void close(){
        if(!closed.compareAndSet(false,true))return;
        Process active=process;if(active!=null){active.destroy();try{if(!active.waitFor(500,TimeUnit.MILLISECONDS)){active.destroyForcibly();active.waitFor(1,TimeUnit.SECONDS);}}catch(InterruptedException error){active.destroyForcibly();Thread.currentThread().interrupt();}}
        closeKeeper();if(input!=null)try{input.close();}catch(IOException error){android.util.Log.w("WalkLab","Single stream close",error);}
        if(stream!=null)stream.worker.interrupt();if(watcher!=null)watcher.interrupt();new File(folder,"signal.fifo").delete();
    }
}
