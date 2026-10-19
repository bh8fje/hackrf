package local.hackrf.receiver;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;
import org.json.JSONObject;

/** Read-only log tail for an explicitly authorized ADB connection. */
public final class ReceiverLogProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private void authorize(){
        int uid=Binder.getCallingUid();
        if(uid!=2000&&uid!=android.os.Process.myUid())throw new SecurityException("Authorized ADB access required");
    }
    @Override public synchronized Bundle call(String method,String arg,Bundle extras){
        if(!"setScenario".equals(method))throw new IllegalArgumentException("Unsupported receiver operation");
        int uid=Binder.getCallingUid();
        boolean allowed=uid==2000||uid==android.os.Process.myUid();
        String[] packages=getContext().getPackageManager().getPackagesForUid(uid);
        if(packages!=null&&getContext().getPackageManager().checkSignatures(uid,android.os.Process.myUid())==android.content.pm.PackageManager.SIGNATURE_MATCH)
            for(String name:packages)if(name.equals("local.hackrf.controller.public"))allowed=true;
        if(!allowed)throw new SecurityException("Authorized controller required");
        try{
            String text=arg!=null?arg:extras==null?null:extras.getString("json");
            if(text==null||text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>200000)throw new IllegalArgumentException("场景信息过大或缺失");
            JSONObject next=new JSONObject(text),current=null;
            android.util.AtomicFile file=new android.util.AtomicFile(new File(getContext().getFilesDir(),"scenario.json"));
            if(file.getBaseFile().length()<=200000)try{current=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.getBaseFile().toPath()),java.nio.charset.StandardCharsets.UTF_8));ReceiverScenario.validate(current);}
            catch(Exception invalid){current=null;}
            ReceiverScenario.checkReplacement(next,current,System.currentTimeMillis());
            FileOutputStream out=null;
            try{out=file.startWrite();out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));file.finishWrite(out);}
            catch(IOException failure){if(out!=null)file.failWrite(out);throw failure;}
            Bundle result=new Bundle();result.putBoolean("accepted",true);result.putString("scenarioId",next.getString("id"));return result;
        }catch(Exception failure){throw new IllegalArgumentException("接收场景同步失败："+failure.getMessage(),failure);}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        authorize();
        if(!"r".equals(mode)||!("/tail".equals(uri.getPath())||"/history".equals(uri.getPath())))throw new FileNotFoundException("Read-only log tail");
        File latest=null;
        File[] files=getContext().getFilesDir().listFiles();
        if(files!=null)for(File file:files)if(file.getName().matches("gnss-[0-9]{13}\\.jsonl")&&(latest==null||file.getName().compareTo(latest.getName())>0))latest=file;
        final File selected=latest;
        final int limit="/history".equals(uri.getPath())?20001000:262144;
        try{
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            new Thread(()->{
                try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){
                    out.write((new JSONObject().put("type","monitor_source").put("file",selected==null?"":selected.getName()).toString()+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if(selected!=null)try(RandomAccessFile input=new RandomAccessFile(selected,"r")){
                        long size=input.length();input.seek(Math.max(0,size-limit));
                        if(input.getFilePointer()>0)input.readLine();
                        long remaining=size-input.getFilePointer();byte[] buffer=new byte[8192];
                        while(remaining>0){int count=input.read(buffer,0,(int)Math.min(remaining,buffer.length));if(count<0)break;out.write(buffer,0,count);remaining-=count;}
                    }
                }catch(Exception failure){android.util.Log.w("ReceiverLogProvider","Log tail closed",failure);}
            },"receiver-log-tail").start();
            return pipe[0];
        }catch(IOException failure){throw new FileNotFoundException(failure.toString());}
    }
    @Override public String getType(Uri uri){authorize();return "application/x-ndjson";}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){authorize();throw new UnsupportedOperationException("Read-only log tail");}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException("Read-only log tail");}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException("Read-only log tail");}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException("Read-only log tail");}
}
