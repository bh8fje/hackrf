package local.hackrf.controller;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class NavigationChunksTest {
    static class Server extends URLStreamHandler {
        byte[] data=new byte[655367];String etag="\"v1\"";boolean badRange,acceptRanges=true;int headCode=200,version=1;AtomicInteger updates=new AtomicInteger(),heads=new AtomicInteger();
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),failures=new AtomicInteger(),reads=new AtomicInteger();
        List<String> ranges=Collections.synchronizedList(new ArrayList<>());CountDownLatch barrier;
        Server(){for(int i=0;i<data.length;i++)data[i]=(byte)(i*31);}
        URL url()throws Exception{return new URL(null,"https://navigation.invalid/current.gz",this);}
        protected URLConnection openConnection(URL u){return new Reply(u,this);}
    }
    static class Reply extends HttpURLConnection {
        final Server s;long first,last;boolean range;
        Reply(URL u,Server s){super(u);this.s=s;}
        public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){}
        public int getResponseCode(){if("HEAD".equals(getRequestMethod())){s.heads.incrementAndGet();return s.headCode;}String request=getRequestProperty("Range");range=request!=null;first=0;last=s.data.length-1;if(range){String[] p=request.substring(6).split("-");first=Long.parseLong(p[0]);last=Long.parseLong(p[1]);s.ranges.add(request);
            synchronized(s){if(last>first&&s.updates.get()>0&&s.etag.equals(getRequestProperty("If-Range"))){s.updates.decrementAndGet();s.etag="\"v"+(++s.version)+"\"";s.data[0]=(byte)s.version;}}}return range?206:200;}
        public long getContentLengthLong(){return "HEAD".equals(getRequestMethod())?s.data.length:last-first+1;}
        public String getHeaderField(String name){if(name.equals("ETag"))return s.etag;if(name.equals("Accept-Ranges"))return s.acceptRanges?"bytes":null;if(name.equals("Content-Range"))return "bytes "+(s.badRange?first+1:first)+"-"+last+"/"+s.data.length;return null;}
        public InputStream getInputStream()throws IOException{
            if(range&&last>first&&!s.etag.equals(getRequestProperty("If-Range")))throw new IOException("Missing version guard");
            int now=s.active.incrementAndGet();s.peak.accumulateAndGet(now,Math::max);
            if(s.barrier!=null){s.barrier.countDown();try{if(!s.barrier.await(2,TimeUnit.SECONDS))throw new IOException("Five workers were not concurrent");}catch(InterruptedException e){throw new InterruptedIOException();}}
            final boolean broken=range&&first<s.data.length/5&&s.failures.getAndUpdate(n->Math.max(0,n-1))>0;
            return new InputStream(){long at=first,sent;boolean closed;
                public int read()throws IOException{byte[] b=new byte[1];int n=read(b,0,1);return n<0?-1:b[0]&255;}
                public int read(byte[] b,int off,int count)throws IOException{
                    if(broken&&sent>=32768){try{Thread.sleep(100);}catch(InterruptedException e){throw new InterruptedIOException();}throw new SocketException("Injected disconnect");}
                    if(at>last)return -1;int n=(int)Math.min(count,last-at+1);System.arraycopy(s.data,(int)at,b,off,n);at+=n;sent+=n;s.reads.incrementAndGet();return n;
                }
                public void close(){if(!closed){s.active.decrementAndGet();closed=true;}}
            };
        }
    }
    public static void main(String[] args)throws Exception{
        File root=Files.createTempDirectory("navigation-five-parts-").toFile();
        try{
            Server large=new Server();large.data=Arrays.copyOf(large.data,1_300_001);large.barrier=new CountDownLatch(3);
            File largeRoot=new File(root,"large");
            NavigationHttp.Result big=NavigationChunks.fetch(large.url(),largeRoot,()->{},m->{});
            assert Arrays.equals(big.bytes,large.data)&&big.parts==3&&large.peak.get()==3&&large.ranges.size()==3;
            large.barrier=null;large.ranges.clear();big=NavigationChunks.fetch(large.url(),largeRoot,()->{},m->{});
            assert large.ranges.isEmpty()&&big.transferredBytes==0;
            File largeFolder=Arrays.stream(largeRoot.listFiles()).filter(File::isDirectory).findFirst().get();
            Files.delete(new File(largeFolder,"complete.bin").toPath());Files.write(new File(largeFolder,"1.part").toPath(),new byte[1]);
            big=NavigationChunks.fetch(large.url(),largeRoot,()->{},m->{});
            assert Arrays.equals(big.bytes,large.data)&&large.ranges.size()==1&&large.ranges.get(0).startsWith("bytes="+(large.data.length/3)+"-");
            Server boundary=new Server();boundary.data=new byte[1_000_000];
            assert NavigationChunks.fetch(boundary.url(),new File(root,"boundary"),()->{},m->{}).parts==1;
            Server legacy=new Server();legacy.data=new byte[1_100_000];File legacyRoot=new File(root,"legacy");
            String key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(legacy.url().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            File legacyFolder=new File(legacyRoot,key);assert legacyFolder.mkdirs();
            Properties old=new Properties();old.setProperty("length",Integer.toString(legacy.data.length));old.setProperty("etag",legacy.etag);
            try(OutputStream out=new FileOutputStream(new File(legacyFolder,"version.meta"))){old.store(out,null);}
            byte[] prefix=Arrays.copyOf(legacy.data,65536);Files.write(new File(legacyFolder,"0.part").toPath(),prefix);
            Properties point=new Properties();point.setProperty("bytes","65536");point.setProperty("sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(prefix)));
            try(OutputStream out=new FileOutputStream(new File(legacyFolder,"0.part.meta"))){point.store(out,null);}
            NavigationHttp.Result continued=NavigationChunks.fetch(legacy.url(),legacyRoot,()->{},m->{});
            assert Arrays.equals(continued.bytes,legacy.data)&&continued.resumedBytes==65536&&legacy.ranges.equals(Arrays.asList("bytes=65536-1099999"));
            Server s=new Server();s.barrier=new CountDownLatch(1);
            NavigationHttp.Result r=NavigationChunks.fetch(s.url(),root,()->{},m->{});
            assert Arrays.equals(r.bytes,s.data)&&r.parts==1&&r.transferredBytes==s.data.length&&s.peak.get()==1;
            assert s.ranges.size()==1&&s.active.get()==0;
            s.ranges.clear();s.barrier=null;r=NavigationChunks.fetch(s.url(),root,()->{},m->{});
            assert s.ranges.isEmpty()&&r.transferredBytes==0&&r.resumedBytes==s.data.length;
            s.etag="\"v2\"";s.data[0]++;s.ranges.clear();r=NavigationChunks.fetch(s.url(),root,()->{},m->{});
            assert s.ranges.size()==1&&Arrays.equals(r.bytes,s.data);
            Path folder=Arrays.stream(root.listFiles()).filter(f->f.isDirectory()&&!f.getName().equals("large")&&!f.getName().equals("boundary")&&!f.getName().equals("legacy")).findFirst().get().toPath();
            Files.write(folder.resolve("complete.bin"),new byte[s.data.length]);Files.write(folder.resolve("0.part"),new byte[s.data.length/5]);s.ranges.clear();
            r=NavigationChunks.fetch(s.url(),root,()->{},m->{});assert Arrays.equals(r.bytes,s.data)&&s.ranges.size()==1:"Only the damaged part should be fetched";
            s.etag="\"v3\"";s.badRange=true;try{NavigationChunks.fetch(s.url(),root,()->{},m->{});throw new AssertionError("Bad range accepted");}catch(IOException expected){}assert s.active.get()==0;
            Server interrupted=new Server();interrupted.failures.set(2);File resume=new File(root,"resume");
            try{NavigationChunks.fetch(interrupted.url(),resume,()->{},m->{});throw new AssertionError("Disconnect accepted");}catch(IOException expected){}
            interrupted.ranges.clear();r=NavigationChunks.fetch(interrupted.url(),resume,()->{},m->{});
            assert Arrays.equals(r.bytes,interrupted.data)&&r.resumedBytes>0&&r.transferredBytes<interrupted.data.length;
            assert interrupted.ranges.size()==1&&interrupted.ranges.get(0).startsWith("bytes=65536-")&&interrupted.active.get()==0;
            Server cancelled=new Server();File cancel=new File(root,"cancel");
            try{NavigationChunks.fetch(cancelled.url(),cancel,()->{if(cancelled.reads.get()>=6)throw new IOException("cancelled");},m->{});throw new AssertionError("Cancellation ignored");}catch(IOException expected){}
            assert cancelled.active.get()==0;r=NavigationChunks.fetch(cancelled.url(),cancel,()->{},m->{});assert Arrays.equals(r.bytes,cancelled.data)&&r.resumedBytes>0;
            Server fallback=new Server();fallback.acceptRanges=false;r=NavigationChunks.fetch(fallback.url(),new File(root,"fallback"),()->{},m->{});assert Arrays.equals(r.bytes,fallback.data)&&r.parts==1;
            Server getOnly=new Server();getOnly.headCode=403;r=NavigationChunks.fetch(getOnly.url(),new File(root,"get-only"),()->{},m->{});assert Arrays.equals(r.bytes,getOnly.data)&&r.parts==1;
            Server changed=new Server();changed.updates.set(1);
            NavigationHttp.Result refreshed=NavigationChunks.fetch(changed.url(),new File(root,"changed"),()->{},m->{});
            assert Arrays.equals(refreshed.bytes,changed.data)&&changed.heads.get()==2;
            assert refreshed.transferredBytes>=changed.data.length&&changed.active.get()==0;
            Server unstable=new Server();unstable.updates.set(2);
            try{NavigationChunks.fetch(unstable.url(),new File(root,"unstable"),()->{},m->{});throw new AssertionError("Unlimited version changes accepted");}catch(IOException expected){}
            assert unstable.heads.get()==2&&unstable.active.get()==0;
            System.out.println("PASS: size-aware three/single connections, durable resume, no repeated completed download, version/corruption/range rejection, cancellation and fallback");
        }finally{try(java.util.stream.Stream<Path> paths=Files.walk(root.toPath())){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(p);}}
    }
}
