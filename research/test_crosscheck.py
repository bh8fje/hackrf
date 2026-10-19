import unittest
from crosscheck import gga, ecef


class CrosscheckTest(unittest.TestCase):
    def test_reference_sentence_and_height(self):
        fix = gga('$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47')
        self.assertAlmostEqual(fix[0], 48.1173)
        self.assertAlmostEqual(fix[1], 11.5166666667)
        self.assertAlmostEqual(fix[2], 592.3)
        self.assertEqual(ecef((0,0,0)), (6378137.,0.,0.))

    def test_bad_checksum(self):
        with self.assertRaises(ValueError):
            gga('$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*00')
        self.assertIsNone(gga('$GPRMC,irrelevant'))

    def test_constellation_talkers(self):
        for talker in ('GA','GL','GB','BD','GN'):
            body=talker+'GGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,'
            checksum=0
            for c in body:checksum^=ord(c)
            self.assertIsNotNone(gga(f'${body}*{checksum:02X}'))
    def test_no_fix_and_invalid_coordinates(self):
        def sentence(body):
            checksum = 0
            for char in body:
                checksum ^= ord(char)
            return f'${body}*{checksum:02X}'
        no_fix = 'GPGGA,123519,,,,,0,00,99.9,,M,,M,,'
        self.assertIsNone(gga(sentence(no_fix)))
        for body in ('GPGGA,123519,4867.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,',
                     'GPGGA,123519,4807.038,X,01131.000,E,1,08,0.9,545.4,M,46.9,M,,',
                     'GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,F,46.9,M,,'):
            with self.assertRaises(ValueError):
                gga(sentence(body))


if __name__ == '__main__':
    unittest.main()
