"""Small synthetic checks only: no navigation generation or RF device access."""
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import numpy as np
ROOT=Path(__file__).resolve().parent


class MixedStreamTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();cls.folder=Path(cls.tmp.name);cls.binary=cls.folder/'mixer'
        subprocess.run(['c++','-std=c++17','-O3','-ffast-math',str(ROOT/'mixed_stream.cpp'),'-o',str(cls.binary)],check=True)

    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()

    def test_duration_limit_600_before_input_open(self):
        for seconds in ('600','600.1'):
            result=subprocess.run([str(self.binary),'/no/gps','/no/bds','--duration-seconds',seconds],capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0)
            self.assertIn('Cannot open IQ8 input' if seconds=='600' else 'Require 0 < seconds <=600',result.stderr)

    def tones(self,duration=.02):
        for name,rate,tone in [('gps',2600000,170000),('bds',5000000,-230000)]:
            t=np.arange(round(rate*duration))/rate
            iq=np.column_stack((50*np.cos(2*np.pi*tone*t),50*np.sin(2*np.pi*tone*t)))
            np.rint(iq).astype(np.int8).tofile(self.folder/(name+'.bin'))

    def test_18m_main_lobes_and_carrier_period(self):
        rate=18000000;center=1567747500
        gps=1575420000-center;bds=1561098000-center
        self.assertEqual(gps,7672500);self.assertEqual(bds,-6649500)
        self.assertEqual((gps+1023000)-(bds-2046000),17391000)
        self.assertEqual(rate/2-max(gps+1023000,abs(bds-2046000)),304500)
        self.assertEqual(gps*12000//rate,5115)
        self.assertEqual(bds*12000//rate,-4433)
        self.assertEqual(gps*12000%rate,0);self.assertEqual(bds*12000%rate,0)

    def test_frequency_images_length_and_continuous_blocks(self):
        self.tones()
        result=subprocess.run([str(self.binary),str(self.folder/'gps.bin'),str(self.folder/'bds.bin')],capture_output=True,check=True)
        values=np.frombuffer(result.stdout,np.int8).astype(float);z=values[::2]+1j*values[1::2]
        self.assertEqual(len(z),360000)
        stats=json.loads(result.stderr);self.assertEqual(stats['clippedComponents'],0)
        # Ignore centered-FIR endpoint padding; integer cycles make tone comparisons coherent.
        n=np.arange(10000,len(z)-10000);z=z[10000:-10000]
        def amplitude(f):return abs(np.mean(z*np.exp(-2j*np.pi*f*n/18000000)))
        self.assertGreater(amplitude(7842500),15)
        self.assertGreater(amplitude(-6879500),10)
        self.assertLess(amplitude(7842500-2600000),amplitude(7842500)*.01)
        self.assertLess(amplitude(-6879500+5000000),amplitude(-6879500)*.01)
        self.assertFalse(stats['rfCalibrated'])

    def test_float_cache_matches_previous_int8_fir(self):
        self.tones(.02)
        reference=self.folder/'reference-int8'
        subprocess.run(['c++','-std=c++17','-O3','-ffast-math','-DMIXED_REFERENCE_INT8','-DMIXED_SERIAL_REFERENCE',str(ROOT/'mixed_stream.cpp'),'-o',str(reference)],check=True)
        # Random wideband IQ exercises rounding beyond the coherent tone check.
        rng=np.random.default_rng(42)
        for name,rate in [('gps',2600000),('bds',5000000)]:
            rng.integers(-100,101,size=round(rate*.02)*2,dtype=np.int8).tofile(self.folder/(name+'.bin'))
        command=[str(self.folder/'gps.bin'),str(self.folder/'bds.bin')]
        cached=subprocess.run([str(self.binary)]+command,capture_output=True,check=True)
        previous=subprocess.run([str(reference)]+command,capture_output=True,check=True)
        a=np.frombuffer(cached.stdout,np.int8).astype(np.int16)
        b=np.frombuffer(previous.stdout,np.int8).astype(np.int16)
        self.assertLessEqual(int(np.max(abs(a-b))),1)
        self.assertEqual(json.loads(cached.stderr)['clippedComponents'],json.loads(previous.stderr)['clippedComponents'])

    def test_fast_math_matches_normal_build(self):
        self.tones(.02)
        normal=self.folder/'normal'
        subprocess.run(['c++','-std=c++17','-O3',str(ROOT/'mixed_stream.cpp'),'-o',str(normal)],check=True)
        command=[str(self.folder/'gps.bin'),str(self.folder/'bds.bin')]
        fast=subprocess.run([str(self.binary)]+command,capture_output=True,check=True)
        plain=subprocess.run([str(normal)]+command,capture_output=True,check=True)
        a=np.frombuffer(fast.stdout,np.int8).astype(np.int16)
        b=np.frombuffer(plain.stdout,np.int8).astype(np.int16)
        self.assertLessEqual(int(np.max(abs(a-b))),1)
        self.assertEqual(json.loads(fast.stderr)['clippedComponents'],json.loads(plain.stderr)['clippedComponents'])

    def test_fifo_chunked_matches_regular_and_short_stream_fails(self):
        import os
        import threading
        self.tones(.02)
        paths=[self.folder/'gps.bin',self.folder/'bds.bin']
        reference=subprocess.run([str(self.binary)]+list(map(str,paths)),capture_output=True,check=True).stdout
        payloads=[p.read_bytes() for p in paths]
        def fifo_run(short=False, extra=False):
            pipes=[self.folder/('fifo-gps'+str(short)+str(extra)),self.folder/('fifo-bds'+str(short)+str(extra))]
            for p in pipes:os.mkfifo(p)
            failures=[]
            def writer(path,raw,chunk):
                try:
                    with open(path,'wb',buffering=0) as stream:
                        for start in range(0,len(raw),chunk):
                            stream.write(raw[start:start+chunk])
                except BrokenPipeError:pass
                except Exception as e:failures.append(e)
            raw=[payloads[0][:-2] if short else payloads[0],payloads[1]+(b'12' if extra else b'')]
            workers=[threading.Thread(target=writer,args=(pipes[j],raw[j],7919 if j==0 else 12347)) for j in range(2)]
            for worker in workers:worker.start()
            try:
                result=subprocess.run([str(self.binary)]+list(map(str,pipes))+['--duration-seconds','.02'],capture_output=True,timeout=10)
            finally:
                for worker in workers:worker.join(2)
                for path in pipes:path.unlink()
            self.assertFalse(failures)
            return result
        streamed=fifo_run()
        self.assertEqual(streamed.returncode,0,streamed.stderr)
        self.assertLessEqual(int(np.max(abs(np.frombuffer(streamed.stdout,np.int8).astype(int)-np.frombuffer(reference,np.int8).astype(int)))),1)
        self.assertNotEqual(fifo_run(short=True).returncode,0)
        self.assertNotEqual(fifo_run(extra=True).returncode,0)
        forced=subprocess.run([str(self.binary)]+list(map(str,paths))+['--seconds','.02','--force-stream'],capture_output=True,check=True)
        self.assertEqual(forced.stdout,streamed.stdout)
        mismatch=subprocess.run([str(self.binary)]+list(map(str,paths))+['--seconds','.01','--force-stream'],capture_output=True)
        self.assertNotEqual(mismatch.returncode,0)

    def test_real_fifo_throughput_without_polling_delay(self):
        import os
        import threading
        self.tones(1)
        paths=[self.folder/'gps.bin',self.folder/'bds.bin']
        payloads=[path.read_bytes() for path in paths]
        pipes=[self.folder/'benchmark-gps-fifo',self.folder/'benchmark-bds-fifo']
        for path in pipes:os.mkfifo(path)
        errors=[]
        def writer(path,payload):
            try:
                fd=os.open(path,os.O_WRONLY)
                try:
                    position=0
                    while position<len(payload):
                        position+=os.write(fd,payload[position:position+16384])
                finally:os.close(fd)
            except Exception as error:errors.append(error)
        workers=[threading.Thread(target=writer,args=(pipes[j],payloads[j])) for j in range(2)]
        for worker in workers:worker.start()
        began=time.perf_counter()
        try:
            with open('/dev/null','wb') as sink:
                result=subprocess.run([str(self.binary)]+list(map(str,pipes))+['--duration-seconds','1'],stdout=sink,stderr=subprocess.PIPE,check=True,timeout=15)
            elapsed=time.perf_counter()-began
            self.assertEqual(json.loads(result.stderr)['bytes'],36000000)
            print(json.dumps({'realFifoSeconds':1,'elapsedSeconds':elapsed,'realTimeFactor':1/elapsed,'outputMBps':36/elapsed}))
        finally:
            for worker in workers:worker.join(2)
            for path in pipes:path.unlink()
        self.assertFalse(errors)

    def test_failed_input_cancels_other_blocked_worker(self):
        import os
        import threading
        pipes=[self.folder/'failed-gps-fifo',self.folder/'waiting-bds-fifo']
        for path in pipes:os.mkfifo(path)
        release=threading.Event()
        def writer(path,short):
            with open(path,'wb',buffering=0) as output:
                if short:output.write(b'12')
                else:release.wait(5)
        workers=[threading.Thread(target=writer,args=(pipes[0],True),daemon=True),threading.Thread(target=writer,args=(pipes[1],False),daemon=True)]
        for worker in workers:worker.start()
        began=time.perf_counter()
        try:
            result=subprocess.run([str(self.binary)]+list(map(str,pipes))+['--duration-seconds','.02'],capture_output=True,timeout=2)
            self.assertNotEqual(result.returncode,0)
            self.assertLess(time.perf_counter()-began,1.5)
        finally:
            release.set()
            for worker in workers:worker.join(2)
            for path in pipes:path.unlink()

    def test_saturation_is_counted_and_weights_checked(self):
        for name, samples in [('gps', 13000), ('bds', 25000)]:
            (self.folder/(name+'.bin')).write_bytes(bytes([127, 127])*samples)
        result=subprocess.run([str(self.binary),str(self.folder/'gps.bin'),str(self.folder/'bds.bin'),'-','.99','.01'],capture_output=True,check=True)
        stats=json.loads(result.stderr)
        self.assertGreater(stats['clippedComponents'],0)
        values=np.frombuffer(result.stdout,np.int8)
        self.assertTrue(np.any(values==127))
        result=subprocess.run([str(self.binary),str(self.folder/'gps.bin'),str(self.folder/'bds.bin'),'-','.2','.3'],capture_output=True)
        self.assertNotEqual(result.returncode,0)
        for weight in ('nan','inf','0.3junk'):
            result=subprocess.run([str(self.binary),str(self.folder/'gps.bin'),str(self.folder/'bds.bin'),'-',weight,'.1'],capture_output=True)
            self.assertNotEqual(result.returncode,0)

    def test_reject_bad_inputs_and_report_throughput(self):
        self.tones(1)
        began=time.perf_counter()
        with open('/dev/null','wb') as sink:
            result=subprocess.run([str(self.binary),str(self.folder/'gps.bin'),str(self.folder/'bds.bin')],stdout=sink,stderr=subprocess.PIPE,check=True)
        elapsed=time.perf_counter()-began
        print(json.dumps({'syntheticSeconds':1,'elapsedSeconds':elapsed,'realTimeFactor':1/elapsed,'outputMBps':36/elapsed}))
        began=time.perf_counter()
        with open('/dev/null','wb') as sink:
            subprocess.run([str(self.binary),str(self.folder/'gps.bin'),str(self.folder/'bds.bin'),'--duration-seconds','1','--force-stream'],stdout=sink,stderr=subprocess.PIPE,check=True)
        elapsed=time.perf_counter()-began
        print(json.dumps({'streamSeconds':1,'elapsedSeconds':elapsed,'realTimeFactor':1/elapsed,'outputMBps':36/elapsed,'inputBufferBytes':131072,'inputFloatCacheBytes':262144,'workerOutputBytes':524288,'firWorkers':2}))
        bad=self.folder/'bad.bin';bad.write_bytes(b'123')
        with open('/dev/null','wb') as sink:
            result=subprocess.run([str(self.binary),str(bad),str(self.folder/'bds.bin')],stdout=sink,stderr=subprocess.PIPE)
        self.assertNotEqual(result.returncode,0)
        short=self.folder/'short.bin';short.write_bytes(bytes(100))
        result=subprocess.run([str(self.binary),str(short),str(self.folder/'bds.bin')],capture_output=True)
        self.assertNotEqual(result.returncode,0)
        self.assertIn(b'durations differ',result.stderr)


if __name__=='__main__':unittest.main()
