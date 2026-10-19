from pathlib import Path
import tempfile
import unittest
from scenarios import add_noise, receiver_config


class ScenarioTest(unittest.TestCase):
    def test_noise_reproducibility_and_clipping(self):
        with tempfile.TemporaryDirectory() as name:
            folder = Path(name)
            source, a, b = [folder / n for n in ('source.bin', 'a.bin', 'b.bin')]
            source.write_bytes(bytes([127, 128, 0, 0]) * 2000)
            clipped = add_noise(source, a, 50, 42)
            self.assertEqual(clipped, add_noise(source, b, 50, 42))
            self.assertEqual(a.read_bytes(), b.read_bytes())
            self.assertEqual(a.stat().st_size, source.stat().st_size)
            self.assertGreater(clipped, 0)
            add_noise(source, b, 50, 43)
            self.assertNotEqual(a.read_bytes(), b.read_bytes())
            for sigma in (0, -1, float('nan')):
                with self.assertRaises(ValueError):
                    add_noise(source, a, sigma, 42)
            original = source.read_bytes()
            with self.assertRaises(ValueError):
                add_noise(source, source, 50, 42)
            self.assertEqual(source.read_bytes(), original)
            source.write_bytes(b'odd')
            with self.assertRaises(ValueError):
                add_noise(source, a, 50, 42)

    def test_config_replaces_prns_and_uses_file_only(self):
        config = receiver_config(Path('/tmp/test.iq'), [1, 7, 9, 32])['GNSS-SDR']
        self.assertEqual(config['Channels_1C.count'], '4')
        self.assertEqual(config['Channel3.satellite'], '32')
        self.assertNotIn('Channel4.satellite', config)
        self.assertEqual(config['SignalSource.implementation'], 'File_Signal_Source')
        self.assertEqual(config['SignalSource.filename'], str(Path('/tmp/test.iq').resolve()))
        self.assertEqual(config['PVT.flag_rtcm_server'], 'false')
        for prns in ([], [1,1], [0], [33]):
            with self.assertRaises(ValueError):
                receiver_config(Path('/tmp/test.iq'), prns)


if __name__ == '__main__':
    unittest.main()
