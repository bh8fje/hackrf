package local.hackrf.controller;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class MixedTransportTest {
    public static void main(String[] args)throws Exception {
        assert IqPackets.queueSlots(true,28)==48 && IqPackets.queueSlots(false,28)==8;
        assert IqPackets.queueSlots(true,27)==64 && IqPackets.queueSlots(false,27)==64;
        assert IqPackets.queueSlots(true,28)*262144<16*1024*1024;
        assert SignalProfile.MIXED_ENABLED;SignalProfile.requireMixedEnabled();
        HackrfProtocol.IdleState idle=new HackrfProtocol.IdleState();byte[] state=new byte[40];
        java.nio.ByteBuffer raw=java.nio.ByteBuffer.wrap(state).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        raw.putShort(0,(short)3);assert !idle.observe(state);raw.putShort(0,(short)0);
        raw.putShort(2,(short)1);assert !idle.observe(state);raw.putShort(2,(short)0);
        raw.putInt(4,4);assert !idle.observe(state);raw.putInt(4,0);
        raw.putInt(36,1);assert !idle.observe(state);raw.putInt(36,0);
        assert !idle.observe(null);
        raw.putInt(16,1);raw.putInt(20,736);assert !idle.observe(state);
        raw.putInt(16,0);raw.putInt(20,0);assert !idle.observe(state);assert !idle.observe(state);assert idle.observe(state);
        raw.putInt(16,1);raw.putInt(20,512);assert !idle.observe(state);assert !idle.observe(state);assert idle.observe(state);
        long[] failedState=HackrfProtocol.decodeState(state);
        assert failedState[1]==1;
        try{HackrfProtocol.requireCompleted(failedState,3240000000L,3240000000L);throw new AssertionError("Real underrun hidden");}catch(IOException expected){}
        raw.putInt(16,0);raw.putInt(20,0);raw.putInt(36,2);
        assert !idle.observe(state);assert !idle.observe(state);assert idle.observe(state);
        try{HackrfProtocol.requireCompleted(HackrfProtocol.decodeState(state),3240000000L,3240000000L);throw new AssertionError("Sticky error hidden by physical OFF");}catch(IOException expected){}
        final int[] requests={0};
        new HackrfProtocol((type,request,value,index,data)->{
            requests[0]++;
            if(request==1)assert value==0;
            if(request==6)assert Arrays.equals(data,HackrfProtocol.pair(18000000,1));
            if(request==7)assert (value|index<<16)==20000000;
            if(request==16)assert Arrays.equals(data,HackrfProtocol.pair(1567,747500));
            if(request==42)assert (value|index<<16)==3600000;
            if(request==21)data[0]=1;
            return data==null?0:data.length;
        }).configure(47,true,SignalProfile.MIXED_SYSTEM);
        assert requests[0]==8;
        byte[] data=new byte[262144+12];Arrays.fill(data,(byte)23);
        InputStream partial=new ByteArrayInputStream(data){@Override public synchronized int read(byte[] b,int off,int len){return super.read(b,off,Math.min(len,7));}};
        ByteBuffer target=ByteBuffer.allocateDirect(262144);byte[] scratch=new byte[target.capacity()];long[] left={data.length,32768-12};
        int total=0,wire=0;
        while(left[0]+left[1]>0){int actual=IqPackets.fill(target,partial,scratch,left);total+=actual;wire+=target.remaining();for(int i=0;i<target.remaining();i++)assert target.get(i)==(i<actual?23:0);}
        assert total==data.length && wire==262144+32768;
        try{IqPackets.fill(target,new ByteArrayInputStream(new byte[1]),scratch,new long[]{2,0});throw new AssertionError("Short stream accepted");}catch(EOFException expected){}
        try{IqPackets.fill(target,new InputStream(){public int read(){return 0;}public int read(byte[] b,int o,int n){return 0;}},scratch,new long[]{2,0});throw new AssertionError("Stalled stream accepted");}catch(IOException expected){}
        Thread.currentThread().interrupt();
        try{IqPackets.fill(target,new ByteArrayInputStream(data),scratch,new long[]{2,0});throw new AssertionError("Stop ignored");}catch(InterruptedIOException expected){}finally{Thread.interrupted();}
        System.out.println("PASS: mixed candidate enabled; 18Msps RF-off configuration, partial reads, exact bytes, padding, truncation and stop");
    }
}
