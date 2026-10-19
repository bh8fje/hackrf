package local.hackrf.controller;
import android.app.*;
import android.net.*;
import android.os.Bundle;
import java.net.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Read public navigation over a physical network; does not change VPN settings. */
public class DirectNetworkCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();try{
        ConnectivityManager manager=getTargetContext().getSystemService(ConnectivityManager.class);Network direct=null;
        for(Network network:manager.getAllNetworks()){NetworkCapabilities c=manager.getNetworkCapabilities(network);if(c!=null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)){direct=network;if(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))break;}}
        if(direct==null)throw new IOException("No physical internet network available");
        ZonedDateTime now=Instant.now().atZone(ZoneOffset.UTC);String url=String.format(Locale.US,"https://igs.bkg.bund.de/root_ftp/IGS/BRDC/%d/%03d/BRDC00WRD_S_%d%03d0000_01D_MN.rnx.gz",now.getYear(),now.getDayOfYear(),now.getYear(),now.getDayOfYear());
        HttpURLConnection connection=(HttpURLConnection)direct.openConnection(new URL(url));connection.setConnectTimeout(10000);connection.setReadTimeout(10000);
        try{if(connection.getResponseCode()!=200)throw new IOException("HTTP "+connection.getResponseCode());try(BufferedReader reader=new BufferedReader(new InputStreamReader(new GZIPInputStream(connection.getInputStream()),java.nio.charset.StandardCharsets.US_ASCII))){String first=reader.readLine();if(first==null || !first.contains("RINEX VERSION"))throw new IOException("Invalid navigation header");out.putString("stream","PASS: current BKG navigation accessible via physical non-VPN network; "+first);}}
        finally{connection.disconnect();}
        finish(Activity.RESULT_OK,out);
    }catch(Throwable e){out.putString("stream","FAIL: direct physical network probe: "+e);finish(Activity.RESULT_CANCELED,out);}}
}
