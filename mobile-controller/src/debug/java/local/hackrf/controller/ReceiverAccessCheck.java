package local.hackrf.controller;

import android.app.*;
import android.net.Uri;
import android.os.Bundle;
import java.io.InputStream;

/** The receiver log export must reject an ordinary application UID. */
public final class ReceiverAccessCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;
        try{
            try(InputStream input=getTargetContext().getContentResolver().openInputStream(Uri.parse("content://local.hackrf.receiver.public.logs/tail"))){
                throw new AssertionError("Receiver log readable by another app UID");
            }catch(SecurityException expected){
                if(!expected.toString().contains("Authorized ADB access required"))throw expected;
            }
            result.putString("stream","PASS: unrelated app UID denied receiver logs; authorized ADB is required");code=Activity.RESULT_OK;
        }catch(Throwable failure){result.putString("stream","FAIL: "+failure);}
        finish(code,result);
    }
}
