from datetime import datetime, timezone
from pathlib import Path
import json
import tempfile
import unittest
from unittest.mock import patch
import numpy as np
from mixed_b1c_lab import configuration, prepare
from mixed_lab import configuration as original_configuration
from walking import DEFAULTS


class MixedB1cTest(unittest.TestCase):
    def test_profile_has_explicit_limits_and_shared_truth(self):
        settings = DEFAULTS | {'duration': 90}
        start = datetime(2026, 10, 18, tzinfo=timezone.utc)
        config, ideal, emitted, preview, audit = configuration(settings, 'nav', 'iq', start)
        original, truth, motion, _ = original_configuration(settings, 'nav', 'iq', start)
        np.testing.assert_array_equal(ideal, truth)
        np.testing.assert_array_equal(emitted, motion)
        self.assertEqual(config['time'], original['time'])
        self.assertEqual(config['power'], original['power'])
        self.assertEqual([(s['system'], s.get('signal')) for s in config['output']['systemSelect'] if s['enable']], [('GPS', 'L1CA'), ('BDS', 'B1C')])
        self.assertEqual(config['output']['sampleFreq'], 8)
        self.assertEqual(config['output']['centerFreq'], 1575.42)
        self.assertEqual(audit['estimatedFileBytes'], 1_440_000_000)
        self.assertEqual(preview['estimatedFileMB'], 1440)
        self.assertLess(2*1_023_000, audit['sampleRate']/2)
        for key in ('fullQmboc', 'boc6PilotComponentPresent', 'receiverVerified', 'hardwareOutputAllowed', 'antiAliasFilteringVerified', 'passed'):
            self.assertFalse(audit[key])
        self.assertTrue(any('not a complete' in text for text in audit['limitations']))

    def test_prepare_writes_no_iq_and_preserves_gates(self):
        def navigation(folder, duration):
            path = folder/'broadcast-mixed.rnx'; path.write_text('validated navigation fixture')
            return path, {'startUTC': '2026-10-18T00:00:00+00:00'}
        with tempfile.TemporaryDirectory() as tmp, patch('mixed_navigation.prepare', side_effect=navigation):
            folder = Path(tmp)/'profile'
            audit = prepare(DEFAULTS, folder)
            self.assertEqual(audit['status'], 'configuration-only')
            self.assertFalse((folder/'signal.bin').exists())
            saved = json.loads((folder/'experiment.json').read_text())
            self.assertFalse(saved['hardwareOutputAllowed'])
            self.assertFalse(saved['receiverVerified'])
            self.assertTrue((folder/'emitted.csv').exists())
            with self.assertRaises(FileExistsError): prepare(DEFAULTS, folder)


if __name__ == '__main__':
    unittest.main()
