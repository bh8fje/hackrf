package local.hackrf.controller;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class ReceiverSync {
    static JSONObject scenario(File folder,JSONObject manifest)throws Exception {
        String system=ScenarioStore.system(manifest);if(!system.equals("gps")&&!system.equals("bds"))throw new IOException("不支持的接收场景");
        PhoneMotion motion=NativeScene.parameters(manifest.getJSONObject("settings"));double[] wgs=motion.wgs84(),origin=motion.origin();
        double lat=Math.toRadians(wgs[0]),lon=Math.toRadians(wgs[1]);JSONArray points=new JSONArray(),svids=new JSONArray();
        try(BufferedReader input=new BufferedReader(new InputStreamReader(new FileInputStream(new File(folder,"emitted.csv")),StandardCharsets.US_ASCII))){
            String line;int index=0;
            while((line=input.readLine())!=null){
                if(index++%10!=0)continue;
                String[] cells=line.split(",");if(cells.length!=4)throw new IOException("接收轨迹格式无效");
                double t=Double.parseDouble(cells[0]),x=Double.parseDouble(cells[1])-origin[0],y=Double.parseDouble(cells[2])-origin[1],z=Double.parseDouble(cells[3])-origin[2];
                double east=-Math.sin(lon)*x+Math.cos(lon)*y,north=-Math.sin(lat)*Math.cos(lon)*x-Math.sin(lat)*Math.sin(lon)*y+Math.cos(lat)*z;
                points.put(new JSONArray().put(t).put(east).put(north));
            }
        }
        JSONArray satellites=manifest.getJSONArray("sceneSatellites");String prefix=system.equals("bds")?"C":"G";
        for(int i=0;i<satellites.length();i++){
            String text=satellites.getString(i);if(!text.matches(prefix+"[0-9]{2}(?: .*)?"))throw new IOException("接收场景卫星清单无效");
            svids.put(Integer.parseInt(text.substring(1,3)));
        }
        JSONObject result=new JSONObject().put("id",folder.getName()).put("phase","armed").put("startUTC",manifest.getJSONObject("navigation").getString("startUTC"))
                .put("durationSeconds",manifest.getInt("durationSeconds")).put("targetLatitude",wgs[0]).put("targetLongitude",wgs[1])
                .put("constellationId",system.equals("bds")?5:1).put("svids",svids).put("points",points);
        if(manifest.has("receiverMatchingNote"))result.put("receiverMatchingNote",manifest.getString("receiverMatchingNote"));
        return result;
    }
    static void send(Context context,File folder,JSONObject manifest)throws Exception {
        JSONObject scene=scenario(folder,manifest);Bundle input=new Bundle();input.putString("json",scene.toString());
        Bundle result=context.getContentResolver().call(Uri.parse("content://local.hackrf.receiver.public.logs"),"setScenario",null,input);
        if(result==null||!result.getBoolean("accepted")||!scene.getString("id").equals(result.getString("scenarioId")))throw new IOException("接收实验室未确认当前场景");
        java.nio.file.Files.write(new File(folder,"phone-scenario.json").toPath(),scene.toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
