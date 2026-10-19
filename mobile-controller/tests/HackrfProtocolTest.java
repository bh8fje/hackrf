package local.hackrf.controller;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;

public class HackrfProtocolTest {
    public static void main(String[] args) throws Exception {
        ArrayList<Integer> requests = new ArrayList<>();
        HackrfProtocol protocol = new HackrfProtocol((type, request, value, index, data) -> {
            requests.add(request);
            if (request == 1) assert value == 0 : "Configuration must never start RF";
            if (request == 6) assert Arrays.equals(data, HackrfProtocol.pair(2600000, 1));
            if (request == 7) assert (value | index << 16) == 1750000;
            if (request == 16) assert Arrays.equals(data, HackrfProtocol.pair(1575, 420000));
            if (request == 23) assert value == 0;
            if (request == 21) { assert type == 0xc0 && index == 47; data[0] = 1; }
            if (request == 42) assert (value | index << 16) == 520000;
            if (request == 61) ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(32768);
            if (request == 41) {
                ByteBuffer bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                bytes.putInt(8, -1); bytes.putInt(16, 4); bytes.putInt(36, 2); bytes.putInt(4, 0);
            }
            return data == null ? 0 : data.length;
        });
        protocol.configure(47, true);
        assert requests.equals(Arrays.asList(1,6,7,16,23,17,21,42));
        int count = requests.size();
        assert protocol.bufferSize(0x0111) == 32768;
        assert requests.size() == count : "Old firmware must not receive request 61";
        assert protocol.bufferSize(0x0112) == 32768;
        byte[] descriptor = new byte[18]; descriptor[0] = 18; descriptor[1] = 1; descriptor[12] = 0x11; descriptor[13] = 1;
        assert HackrfProtocol.apiVersion(descriptor) == 0x0111;
        try { HackrfProtocol.apiVersion(new byte[3]); throw new AssertionError("Bad descriptor accepted"); } catch (IOException expected) { }
        assert Arrays.equals(protocol.state(), new long[]{4294967295L,4,2,0});
        try { protocol.configure(48, true); throw new AssertionError("Invalid gain accepted"); } catch (IOException expected) { }
        try { new HackrfProtocol((a,b,c,d,e) -> -1).configure(0, false); throw new AssertionError("Failed transfer ignored"); } catch (IOException expected) { }
        try { new HackrfProtocol((a,b,c,d,e) -> e == null ? 0 : e.length).configure(0, false); throw new AssertionError("Rejected gain ignored"); } catch (IOException expected) { }
        ArrayList<Integer> bdsRequests=new ArrayList<>();
        HackrfProtocol bds=new HackrfProtocol((type,request,value,index,data)->{
            bdsRequests.add(request);
            if(request==1)assert value==0 : "BDS configuration started RF";
            if(request==6)assert Arrays.equals(data,HackrfProtocol.pair(5000000,1));
            if(request==7)assert (value|index<<16)==5000000;
            if(request==16)assert Arrays.equals(data,HackrfProtocol.pair(1561,98000));
            if(request==17)assert value==1;
            if(request==21){assert index==47;data[0]=1;}
            if(request==42)assert (value|index<<16)==1000000;
            return data==null?0:data.length;
        });
        bds.configure(47,true,true);assert bdsRequests.equals(Arrays.asList(1,6,7,16,23,17,21,42));
        HackrfProtocol glonass=new HackrfProtocol((type,request,value,index,data)->{
            if(request==1)assert value==0 : "GLONASS configuration started RF";
            if(request==6)assert Arrays.equals(data,HackrfProtocol.pair(10000000,1));
            if(request==7)assert (value|index<<16)==10000000;
            if(request==16)assert Arrays.equals(data,HackrfProtocol.pair(1602,0));
            if(request==42)assert (value|index<<16)==2000000;
            if(request==21){assert index==47;data[0]=1;}
            return data==null?0:data.length;
        });
        glonass.configure(47,true,"glonass");
        HackrfProtocol galileo=new HackrfProtocol((type,request,value,index,data)->{
            if(request==1)assert value==0;
            if(request==6)assert Arrays.equals(data,HackrfProtocol.pair(16000000,1));
            if(request==7)assert (value|index<<16)==12000000;
            if(request==16)assert Arrays.equals(data,HackrfProtocol.pair(1575,420000));
            if(request==42)assert (value|index<<16)==3200000;
            if(request==21)data[0]=1;
            return data==null?0:data.length;
        });galileo.configure(47,true,"galileo");
        assert HackrfProtocol.extendCounter(0xfffffff0L,32)==0x100000020L;
        assert HackrfProtocol.extendCounter(0x100000020L,64)==0x100000040L;
        byte[] stopped=new byte[40];
        ByteBuffer.wrap(stopped).order(ByteOrder.LITTLE_ENDIAN).putInt(8,900000000);
        long[] finalState=HackrfProtocol.decodeState(stopped);
        HackrfProtocol.requireCompleted(finalState,900000000,900000000);
        for(long[] bad:new long[][]{null,new long[]{900000000,1,0,0},new long[]{900000000,0,2,0},new long[]{900000000,0,0,4}}){
            try{HackrfProtocol.requireCompleted(bad,900000000,900000000);throw new AssertionError("Incomplete or non-idle transmission accepted");}catch(IOException expected){}
        }
        try{HackrfProtocol.requireCompleted(finalState,899999999,900000000);throw new AssertionError("Truncated transmission accepted");}catch(IOException expected){}
        try{HackrfProtocol.decodeState(new byte[39]);throw new AssertionError("Short status accepted");}catch(IOException expected){}
        System.out.println("PASS: HackRF layouts, Galileo/GPS/BDS RF-off configuration, counter rollover and errors");
    }
}
