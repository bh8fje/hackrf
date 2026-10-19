from pathlib import Path
import unittest
from mixed_receiver import receiver_config,mixed_solution_evidence,generated_prns,mixed_nmea_solution_evidence

class MixedReceiverTest(unittest.TestCase):
    def test_two_bands_share_one_recording(self):
        c=receiver_config(Path('/tmp/mixed.bin'),[1,2,3,4],[6,7,8,9,10])['GNSS-SDR']
        self.assertEqual(c['SignalSource0.filename'],c['SignalSource1.filename'])
        self.assertEqual(c['InputFilter0.IF'],'7672500');self.assertEqual(c['InputFilter1.IF'],'-6649500')
        self.assertEqual(c['GNSS-SDR.internal_fs_sps'],'6000000')
        self.assertEqual(c['Channels_1C.count'],'4');self.assertEqual(c['Channels_B1.count'],'5');self.assertEqual(c['Channels.in_acquisition'],'9')
        self.assertEqual(c['PVT.threshold_reject_gdop'],'6')
        self.assertEqual(c['Channel3.signal'],'1C');self.assertEqual(c['Channel4.signal'],'B1')
        self.assertEqual(c['Channel4.RF_channel_ID'],'1')
        self.assertNotIn('Channel.signal',c);self.assertNotIn('Channels_1C.countX',c)
        with self.assertRaises(ValueError):receiver_config('x',[1,1,2,3],[6,7,8,9])
    def test_generator_sections_do_not_leak_prns(self):
        log='GPS L1CA with IF +7161kHz:\n| 01 | +20 | 02 | -30 |\nBeiDou B1I with IF -7161kHz:\n| 06 | 40 | 07 | 50 |\nTotal channels: 4'
        self.assertEqual(generated_prns(log),{'gps':[1,2],'bds':[6,7]})

    def test_single_constellation_or_different_epochs_cannot_pass(self):
        gps='$SAT,2440,10.0,G01,1,0,0,0,0,1,40'
        bds='$SAT,2440,10.0,C06,1,0,0,0,0,1,37'
        self.assertFalse(mixed_solution_evidence(gps,10)['passed'])
        self.assertFalse(mixed_solution_evidence(gps+'\n'+bds.replace('10.0','11.0'),10)['passed'])
        self.assertFalse(mixed_solution_evidence(gps+'\n'+bds,0)['passed'])
        self.assertTrue(mixed_solution_evidence(gps+'\n'+bds,10)['passed'])
        self.assertFalse(mixed_solution_evidence(gps+'\n'+bds.replace(',1,37',',0,37'),10)['passed'])

class MixedNmeaEvidenceTest(unittest.TestCase):
    @staticmethod
    def nmea(body):
        checksum=0
        for char in body:checksum^=ord(char)
        return '$'+body+'*%02X'%checksum

    def test_same_solution_uses_both_constellations(self):
        n=self.nmea
        gga=n('GPGGA,091646.00,3039.48,N,10406.22,E,1,10,0.9,400.0,M,0,M,,')
        gps=n('GPGSA,A,3,02,04,07,08,09,,,,,,,,1.1,0.9,0.8,1')
        bds=n('BDGSA,A,3,19,21,22,27,30,,,,,,,,1.1,0.9,0.8,3')
        both='\n'.join([gga,gps,bds])
        self.assertTrue(mixed_nmea_solution_evidence(both)['passed'])
        self.assertFalse(mixed_nmea_solution_evidence('\n'.join([gga,gps]))['passed'])
        self.assertFalse(mixed_nmea_solution_evidence('\n'.join([gga,gps,gga,bds]))['passed'])
        self.assertFalse(mixed_nmea_solution_evidence(both.replace('091646','091647'))['passed'])
        invalid=n('GPGGA,091646.00,3039.48,N,10406.22,E,0,10,0.9,400.0,M,0,M,,')
        self.assertFalse(mixed_nmea_solution_evidence('\n'.join([invalid,gps,bds]))['passed'])
        self.assertFalse(mixed_nmea_solution_evidence('\n'.join([gps,bds]))['passed'])
        gps_sbas=n('GPGSA,A,3,33,35,,,,,,,,,,,1.1,0.9,0.8,1')
        self.assertFalse(mixed_nmea_solution_evidence('\n'.join([gga,gps_sbas,bds]))['passed'])

if __name__=='__main__':unittest.main()
