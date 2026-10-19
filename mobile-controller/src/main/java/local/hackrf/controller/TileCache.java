package local.hackrf.controller;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Only tiles requested by the visible map are cached; no prefetch. */
final class TileCache {
    static byte[] load(Context context,Uri uri) throws IOException {
        String path=uri.getPath();if(!"osm".equals(uri.getHost()) || path==null || !path.matches("/[0-9]{1,2}/[0-9]{1,6}/[0-9]{1,6}\\.png"))throw new IOException("Invalid map tile");
        String[] parts=path.substring(1).replace(".png","").split("/");int z=Integer.parseInt(parts[0]),x=Integer.parseInt(parts[1]),y=Integer.parseInt(parts[2]);
        if(z<3 || z>18 || x>=(1<<z) || y>=(1<<z))throw new IOException("Invalid tile coordinates");
        File root=new File(context.getFilesDir(),"map-history"),file=new File(root,z+"-"+x+"-"+y+".png"),expiry=new File(root,file.getName()+".expires");root.mkdirs();
        byte[] cached=file.isFile()?Files.readAllBytes(file.toPath()):null;long expires=0;
        try{expires=Long.parseLong(new String(Files.readAllBytes(expiry.toPath()),java.nio.charset.StandardCharsets.US_ASCII));}catch(Exception ignored){}
        if(cached!=null && System.currentTimeMillis()<expires)return cached;
        HttpURLConnection request=null;
        try{
            request=(HttpURLConnection)new URL("https://tile.openstreetmap.org"+path).openConnection();request.setConnectTimeout(8000);request.setReadTimeout(8000);request.setRequestProperty("User-Agent","WalkLab/0.6.12 Android");
            if(file.isFile())request.setIfModifiedSince(file.lastModified());
            int code=request.getResponseCode();long ttl=604800;
            String control=request.getHeaderField("Cache-Control");java.util.regex.Matcher age=java.util.regex.Pattern.compile("max-age=(\\d+)").matcher(control==null?"":control);if(age.find())ttl=Math.max(ttl,Long.parseLong(age.group(1)));
            ttl=Math.max(ttl,Math.max(0,(request.getExpiration()-System.currentTimeMillis())/1000));
            byte[] data;if(code==304 && cached!=null)data=cached;else{
                if(code!=200)throw new IOException("Map HTTP "+code);
                try(InputStream in=request.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>1000000)throw new IOException("Map tile too large");}data=out.toByteArray();}
                if(data.length<8 || data[0]!=(byte)137 || data[1]!=80 || data[2]!=78 || data[3]!=71)throw new IOException("Invalid map image");
            }
            try{File temporary=File.createTempFile("tile-",".tmp",root);Files.write(temporary.toPath(),data);Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);Files.write(expiry.toPath(),Long.toString(System.currentTimeMillis()+Math.min(ttl,31536000)*1000).getBytes(java.nio.charset.StandardCharsets.US_ASCII));trim(root);}catch(IOException disk){android.util.Log.w("WalkLab","Map history cache unavailable",disk);}
            return data;
        }catch(IOException failure){if(cached!=null)return cached;throw failure;}finally{if(request!=null)request.disconnect();}
    }
    private static void trim(File root){File[] files=root.listFiles((dir,name)->name.endsWith(".png"));if(files==null)return;Arrays.sort(files,Comparator.comparingLong(File::lastModified));long total=0;for(File f:files)total+=f.length();for(File f:files){if(total<=32L*1024*1024)break;long size=f.length();if(f.delete()){total-=size;new File(root,f.getName()+".expires").delete();}}}
}
