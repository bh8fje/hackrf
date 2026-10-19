package local.hackrf.controller;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Size-aware version-bound streams, with durable and hash-checked per-range checkpoints. */
final class NavigationChunks {
    private static final class VersionChanged extends IOException {long transferred;int requests;VersionChanged(){super("导航文件版本已更新");}}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
    private static String hash(File file,long count)throws IOException{
        try(InputStream in=new FileInputStream(file)){
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] b=new byte[32768];
            while(count>0){int n=in.read(b,0,(int)Math.min(b.length,count));if(n<0)throw new EOFException("分块校验长度不足");digest.update(b,0,n);count-=n;}return hex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static Properties read(File file)throws IOException{
        if(file.length()>4096)throw new IOException("分块元数据过大");Properties p=new Properties();try(InputStream in=new FileInputStream(file)){p.load(in);}return p;
    }
    private static void write(File file,Properties value)throws IOException{
        File tmp=new File(file.getPath()+".pending");try(FileOutputStream out=new FileOutputStream(tmp)){value.store(out,null);out.getFD().sync();}
        Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private static void checkpoint(File part,RandomAccessFile output)throws IOException{
        output.getFD().sync();Properties p=new Properties();p.setProperty("bytes",Long.toString(output.length()));p.setProperty("sha256",hash(part,output.length()));write(new File(part.getPath()+".meta"),p);
    }
    private static long restore(File part,long limit)throws IOException{
        long saved=0;
        try{Properties p=read(new File(part.getPath()+".meta"));long n=Long.parseLong(p.getProperty("bytes"));if(n>=0&&n<=limit&&part.length()>=n&&hash(part,n).equals(p.getProperty("sha256")))saved=n;}catch(IOException|IllegalArgumentException invalid){}
        try(RandomAccessFile out=new RandomAccessFile(part,"rw")){out.setLength(saved);}return saved;
    }
    private static void check(PhoneMotion.Check check)throws IOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止分块下载");check.run();}
    static NavigationHttp.Result fetch(URL url,File cache,PhoneMotion.Check check,Consumer<String> status)throws IOException{
        long began=System.nanoTime();String key;
        try{key=hex(MessageDigest.getInstance("SHA-256").digest(url.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        File folder=new File(cache,key);if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法创建分块缓存");
        try(FileOutputStream guard=new FileOutputStream(new File(folder,".lock"),true);FileChannel channel=guard.getChannel()){
            FileLock lock;
            try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new IOException("该导航文件正在下载",busy);}if(lock==null)throw new IOException("该导航文件正在下载");
            try{
                long discarded=0;int previousRequests=0;
                for(int attempt=0;attempt<2;attempt++)try{
                    NavigationHttp.Result result=locked(url,folder,check,status,began);
                    return new NavigationHttp.Result(result.bytes,result.modified,result.elapsedMillis,result.attempts+previousRequests,result.parts,result.resumedBytes,result.transferredBytes+discarded);
                }catch(VersionChanged changed){
                    if(attempt==1)throw changed;
                    discarded+=changed.transferred;previousRequests+=changed.requests;check(check);
                    status.accept("服务器已更新导航文件，重新确认版本一次；不会混用旧分块");
                }
                throw new AssertionError("Unreachable navigation version retry");
            }finally{lock.release();}
        }
    }
    private static NavigationHttp.Result locked(URL url,File folder,PhoneMotion.Check check,Consumer<String> status,long began)throws IOException{
        check(check);HttpURLConnection head=(HttpURLConnection)url.openConnection();String etag,modified;long length;
        status.accept("正在检查导航文件版本 · "+url.getPath().substring(url.getPath().lastIndexOf('/')+1));
        try{
            head.setRequestMethod("HEAD");head.setConnectTimeout(15000);head.setReadTimeout(15000);head.setUseCaches(false);head.setRequestProperty("Accept-Encoding","identity");
            int code=head.getResponseCode();boolean probe=code==403||code==405||code==501;
            if(probe){
                head.disconnect();head=(HttpURLConnection)url.openConnection();head.setConnectTimeout(15000);head.setReadTimeout(15000);head.setUseCaches(false);head.setRequestProperty("Accept-Encoding","identity");head.setRequestProperty("Range","bytes=0-0");code=head.getResponseCode();
                if(code==200)return NavigationHttp.fetch(url,check,status);
                java.util.regex.Matcher range=java.util.regex.Pattern.compile("bytes 0-0/([0-9]+)").matcher(String.valueOf(head.getHeaderField("Content-Range")));
                if(code!=206||!range.matches())throw new IOException("导航文件范围探测失败");
                try{length=Long.parseLong(range.group(1));}catch(NumberFormatException invalid){throw new IOException("导航文件长度无效");}
                try(InputStream in=head.getInputStream()){if(in.read()<0||in.read()!=-1)throw new IOException("导航探测响应长度不符");}
            }else{if(code!=200)throw new IOException("导航文件信息HTTP "+code);length=head.getContentLengthLong();}
            etag=head.getHeaderField("ETag");modified=head.getHeaderField("Last-Modified");
            if(length>NavigationHttp.LIMIT)throw new IOException("导航下载超出大小限制");
            if(length<=0||!NavigationHttp.strongEtag(etag)||(!probe&&!"bytes".equalsIgnoreCase(head.getHeaderField("Accept-Ranges"))))return NavigationHttp.fetch(url,check,status);
        }finally{head.disconnect();}
        File manifest=new File(folder,"version.meta"),complete=new File(folder,"complete.bin");boolean same=false;Properties old=new Properties();
        try{old=read(manifest);same=etag.equals(old.getProperty("etag"))&&Long.toString(length).equals(old.getProperty("length"));}catch(IOException invalid){}
        // Preserve the saved range layout when resuming an earlier app version.
        final int parts;
        try{parts=same&&new File(folder,"0.part.meta").exists()?Integer.parseInt(old.getProperty("parts","1")):(length>1_000_000?3:1);}catch(NumberFormatException invalid){throw new IOException("分块布局无效",invalid);}
        if(parts!=1&&parts!=3)throw new IOException("分块布局无效");
        if(!same){
            for(int i=0;i<5;i++)for(String suffix:new String[]{".part",".part.meta",".part.meta.pending"})Files.deleteIfExists(new File(folder,i+suffix).toPath());
            for(String n:new String[]{"complete.bin","complete.meta","complete.pending"})Files.deleteIfExists(new File(folder,n).toPath());
        }
        Properties layout=new Properties();layout.setProperty("etag",etag);layout.setProperty("length",Long.toString(length));layout.setProperty("parts",Integer.toString(parts));write(manifest,layout);
        try{
            Properties m=read(new File(folder,"complete.meta"));
            if(complete.length()==length&&hash(complete,length).equals(m.getProperty("sha256"))){status.accept("服务器确认文件未变化，复用完整文件缓存 · "+String.format(Locale.US,"%.2f MB",length/1e6));return new NavigationHttp.Result(Files.readAllBytes(complete.toPath()),modified,(System.nanoTime()-began)/1000000,1,parts,length,0);}
        }catch(IOException invalid){}
        AtomicLongArray progress=new AtomicLongArray(parts);long initial=0;
        for(int i=0;i<parts;i++){long n=restore(new File(folder,i+".part"),(i+1)*length/parts-i*length/parts);progress.set(i,n);initial+=n;}
        AtomicLong transferred=new AtomicLong();AtomicInteger requests=new AtomicInteger(1);
        Set<HttpURLConnection> active=ConcurrentHashMap.newKeySet();ExecutorService workers=Executors.newFixedThreadPool(parts);ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
        CompletionService<Boolean> done=new ExecutorCompletionService<>(workers);String file=url.getPath().substring(url.getPath().lastIndexOf('/')+1);long last=0;
        VersionChanged changed=null;
        try{
            for(int i=0;i<parts;i++){
                final int part=i;final long total=length;final String version=etag;
                done.submit(()->{download(url,folder,part,parts,total,version,progress,transferred,requests,active,timer,check);return true;});
            }
            int finished=0;
            while(finished<parts){
                check(check);Future<Boolean> result=done.poll(200,TimeUnit.MILLISECONDS);if(result!=null){result.get();finished++;}
                long now=System.nanoTime();if(now-last>=1000000000L){long bytes=0;for(int i=0;i<parts;i++)bytes+=progress.get(i);status.accept(String.format(Locale.US,(parts==3?"3路分块续传 ":"单连接续传 ")+"%.1f%% · ",bytes*100.0/length)+NavigationHttp.progress(file,bytes,length,(now-began)/1000000,transferred.get()));last=now;}
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("已停止分块下载");}
        catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof VersionChanged)changed=(VersionChanged)cause;if(cause instanceof IOException)throw(IOException)cause;throw new IOException("导航分块下载失败",cause);}
        finally{
            workers.shutdownNow();for(HttpURLConnection connection:active)connection.disconnect();
            try{if(!workers.awaitTermination(20,TimeUnit.SECONDS))throw new IOException("分块下载线程未能停止");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{timer.shutdownNow();}
            if(changed!=null){changed.transferred=transferred.get();changed.requests=requests.get();}
        }
        File tmp=new File(folder,"complete.pending");try(FileOutputStream out=new FileOutputStream(tmp)){
            byte[] buffer=new byte[32768];for(int i=0;i<parts;i++)try(InputStream in=new FileInputStream(new File(folder,i+".part"))){int n;while((n=in.read(buffer))!=-1){check(check);out.write(buffer,0,n);}}out.getFD().sync();
        }
        if(tmp.length()!=length)throw new IOException("合并分块后的文件长度不符");
        Properties checksum=new Properties();checksum.setProperty("sha256",hash(tmp,length));Files.move(tmp.toPath(),complete.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);write(new File(folder,"complete.meta"),checksum);
        status.accept("下载完成 · "+NavigationHttp.progress(file,length,length,(System.nanoTime()-began)/1000000,transferred.get()).replace("正在下载 ","下载完成 "));
        return new NavigationHttp.Result(Files.readAllBytes(complete.toPath()),modified,(System.nanoTime()-began)/1000000,requests.get(),parts,initial,transferred.get());
    }
    private static void download(URL url,File folder,int part,int parts,long total,String etag,AtomicLongArray progress,AtomicLong transferred,AtomicInteger requests,Set<HttpURLConnection> active,ScheduledExecutorService timer,PhoneMotion.Check check)throws IOException{
        File file=new File(folder,part+".part");long start=part*total/parts,end=(part+1)*total/parts-1;
        for(int attempt=0;attempt<2;attempt++){
            check(check);long saved=file.length();if(saved==end-start+1)return;
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();active.add(connection);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);ScheduledFuture<?> abort=timer.schedule(connection::disconnect,120,TimeUnit.SECONDS);
            try{
                connection.setConnectTimeout(15000);connection.setReadTimeout(15000);connection.setUseCaches(false);connection.setRequestProperty("Accept-Encoding","identity");connection.setRequestProperty("Range","bytes="+(start+saved)+"-"+end);connection.setRequestProperty("If-Range",etag);requests.incrementAndGet();
                int code=connection.getResponseCode();
                if(!etag.equals(connection.getHeaderField("ETag"))&&NavigationHttp.strongEtag(connection.getHeaderField("ETag")))throw new VersionChanged();
                if(code!=206||!etag.equals(connection.getHeaderField("ETag"))||!("bytes "+(start+saved)+"-"+end+"/"+total).equals(connection.getHeaderField("Content-Range")))throw new IOException("导航分块版本或范围不一致，请重新核验文件版本");
                long declared=connection.getContentLengthLong();if(declared>=0&&declared!=end-start+1-saved)throw new IOException("导航分块声明长度不符");
                try(InputStream in=connection.getInputStream();RandomAccessFile out=new RandomAccessFile(file,"rw")){
                    out.seek(saved);byte[] buffer=new byte[32768];long checkpoint=saved;
                    try{
                        while(true){check(check);if(System.nanoTime()>deadline)throw new SocketTimeoutException("导航分块超时");int n=in.read(buffer);if(n<0)break;check(check);if(out.length()+n>end-start+1)throw new IOException("导航分块超过预期长度");out.write(buffer,0,n);transferred.addAndGet(n);progress.set(part,out.length());if(out.length()-checkpoint>=65536){checkpoint(file,out);checkpoint=out.length();}}
                        if(out.length()!=end-start+1)throw new EOFException("导航分块不完整");
                    }finally{checkpoint(file,out);}
                }
                return;
            }catch(IOException failure){check(check);if(failure instanceof VersionChanged)throw failure;if(attempt==1)throw new IOException("导航第"+(part+1)+"块下载中断，已保存断点："+failure.getClass().getSimpleName(),failure);}
            finally{abort.cancel(false);active.remove(connection);connection.disconnect();}
        }
    }
}
