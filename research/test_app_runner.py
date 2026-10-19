"""Exercise the actual JSON bridge used by the native app, without RF or I/Q jobs."""
import json
import hashlib
import tempfile
from pathlib import Path
import subprocess
import sys
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch

from walking import DEFAULTS
import app_runner


class AppProtocolTest(unittest.TestCase):
    def test_late_preparation_retries_once_with_new_folder(self):
        folder=Path('/test/job')
        with patch.object(app_runner,'run_once',side_effect=[app_runner.PreparationLate(),{'passed':True,'folder':'/test/job-retry'}]) as execute,patch.object(app_runner,'emit'):
            result=app_runner.run(DEFAULTS.copy(),folder)
            self.assertEqual(result['folder'],'/test/job-retry')
            self.assertEqual(execute.call_args_list[1].args[1],Path('/test/job-retry'))
        with patch.object(app_runner,'run_once',side_effect=app_runner.PreparationLate()) as execute,patch.object(app_runner,'emit'):
            with self.assertRaises(ValueError):app_runner.run(DEFAULTS.copy(),folder)
            self.assertEqual(execute.call_count,2)

    def test_generator_uses_short_names_in_long_scene_directories(self):
        class Captured(Exception): pass
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory) / ('long-scene-' + 'x'*110)
            timing = {'startUTC': '2026-10-16T15:00:00+00:00', 'startGPS': '2026/10/16,15:00:18'}
            def execute(command, cwd, logfile, timeout):
                if logfile == 'generator.log':
                    self.assertEqual(cwd, folder)
                    self.assertEqual(command[command.index('-e')+1], 'broadcast-gps.rnx')
                    self.assertEqual(command[command.index('-u')+1], 'emitted.csv')
                    self.assertEqual(command[command.index('-o')+1], 'signal.bin')
                    raise Captured()
            with patch.object(app_runner, 'prepare', return_value=(folder/'broadcast-gps.rnx', timing)), patch.object(app_runner, 'execute', side_effect=execute), patch.object(app_runner, 'emit'), patch.object(app_runner.shutil, 'disk_usage', return_value=type('Space', (), {'free': 10**12})()):
                with self.assertRaises(Captured): app_runner.run(DEFAULTS.copy(), folder)

    def test_preview_preserves_offline_size_and_exposes_stream_rate(self):
        for action,rate,mbps,size in [('preview',2600000,5.2,468),('bds-preview',5000000,10,900)]:
            result=subprocess.run([sys.executable,str(Path(app_runner.__file__)),action],input=json.dumps(dict(DEFAULTS,duration=90)),text=True,capture_output=True,check=True)
            preview=json.loads(result.stdout)['preview']
            self.assertEqual(preview['estimatedFileMB'],size)
            self.assertEqual(preview['streamSampleRate'],rate)
            self.assertEqual(preview['streamMBps'],mbps)

    def test_one_click_uses_native_stream_without_full_sample_or_decode(self):
        payload={'settings':dict(DEFAULTS,duration=90),'gain':47,'amplifier':True}
        for system in ('gps','bds'):
            folder=Path('/test/'+system)
            with patch('single_live.run',return_value={'verificationKind':'hardware-stream'}) as stream,patch.object(app_runner,'run') as offline,patch.object(app_runner,'transmit') as send:
                result=app_runner.start_simulation(payload,folder,system=system)
                stream.assert_called_once_with(payload,folder,app_runner.emit,system=system)
                self.assertEqual(result['verificationKind'],'hardware-stream')
                offline.assert_not_called();send.assert_not_called()
        with patch('single_live.run') as stream:
            with self.assertRaises(ValueError):app_runner.start_simulation(dict(payload,gain=48),Path('/test/job'))
            stream.assert_not_called()

    def test_cancelled_systems_reject_before_generation(self):
        for system in ('galileo','glonass'):
            with patch.object(app_runner,'execute') as execute, patch.object(app_runner,'transmit') as send:
                with self.assertRaises(ValueError):
                    app_runner.start_simulation({},Path('/test/cancelled'),system=system)
                execute.assert_not_called()
                send.assert_not_called()

    def test_phone_sync_is_atomic_and_scoped_to_one_device(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)/'jobs/job'
            folder.mkdir(parents=True)
            (folder/'phone-scenario.json').write_text('{"id":"fixture","phase":"prepared"}')
            with patch.object(app_runner.shutil, 'which', return_value='/test/adb'), patch.object(app_runner.subprocess, 'check_output', return_value='List of devices attached\nphone device\n'), patch.object(app_runner.subprocess, 'run',return_value=subprocess.CompletedProcess([],0,stdout='Result: Bundle[{accepted=true}]')) as run, patch.object(app_runner, 'emit'):
                app_runner.sync_phone(folder, 'armed')
                self.assertEqual(run.call_count, 1)
                import shlex
                remote=shlex.split(run.call_args.args[0][-1])
                self.assertEqual(remote[:2],['content','call'])
                self.assertEqual(remote[remote.index('--method')+1],'setScenario')
                self.assertEqual(json.loads(remote[remote.index('--arg')+1])['phase'],'armed')

    def test_phone_sync_does_not_choose_between_multiple_devices(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)/'jobs/job'
            folder.mkdir(parents=True)
            (folder/'phone-scenario.json').write_text('{}')
            with patch.object(app_runner.shutil, 'which', return_value='/test/adb'), patch.object(app_runner.subprocess, 'check_output', return_value='List of devices attached\na device\nb device\n'), patch.object(app_runner.subprocess, 'run') as run, patch.object(app_runner, 'emit'):
                app_runner.sync_phone(folder, 'prepared')
                run.assert_not_called()

    def test_prepared_scenario_cannot_replace_active_transmission(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)/'jobs/job'
            folder.mkdir(parents=True)
            (folder/'phone-scenario.json').write_text('{}')
            with patch.object(app_runner.shutil, 'which', return_value='/test/adb'), patch.object(app_runner.fcntl, 'flock', side_effect=BlockingIOError), patch.object(app_runner.subprocess, 'run') as run, patch.object(app_runner.subprocess, 'check_output') as devices, patch.object(app_runner, 'emit'):
                app_runner.sync_phone(folder, 'prepared')
                devices.assert_not_called()
                run.assert_not_called()

    def test_scheduled_wait_rechecks_deadline(self):
        with patch.object(app_runner, 'launch_delay', return_value=0.2) as check, patch.object(app_runner.time, 'monotonic', side_effect=[0, 0, 0.2]), patch.object(app_runner.time, 'sleep') as sleep, patch.object(app_runner, 'emit'):
            app_runner.wait_until_start({})
            sleep.assert_called_once_with(0.2)
            self.assertEqual(check.call_count, 2)

    def test_scheduled_wait_can_stop_before_device_access(self):
        with patch.object(app_runner, 'launch_delay', return_value=1), patch.object(app_runner.time, 'monotonic', return_value=0), patch.object(app_runner.time, 'sleep', side_effect=SystemExit(130)), patch.object(app_runner, 'emit'), patch.object(app_runner, 'execute') as execute:
            with self.assertRaises(SystemExit):
                app_runner.wait_until_start({})
            execute.assert_not_called()

    def request(self, payload):
        process = subprocess.run(
            [sys.executable, str(Path(__file__).with_name('app_runner.py')), 'preview'],
            input=payload, capture_output=True, text=True, timeout=15)
        self.assertEqual(process.stderr, '')
        return process.returncode, json.loads(process.stdout)

    def test_preview_multiple_positions(self):
        for latitude, longitude in ((39.9, 116.4), (-33.86, 151.21), (51.5, -0.12)):
            with self.subTest(latitude=latitude, longitude=longitude):
                settings = dict(DEFAULTS, latitude=latitude, longitude=longitude,
                                coordinateSystem='WGS-84')
                code, event = self.request(json.dumps(settings))
                self.assertEqual(code, 0)
                self.assertEqual(event['type'], 'preview')
                self.assertFalse(event['preview']['rfEnabled'])
                self.assertEqual(event['preview']['wgs84Longitude'], longitude)
                self.assertLessEqual(len(event['preview']['idealPath']), 240)

    def test_untrusted_input_rejected(self):
        payloads = ['[]', '{', ' ' * 16385,
                    json.dumps(dict(DEFAULTS, speed=-1)),
                    json.dumps(dict(DEFAULTS, duration=90.5)),
                    json.dumps(dict(DEFAULTS, rfEnabled=True)),
                    json.dumps(dict(DEFAULTS, latitude=float('nan')))]
        for payload in payloads:
            with self.subTest(payload=payload[:80]):
                code, event = self.request(payload)
                self.assertEqual(code, 1)
                self.assertEqual(event['type'], 'error')
                self.assertTrue(event['message'])


class TransmissionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.folder = self.root / 'output/app-jobs/fixture'
        self.folder.mkdir(parents=True)
        self.iq = self.folder / 'signal.bin'
        with self.iq.open('wb') as stream:
            stream.truncate(61 * 5_200_000)
        with self.iq.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        now = datetime.now(timezone.utc)
        navigation = {'mode': 'current-broadcast', 'startUTC': (now+timedelta(seconds=30)).isoformat(), 'broadcastProducedAtUTC': now.isoformat()}
        (self.folder / 'result.json').write_text(json.dumps({'passed': True, 'iqSHA256': digest, 'navigation': navigation}))
        (self.folder / 'settings.json').write_text(json.dumps(dict(DEFAULTS, duration=61)))
        self.payload = {'folder': str(self.folder), 'gain': 47, 'amplifier': True}

    def test_bounded_command_and_completion(self):
        def fake_execute(command, folder, logfile, timeout):
            self.assertEqual(command[command.index('-n') + 1], str(61*2_600_000))
            self.assertEqual(command[command.index('-p') + 1], '0')
            self.assertEqual(command[command.index('-x') + 1], '47')
            self.assertEqual(command[command.index('-a') + 1], '1')
            self.assertNotIn('-R', command)
            self.assertEqual(timeout, 66)
            (folder / logfile).write_text('hackrf_stop_tx() done\n')
        with patch.object(app_runner, 'ROOT', self.root), patch.object(app_runner, 'wait_until_start'), patch.object(app_runner, 'execute', side_effect=fake_execute), patch.object(app_runner, 'emit'):
            app_runner.transmit(self.payload)
        record = json.loads(next(self.folder.glob('transmit-*.json')).read_text())
        self.assertEqual(record['status'], 'completed')
        self.assertFalse(record['phoneReceptionVerified'])

    def test_changed_file_rejected_before_device_access(self):
        with self.iq.open('r+b') as stream:
            stream.write(b'changed')
        with patch.object(app_runner, 'ROOT', self.root), patch.object(app_runner, 'execute') as execute:
            with self.assertRaisesRegex(ValueError, 'I/Q'):
                app_runner.transmit(self.payload)
            execute.assert_not_called()

    def test_maximum_duration_command_without_rf(self):
        (self.folder / 'settings.json').write_text(json.dumps(dict(DEFAULTS, duration=180)))
        with self.iq.open('wb') as stream:
            stream.truncate(180*5_200_000)
        with self.iq.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        result = json.loads((self.folder / 'result.json').read_text())
        result['iqSHA256'] = digest
        (self.folder / 'result.json').write_text(json.dumps(result))
        def fake_execute(command, folder, logfile, timeout):
            self.assertEqual(command[command.index('-n')+1], '468000000')
            self.assertEqual(timeout, 185)
            self.assertNotIn('-R', command)
            (folder / logfile).write_text('hackrf_stop_tx() done\n')
        with patch.object(app_runner, 'ROOT', self.root), patch.object(app_runner, 'wait_until_start'), patch.object(app_runner, 'execute', side_effect=fake_execute), patch.object(app_runner, 'emit'):
            app_runner.transmit(self.payload)
        record = json.loads(next(self.folder.glob('transmit-*.json')).read_text())
        self.assertEqual(record['durationSeconds'], 180)

    def test_cancel_record_and_parameter_bounds(self):
        with patch.object(app_runner, 'ROOT', self.root), patch.object(app_runner, 'wait_until_start'), patch.object(app_runner, 'execute', side_effect=SystemExit(130)), patch.object(app_runner, 'emit'):
            with self.assertRaises(SystemExit):
                app_runner.transmit(self.payload)
        record = json.loads(next(self.folder.glob('transmit-*.json')).read_text())
        self.assertEqual(record['status'], 'stopped')
        for value in (-1, 48, True, 3.5):
            with self.subTest(gain=value), self.assertRaises(ValueError):
                app_runner.transmit(dict(self.payload, gain=value))


if __name__ == '__main__':
    unittest.main()
