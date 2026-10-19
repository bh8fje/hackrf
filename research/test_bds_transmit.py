"""Exercise BDS preflight/command construction with the hardware process mocked."""
import hashlib,json,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import app_runner
from bds_navigation import convert
from test_bds_navigation import START,FIX
from walking import DEFAULTS

class BdsTransmitTest(unittest.TestCase):
    def test_cancelled_archives_reject_before_wait_or_device(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);folder=root/'output/app-jobs/check';folder.mkdir(parents=True)
            for signal in ('GAL-E1','GLO-G1'):
                (folder/'result.json').write_text(json.dumps({'passed':True,'signal':signal}))
                with patch.object(app_runner,'ROOT',root),patch.object(app_runner,'execute') as device,patch.object(app_runner,'launch_delay') as delay:
                    with self.assertRaisesRegex(ValueError,'已取消'):
                        app_runner.transmit({'folder':str(folder),'gain':0,'amplifier':False})
                    device.assert_not_called()
                    delay.assert_not_called()

    def test_scheduled_bds_command_and_rejections_without_device_access(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);folder=root/'output/app-jobs/check';folder.mkdir(parents=True)
            settings=dict(DEFAULTS,duration=60)
            (folder/'settings.json').write_text(json.dumps(settings))
            nav,_=convert((FIX/'bds-broadcast.rnx').read_text(),(FIX/'bds-models.rnx').read_text(),START,18,60)
            (folder/'broadcast-bds.rnx').write_text(nav)
            (folder/'source-iers.dat').write_bytes((app_runner.ROOT.parent/'mobile-controller/data/Leap_Second.dat').read_bytes())
            for name in ('scenario.json','emitted.csv'):(folder/name).write_text('fixture')
            iq=folder/'signal.bin'
            with iq.open('wb') as stream:stream.truncate(600_000_000)
            with iq.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
            result={'passed':True,'signal':'BDS-B1I','hardwareOutputAllowed':True,'sampleRate':5000000,'frequencyHz':1561098000,'durationSeconds':60,
                    'iqSHA256':digest,'navigation':{'mode':'current-broadcast','startUTC':START.isoformat(),'broadcastProducedAtUTC':START.isoformat()},
                    'preparedFilesSHA256':{name:hashlib.sha256((folder/name).read_bytes()).hexdigest() for name in ('scenario.json','broadcast-bds.rnx','emitted.csv')}}
            payload={'folder':str(folder),'gain':0,'amplifier':False}
            report=['M0 mode 4 requested 4 error 0\nhackrf_stop_tx() done\n600000000 bytes transferred by M0\n0 underruns, longest 0 bytes\n']
            def execute(command,cwd,logfile,timeout):
                self.assertEqual(command[command.index('-f')+1],'1561098000')
                self.assertEqual(command[command.index('-s')+1],'5000000')
                self.assertEqual(command[command.index('-n')+1],'300000000')
                self.assertEqual(command[command.index('-a')+1],'0')
                (cwd/logfile).write_text(report[0])
            with patch.object(app_runner,'ROOT',root),patch.object(app_runner,'execute',side_effect=execute) as device,patch.object(app_runner,'launch_delay',return_value=0),patch.object(app_runner,'wait_until_start'),patch.object(app_runner,'emit'):
                (folder/'result.json').write_text(json.dumps(result));app_runner.transmit(payload);device.assert_called_once()
                for changes in ({'hardwareOutputAllowed':False},{'passed':False},{'sampleRate':2600000},{'frequencyHz':1575420000},{'durationSeconds':90},{'iqSHA256':'bad'}):
                    device.reset_mock();(folder/'result.json').write_text(json.dumps(result|changes))
                    with self.assertRaises(ValueError):app_runner.transmit(payload)
                    device.assert_not_called()
                for bad in ('M0 mode 2 requested 2 error 0\nhackrf_stop_tx() done\n600000000 bytes transferred by M0\n0 underruns','M0 mode 4 requested 4 error 2\nhackrf_stop_tx() done\n600000000 bytes transferred by M0\n0 underruns','hackrf_stop_tx() done','hackrf_stop_tx() done\n123 bytes transferred by M0\n0 underruns','hackrf_stop_tx() done\n600000000 bytes transferred by M0\n3 underruns'):
                    device.reset_mock();report[0]=bad;(folder/'result.json').write_text(json.dumps(result))
                    with self.assertRaises(ValueError):app_runner.transmit(payload)
                    device.assert_called_once()
                failed=json.loads(max(folder.glob('transmit-*.json'),key=lambda p:p.stat().st_mtime_ns).read_text())
                self.assertEqual(failed['status'],'failed');self.assertEqual(failed['underruns'],3)
                (folder/'result.json').write_text(json.dumps(result));(folder/'emitted.csv').write_text('modified');device.reset_mock()
                with self.assertRaises(ValueError):app_runner.transmit(payload)
                device.assert_not_called()
