package local.hackrf.receiver;

import org.json.*;
import java.time.OffsetDateTime;

final class ReceiverScenario {
    static void checkReplacement(JSONObject next,JSONObject current,long now)throws Exception {
        long start=validate(next);String phase=next.optString("phase");
        boolean same=current!=null&&next.getString("id").equals(current.optString("id"))&&start==validate(current);
        boolean terminal=phase.equals("completed")||phase.equals("failed")||phase.equals("stopped");
        if(start>now+900000||(start<now-2000&&!(same&&terminal)))throw new IllegalArgumentException("仅同步当前准备的场景");
        if(current!=null&&phase.equals("prepared")&&current.optString("phase").equals("armed")&&validate(current)+current.getInt("durationSeconds")*1000L>now)
            throw new IllegalArgumentException("已有计划发送场景，不能用预览替换");
    }
    static long validate(JSONObject next)throws Exception {
        String id=next.getString("id");
        double lat=next.getDouble("targetLatitude"),lon=next.getDouble("targetLongitude");
        int duration=next.getInt("durationSeconds");JSONArray points=next.getJSONArray("points");
        if(id.isEmpty()||id.length()>128||!Geometry.validTarget(lat,lon)||duration<60||duration>600||next.getDouble("durationSeconds")!=duration||points.length()<2||points.length()>602)throw new IllegalArgumentException("场景参数无效");
        double previous=-1;
        for(int i=0;i<points.length();i++){
            JSONArray p=points.getJSONArray(i);double t=p.getDouble(0),east=p.getDouble(1),north=p.getDouble(2);
            if(p.length()!=3||!Double.isFinite(t)||!Double.isFinite(east)||!Double.isFinite(north)||t<=previous)throw new IllegalArgumentException("场景轨迹无效");
            previous=t;
        }
        if(points.getJSONArray(0).getDouble(0)!=0||previous<duration||previous>duration+.1)throw new IllegalArgumentException("场景轨迹未覆盖完整时长");
        int constellation=next.optInt("constellationId",1),limit=Geometry.svidLimit(constellation);
        if(constellation!=1&&!next.has("svids"))throw new IllegalArgumentException("非 GPS 场景缺少卫星清单");
        JSONArray prns=next.has("svids")?next.getJSONArray("svids"):next.getJSONArray("gpsPRNs");java.util.HashSet<Integer> unique=new java.util.HashSet<>();
        for(int i=0;i<prns.length();i++){int prn=prns.getInt(i);if(prn<1||prn>limit||prns.getDouble(i)!=prn)throw new IllegalArgumentException("场景 SVID 无效");unique.add(prn);}
        if(unique.size()<4||prns.length()>limit)throw new IllegalArgumentException("场景卫星数量无效");
        return OffsetDateTime.parse(next.getString("startUTC")).toInstant().toEpochMilli();
    }
}
