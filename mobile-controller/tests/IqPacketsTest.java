package local.hackrf.controller;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Arrays;

public class IqPacketsTest {
    public static void main(String[] arguments) throws Exception {
        for (int capacity : new int[]{16384, 262144}) check(capacity);
        System.out.println("PASS: 16/256 KiB queues, partial I/Q plus zero tail, exact byte accounting and truncation");
    }
    private static void check(int capacity) throws Exception {
        File file = File.createTempFile("iq-packet-test", ".bin");
        try {
            byte[] data = new byte[capacity+123]; Arrays.fill(data, (byte)53); Files.write(file.toPath(), data);
            long padding = (32768-data.length%32768)%32768+32768;
            long[] remaining = {data.length, padding}; int actualTotal = 0, wireTotal = 0;
            try (FileInputStream source = new FileInputStream(file)) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
                while (remaining[0]+remaining[1] > 0) {
                    int actual = IqPackets.fill(buffer, source.getChannel(), remaining);
                    assert buffer.remaining() > 0 && buffer.remaining()%16384 == 0 : "Final I/Q fragment must share a full packet with padding";
                    for (int i = 0; i < buffer.remaining(); i++) assert buffer.get(i) == (i < actual ? 53 : 0);
                    actualTotal += actual; wireTotal += buffer.remaining();
                }
                assert actualTotal == data.length && wireTotal == data.length+padding;
                assert IqPackets.fill(buffer, source.getChannel(), remaining) == 0 && buffer.remaining() == 0;
            }
            try (FileInputStream source = new FileInputStream(file)) {
                source.getChannel().position(file.length());
                try { IqPackets.fill(ByteBuffer.allocateDirect(capacity), source.getChannel(), new long[]{2,0}); throw new AssertionError("Truncation accepted"); }
                catch (EOFException expected) { }
            }
        } finally { file.delete(); }
    }
}
