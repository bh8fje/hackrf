package local.hackrf.controller;

import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.concurrent.*;

/** Bounded transport retries; navigation validation stays with the caller. */
final class NavigationHttp {
    static URL gaPublicFile(String name) throws IOException {
        if(!name.matches("[A-Z0-9]{9}_R_[0-9]{11}_01H_MN\\.rnx\\.gz"))throw new IOException("无效的 GA 小时文件名");
        int year=Integer.parseInt(name.substring(12,16)),day=Integer.parseInt(name.substring(16,19)),hour=Integer.parseInt(name.substring(19,21));
        try{java.time.LocalDate.ofYearDay(year,day);if(year<1980||hour>23)throw new IllegalArgumentException();}catch(RuntimeException invalid){throw new IOException("无效的 GA 小时文件日期",invalid);}
        return new URL(String.format(Locale.US,"https://ga-gnss-data-rinex-v1.s3.dualstack.ap-southeast-2.amazonaws.com/public/hourly/%04d/%03d/%02d/%s",year,day,hour,name));
    }
    static final int LIMIT=10000000;
    private static final int ATTEMPT_SECONDS=120;
    static final class Result {
        final byte[] bytes;final String modified;final long elapsedMillis;final int attempts,parts;final long resumedBytes,transferredBytes;
        Result(byte[] bytes,String modified,long elapsedMillis,int attempts){this(bytes,modified,elapsedMillis,attempts,1,0,bytes.length);}
        Result(byte[] bytes,String modified,long elapsedMillis,int attempts,int parts,long resumedBytes,long transferredBytes){this.bytes=bytes;this.modified=modified;this.elapsedMillis=elapsedMillis;this.attempts=attempts;this.parts=parts;this.resumedBytes=resumedBytes;this.transferredBytes=transferredBytes;}
    }
    private static final class Rejected extends IOException {Rejected(String message){super(message);}}
    static String progress(String file,long bytes,long total,long millis) {
        return progress(file,bytes,total,millis,bytes);
    }
    static String progress(String file,long bytes,long total,long millis,long transferred){return String.format(Locale.US,"正在下载 %s · %.2f/%s MB · %.2f MB/s · %.1f秒",file,bytes/1e6,total>=0?String.format(Locale.US,"%.2f",total/1e6):"未知",transferred/1000.0/Math.max(1,millis),millis/1000.0);}
    static boolean strongEtag(String tag){return tag!=null&&tag.length()<=256&&tag.matches("\"[^\"\\r\\n]*\"");}
    private static int timeout(long deadline) throws SocketTimeoutException {
        long remaining=(deadline-System.nanoTime())/1000000;
        if(remaining<=0)throw new SocketTimeoutException("导航下载超过"+ATTEMPT_SECONDS+"秒总时限");
        return (int)Math.min(15000,remaining);
    }
    static Result fetch(URL url,PhoneMotion.Check check) throws IOException {
        return fetch(url,check,message->{});
    }
    static Result fetch(URL url,PhoneMotion.Check check,Consumer<String> status) throws IOException {
        long began=System.nanoTime();String file=url.getPath().substring(url.getPath().lastIndexOf('/')+1);
        ByteArrayOutputStream output=new ByteArrayOutputStream();String etag=null;long total=-1;
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"navigation-deadline");t.setDaemon(true);return t;});
        try {for(int attempt=0;attempt<2;attempt++) {
            check.run();HttpURLConnection connection=null;ScheduledFuture<?> abort=null;
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(ATTEMPT_SECONDS);
                status.accept("正在下载 "+file+" · "+url.getHost()+" · 第"+(attempt+1)+"/2次 · 连接服务器…");
                connection=(HttpURLConnection)url.openConnection();
                final HttpURLConnection active=connection;
                abort=timer.schedule(active::disconnect,ATTEMPT_SECONDS,TimeUnit.SECONDS);
                if(attempt>0)connection.setRequestProperty("Connection","close");
                connection.setConnectTimeout(timeout(deadline));connection.setReadTimeout(timeout(deadline));connection.setUseCaches(false);
                long resume=etag==null?0:output.size();
                if(resume>0){connection.setRequestProperty("Range","bytes="+resume+"-");connection.setRequestProperty("If-Range",etag);status.accept("正在续传 "+file+" · 已收到 "+String.format(Locale.US,"%.2f MB",resume/1e6));}
                int code=connection.getResponseCode();long length=connection.getContentLengthLong();
                if(code==206 && resume>0){
                    java.util.regex.Matcher range=java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matcher(String.valueOf(connection.getHeaderField("Content-Range")));
                    if(!range.matches() || !etag.equals(connection.getHeaderField("ETag")))throw new Rejected("导航续传文件版本或范围不一致");
                    long begin,end,size;
                    try{begin=Long.parseLong(range.group(1));end=Long.parseLong(range.group(2));size=Long.parseLong(range.group(3));}catch(NumberFormatException invalid){throw new Rejected("导航续传范围无效");}
                    if(begin!=resume || size<=resume || size>LIMIT || end!=size-1 || (total>=0 && size!=total) || (length>=0 && length!=size-resume))throw new Rejected("导航续传范围或长度不一致");
                    total=size;
                }else if(code==200){
                    output.reset();total=length;String next=connection.getHeaderField("ETag");
                    // Only a strong validator permits appending bytes from another connection.
                    etag=strongEtag(next)?next:null;
                }else throw new Rejected("导航下载HTTP "+code);
                if(total>LIMIT)throw new Rejected("导航下载超出大小限制");
                byte[] buffer=new byte[32768];long last=0;
                try(InputStream stream=connection.getInputStream()) {
                    while(true) {
                        check.run();timeout(deadline);int n=stream.read(buffer);check.run();timeout(deadline);
                        if(n==-1)break;
                        if(output.size()+n>LIMIT)throw new Rejected("导航下载超出大小限制");
                        output.write(buffer,0,n);
                        if(total>=0 && output.size()>total)throw new Rejected("导航文件超过声明长度");
                        long now=System.nanoTime();if(now-last>=1000000000L){status.accept(progress(file,output.size(),total,(now-began)/1000000));last=now;}
                    }
                }
                if(total>=0 && output.size()!=total)throw new EOFException("导航文件下载不完整");
                long elapsed=(System.nanoTime()-began)/1000000;
                status.accept(progress(file,output.size(),total,elapsed).replace("正在下载 ","下载完成 "));
                return new Result(output.toByteArray(),connection.getHeaderField("Last-Modified"),elapsed,attempt+1);
            }catch(Rejected failure){throw failure;}
            catch(IOException failure) {
                check.run();
                status.accept("下载中断 "+file+" · "+((System.nanoTime()-began)/1000000000L)+"秒 · "+failure.getClass().getSimpleName()+ (attempt==0?"；正在重试":"；重试失败"));
                if(attempt==1){
                    String endpoint=new URL(url.getProtocol(),url.getHost(),url.getPort(),url.getPath()).toString();
                    throw new IOException("导航下载连续两次中断："+endpoint+"；"+String.valueOf(failure.getMessage()).replace(url.toString(),endpoint),failure);
                }
            }finally{if(abort!=null)abort.cancel(false);if(connection!=null)connection.disconnect();}
        }}finally{timer.shutdownNow();}
        throw new AssertionError();
    }
}
