import unittest
from satellite_status import scene_satellites
class SatelliteStatusTest(unittest.TestCase):
    def test_geometry_is_parsed_and_invalid_rows_ignored(self):
        text='05  132.8  38.4  22193165.8   4.1\n10  314.3   7.5  25151827.0   4.3\n99  50.0  40.0  20000000.0  2.0\n11  0.0 -1.0 20000000.0 2.0\nTime into run = 10.0\n'
        self.assertEqual(scene_satellites(text),['G05 方位132.8° 仰角38.4°','G10 方位314.3° 仰角7.5°'])
