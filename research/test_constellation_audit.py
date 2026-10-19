import unittest
from constellation_audit import inventory


def fixture(systems):
    header = '     3.05           N: GNSS NAV DATA    M: MIXED'.ljust(60) + 'RINEX VERSION / TYPE\n'
    header += ' '.ljust(60) + 'END OF HEADER\n'
    return header + ''.join(f'{s}01 2026 10 17 00 00 00 0.0\n' + '     0.0\n' * (4 if s == 'R' else 7) for s in systems)


class ConstellationAuditTest(unittest.TestCase):
    def test_individual_and_combined_inventory(self):
        text = fixture('GCRE')
        result = inventory(text)['systems']
        self.assertEqual(len(result), 4)
        for system, name in [('G', 'GPS'), ('C', 'BDS'), ('R', 'GLONASS'), ('E', 'Galileo')]:
            single = inventory(text, [system])['systems']
            self.assertEqual(single, {name: result[name]})
            self.assertEqual(single[name]['satelliteCount'], 1)
            self.assertEqual(single[name]['recordCount'], 1)
        self.assertEqual(result['BDS']['timeScale'], 'BDT')
        self.assertFalse(result['BDS']['lastEpochNative'].endswith('Z'))

    def test_missing_system_is_explicit(self):
        self.assertEqual(inventory(fixture('G'), ['C'])['systems']['BDS']['satelliteCount'], 0)

    def test_rejects_bad_input(self):
        for text in ['', fixture('C').rsplit('\n', 2)[0], fixture('C').replace('10 17', '13 17')]:
            with self.assertRaises(ValueError):
                inventory(text)
        for systems in [[], ['C', 'C'], ['X']]:
            with self.assertRaises(ValueError):
                inventory(fixture('C'), systems)
