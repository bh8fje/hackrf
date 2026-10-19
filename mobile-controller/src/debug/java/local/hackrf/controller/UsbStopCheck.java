package local.hackrf.controller;

import android.app.*;
import android.hardware.usb.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Explicit OFF-only recovery: no permission request, configuration, samples, or TX mode. */
public class UsbStopCheck extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();JSONObject report=new JSONObject();JSONArray snapshots=new JSONArray();
        UsbDeviceConnection connection=null;UsbInterface face=null;boolean claimed=false,off=false,released=false;
        try {
            UsbManager manager=getTargetContext().getSystemService(UsbManager.class);ArrayList<UsbDevice> devices=new ArrayList<>();
            for(UsbDevice device:manager.getDeviceList().values())if(device.getVendorId()==0x1d50&&device.getProductId()==0x6089)devices.add(device);
            if(devices.size()!=1||!manager.hasPermission(devices.get(0)))throw new IOException("需要恰好一个已授权HackRF；检查不会请求授权");
            UsbDevice device=devices.get(0);connection=manager.openDevice(device);if(connection==null)throw new IOException("USB打开失败");
            for(int i=0;i<device.getInterfaceCount();i++){
                UsbInterface candidate=device.getInterface(i);
                for(int j=0;j<candidate.getEndpointCount();j++)if(candidate.getEndpoint(j).getAddress()==0x02&&candidate.getEndpoint(j).getType()==UsbConstants.USB_ENDPOINT_XFER_BULK)face=candidate;
            }
            if(face==null||!connection.claimInterface(face,true))throw new IOException("无法占用HackRF接口以确认停止");claimed=true;
            final UsbDeviceConnection link=connection;
            HackrfProtocol protocol=new HackrfProtocol((type,request,value,index,data)->{
                if(type!=0x40||request!=1||value!=0||index!=0||data!=null)throw new IOException("OFF-only检查拒绝非停止命令");
                return link.controlTransfer(type,request,value,index,null,0,100);
            });
            protocol.mode(0);report.put("offCommandAccepted",true);
            HackrfProtocol.IdleState idle=new HackrfProtocol.IdleState();long deadline=SystemClock.elapsedRealtime()+2000;
            for(int poll=0;poll<50&&SystemClock.elapsedRealtime()<deadline;poll++){
                byte[] raw=new byte[40];int count=connection.controlTransfer(0xc0,41,0,0,raw,40,100);
                if(count==40){
                    ByteBuffer bytes=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);long[] decoded=HackrfProtocol.decodeState(raw);
                    snapshots.put(new JSONObject().put("raw40Hex",ScenarioStore.hex(raw)).put("requested",Short.toUnsignedInt(bytes.getShort(0)))
                        .put("pending",Short.toUnsignedInt(bytes.getShort(2))).put("active",decoded[3]).put("m0Bytes",decoded[0])
                        .put("m4Bytes",Integer.toUnsignedLong(bytes.getInt(12))).put("shortfalls",decoded[1]).put("longestShortfallBytes",Integer.toUnsignedLong(bytes.getInt(20))).put("error",decoded[2]));
                    if(idle.observe(raw)){off=true;report.put("error",decoded[2]).put("transmitHealthy",decoded[2]==0&&decoded[1]==0);break;}
                }else {idle.observe(null);snapshots.put(new JSONObject().put("readLength",count));}
                SystemClock.sleep(20);
            }
            if(!off)throw new IOException("限定时间内未确认稳定OFF/IDLE");
        }catch(Throwable error){try{report.put("failure",error.toString());}catch(JSONException impossible){throw new RuntimeException(impossible);}}
        finally {
            if(connection!=null){try{if(claimed)released=connection.releaseInterface(face);}finally{connection.close();}}
        }
        try{report.put("offConfirmed",off).put("interfaceReleased",released).put("rfStarted",false).put("onlyModeCommand",0).put("snapshots",snapshots);}
        catch(JSONException impossible){throw new RuntimeException(impossible);}
        result.putString("stream",(off?"PASS: physical OFF confirmed; ":"FAIL: ")+report.toString());finish(off?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
}
