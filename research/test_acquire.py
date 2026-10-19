import unittest
from pathlib import Path
import numpy as np
from acquire import RATE, N, ca_code, acquire


class AcquisitionTest(unittest.TestCase):
    def test_code_reference(self):
        code = ca_code(1)
        # IS-GPS-200 Table 3-Ia: PRN 1 first ten chips, octal 1440.
        self.assertEqual(''.join(str(int(x < 0)) for x in code[:10]), '1100100000')
        correlation = np.fft.ifft(abs(np.fft.fft(code)) ** 2).real
        self.assertAlmostEqual(correlation[0], 1023)
        self.assertLessEqual(abs(correlation[1:]).max(), 65.00001)

    def test_known_delay_and_doppler(self):
        code = ca_code(1)[np.arange(N) * 1023 // N]
        samples = np.tile(np.roll(code, 417), 10)
        samples = samples * np.exp(2j * np.pi * 1500 * np.arange(N * 10) / RATE)
        result = acquire(samples, [1])[0]
        self.assertTrue(result['detected'])
        self.assertEqual(result['doppler_hz'], 1500)
        self.assertEqual(result['code_phase_samples'], 417)

    def test_noise_and_zero(self):
        rng = np.random.default_rng(42)
        for samples in (np.zeros(N * 10),
                        rng.normal(size=N * 10) + 1j * rng.normal(size=N * 10)):
            self.assertFalse(acquire(samples, [1])[0]['detected'])

    def test_invalid_input(self):
        for samples in (np.zeros(10), np.full(N * 10, np.nan)):
            with self.assertRaises(ValueError):
                acquire(samples, [1])
        with self.assertRaises(ValueError):
            ca_code(0)

    def test_offline_fixture(self):
        fixture = Path(__file__).resolve().parent / 'output' / 'smoke.bin'
        with fixture.open('rb') as stream:
            raw = np.frombuffer(stream.read(N * 10 * 2), dtype=np.int8)
        signal = raw[::2].astype(float) + 1j * raw[1::2]
        found = [r['prn'] for r in acquire(signal) if r['detected']]
        self.assertEqual(found, [5, 10, 12, 13, 14, 15, 18, 20, 23, 24, 28])


if __name__ == '__main__':
    unittest.main()
