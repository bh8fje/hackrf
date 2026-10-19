package local.hackrf.receiver;

import android.app.*;
import android.os.Bundle;
import org.json.*;
import java.time.Instant;

public final class ReceiverScenarioCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle out=new Bundle();int code=Activity.RESULT_CANCELED;
        try{
            JSONObject valid=new JSONObject().put("id","test").put("targetLatitude",30).put("targetLongitude",104).put("durationSeconds",90)
                .put("startUTC",Instant.now().plusSeconds(30).toString()).put("constellationId",5).put("svids",new JSONArray("[2,9,35,63]"))
                .put("points",new JSONArray("[[0,10,20],[90,11,21]]"));
            ReceiverScenario.validate(valid);
            long now=System.currentTimeMillis();valid.put("phase","armed");ReceiverScenario.checkReplacement(valid,null,now);
            JSONObject prepared=new JSONObject(valid.toString()).put("phase","prepared").put("id","another-preview");
            boolean guarded=false;try{ReceiverScenario.checkReplacement(prepared,valid,now);}catch(IllegalArgumentException expected){guarded=true;}
            if(!guarded)throw new AssertionError("Active scene replaced by preview");
            JSONObject ended=new JSONObject(valid.toString()).put("phase","completed");
            ReceiverScenario.checkReplacement(ended,valid,now+180000);
            guarded=false;try{ReceiverScenario.checkReplacement(valid,null,now+180000);}catch(IllegalArgumentException expected){guarded=true;}
            if(!guarded)throw new AssertionError("Stale scene armed");
            for(String key:new String[]{"durationSeconds","points","svids","startUTC"}){
                JSONObject bad=new JSONObject(valid.toString());
                if(key.equals("durationSeconds"))bad.put(key,90.5);
                if(key.equals("points"))bad.put(key,new JSONArray("[[0,10,20],[89,11,21]]"));
                if(key.equals("svids"))bad.put(key,new JSONArray("[2,9,35,64]"));
                if(key.equals("startUTC"))bad.put(key,"invalid");
                boolean rejected=false;try{ReceiverScenario.validate(bad);}catch(Exception expected){rejected=true;}
                if(!rejected)throw new AssertionError("Accepted invalid "+key);
            }
            out.putString("stream","PASS: receiver scene fields, complete trajectory, fractional duration, invalid SVID/time rejection; no RF");code=Activity.RESULT_OK;
        }catch(Throwable failure){out.putString("stream","FAIL: "+failure);}
        finish(code,out);
    }
}
