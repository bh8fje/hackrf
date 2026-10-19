package local.hackrf.controller;
import java.io.*;
import java.net.*;
import java.util.*;
public class NavigationHttpTest {
    static class Fake extends HttpURLConnection {
        byte[] body="navigation".getBytes();int code=200;long length=body.length;IOException failure;boolean closed;Map<String,String> headers=new HashMap<>();
        Fake()throws Exception{super(new URL("https://nav.invalid/file"));}
        public void connect(){}public boolean usingProxy(){return false;}public void disconnect(){closed=true;}
        public int getResponseCode()throws IOException{if(failure!=null)throw failure;return code;}
        public long getContentLengthLong(){return length;}
        public String getHeaderField(String name){return headers.get(name);}
        public InputStream getInputStream(){return new ByteArrayInputStream(body);}
    }
    static class Source extends URLStreamHandler {
        List<Fake> replies;int opens;
        Source(Fake...replies){this.replies=Arrays.asList(replies);}
        protected URLConnection openConnection(URL u){return replies.get(opens++);}
        URL url()throws Exception{return new URL(null,"https://nav.invalid/file",this);}
    }
    public static void main(String[]args)throws Exception{
        assert NavigationHttp.gaPublicFile("PERT00AUS_R_20262910200_01H_MN.rnx.gz").toString().equals("https://ga-gnss-data-rinex-v1.s3.dualstack.ap-southeast-2.amazonaws.com/public/hourly/2026/291/02/PERT00AUS_R_20262910200_01H_MN.rnx.gz");
        for(String name:new String[]{"../file","PERT00AUS_R_20263670000_01H_MN.rnx.gz","PERT00AUS_R_20262912400_01H_MN.rnx.gz","PERT00AUS_R_19792910200_01H_MN.rnx.gz"})try{NavigationHttp.gaPublicFile(name);throw new AssertionError("Invalid GA file accepted");}catch(IOException expected){}
        Fake broken=new Fake(),ok=new Fake();broken.failure=new SocketException("connection abort");Source s=new Source(broken,ok);
        assert Arrays.equals(NavigationHttp.fetch(s.url(),()->{}).bytes,ok.body);assert s.opens==2&&broken.closed&&ok.closed;assert ok.getReadTimeout()>0&&ok.getConnectTimeout()>0;
        Fake missing=new Fake();missing.code=404;s=new Source(missing);try{NavigationHttp.fetch(s.url(),()->{});throw new AssertionError();}catch(IOException expected){}assert s.opens==1&&missing.closed;
        Fake large=new Fake();large.length=NavigationHttp.LIMIT+1;s=new Source(large);try{NavigationHttp.fetch(s.url(),()->{});throw new AssertionError();}catch(IOException expected){}assert s.opens==1;
        Fake truncated=new Fake();truncated.length=100;s=new Source(truncated,new Fake());assert NavigationHttp.fetch(s.url(),()->{}).bytes.length==10&&s.opens==2;
        Fake fail1=new Fake(),fail2=new Fake();fail1.failure=new SocketTimeoutException();fail2.failure=new SocketException();s=new Source(fail1,fail2);
        try{NavigationHttp.fetch(s.url(),()->{});throw new AssertionError();}catch(IOException expected){assert expected.getMessage().contains("两次");}assert s.opens==2&&fail1.closed&&fail2.closed;
        URL signed=new URL(null,"https://nav.invalid/file?signature=temporary",new Source(fail1,fail2));
        fail2.failure=new SocketException("failed "+signed);
        try{NavigationHttp.fetch(signed,()->{});throw new AssertionError();}catch(IOException expected){assert !expected.getMessage().contains("signature");assert expected.getMessage().contains("https://nav.invalid/file");}
        s=new Source(new Fake());try{NavigationHttp.fetch(s.url(),()->{throw new IOException("cancelled");});throw new AssertionError();}catch(IOException expected){}assert s.opens==0;
        Fake unannounced=new Fake();unannounced.length=-1;unannounced.body=new byte[NavigationHttp.LIMIT+1];s=new Source(unannounced);try{NavigationHttp.fetch(s.url(),()->{});throw new AssertionError();}catch(IOException expected){}assert s.opens==1;
        Fake partial=partial(),suffix=suffix();s=new Source(partial,suffix);
        assert Arrays.equals(NavigationHttp.fetch(s.url(),()->{}).bytes,"navigation".getBytes());
        assert "bytes=7-".equals(suffix.getRequestProperty("Range"))&&"\"v1\"".equals(suffix.getRequestProperty("If-Range"));assert partial.closed&&suffix.closed;
        Fake replacement=new Fake();replacement.body="new-file".getBytes();replacement.length=replacement.body.length;s=new Source(partial(),replacement);
        assert Arrays.equals(NavigationHttp.fetch(s.url(),()->{}).bytes,replacement.body):"A full response must replace partial old bytes";
        Fake weak=partial();weak.headers.put("ETag","W/\"v1\"");replacement=new Fake();s=new Source(weak,replacement);
        assert NavigationHttp.fetch(s.url(),()->{}).bytes.length==10&&replacement.getRequestProperty("Range")==null;
        for(String range:new String[]{"bytes 6-9/10","bytes 7-10/11","bytes 7-9/10000001","bytes 7-99999999999999999999999/10","bad"}){
            Fake bad=suffix();bad.headers.put("Content-Range",range);s=new Source(partial(),bad);
            try{NavigationHttp.fetch(s.url(),()->{});throw new AssertionError("Invalid range accepted: "+range);}catch(IOException expected){}assert s.opens==2&&bad.closed;
        }
        Fake changed=suffix();changed.headers.put("ETag","\"v2\"");s=new Source(partial(),changed);
        try{NavigationHttp.fetch(s.url(),()->{});throw new AssertionError("Mixed file versions accepted");}catch(IOException expected){}
        s=new Source(suffix());try{NavigationHttp.fetch(s.url(),()->{});throw new AssertionError("Unrequested partial response accepted");}catch(IOException expected){}
        assert NavigationHttp.progress("model.gz",2000000,4000000,10000).contains("2.00/4.00 MB · 0.20 MB/s · 10.0秒");
        assert NavigationHttp.progress("model.gz",1,-1,0).contains("未知");
        System.out.println("PASS bounded HTTP retry/resume, strong ETag/range/version validation, replacement, size rejection, cancellation and cleanup");
    }
    static Fake partial()throws Exception{Fake f=new Fake();f.body="navigat".getBytes();f.length=10;f.headers.put("ETag","\"v1\"");return f;}
    static Fake suffix()throws Exception{Fake f=new Fake();f.body="ion".getBytes();f.length=3;f.code=206;f.headers.put("ETag","\"v1\"");f.headers.put("Content-Range","bytes 7-9/10");return f;}
}
