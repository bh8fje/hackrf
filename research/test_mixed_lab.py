from datetime import datetime, timezone
import unittest
import numpy as np
from mixed_lab import configuration, inspect_iq8, RATE, FREQUENCY_HZ, CARRIERS_HZ, MAIN_LOBE_HALF_WIDTH_HZ
from walking import DEFAULTS
from bds_lab import configuration as bds_configuration


class MixedLabTest(unittest.TestCase):
    def test_shared_motion_time_and_primary_power(self):
        start = datetime(2026, 10, 18, tzinfo=timezone.utc)
        settings = DEFAULTS | {'duration': 90}
        config, ideal, emitted, preview = configuration(settings, 'combined.rnx', 'mixed.bin', start)
        base, original_ideal, original_emitted, _ = bds_configuration(settings, 'combined.rnx', 'single.bin', start)
        np.testing.assert_array_equal(ideal, original_ideal)
        np.testing.assert_array_equal(emitted, original_emitted)
        self.assertEqual(config['time'], base['time'])
        self.assertEqual(config['trajectory'], base['trajectory'])
        self.assertEqual([(s['system'], s['signal']) for s in config['output']['systemSelect'] if s['enable']], [('GPS', 'L1CA'), ('BDS', 'B1I')])
        powers = {s['system']: s['powerValue']['value'] for s in config['power']['signalPower']}
        self.assertEqual(powers['GPS'] - powers['BDS'], 3)
        self.assertEqual(preview['estimatedFileMB'], 3240)
        from mixed_lab import BASEBAND_RATES
        self.assertEqual(90*sum(BASEBAND_RATES.values())*2, 1_368_000_000)
        self.assertFalse(preview['hardwareOutputAllowed'])
        self.assertFalse(preview['receiverPriorityGuaranteed'])

    def test_both_main_lobes_fit_nyquist(self):
        for system, carrier in CARRIERS_HZ.items():
            edge = abs(carrier - FREQUENCY_HZ) + MAIN_LOBE_HALF_WIDTH_HZ[system]
            self.assertLess(edge, RATE / 2)
        self.assertEqual(CARRIERS_HZ['GPS'] - CARRIERS_HZ['BDS'], 14_322_000)

    def test_epoch_validation_and_sample_diagnostics(self):
        for start in (datetime(2026, 10, 18), datetime(2026, 10, 18, microsecond=1, tzinfo=timezone.utc)):
            with self.assertRaises(ValueError):
                configuration(DEFAULTS, 'nav', 'iq', start)
        for raw in (b'', b'1'):
            with self.assertRaises(ValueError): inspect_iq8(raw)
        result = inspect_iq8(bytes([3, 4, 253, 252]))
        self.assertEqual(result['samples'], 2)
        self.assertEqual(result['rms'], 5)
        self.assertEqual(result['clippedComponents'], 0)
        self.assertEqual(result['dcI'], 0)
        self.assertEqual(result['dcQ'], 0)
        self.assertEqual(inspect_iq8(bytes([127, 128]))['clippedComponents'], 2)


    def test_run_never_authorizes_hardware_and_records_failure(self):
        import json
        import tempfile
        from pathlib import Path
        from unittest.mock import patch
        from mixed_lab import run
        def prepare(folder, duration):
            nav = folder/'broadcast-mixed.rnx'
            nav.write_text('independently validated navigation supplied by prepare')
            return nav, {'startUTC': '2026-10-18T00:00:00+00:00'}
        def execute(command, folder, log, timeout):
            if log.startswith('generator-'):
                scenario = json.loads(Path(command[command.index('-c')+1]).read_text())
                rate = round(scenario['output']['sampleFreq']*1e6)
                Path(scenario['output']['name']).write_bytes(bytes([3, 4]) * (60*rate))
        with tempfile.TemporaryDirectory() as tmp, patch('mixed_navigation.prepare', side_effect=prepare), patch('mixed_lab.BASEBAND_RATES', {'GPS': 13, 'BDS': 25}):
            folder = Path(tmp)/'success'
            result = run(DEFAULTS, folder, execute, lambda *args, **kwargs: None)
            self.assertEqual(result['status'], 'generated-only')
            self.assertEqual(result['estimatedFileBytes'], 60*38*2)
            self.assertEqual(set(result['components']), {'GPS', 'BDS'})
            self.assertFalse((folder/'signal.bin').exists())
            self.assertFalse((folder/'wideband-not-generated.bin').exists())
            self.assertFalse(result['relativeRfPowerVerified'])
            for system in ('GPS', 'BDS'):
                component = json.loads((folder/('scenario-'+system.lower()+'.json')).read_text())
                self.assertEqual([e['system'] for e in component['output']['systemSelect'] if e['enable']], [system])
            for key in ('passed', 'softwareReceiverVerified', 'hardwareOutputAllowed', 'rfEnabled'):
                self.assertFalse(result[key])
            def fail(*args): raise RuntimeError('generation interrupted')
            failed = Path(tmp)/'failure'
            with self.assertRaisesRegex(RuntimeError, 'interrupted'):
                run(DEFAULTS, failed, fail, lambda *args, **kwargs: None)
            metadata = json.loads((failed/'experiment.json').read_text())
            self.assertEqual(metadata['status'], 'failed')
            self.assertFalse(metadata['hardwareOutputAllowed'])
            self.assertFalse(metadata['passed'])


if __name__ == '__main__':
    unittest.main()
