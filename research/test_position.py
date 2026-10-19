import copy
import hashlib
import json
from pathlib import Path
import unittest
import numpy as np
from position import C, OMEGA, ephemeris, satellite, solve, geodetic, week_delta
from fix import measurements

ROOT = Path(__file__).resolve().parent


class PositionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = json.loads((ROOT / 'output/position.json').read_text())
        cls.channel = next(c for c in cls.report['channels'] if c['prn'] == 24)

    def test_ephemeris_against_rinex(self):
        lines = (ROOT / 'gps-sdr-sim/brdc0010.22n').read_text().splitlines()
        row = next(i for i, line in enumerate(lines) if line.startswith('24 22  1  1  0  0'))
        clock = [float(lines[row][22 + 19*j:41 + 19*j].replace('D', 'E')) for j in range(3)]
        orbit = [[float(lines[row + i][3 + 19*j:22 + 19*j].replace('D', 'E'))
                  for j in range(4)] for i in range(1, 8)]
        e = ephemeris(self.channel['subframes'])
        expected = {'af0': clock[0], 'af1': clock[1], 'af2': clock[2],
                    'iode': orbit[0][0], 'crs': orbit[0][1], 'dn': orbit[0][2], 'm0': orbit[0][3],
                    'cuc': orbit[1][0], 'ecc': orbit[1][1], 'cus': orbit[1][2], 'sqrt_a': orbit[1][3],
                    'toe': orbit[2][0], 'cic': orbit[2][1], 'omega0': orbit[2][2], 'cis': orbit[2][3],
                    'i0': orbit[3][0], 'crc': orbit[3][1], 'argp': orbit[3][2], 'omega_dot': orbit[3][3],
                    'idot': orbit[4][0], 'tgd': orbit[5][2]}
        quanta = {'af0': 2**-31, 'af1': 2**-43, 'af2': 2**-55, 'crs': 2**-5,
                  'dn': np.pi * 2**-43, 'm0': np.pi * 2**-31, 'cuc': 2**-29,
                  'ecc': 2**-33, 'cus': 2**-29, 'sqrt_a': 2**-19,
                  'cic': 2**-29, 'omega0': np.pi * 2**-31, 'cis': 2**-29,
                  'i0': np.pi * 2**-31, 'crc': 2**-5, 'argp': np.pi * 2**-31,
                  'omega_dot': np.pi * 2**-43, 'idot': np.pi * 2**-43, 'tgd': 2**-31}
        for key, value in expected.items():
            with self.subTest(field=key):
                self.assertAlmostEqual(e[key], value, delta=quanta.get(key, 0) + 1e-11)

    def test_reject_bad_ephemeris(self):
        for field, value in (('valid_words', 9), ('next_tow_seconds', 100)):
            frames = copy.deepcopy(self.channel['subframes'])
            frames[0][field] = value
            with self.assertRaises(ValueError):
                ephemeris(frames)
        frames = copy.deepcopy(self.channel['subframes'])
        frames[2]['data_words_hex'][9] = '00e542'
        with self.assertRaises(ValueError):
            ephemeris(frames)
        with self.assertRaises(ValueError):
            satellite(self.channel['ephemeris'], self.channel['ephemeris']['toe'] + 7201)

    def test_circular_orbit_and_clock(self):
        e = self.channel['ephemeris'].copy()
        for key in ('ecc', 'm0', 'dn', 'argp', 'cus', 'cuc', 'crs', 'crc', 'i0',
                    'idot', 'cis', 'cic', 'omega0', 'omega_dot', 'toe', 'toc', 'af1', 'af2', 'tgd'):
            e[key] = 0.0
        e['af0'] = 0.0001
        xyz, clock = satellite(e, 0)
        np.testing.assert_allclose(xyz, [e['sqrt_a']**2, 0, 0], atol=1e-8)
        self.assertAlmostEqual(clock, 0.0001)
        self.assertEqual(week_delta(604801), 1)

    def test_solver_known_geometry_and_bias(self):
        satellites = 26_560_000 * np.array([[1,0,0], [0,1,0], [0,0,1],
                                           [-1,0,0], [0,-1,0], [0,0,-1]], dtype=float)
        truth = np.array([3_000_000., -4_000_000., 3_500_000.])
        ranges = np.linalg.norm(satellites - truth, axis=1)
        for _ in range(5):
            angle = OMEGA * ranges / C
            rotated = satellites.copy()
            rotated[:, 0] = np.cos(angle) * satellites[:, 0] + np.sin(angle) * satellites[:, 1]
            rotated[:, 1] = -np.sin(angle) * satellites[:, 0] + np.cos(angle) * satellites[:, 1]
            ranges = np.linalg.norm(rotated - truth, axis=1)
        state, residual = solve(satellites, ranges + 123456)
        np.testing.assert_allclose(state, [*truth, 123456], atol=0.001)
        self.assertLess(np.max(abs(residual)), 0.001)
        np.testing.assert_allclose(geodetic([6378137,0,0]), [0,0,0], atol=1e-6)
        for positions, pseudoranges in ((satellites[:3], ranges[:3]),
                                       (np.tile(satellites[0], (4, 1)), ranges[:4]),
                                       (satellites, np.full(6, np.nan))):
            with self.assertRaises(ValueError):
                solve(positions, pseudoranges)

    def test_measurement_phase_wrap(self):
        # Known code delay of 67.4 ms at a nominal code rate.
        phases = np.full(19000, (-0.0004 * 1_023_000) % 1023)
        frame = {'start_ms': 67, 'next_tow_seconds': 518406}
        self.assertAlmostEqual(measurements(phases, frame), 518414.9326, places=8)
        with self.assertRaises(ValueError):
            measurements(phases, frame, 0)

    def test_actual_position(self):
        with (ROOT / 'output/navigation.bin').open('rb') as stream:
            self.assertEqual(hashlib.file_digest(stream, 'sha256').hexdigest(),
                             self.report['fixture_sha256'])
        self.assertGreaterEqual(len(self.report['channels']), 4)
        self.assertLess(self.report['position_error_3d_m'], 20)
        self.assertLess(self.report['residual_rms_m'], 5)
        self.assertTrue(self.report['static_position_verified'])


if __name__ == '__main__':
    unittest.main()
