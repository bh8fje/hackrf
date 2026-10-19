package local.hackrf.controller;

import android.system.Os;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Common validated navigation inputs; no RF access and no complete I/Q file. */
final class MixedScene implements Closeable {
    interface Owner {boolean isClosed();void verifyProcesses()throws IOException;void close();}
    static final int RATE=18000000, CHUNK=262144, PREFILL=8000000, QUEUE_CHUNKS=64;
    static final long FREQUENCY=1567747500L;
    long planningBeganElapsedMs;
    final File folder;
    final JSONObject manifest;
    final long expectedBytes;
    final List<ProcessBuilder> commands;
    private final List<Process> processes=new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private Stream stream;
    private boolean started;

    MixedScene(File folder,JSONObject manifest,List<ProcessBuilder> commands,long bytes){
        this.folder=folder;this.manifest=manifest;this.commands=commands;expectedBytes=bytes;
    }
    static MixedScene prepare(File folder,File binaryDir,JSONObject settings,String gpsBroadcast,String gpsModels,
            String bdsBroadcast,String bdsModels,String leaps,Instant start,PhoneMotion.Check check)throws Exception {
        PhoneMotion motion=NativeScene.parameters(settings);
        if(start.getNano()!=0)throw new IOException("混合场景必须使用整秒UTC");
        int offset=RinexNav.gpsMinusUTC(leaps,start);
        if(RinexNav.gpsMinusUTC(leaps,start.plusSeconds(motion.duration))!=offset)throw new IOException("混合场景跨越闰秒");
        String gps=RinexNav.convert(RinexNav.broadcast3(gpsBroadcast),gpsModels,start.plusSeconds(offset),offset,motion.duration);
        String bds=BdsNavigation.convert(bdsBroadcast,bdsModels,start,offset,motion.duration);
        if(folder.exists() || !folder.mkdirs())throw new IOException("混合场景目录必须是新目录");
        write(folder,"broadcast-gps.rnx",gps);write(folder,"broadcast-bds.rnx",bds);write(folder,"source-iers.dat",leaps);
        PhoneMotion.Trace trace=motion.write(new File(folder,"emitted.csv"),check);
        motion.writePoints(new File(folder,"ideal.csv"),trace.ideal,check);
        String[] first=Files.readAllLines(new File(folder,"emitted.csv").toPath(),StandardCharsets.US_ASCII).get(0).split(",");
        double[] xyz={Double.parseDouble(first[1]),Double.parseDouble(first[2]),Double.parseDouble(first[3])};
        JSONObject scenario=SampledScene.configuration(motion,start,xyz,"bds");
        scenario.getJSONObject("output").put("name","bds.fifo");
        scenario.getJSONObject("power").getJSONObject("initPower").put("value",44);
        write(folder,"scenario-bds.json",scenario.toString(2));write(folder,"settings.json",settings.toString(2));
        JSONObject manifest=new JSONObject().put("schema",2).put("type","hackrf-mixed-stream").put("signal","GPS-L1CA+BDS-B1I")
                .put("format","s8-iq").put("sampleRate",RATE).put("frequencyHz",FREQUENCY).put("durationSeconds",motion.duration)
                .put("iqBytes",motion.duration*(long)RATE*2).put("storedIQBytes",0).put("hardwareOutputAllowed",false)
                .put("softwareReceiverVerified",false).put("jointPositioningVerified",false).put("relativeRfPowerVerified",false)
                .put("navigation",new JSONObject().put("mode","current-broadcast").put("startUTC",start.toString()).put("gpsMinusUTCSeconds",offset).put("coverageCheckedThroughUTC",start.plusSeconds(motion.duration).toString()))
                .put("startUTC",start.toString()).put("gpsMinusUTCSeconds",offset).put("coverageCheckedThroughUTC",start.plusSeconds(motion.duration).toString())
                .put("generatorGPS","gps-sdr-sim").put("generatorBDS","SignalSim B1I").put("settings",settings)
                .put("signalProfile",SignalProfile.IDS[SignalProfile.forRoute(motion.mode)]).put("prefillBytes",PREFILL)
                .put("powerNote","不同生成器与混合权重不代表逐星严格3dB；GPS主、北斗辅需独立接收验收");
        write(folder,"experiment.json",manifest.toString(2));
        return new MixedScene(folder,manifest,commands(binaryDir,folder,motion,start,offset),motion.duration*(long)RATE*2);
    }
    static List<ProcessBuilder> commands(File binaryDir,File folder,PhoneMotion motion,Instant start,int offset){
        int profile=SignalProfile.forRoute(motion.mode);
        String gpst=DateTimeFormatter.ofPattern("uuuu/MM/dd,HH:mm:ss").withZone(ZoneOffset.UTC).format(start.plusSeconds(offset));
        ProcessBuilder gps=new ProcessBuilder(new File(binaryDir,"libgpssim.so").getAbsolutePath(),"-Q",Integer.toString(profile),"-S",Long.toString(motion.seed),
                "-e","broadcast-gps.rnx","-u","emitted.csv","-t",gpst,"-d",Double.toString(motion.duration+.1),"-s","2600000","-b","8","-o","gps.fifo");
        ProcessBuilder bds=new ProcessBuilder(new File(binaryDir,"libbdssim.so").getAbsolutePath(),"-c","scenario-bds.json","-u","emitted.csv","-mt","-Q",Integer.toString(profile),"-S",Long.toString(motion.seed));
        bds.environment().put("OMP_NUM_THREADS","4");bds.environment().put("OMP_WAIT_POLICY","PASSIVE");bds.environment().put("OMP_DYNAMIC","FALSE");
        ProcessBuilder mixer=new ProcessBuilder(new File(binaryDir,"libmixedstream.so").getAbsolutePath(),"gps.fifo","bds.fifo","-",".35",".25","--duration-seconds",Integer.toString(motion.duration));
        List<ProcessBuilder> result=Arrays.asList(gps,bds,mixer);
        for(ProcessBuilder command:result){command.directory(folder);command.environment().put("LC_ALL","C");}
        return result;
    }
    static void write(File folder,String name,String text)throws IOException {Files.write(new File(folder,name).toPath(),text.getBytes(StandardCharsets.UTF_8));}
    Stream start(PhoneMotion.Check check)throws Exception {
        synchronized(this){if(started || closed.get())throw new IOException("混合流已启动或关闭");started=true;}
        try{
            for(String name:new String[]{"gps.fifo","bds.fifo"})Os.mkfifo(new File(folder,name).getAbsolutePath(),0600);
            for(int i=0;i<commands.size();i++){
                check.run();if(closed.get())throw new InterruptedIOException("已停止混合流");
                ProcessBuilder command=commands.get(i);
                command.redirectError(new File(folder,i==2?"mixer.log":i==0?"generator-gps.log":"generator-bds.log"));
                if(i<2)command.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(new File(folder,i==0?"generator-gps.log":"generator-bds.log")));
                Process process=command.start();processes.add(process);
                if(closed.get()){process.destroyForcibly();throw new InterruptedIOException("已停止混合流");}
            }
            stream=new Stream(processes.get(2).getInputStream(),expectedBytes,check,new Owner(){public boolean isClosed(){return MixedScene.this.isClosed();}public void verifyProcesses()throws IOException{MixedScene.this.verifyProcesses();}public void close(){MixedScene.this.close();}});
            stream.prefill();check.run();
            JSONArray gps=NativeScene.sceneSatellites(new String(Files.readAllBytes(new File(folder,"generator-gps.log").toPath()),StandardCharsets.UTF_8));
            if(gps.length()<4)throw new IOException("混合主GPS实际生成通道不足四颗");
            int[] bds=SampledSatellites.ids(new String(Files.readAllBytes(new File(folder,"generator-bds.log").toPath()),StandardCharsets.UTF_8),"bds");
            JSONArray auxiliary=new JSONArray();for(int svid:bds)auxiliary.put(String.format(Locale.US,"C%02d",svid));
            manifest.put("sceneSatellites",gps).put("auxiliarySceneSatellites",auxiliary)
                .put("receiverMatchingNote","接收实验室按GPS主星座匹配目标轨迹；北斗参与须另用原始usedInFix或同周期NMEA联合验收，GPS匹配不代表混合定位已验证");
            write(folder,"experiment.json",manifest.toString(2));return stream;
        }catch(Exception error){close();throw error;}
    }
    boolean isClosed(){return closed.get();}
    void verifyProcesses()throws IOException {
        for(Process process:processes)try{
            if(!process.waitFor(3,TimeUnit.SECONDS) || process.exitValue()!=0)throw new IOException("混合原生进程未正常完成");
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException("混合流已中断");}
    }
    @Override public void close(){
        if(!closed.compareAndSet(false,true))return;
        for(Process process:processes)process.destroy();
        for(Process process:processes){
            try{if(!process.waitFor(500,TimeUnit.MILLISECONDS)){process.destroyForcibly();process.waitFor(1,TimeUnit.SECONDS);}}catch(InterruptedException error){process.destroyForcibly();Thread.currentThread().interrupt();}
            try{process.getInputStream().close();}catch(IOException ignored){}
            try{process.getErrorStream().close();}catch(IOException ignored){}
            try{process.getOutputStream().close();}catch(IOException ignored){}
        }
        if(stream!=null){stream.worker.interrupt();try{stream.worker.join(500);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
        for(String name:new String[]{"gps.fifo","bds.fifo"})new File(folder,name).delete();
    }

    /** Bounded producer queue: caller must close on USB disconnect and call verifyComplete at the end. */
    static final class Stream extends InputStream {
        private final ArrayBlockingQueue<Object> queue=new ArrayBlockingQueue<>(QUEUE_CHUNKS);
        private final PhoneMotion.Check check;
        private final Owner owner;
        private final long expected;
        private byte[] current,pending;private int position,pendingPosition;private long consumed;
        private boolean ended;
        final Thread worker;
        Stream(InputStream input,long expected,PhoneMotion.Check check,MixedScene scene){this(input,expected,check,new Owner(){public boolean isClosed(){return scene.isClosed();}public void verifyProcesses()throws IOException{scene.verifyProcesses();}public void close(){scene.close();}});}
        Stream(InputStream input,long expected,PhoneMotion.Check check,Owner owner){
            this.check=check;this.owner=owner;this.expected=expected;
            worker=new Thread(()->{
                long total=0;
                try{
                    while(!owner.isClosed()){
                        byte[] block=new byte[CHUNK];int n=0;
                        while(n<block.length){int got=input.read(block,n,block.length-n);if(got<0)break;if(got==0)continue;n+=got;}
                        if(n==0)break;total+=n;
                        if(total>expected)throw new IOException("混合流超出声明时长");
                        enqueue(n==block.length?block:Arrays.copyOf(block,n));
                    }
                    if(total!=expected)throw new EOFException("混合流提前结束");
                    enqueue(new byte[0]);
                }catch(Exception error){enqueue(error);}
            },"mixed-iq-pump");worker.setDaemon(true);worker.start();
        }
        private void enqueue(Object value){
            while(!owner.isClosed())try{if(queue.offer(value,100,TimeUnit.MILLISECONDS))return;}catch(InterruptedException error){Thread.currentThread().interrupt();return;}
        }
        private byte[] take()throws IOException{
            check.run();if(owner.isClosed())throw new InterruptedIOException("混合流已停止");
            Object value;
            try{value=queue.poll(2,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException("混合流已中断");}
            if(value==null)throw new IOException("混合流供数停顿，必须停止发送");
            if(value instanceof IOException)throw (IOException)value;
            if(value instanceof Exception)throw new IOException("混合生成失败",(Exception)value);
            return (byte[])value;
        }
        void prefill()throws IOException{
            byte[] prefix=new byte[(int)Math.min(PREFILL,expected)];int offset=0;
            while(offset<prefix.length){
                if(current==null || position==current.length){current=take();position=0;if(current.length==0)throw new EOFException("混合预缓冲不足");}
                int count=Math.min(prefix.length-offset,current.length-position);System.arraycopy(current,position,prefix,offset,count);position+=count;offset+=count;
            }
            if(current!=null && position<current.length){pending=current;pendingPosition=position;}
            current=prefix;position=0;
        }
        @Override public int read()throws IOException{byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{
            if(bytes==null)throw new NullPointerException();if(offset<0||length<0||offset>bytes.length-length)throw new IndexOutOfBoundsException();
            if(length==0)return 0;check.run();if(owner.isClosed())throw new InterruptedIOException("混合流已停止");if(ended)return -1;
            if(current==null || position==current.length){if(pending!=null){current=pending;position=pendingPosition;pending=null;}else{current=take();position=0;}if(current.length==0){ended=true;owner.verifyProcesses();return -1;}}
            int count=Math.min(length,current.length-position);System.arraycopy(current,position,bytes,offset,count);position+=count;consumed+=count;return count;
        }
        void verifyComplete()throws IOException {if(consumed!=expected || read()!=-1)throw new IOException("混合流未完整消费");}
        @Override public void close(){owner.close();}
    }
}
