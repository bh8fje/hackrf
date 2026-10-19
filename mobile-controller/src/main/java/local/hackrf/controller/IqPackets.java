package local.hackrf.controller;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Fills full USB packets with real I/Q followed by the bounded DMA drain tail. */
final class IqPackets {
    static int queueSlots(boolean mixed,int sdk){return sdk>=28?(mixed?48:8):64;}
    static int fill(ByteBuffer buffer, FileChannel file, long[] remaining) throws IOException {
        buffer.clear(); int actual = (int)Math.min(buffer.capacity(), remaining[0]);
        if (actual == 0 && remaining[1] == 0) { buffer.limit(0); return 0; }
        buffer.limit(actual);
        while (buffer.hasRemaining()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已停止");
            if (file.read(buffer) < 0) throw new EOFException("I/Q 文件被截断");
        }
        remaining[0] -= actual;
        int zeros = (int)Math.min(buffer.capacity()-actual, remaining[1]);
        buffer.limit(actual+zeros);
        for (int i = 0; i < zeros; i++) buffer.put((byte)0);
        remaining[1] -= zeros;
        buffer.flip(); return actual;
    }
    static int fill(ByteBuffer buffer, InputStream stream, byte[] scratch, long[] remaining) throws IOException {
        if(scratch.length<buffer.capacity())throw new IOException("I/Q stream buffer is too small");
        buffer.clear();int actual=(int)Math.min(buffer.capacity(),remaining[0]);
        if(actual==0 && remaining[1]==0){buffer.limit(0);return 0;}
        int offset=0;
        while(offset<actual){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止");
            int count=stream.read(scratch,offset,actual-offset);
            if(count<0)throw new EOFException("混合流被截断");
            if(count==0)throw new IOException("混合流未提供样本");
            offset+=count;
        }
        buffer.put(scratch,0,actual);remaining[0]-=actual;
        int zeros=(int)Math.min(buffer.capacity()-actual,remaining[1]);
        for(int i=0;i<zeros;i++)buffer.put((byte)0);
        remaining[1]-=zeros;buffer.flip();return actual;
    }

}
