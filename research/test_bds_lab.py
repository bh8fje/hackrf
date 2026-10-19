from pathlib import Path
import math
import unittest
import numpy as np
from bds_lab import configuration, generated_prns, receiver_config, summarize
from crosscheck import ecef
from walking import DEFAULTS


def phone_truth(folder,manifest):
    import hashlib
    from walking import plan
    manifest['trajectorySHA256']={}
    for name,points in zip(('ideal.csv','emitted.csv'),plan(DEFAULTS|{'duration':90})[:2]):
        np.savetxt(folder/name,points,delimiter=',')
        manifest['trajectorySHA256'][name]=hashlib.sha256((folder/name).read_bytes()).hexdigest()


class BdsLabTest(unittest.TestCase):
    def test_no_receiver_fix_is_a_failed_result(self):
        from walking import plan
        ideal,emitted,_=plan(DEFAULTS|{'duration':90})
        result=summarize('',ideal,emitted,90)
        self.assertFalse(result['passed'])
        self.assertEqual(result['validFixes'],0)
        self.assertIsNone(result['maxError'])

    def test_phone_glonass_checks_full_coverage_before_decoder(self):
        import tempfile,json
        from unittest.mock import patch
        from bds_lab import verify_phone
        from glonass_lab import convert
        from test_glonass_lab import records,glo_models,START
        class Captured(Exception):pass
        class Digest:
            def hexdigest(self):return 'a'*64
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp)
            with (folder/'signal.bin').open('wb') as stream:stream.truncate(1800000000)
            nav,_=convert(records(),glo_models(),START,18,90)
            (folder/'broadcast-glonass.rnx').write_text(nav)
            (folder/'source-iers.dat').write_bytes((Path(__file__).resolve().parent.parent/'mobile-controller/data/Leap_Second.dat').read_bytes())
            (folder/'generator.log').write_text('GLONASS G1 with IF +0kHz:\n| 01 | +0 | 02 | +0 | 03 | +0 | 04 | +0 |\nTotal channels: 4')
            manifest=dict(schema=2,type='glonass-research-iq',signal='GLO-G1',mode='current-broadcast',hardwareOutputAllowed=False,sampleRate=10000000,frequencyHz=1602000000,durationSeconds=90,iqSHA256='a'*64,navigation={'startUTC':START.isoformat()})
            phone_truth(folder,manifest)
            (folder/'experiment.json').write_text(json.dumps(manifest))
            def execute(*args):
                config=(folder/'receiver.conf').read_text()
                self.assertIn('GLONASS_L1_CA',config);self.assertIn('dll_bw_narrow_hz = 0.5',config)
                raise Captured()
            with patch('bds_lab.hashlib.file_digest',return_value=Digest()):
                with self.assertRaises(Captured):verify_phone(folder,execute)
                (folder/'broadcast-glonass.rnx').write_text(nav.replace('GLGP','XXXX'))
                with self.assertRaises(ValueError):verify_phone(folder,execute)

    def test_phone_bds_checks_navigation_before_decoder(self):
        import tempfile,json
        from datetime import timedelta
        from unittest.mock import patch
        from bds_lab import verify_phone
        from bds_navigation import convert
        from test_bds_navigation import FIX,START
        class Captured(Exception):pass
        class Digest:
            def hexdigest(self):return 'a'*64
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp)
            with (folder/'signal.bin').open('wb')as stream:stream.truncate(900000000)
            nav,_=convert((FIX/'bds-broadcast.rnx').read_text(),(FIX/'bds-models.rnx').read_text(),START,18,90)
            (folder/'broadcast-bds.rnx').write_text(nav)
            (folder/'source-iers.dat').write_bytes((FIX.parent.parent/'data/Leap_Second.dat').read_bytes())
            (folder/'generator.log').write_text('BeiDou B1I with IF +0kHz:\n| 01 | +0 | 02 | +0 | 03 | +0 | 04 | +0 |\nTotal channels: 4')
            manifest=dict(schema=2,signal='BDS-B1I',type='bds-research-iq',mode='current-broadcast',hardwareOutputAllowed=False,sampleRate=5000000,frequencyHz=1561098000,durationSeconds=90,iqSHA256='a'*64,navigation={'startUTC':START.isoformat()})
            phone_truth(folder,manifest)
            (folder/'experiment.json').write_text(json.dumps(manifest))
            def execute(*args):
                self.assertIn('BEIDOU_B1I',(folder/'receiver.conf').read_text());raise Captured()
            with patch('bds_lab.hashlib.file_digest',return_value=Digest()):
                with self.assertRaises(Captured):verify_phone(folder,execute)
                original=(folder/'emitted.csv').read_bytes();(folder/'emitted.csv').write_bytes(original+b'corrupt')
                with self.assertRaisesRegex(ValueError,'trajectory hash'):verify_phone(folder,execute)
                (folder/'emitted.csv').write_bytes(original)
                (folder/'broadcast-bds.rnx').write_text(nav.replace('BDUT','XXXX'))
                with self.assertRaises(ValueError):verify_phone(folder,execute)
                (folder/'broadcast-bds.rnx').write_text(nav);manifest['navigation']['startUTC']=(START+timedelta(hours=1)).isoformat();(folder/'experiment.json').write_text(json.dumps(manifest))
                with self.assertRaises(Captured):verify_phone(folder,execute)
                manifest['navigation']['startUTC']=(START+timedelta(hours=2)).isoformat();(folder/'experiment.json').write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):verify_phone(folder,execute)

    def test_phone_galileo_validation_and_receiver_dispatch(self):
        import tempfile,json
        from unittest.mock import patch
        from bds_lab import verify_phone
        from test_galileo_lab import records,START
        class Captured(Exception):pass
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);iq=folder/'signal.bin'
            with iq.open('wb')as stream:stream.truncate(2880000000)
            (folder/'broadcast-galileo.rnx').write_text(records())
            (folder/'source-iers.dat').write_bytes((Path(__file__).resolve().parent.parent/'mobile-controller/data/Leap_Second.dat').read_bytes())
            (folder/'generator.log').write_text('Galileo E1 with IF +0kHz:\n| 01 | +0 | 02 | +0 | 03 | +0 | 04 | +0 |\nTotal channels: 4')
            manifest=dict(schema=2,signal='GAL-E1',type='galileo-research-iq',mode='current-broadcast',hardwareOutputAllowed=False,
                sampleRate=16000000,frequencyHz=1575420000,durationSeconds=90,iqSHA256='a'*64,navigation={'startUTC':START.isoformat()})
            phone_truth(folder,manifest)
            def execute(command,cwd,logfile,timeout):
                self.assertIn('Galileo_E1',(folder/'receiver.conf').read_text());self.assertIn('16000000',(folder/'receiver.conf').read_text());raise Captured()
            class Digest:
                def hexdigest(self):return 'a'*64
            with patch('bds_lab.hashlib.file_digest',return_value=Digest()):
                (folder/'experiment.json').write_text(json.dumps(manifest))
                with self.assertRaises(Captured):verify_phone(folder,execute)
                for change in ({'sampleRate':5000000},{'hardwareOutputAllowed':True},{'iqSHA256':'b'*64},{'signal':'GLO-G1'}):
                    (folder/'experiment.json').write_text(json.dumps(manifest|change))
                    with self.assertRaises(ValueError):verify_phone(folder,execute)

    def test_motion_archive_keeps_bias_and_route(self):
        settings=dict(DEFAULTS,route='park',speed=1)
        original=settings.copy()
        config,ideal,emitted,preview=configuration(settings,Path('nav'),Path('iq'))
        self.assertEqual(settings,original)
        self.assertEqual(config['time']['year'],2021)
        self.assertEqual(config['trajectory']['initVelocity']['speed'],0)
        self.assertEqual([s for s in config['output']['systemSelect'] if s['enable']],
                         [{'system':'BDS','signal':'B1I','enable':True}])
        self.assertTrue(10<=math.dist(ideal[0,1:],emitted[0,1:])<=45.0001)
        p=config['trajectory']['initPosition']
        np.testing.assert_allclose(ecef((p['latitude'],p['longitude'],p['altitude'])),emitted[0,1:],atol=1e-6)
        self.assertEqual(preview['estimatedFileMB'],600)
        self.assertGreater(np.linalg.norm(ideal[-1,1:]-ideal[0,1:]),1)
        self.assertEqual(ideal.shape,(602,4))
        self.assertGreater(np.ptp(emitted[:,1:]-ideal[:,1:],axis=0).max(),0.1)

    def test_bds_receiver_only_and_prn_boundaries(self):
        config=receiver_config(Path('signal.bin'),[6,8,13,63])['GNSS-SDR']
        self.assertEqual(config['Channel.signal'],'B1')
        self.assertEqual(config['Channel3.satellite'],'63')
        self.assertEqual(config['Channels.in_acquisition'],'4')
        self.assertEqual(receiver_config(Path('signal.bin'),list(range(1,17)))['GNSS-SDR']['Channels.in_acquisition'],'16')
        self.assertEqual(config['Acquisition_B1.coherent_integration_time_ms'],'2')
        self.assertEqual(config['Tracking_B1.extend_correlation_symbols'],'20')
        self.assertEqual(config['Acquisition_B1.pfa'],'0.0001')
        self.assertFalse(any('_1C' in key for key in config))
        for prns in ([1,2,3],[1,2,3,64],[1,2,3,3]):
            with self.assertRaises(ValueError):receiver_config(Path('iq'),prns)
        log='BeiDou B1I with IF +0kHz:\n| 06 | +123 | 63 | -456 |\nTotal channels: 2'
        self.assertEqual(generated_prns(log),[6,63])

    def test_receiver_calendar_date_is_required(self):
        def sentence(body):
            checksum=0
            for c in body:checksum^=ord(c)
            return f'${body}*{checksum:02X}'
        lines=[]
        for i in range(100):
            stamp=f'1005{31+i//10:02d}.{i%10}'
            lines.append(sentence(f'GPGGA,{stamp},3000.0000,N,10400.0000,E,1,08,1.0,400.0,M,0.0,M,,'))
        truth=ecef((30,104,400))
        self.assertFalse(summarize('\n'.join(lines),truth,truth,60)['passed'])
        rmc=sentence('GPRMC,100531.0,A,3000.0000,N,10400.0000,E,0.0,0.0,190621,0.0,E,A')
        self.assertTrue(summarize('\n'.join(lines+[rmc]),truth,truth,60)['passed'])
        for error,passed in ((29.9,True),(30.1,False)):
            shifted=np.array(truth);shifted[0]+=error
            result=summarize('\n'.join(lines+[rmc]),shifted,shifted,60)
            self.assertEqual(result['passed'],passed);self.assertAlmostEqual(result['maxError'],error)

        moving=np.column_stack((np.arange(602)/10,np.tile(truth,(602,1))))
        moving[:,1]+=moving[:,0]*10
        self.assertFalse(summarize('\n'.join(lines+[rmc]),moving,moving,60)['passed'])
        moving[:,1]-=moving[:,0]*10
        self.assertTrue(summarize('\n'.join(lines+[rmc]),moving,moving,60)['passed'])
        bad=sentence('GPRMC,100531.0,A,3000.0000,N,10400.0000,E,0.0,0.0,190622,0.0,E,A')
        with self.assertRaises(ValueError):summarize('\n'.join(lines+[bad]),truth,truth,60)

    def test_no_fixes_fails_and_wrong_epoch_rejected(self):
        truth=ecef((30,104,400))
        self.assertFalse(summarize('',truth,truth,60)['passed'])
        import json
        self.assertEqual(json.loads(json.dumps(summarize('',truth,truth,60)))['maxGapSeconds'],60)
        def gga(time):
            body=f'GPGGA,{time},3000.0000,N,10400.0000,E,1,08,1.0,400.0,M,0.0,M,,'
            checksum=0
            for char in body:checksum^=ord(char)
            return f'${body}*{checksum:02X}'
        with self.assertRaises(ValueError):summarize(gga('120000'),truth,truth,60)
        with self.assertRaises(ValueError):summarize('\n'.join([gga('100531')]*2),truth,truth,60)
        self.assertFalse(summarize(gga('100531'),truth,truth,60)['passed'])

    def test_percentile_keeps_maximum_outlier_and_separates_axes(self):
        def sentence(body):
            checksum=0
            for c in body:checksum^=ord(c)
            return f'${body}*{checksum:02X}'
        rows=[sentence(f'GPGGA,1005{31+i:02d},0000.0000,N,{"00000.0600" if i==20 else "00000.0000"},E,1,08,1.0,10.0,M,0.0,M,,') for i in range(21)]
        r=summarize('\n'.join(rows),ecef((0,0,0)),ecef((0,0,0)),60)
        self.assertLess(r['horizontalErrorP95M'],1e-6)
        self.assertAlmostEqual(r['verticalAbsErrorP95M'],10,places=5)
        self.assertGreater(r['maxError'],100)

    def test_receiver_geometry_distinguishes_large_dop_from_system_accuracy(self):
        def sentence(pdop):
            body='GLGSA,A,3,'+','.join(['65','72','73','75','83','85']+['']*6)+f',{pdop},8.1,21.1,2'
            checksum=0
            for c in body:checksum^=ord(c)
            return f'${body}*{checksum:02X}'
        truth=ecef((30,104,400))
        r=summarize(sentence(22.6),truth,truth,60)
        self.assertEqual(r['geometry']['medianPDOP'],22.6);self.assertEqual(r['geometry']['messagesWithPDOPAtMost6'],0)
        self.assertFalse(r['passed'])
        with self.assertRaisesRegex(ValueError,'DOP'):summarize(sentence('nan'),truth,truth,60)

if __name__=='__main__':unittest.main()
