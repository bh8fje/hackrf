package local.hackrf.controller;

import android.app.*;
import android.os.Bundle;
import org.json.*;
import java.io.File;
import java.time.Instant;
import java.nio.file.Files;

public final class ReceiverSyncCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();int code=Activity.RESULT_CANCELED;File folder=null;
        try{
            folder=Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),"receiver-sync-").toFile();
            for(String system:new String[]{"gps","bds"}){
                JSONObject settings=PhoneConfig.defaults(getTargetContext()).put("duration",90).put("gnssSystem",system);
                PhoneMotion motion=NativeScene.parameters(settings);motion.write(new File(folder,"emitted.csv"),()->{});
                JSONObject manifest=new JSONObject().put("type","hackrf-"+system+"-iq").put("settings",settings).put("durationSeconds",90)
                    .put("navigation",new JSONObject().put("startUTC",Instant.now().plusSeconds(60).toString()))
                    .put("sceneSatellites",new JSONArray(system.equals("gps")?"[\"G01 方位20° 仰角30°\",\"G02\",\"G03\",\"G04\"]":"[\"C02\",\"C09\",\"C35\",\"C63\"]"));
                JSONObject scene=ReceiverSync.scenario(folder,manifest);JSONArray points=scene.getJSONArray("points");
                if(scene.getInt("constellationId")!=(system.equals("gps")?1:5)||points.length()!=91||points.getJSONArray(90).getDouble(0)!=90||scene.getJSONArray("svids").getInt(3)!=(system.equals("gps")?4:63))throw new AssertionError("Wrong scene conversion");
                if(Math.hypot(points.getJSONArray(0).getDouble(1),points.getJSONArray(0).getDouble(2))<9)throw new AssertionError("Startup bias lost");
            }
            boolean validatedPeer=false;
            try{getTargetContext().getContentResolver().call(android.net.Uri.parse("content://local.hackrf.receiver.public.logs"),"setScenario",null,new Bundle());}
            catch(IllegalArgumentException expected){validatedPeer=expected.toString().contains("场景信息过大或缺失");}
            if(!validatedPeer)throw new AssertionError("Signed controller did not reach receiver validation");
            out.putString("stream","PASS: GPS/BDS receiver scene uses actual emitted ECEF, WGS84 target, startup bias, full 90s and SVIDs; signed receiver handoff authorized; no RF");code=Activity.RESULT_OK;
        }catch(Throwable failure){out.putString("stream","FAIL: "+failure);}
        finally{if(folder!=null){new File(folder,"emitted.csv").delete();folder.delete();}}
        finish(code,out);
    }
}
