package local.hackrf.controller;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.Arrays;

/** Seed isolated viewed-tile cache, force network failure, never contact a server or USB. */
public class MapHistoryCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();File base=new File(getTargetContext().getCacheDir(),"map-history-test-"+System.nanoTime());try{
        Context context=new ContextWrapper(getTargetContext()){@Override public File getFilesDir(){return base;}};
        File folder=new File(base,"map-history");folder.mkdirs();File image=new File(folder,"3-0-0.png"),expiry=new File(folder,"3-0-0.png.expires");
        byte[] png=android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a9xkAAAAASUVORK5CYII=",0);Files.write(image.toPath(),png);
        Files.write(expiry.toPath(),Long.toString(System.currentTimeMillis()+60000).getBytes());
        URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler(){protected URLConnection openConnection(URL url)throws IOException{throw new IOException("Offline test");}}:null);
        if(!Arrays.equals(png,TileCache.load(context,Uri.parse("walklab-tile://osm/3/0/0.png"))))throw new AssertionError("Fresh history cache miss");
        Files.write(expiry.toPath(),"0".getBytes());if(!Arrays.equals(png,TileCache.load(context,Uri.parse("walklab-tile://osm/3/0/0.png"))))throw new AssertionError("Network failure did not fall back to history");
        try{TileCache.load(context,Uri.parse("walklab-tile://osm/3/1/1.png"));throw new AssertionError("Missing history fabricated image");}catch(IOException expected){}
        if(NativeScene.sceneSatellites("05  132.8  38.4  22193165.8   4.1\n").length()!=1)throw new AssertionError("Generated satellites not parsed");
        result.putString("stream","PASS: fresh and stale viewed-tile cache survives forced offline; absent tile rejected; generated satellite geometry; no network/USB/RF");finish(Activity.RESULT_OK,result);
    }catch(Throwable failure){result.putString("stream","FAIL: "+failure);finish(Activity.RESULT_CANCELED,result);}finally{File[] files=new File(base,"map-history").listFiles();if(files!=null)for(File file:files)file.delete();new File(base,"map-history").delete();base.delete();}}
}
