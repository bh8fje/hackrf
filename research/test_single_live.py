from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from walking import DEFAULTS
import single_live
from mixed_transmit import RadioSink


class SingleLiveTest(unittest.TestCase):
    def test_profiles_keep_existing_native_generator_and_own_carrier(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);timing={'startUTC':'2026-10-18T00:00:00+00:00','startGPS':'2026/10/18,00:00:18'}
            for system,rate,frequency in [('gps',2600000,1575420000),('bds',5000000,1561098000)]:
                args,preview=single_live.command(system,dict(DEFAULTS,duration=90),folder,folder/('broadcast-'+system+'.rnx'),timing)
                self.assertEqual(preview['estimatedFileMB'],90*rate*2/1000000)
                self.assertEqual(preview['streamSampleRate'],rate)
                self.assertEqual(preview['sampleRate'],rate);self.assertEqual(preview['frequencyHz'],frequency)
                self.assertIn('emitted.csv',args);self.assertNotIn('mixed_stream',args[0])
                if system=='gps':self.assertIn('signal.fifo',args);self.assertIn('90.1',args)
                else:
                    config=json.loads((folder/'scenario.json').read_text())
                    self.assertEqual(config['output']['name'],str(folder/'signal.fifo'))
                    self.assertEqual([s['system'] for s in config['output']['systemSelect'] if s['enable']],['BDS'])
            self.assertFalse((folder/'signal.bin').exists())

    def test_600_second_profiles_and_mixed_preview(self):
        from mixed_transmit import preview as mixed_preview
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);settings=dict(DEFAULTS,duration=600)
            for system in ('gps','bds'):
                args,value=single_live.command(system,settings,folder,folder/'broadcast.rnx',{'startUTC':'2026-10-18T00:00:00+00:00','startGPS':'2026/10/18,00:00:18'})
                if system=='gps':self.assertIn('600.1',args)
                else:self.assertEqual(json.loads((folder/'scenario.json').read_text())['trajectory']['trajectoryList'][0]['time'],600)
                self.assertGreater(value['estimatedFileMB'],3000)
            self.assertEqual(mixed_preview(settings)['streamSampleRate'],18000000)

    def test_fifo_startup_short_chunks_and_eof(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'signal.fifo';os.mkfifo(path)
            fd=os.open(path,os.O_RDONLY|os.O_NONBLOCK)
            code='import os,sys,time;time.sleep(.05);f=os.open(sys.argv[1],os.O_WRONLY);os.write(f,b"ab");os.write(f,b"cdef");os.close(f)'
            process=subprocess.Popen([__import__('sys').executable,'-c',code,str(path)])
            stream=single_live.GeneratorStream(fd,process)
            try:
                data=b''
                while block:=stream.read(4):data+=block
                self.assertEqual(data,b'abcdef');self.assertEqual(process.wait(2),0)
            finally:stream.close();process.kill() if process.poll() is None else None

    def test_profiles_use_hardware_gate_at_their_original_rate(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);fake=folder/'fake-transfer'
            fake.write_text('#!/usr/bin/env python3\nimport sys\ndata=sys.stdin.buffer.read()\nprint("M0 mode 4 requested 4 error 0\\n0 underruns")\nprint(str(len(data))+" bytes transferred by M0")\nprint("hackrf_stop_tx() done\\nPost-stop IDLE confirmed:")\n')
            fake.chmod(0o700)
            for profile,rate,carrier in [('gps',2600000,1575420000),('bds',5000000,1561098000)]:
                timing={'startUTC':datetime.now(timezone.utc).isoformat(),'modelProducedAtUTC':datetime.now(timezone.utc).isoformat()}
                with patch('mixed_transmit.TRANSFER',fake),patch('live_nav.launch_delay'):
                    sink=RadioSink(folder,timing,.001,0,False,profile=profile)
                    with sink:sink.write(b'x'*int(rate*.002))
                self.assertTrue(sink.record['passed'])
                command=sink.record['command'];self.assertEqual(command[command.index('-s')+1],str(rate));self.assertEqual(command[command.index('-f')+1],str(carrier))
                self.assertNotIn('-b',command)

    def test_auto_start_never_builds_a_full_iq_file_and_cleans_fifo(self):
        class Sink:
            def __init__(self,*args,**kwargs):self.record={'rfEnabled':False,'stopConfirmed':True}
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def write(self,block):return len(block)
            def flush(self):pass
        with tempfile.TemporaryDirectory() as tmp:
            for system in ('gps','bds'):
                folder=Path(tmp)/system
                def prepare(*args,**kwargs):
                    nav=folder/'nav.rnx';nav.write_text('navigation fixture')
                    return nav,{'startUTC':'2026-10-18T00:00:00+00:00'}
                def command(*args):
                    return [__import__('sys').executable,'-c','import os;f=os.open("signal.fifo",os.O_WRONLY);os.write(f,b"ab");os.close(f)'],{}
                def pump(stream,sink,expected,**kwargs):
                    self.assertEqual(stream.read(2),b'ab');self.assertEqual(stream.read(2),b'')
                    return {'prefillSeconds':.01}
                with patch('single_live.qualification',return_value={'currentStreamDecoded':False}),patch('live_nav.prepare',side_effect=prepare) as gps,patch('bds_navigation.prepare',side_effect=prepare) as bds,patch('single_live.command',side_effect=command),patch('single_live.RadioSink',Sink),patch('single_live.pump',side_effect=pump):
                    result=single_live.run({'settings':dict(DEFAULTS,duration=90),'gain':0,'amplifier':False},folder,lambda *args,**kwargs:None,system)
                    self.assertEqual(gps.call_count,1 if system=='gps' else 0)
                    self.assertEqual(bds.call_count,1 if system=='bds' else 0)
                self.assertEqual(result['verificationKind'],'hardware-stream');self.assertFalse(result['softwareReceiverVerified']);self.assertFalse(result['phoneReceptionVerified'])
                self.assertEqual(result['storedIQBytes'],0);self.assertFalse((folder/'signal.fifo').exists());self.assertFalse((folder/'signal.bin').exists())

    def test_short_or_cancelled_producer_cleans_fifo_and_never_claims_decode(self):
        class Sink:
            def __init__(self,*args,**kwargs):self.record={'rfEnabled':False,'stopConfirmed':False}
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def write(self,block):raise AssertionError('Short stream must not reach hardware')
            def flush(self):pass
        original_pump=single_live.pump
        with tempfile.TemporaryDirectory() as tmp:
            for mode in ('short','cancel'):
                folder=Path(tmp)/mode
                def prepare(*args,**kwargs):
                    nav=folder/'nav.rnx';nav.write_text('fixture');return nav,{'startUTC':'2026-10-18T00:00:00+00:00'}
                def command(*args):
                    code='import os,time;f=os.open("signal.fifo",os.O_WRONLY);os.write(f,b"ab");'
                    code+=('time.sleep(20)' if mode=='cancel' else 'os.close(f)')
                    return [__import__('sys').executable,'-c',code],{}
                def cancel_pump(stream,*args,**kwargs):
                    self.assertEqual(stream.read(2),b'ab');raise RuntimeError('cancel')
                with patch('single_live.qualification',return_value={}),patch('live_nav.prepare',side_effect=prepare),patch('single_live.command',side_effect=command),patch('single_live.RadioSink',Sink),patch('single_live.pump',side_effect=cancel_pump if mode=='cancel' else original_pump):
                    with self.assertRaises((ValueError,RuntimeError)):
                        single_live.run({'settings':dict(DEFAULTS,duration=90),'gain':0,'amplifier':False},folder,lambda *args,**kwargs:None)
                self.assertFalse((folder/'signal.fifo').exists())
                record=json.loads((folder/'experiment.json').read_text())
                self.assertFalse(record['passed']);self.assertFalse(record['hardwareOutputAllowed']);self.assertFalse(record['softwareReceiverVerified'])

    def test_component_qualification_checks_only_its_fixed_binary(self):
        import hashlib
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);research=root/'research';research.mkdir()
            values={'research/output/generator/gps-sdr-sim':'gps', 'research/walking.py':'motion',
                    'shared/signal-environments.json':'environment','host/build/hackrf-tools/src/hackrf_transfer':'transfer',
                    'verify.json':json.dumps({'passed':True}),'accuracy.json':json.dumps({'passed':True,'validFixes':100,'maxError':5})}
            hashes={}
            for name,value in values.items():
                path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value);hashes[name]=hashlib.sha256(path.read_bytes()).hexdigest()
            qualification=root/'qualified.json';qualification.write_text(json.dumps({'offlineQualified':True,'verification':'verify.json','accuracy':'accuracy.json','filesSHA256':hashes}))
            with patch('single_live.ROOT',research),patch('single_live.QUALIFICATION',qualification):
                self.assertFalse(single_live.qualification('gps')['currentStreamDecoded'])
                (research/'output/generator/gps-sdr-sim').write_text('changed')
                with self.assertRaises(ValueError):single_live.qualification('gps')

    def test_bad_qualification_rejects_before_start(self):
        with tempfile.TemporaryDirectory() as tmp:
            file=Path(tmp)/'qualification.json';file.write_text('{"offlineQualified":false}')
            with patch('single_live.QUALIFICATION',file),self.assertRaises(ValueError):single_live.qualification('gps')
            with self.assertRaises(ValueError):single_live.qualification('galileo')


if __name__=='__main__':unittest.main()
