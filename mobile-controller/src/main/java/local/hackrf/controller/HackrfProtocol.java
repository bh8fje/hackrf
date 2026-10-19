package local.hackrf.controller;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Wire layout follows host/libhackrf/src/hackrf.c. All configuration leaves RF off. */
public final class HackrfProtocol {
    public interface Control { int transfer(int type, int request, int value, int index, byte[] data) throws IOException; }
    private final Control usb;
    public HackrfProtocol(Control usb) { this.usb = usb; }
    private void out(int request, int value, int index, byte[] bytes) throws IOException {
        int expected = bytes == null ? 0 : bytes.length;
        if (usb.transfer(0x40, request, value, index, bytes) != expected) throw new IOException("USB control failed: " + request);
    }
    public void mode(int mode) throws IOException {
        if (mode != 0 && mode != 2) throw new IOException("Unsupported transceiver mode");
        out(1, mode, 0, null);
    }
    public void configure(int gain, boolean amplifier) throws IOException {configure(gain,amplifier,false);}
    public void configure(int gain, boolean amplifier,boolean bds) throws IOException {
        configure(gain,amplifier,bds?"bds":"gps");
    }
    public void configure(int gain, boolean amplifier,String system) throws IOException {
        if (gain < 0 || gain > 47) throw new IOException("TX gain must be 0–47");
        if(!system.equals("gps")&&!system.equals("bds")&&!system.equals("galileo")&&!system.equals("glonass")&&!system.equals("mixed"))throw new IOException("Unsupported GNSS system");
        boolean mixed=system.equals("mixed"),bds=system.equals("bds"),galileo=system.equals("galileo"),glonass=system.equals("glonass");int rate=mixed?18000000:galileo?16000000:glonass?10000000:bds?5000000:2600000;
        mode(0);
        out(6, 0, 0, pair(rate, 1));
        int bandwidth=mixed?20000000:galileo?12000000:glonass?10000000:bds?5000000:1750000;out(7, bandwidth & 65535, bandwidth >>> 16, null);
        out(16, 0, 0, mixed?pair(1567,747500):glonass?pair(1602,0):bds?pair(1561,98000):pair(1575,420000));
        out(23, 0, 0, null);
        out(17, amplifier ? 1 : 0, 0, null);
        byte[] accepted = new byte[1];
        if (usb.transfer(0xc0, 21, 0, gain, accepted) != 1 || accepted[0] == 0) throw new IOException("TX gain rejected");
        // Once streaming, firmware idles the sample pipeline after a >100 ms underrun.
        int limit = rate/5;
        out(42, limit & 65535, limit >>> 16, null);
    }
    public long[] state() throws IOException {
        byte[] data = new byte[40];
        if (usb.transfer(0xc0, 41, 0, 0, data) != data.length) throw new IOException("M0 state read failed");
        return decodeState(data);
    }
    static long[] decodeState(byte[] data) throws IOException {
        if(data==null || data.length!=40)throw new IOException("Invalid M0 state length");
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        return new long[]{Integer.toUnsignedLong(buffer.getInt(8)), Integer.toUnsignedLong(buffer.getInt(16)), Integer.toUnsignedLong(buffer.getInt(36)), Integer.toUnsignedLong(buffer.getInt(4))};
    }
    static final class IdleState {
        private int[] previous;
        private int stableReads;
        boolean observe(byte[] data) {
            if(data==null||data.length!=40){stableReads=0;return false;}
            ByteBuffer bytes=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if(bytes.getShort(0)!=0 || bytes.getShort(2)!=0 || bytes.getInt(4)!=0){stableReads=0;return false;}
            int[] counters={bytes.getInt(8),bytes.getInt(12),bytes.getInt(16),bytes.getInt(20),bytes.getInt(36)};
            stableReads=stableReads>0&&java.util.Arrays.equals(previous,counters)?stableReads+1:1;
            previous=counters;return stableReads>=3;
        }
    }
    static void requireCompleted(long[] state,long consumed,long expected) throws IOException {
        if(state==null || state.length!=4 || state[3]!=0 || state[2]!=0 || state[1]!=0 || expected<=0 || consumed<expected)
            throw new IOException("导航信号停止后未通过完整采样／零欠载／空闲状态检查");
    }
    public static long extendCounter(long total,long raw) {
        if(total<0||raw<0||raw>0xffffffffL)throw new IllegalArgumentException("Invalid M0 counter");
        return total+((raw-(total&0xffffffffL))&0xffffffffL);
    }
    public static int apiVersion(byte[] descriptors) throws IOException {
        if (descriptors == null || descriptors.length < 18 || (descriptors[0] & 255) < 18 || descriptors[1] != 1)
            throw new IOException("USB device descriptor unavailable");
        int version = (descriptors[12] & 255) | (descriptors[13] & 255) << 8;
        if (version < 0x0106) throw new IOException("Firmware lacks required M0 status API");
        return version;
    }
    public int bufferSize(int apiVersion) throws IOException {
        // GET_BUFFER_SIZE first appeared in API 1.12; older firmware has fixed 32 KiB DMA.
        if (apiVersion < 0x0112) return 32768;
        byte[] data = new byte[4];
        if (usb.transfer(0xc0, 61, 0, 0, data) != 4) throw new IOException("USB buffer size unavailable");
        int size = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (size < 16384 || size > 262144 || (size & (size-1)) != 0) throw new IOException("Unsupported USB buffer size");
        return size;
    }
    static byte[] pair(int first, int second) { return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(first).putInt(second).array(); }
}
