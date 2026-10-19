from pathlib import Path
import json
import unittest
import numpy as np
from acquire import RATE, N, ca_code
from track import track
from telemetry import decode_word, integer, synchronize


class NavigationTest(unittest.TestCase):
    def test_parity_vectors_and_bit_errors(self):
        for value, previous in ((0x22c00012, (0, 0)), (0x1d3fffed, (1, 1))):
            bits = [int(x) for x in f'{value:030b}']
            self.assertEqual(integer(decode_word(bits, previous)), 0x8b0000)
            for index in range(30):
                damaged = bits.copy()
                damaged[index] ^= 1
                with self.assertRaises(ValueError):
                    decode_word(damaged, previous)

    def test_reject_invalid_words_and_prompts(self):
        for bits in ([0] * 29, [2] * 30):
            with self.assertRaises(ValueError):
                decode_word(bits, (0, 0))
        for prompts in (np.zeros(10), np.full(7000, np.nan)):
            with self.assertRaises(ValueError):
                synchronize(prompts)
        self.assertEqual(synchronize(np.zeros(7000)), [])
        rng = np.random.default_rng(44)
        self.assertEqual(synchronize(rng.normal(size=7000)), [])

    def test_tracking_known_carrier(self):
        length = 200 * N
        time = np.arange(length) / RATE
        chips = ca_code(24)[np.floor(-417 * 1023 / N +
                1_023_000 * (1 + 1500 / 1_575_420_000) * time).astype(int) % 1023]
        signal = 40 * chips * np.exp(2j * np.pi * 1500 * time)
        raw = np.empty(length * 2, dtype=np.int8)
        raw[::2], raw[1::2] = np.rint(signal.real), np.rint(signal.imag)
        prompts, frequency, error, phases = track(raw, {'prn': 24, 'detected': True,
                                      'doppler_hz': 1500, 'code_phase_samples': 417})
        self.assertLess(abs(frequency[-1] - 1500), 1)
        self.assertLess(np.sqrt(np.mean(error[-100:] ** 2)), 0.02)
        self.assertGreater(np.mean(abs(prompts[-100:])), 50_000)
        with self.assertRaises(ValueError):
            track(np.zeros(0, dtype=np.int8), {})

    def test_tracking_with_coarse_doppler_error_and_data_bits(self):
        length = 200 * N
        time = np.arange(length) / RATE
        for offset in (-260, -240, 240, 260):
            with self.subTest(offset_hz=offset):
                frequency = 1500 + offset
                chips = ca_code(24)[np.floor(-417 * 1023 / N +
                    1_023_000 * (1 + frequency / 1_575_420_000) * time).astype(int) % 1023]
                # Include a bit transition in the initial ten milliseconds.
                bits = 1 - 2 * (((np.arange(length) + 5 * N) // (20 * N)) % 2)
                signal = 40 * chips * bits * np.exp(1j * (0.7 + 2 * np.pi * frequency * time))
                raw = np.empty(length * 2, dtype=np.int8)
                raw[::2], raw[1::2] = np.rint(signal.real), np.rint(signal.imag)
                prompts, frequencies, errors, phases = track(raw, {'prn': 24, 'detected': True,
                    'doppler_hz': 1500, 'code_phase_samples': 417})
                self.assertLess(abs(frequencies[-1] - frequency), 1)
                self.assertLess(np.sqrt(np.mean(errors[-100:] ** 2)), 0.02)

    def test_formerly_unlocked_channels(self):
        path = Path(__file__).resolve().parent / 'output' / 'position.json'
        report = json.loads(path.read_text())
        accepted = {c['prn']: c for c in report['channels']}
        for prn in (5, 14, 23):
            with self.subTest(prn=prn):
                self.assertIn(prn, set(accepted))
                self.assertEqual([f['subframe_id'] for f in accepted[prn]['subframes']], [1, 2, 3])

    def test_navigation_fixture(self):
        path = Path(__file__).resolve().parent / 'output' / 'tracking.npz'
        with np.load(path) as data:
            frames = synchronize(data['prompts'])
            self.assertLess(np.sqrt(np.mean(data['errors'][-1000:] ** 2)), 0.1)
        self.assertEqual([f['subframe_id'] for f in frames], [1, 2, 3])
        self.assertEqual([f['next_tow_seconds'] for f in frames], [518406, 518412, 518418])
        self.assertEqual([f['valid_words'] for f in frames], [10, 10, 10])
        self.assertEqual(np.diff([f['start_ms'] for f in frames]).tolist(), [6000, 6000])


if __name__ == '__main__':
    unittest.main()
