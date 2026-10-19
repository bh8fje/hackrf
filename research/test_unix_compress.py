"""Android Unix-compress decoder parity with the local system codec; no RF."""
import random
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parent.parent
JAVA=Path(__import__('os').environ.get('JAVA_HOME', '/Library/Java/JavaVirtualMachines/current/Contents/Home'))/'bin'

class UnixCompressTest(unittest.TestCase):
    def test_codec_limits_cancellation_and_real_snapshot(self):
        if not shutil.which('compress'):self.skipTest('System compress required for independent fixtures')
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp);source=ROOT/'mobile-controller/src/main/java/local/hackrf/controller'
            helper=p/'Decode.java'
            helper.write_text('''package local.hackrf.controller;import java.nio.file.*;import java.io.*;public class Decode {public static void main(String[] a)throws Exception {byte[] raw=Files.readAllBytes(Path.of(a[0]));byte[] out=UnixCompress.decode(raw,Integer.parseInt(a[2]),()->{if(a.length>3)throw new IOException("cancelled");});Files.write(Path.of(a[1]),out);}}''')
            subprocess.run([str(JAVA/'javac'),'-d',tmp,str(source/'PhoneMotion.java'),str(source/'UnixCompress.java'),str(helper)],check=True)
            command=[str(JAVA/'java'),'-cp',tmp,'local.hackrf.controller.Decode',str(p/'raw.Z'),str(p/'decoded'), '20000000']
            rng=random.Random(42)
            samples=[b'',b'A',b'ABABABA'*100,bytes(range(256))*1000,rng.randbytes(350000)+b'xyz'*150000+rng.randbytes(350000)]
            for bits in range(10,17):
                for data in samples:
                    compressed=subprocess.run(['compress','-c','-f','-b',str(bits)],input=data,capture_output=True,check=True).stdout
                    if not data:compressed=bytes([31,157,128|bits])
                    (p/'raw.Z').write_bytes(compressed)
                    r=subprocess.run(command,capture_output=True);self.assertEqual(r.returncode,0,(bits,len(data),r.stderr.decode()));self.assertEqual((p/'decoded').read_bytes(),data)
            def codes(values,length):return sum(code<<(9*i) for i,code in enumerate(values)).to_bytes(length,'little')
            for raw,expected in [(bytes([31,157,10])+codes([65,66,256,258],5),b'ABABABA'),
                                 (bytes([31,157,138])+codes([65,66,256],9)+codes([67],2),b'ABC')]:
                (p/'raw.Z').write_bytes(raw);subprocess.run(command,capture_output=True,check=True);self.assertEqual((p/'decoded').read_bytes(),expected)
            for payload in [b'',b'bad',bytes([31,157,0]),bytes([31,157,0x89]),bytes([31,157,0x70]),bytes([31,157,0x90,255,255])]:
                (p/'raw.Z').write_bytes(payload);self.assertNotEqual(subprocess.run(command,capture_output=True).returncode,0)
            (p/'raw.Z').write_bytes(subprocess.run(['compress','-c','-f'],input=b'nav'*1000,capture_output=True,check=True).stdout)
            self.assertNotEqual(subprocess.run(command[:-1]+['100'],capture_output=True).returncode,0)
            self.assertIn(b'cancelled',subprocess.run(command+['cancel'],capture_output=True).stderr)
            snapshots=list((ROOT/'research/output/navigation-cache/parts').glob('*/complete.bin'))
            for snapshot in snapshots:
                raw=snapshot.read_bytes()
                if raw[:2]!=b'\x1f\x9d':continue
                (p/'raw.Z').write_bytes(raw);expected=subprocess.run(['uncompress','-c'],input=raw,capture_output=True,check=True).stdout
                result=subprocess.run(command,capture_output=True);self.assertEqual(result.returncode,0,result.stderr.decode());self.assertEqual((p/'decoded').read_bytes(),expected)
                break

if __name__=='__main__':unittest.main()
