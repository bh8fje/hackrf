import configparser,math,unittest
from datetime import datetime,timedelta,timezone
from pathlib import Path
import numpy as np
from galileo_lab import convert,configuration,receiver_config,GPS_EPOCH
from live_nav import header_line
from walking import DEFAULTS

START=datetime(2026,10,17,10,40,tzinfo=timezone.utc)
def models():
    rows=header_line('     3.05           N: GNSS NAV DATA    M: MIXED','RINEX VERSION / TYPE')
    for tag in ('GPSA','GPSB','GAL'):
        rows+=header_line(f'{tag:4s} '+''.join(f'{v:12.4E}' for v in (0,0,0,0)),'IONOSPHERIC CORR')
    rows+=header_line(f'{"GAUT":4s} {0:17.10E}{0:16.9E}{518400:7d}{2440:5d}','TIME SYSTEM CORR')
    return rows+header_line(f'{18:6d}','LEAP SECONDS')+header_line('','END OF HEADER')
def records(flags=517,health=0,shift=0):
    toc=START+timedelta(seconds=18+shift);seconds=(toc-GPS_EPOCH).total_seconds();week=int(seconds//604800);tow=seconds%604800
    text=models()
    for prn in range(1,5):
        text+=f'E{prn:02d} {toc:%Y %m %d %H %M %S}'+''.join(f'{v:19.12E}' for v in (0,0,0))+'\n'
        for row in [(1,0,0,0),(0,.001,0,5440),(tow,0,0,0),(1,0,0,0),(0,flags,week,0),(1,health,0,0),(tow,0,0,0)]:text+='    '+''.join(f'{v:19.12E}'for v in row)+'\n'
    return text
class GalileoTest(unittest.TestCase):
    def test_receiver_retry_is_bounded_and_does_not_rescue_accuracy_failures(self):
        import tempfile,json
        from unittest.mock import patch
        import galileo_lab
        for summaries,expected_attempts,expected_directory in [
            ([dict(passed=True,validFixes=385)],1,'.'),
            ([dict(passed=False,validFixes=95),dict(passed=True,validFixes=395)],2,'tracking-20ms'),
            ([dict(passed=False,validFixes=0),dict(passed=False,validFixes=5)],2,'.'),
            ([dict(passed=False,validFixes=150)],1,'.')]:
            with tempfile.TemporaryDirectory() as tmp:
                folder=Path(tmp);calls=[];messages=[]
                def execute(command,target,log,timeout):
                    calls.append(target)
                    (target/'fix.nmea').write_text(target.name)
                with patch.object(galileo_lab,'summarize',side_effect=summaries):
                    result,nmea=galileo_lab.decode(folder/'signal.bin',folder,[1,2,3,4],[],[],90,START,execute,lambda kind,**data:messages.append(data['message']))
                self.assertEqual(len(calls),expected_attempts)
                self.assertEqual(len(messages),expected_attempts-1)
                self.assertTrue(all(a['processingMillis']>=0 and len(a['nmeaSHA256'])==64 for a in result['independentReceiverAttempts']))
                self.assertEqual(result['independentReceiverDirectory'],expected_directory)
                self.assertEqual(result['passed'],any(s['passed'] for s in summaries))
                self.assertEqual(nmea,(folder/expected_directory/'fix.nmea').read_text())
                self.assertEqual(len(json.loads((folder/'decode-attempts.json').read_text())['independentReceiverAttempts']),expected_attempts)
                if expected_attempts==2:
                    config=configparser.ConfigParser();config.read(folder/'tracking-20ms/receiver.conf')
                    self.assertEqual(config['GNSS-SDR']['Tracking_1B.extend_correlation_symbols'],'5')

    def test_failed_decode_is_saved_even_after_scheduled_start(self):
        import tempfile,json
        from unittest.mock import patch,Mock
        import galileo_lab
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);build=root/'output/bds-build-current';build.mkdir(parents=True)
            (build/'provenance.json').write_text('{}')
            profile=galileo_lab.signal_environment(DEFAULTS)['profile']
            (root/'timing.json').write_text(json.dumps({'secondsPerSceneSecond':5}))
            (root/f'timing-{profile}.json').write_text(json.dumps({'secondsPerSceneSecond':.5}))
            nav=root/'nav.rnx';nav.write_text('fixture')
            mock_config=configparser.ConfigParser();mock_config['GNSS-SDR']={}
            def execute(command,folder,log,timeout):
                if log=='build.log':(folder/'broadcast-galileo.rnx').write_text('fixture')
                if log=='generator.log':
                    with (folder/'signal.bin').open('wb') as f:f.truncate(90*galileo_lab.RATE*2)
                    (folder/log).write_text('Galileo E1 with IF fixture\n| 07 | 0 |\nTotal channels:')
            with patch.object(galileo_lab,'ROOT',root),patch.object(galileo_lab,'STATE',root/'timing.json'),patch.object(galileo_lab,'bds_lead_seconds',wraps=galileo_lab.bds_lead_seconds) as lead,patch.object(galileo_lab,'prepare',return_value=(nav,{'startUTC':START.isoformat()})),patch.object(galileo_lab,'receiver_config',return_value=mock_config),patch.object(galileo_lab,'generated_prns',return_value=[7,13,19,26]),patch.object(galileo_lab.hashlib,'file_digest',return_value=Mock(hexdigest=lambda:'fixture')):
                result=galileo_lab.run(DEFAULTS|{'duration':90},root/'case',execute,lambda *a,**k:None,planned=True)
                self.assertEqual(lead.call_args.args,(90,.5))
            saved=json.loads((root/'case/result.json').read_text())
            self.assertFalse(result['passed']);self.assertFalse(saved['hardwareOutputAllowed'])
            self.assertEqual(saved['validFixes'],0)
            profile=saved['signalEnvironment']['profile']
            self.assertTrue((root/f'timing-{profile}.json').exists())
            self.assertEqual(json.loads((root/'timing.json').read_text())['secondsPerSceneSecond'],5)
            self.assertEqual(saved['navigation']['preparationProfile'],profile)

    def test_android_navigation_matches_mac_and_rejects_invalid_inputs(self):
        import subprocess,tempfile
        root=Path(__file__).resolve().parent.parent
        source=root/'mobile-controller/src/main/java/local/hackrf/controller'
        java=Path(__import__('os').environ.get('JAVA_HOME', '/Library/Java/JavaVirtualMachines/current/Contents/Home'))/'bin'
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);helper=folder/'GalileoCheck.java'
            helper.write_text('''package local.hackrf.controller;import java.nio.file.*;import java.time.*;public class GalileoCheck{public static void main(String[] a)throws Exception{System.out.print(GalileoNavigation.convert(Files.readString(Path.of(a[0])),Files.readString(Path.of(a[1])),Instant.parse(a[2]),Integer.parseInt(a[3]),90));}}''')
            subprocess.run([str(java/'javac'),'-d',tmp,str(source/'RinexNav.java'),str(source/'GalileoNavigation.java'),str(helper)],check=True)
            command=[str(java/'java'),'-cp',tmp,'local.hackrf.controller.GalileoCheck',str(folder/'nav.rnx'),str(folder/'models.rnx')]
            for raw,model,start,offset in [(records(),models(),START,18),(records(flags=258),models(),START,18),
                    (records(health=1),models(),START,18),(records(shift=-8000),models(),START,18),
                    (records(),models().replace('GAL ','XXX '),START,18),(records(),models(),START,17),
                    (records(),models(),START+timedelta(hours=2),18)]:
                (folder/'nav.rnx').write_text(raw);(folder/'models.rnx').write_text(model)
                result=subprocess.run(command+[start.isoformat(),str(offset)],text=True,capture_output=True)
                try:expected=convert(raw,model,start,offset,90)[0]
                except ValueError:self.assertNotEqual(result.returncode,0)
                else:self.assertEqual(result.returncode,0,result.stderr);self.assertEqual(result.stdout,expected)
    def test_current_inav_and_no_epoch_rewriting(self):
        raw=records();nav,info=convert(raw,models(),START,18,90)
        self.assertEqual(info['healthyCoveredPRNs'],[1,2,3,4]);self.assertEqual(nav[nav.index('E01 '):],raw[raw.index('E01 '):])
        for bad in (records(flags=258),records(health=1),records(shift=-8000)):
            with self.assertRaises(ValueError):convert(bad,models(),START,18,90)
        with self.assertRaises(ValueError):convert(raw,models().replace('GAL ','XXX '),START,18,90)
        with self.assertRaises(ValueError):convert(raw,models(),START,17,90)
        rows=models().splitlines(True)
        for flags in (32,-1,.5):
            bad=''.join(row[:41]+f'{flags:12.4E}'+row[53:] if row.startswith('GAL ') else row for row in rows)
            with self.assertRaisesRegex(ValueError,'region flags'):convert(raw,bad,START,18,90)
    def test_shared_walking_and_receiver_signal(self):
        from bds_lab import configuration as bds
        settings=dict(DEFAULTS,duration=90)
        got,ideal,emitted,_=configuration(settings,Path('/nav'),Path('/iq'),START)
        _,a,b,_=bds(settings,Path('/nav'),Path('/iq'),START)
        np.testing.assert_array_equal(ideal,a);np.testing.assert_array_equal(emitted,b)
        self.assertEqual([x['system']for x in got['output']['systemSelect']if x['enable']],['Galileo'])
        self.assertEqual(got['output']['centerFreq'],1575.42)
        self.assertEqual(got['output']['sampleFreq'],16)
        cfg=receiver_config(Path('/iq'),[1,2,3,4])['GNSS-SDR'];self.assertEqual(cfg['Channel.signal'],'1B');self.assertIn('Galileo_E1',cfg['Tracking_1B.implementation'])
        self.assertEqual(cfg['Tracking_1B.track_pilot'],'true');self.assertEqual(cfg['Tracking_1B.extend_correlation_symbols'],'10');self.assertEqual(cfg['PVT.iono_model'],'Broadcast')
        self.assertEqual(cfg['Acquisition_1B.cboc'],'false');self.assertEqual(cfg['Acquisition_1B.acquire_pilot'],'true');self.assertEqual(cfg['Acquisition_1B.second_doppler_step'],'25')
        self.assertEqual(cfg['GNSS-SDR.use_acquisition_resampler'],'true');self.assertEqual(cfg['Acquisition_1B.max_dwells'],'5');self.assertEqual(cfg['Acquisition_1B.full_grid_search'],'true');self.assertEqual(cfg['Acquisition_1B.pfa'],'0.0001')
        with self.assertRaises(ValueError):receiver_config(Path('/iq'),[1,2,3,37])
