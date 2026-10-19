import unittest
import numpy as np
from phone_report import read_rows, summarize, track_metrics, scenario_matches, joint_usage


class PhoneReportTest(unittest.TestCase):
    def test_mixed_usage_requires_both_in_the_same_event(self):
        gps=dict(constellation='GPS',svid=7,cn0DbHz=35,usedInFix=True)
        bds=dict(constellation='北斗',svid=19,cn0DbHz=30,usedInFix=True)
        def event(t,*items):return dict(receivedAtMs=t,satellites=list(items))
        self.assertEqual(joint_usage([event(0,gps),event(1000,bds)])['epochs'],0)
        for change in ({'usedInFix':False},{'cn0DbHz':0},{'cn0DbHz':float('nan')},{'svid':True}):
            self.assertEqual(joint_usage([event(0,gps,dict(bds,**change))])['epochs'],0)
        result=joint_usage([event(0,gps,bds,bds),event(10000,gps,bds)])
        self.assertEqual(result['epochs'],2);self.assertEqual(result['spanSeconds'],10)
        self.assertEqual(result['examples'][0]['bds'],[19])

    def test_cli_uses_synced_target_after_recording_starts_elsewhere(self):
        import tempfile,json,subprocess,sys
        from pathlib import Path
        with tempfile.TemporaryDirectory() as temporary:
            folder=Path(temporary);epoch=1792300650000
            rows=[dict(type='session',receivedAtMs=epoch-1000,receivedElapsedRealtimeNanos=1,
                       targetLatitudeWgs84=0,targetLongitudeWgs84=90)]
            for t in range(1,31):
                rows.append(dict(type='location',provider='gps',latitude=0,longitude=0,ellipsoidHeightM=10,
                    locationTimeMs=epoch+t*1000,receivedAtMs=epoch+t*1000,receivedElapsedRealtimeNanos=t+1,distanceToTargetCenterM=0,mock=False))
            phone=folder/'receiver.jsonl';phone.write_text(''.join(json.dumps(row)+'\n' for row in rows))
            tx=folder/'transmission.json';tx.write_text(json.dumps(dict(startedAt='2026-10-18T05:17:30+00:00',endedAt='2026-10-18T05:18:30+00:00',durationSeconds=60,status='completed')))
            (folder/'navigation.json').write_text(json.dumps(dict(startUTC='2026-10-18T05:17:30+00:00')))
            (folder/'phone-scenario.json').write_text(json.dumps(dict(id='synced',targetLatitude=0,targetLongitude=0)))
            np.savetxt(folder/'emitted.csv',[[0,6378137,0,0],[60,6378137,0,0]],delimiter=',')
            subprocess.run([sys.executable,str(Path(__file__).with_name('phone_report.py')),str(phone),str(tx)],check=True,stdout=subprocess.DEVNULL)
            result=json.loads(phone.with_suffix('.report.json').read_text())['trajectoryComparison']['gpsProvider']
            self.assertAlmostEqual(result['maximumHorizontalErrorM'],0,places=8)
            self.assertAlmostEqual(result['medianSignedVerticalErrorM'],10,places=8)
            (folder/'phone-scenario.json').unlink()
            subprocess.run([sys.executable,str(Path(__file__).with_name('phone_report.py')),str(phone),str(tx)],check=True,stdout=subprocess.DEVNULL)
            fallback=json.loads(phone.with_suffix('.report.json').read_text())
            self.assertEqual(fallback['trajectoryReference']['source'],'recordingSession')
            self.assertAlmostEqual(fallback['trajectoryComparison']['gpsProvider']['maximumHorizontalErrorM'],10,places=8)


    def test_duplicate_epochs_keep_worst_error_and_do_not_inflate_coverage(self):
        motion=np.array([[0,6378137,0,0],[60,6378137,0,0]])
        points=[(0,6378137,0,0)]*10+[(60,6378137,0,0)]*10
        report=track_metrics(points,motion,0,0)
        self.assertEqual(report['uniqueEpochs'],2);self.assertFalse(report['position3DObserved'])
        points=[(t,6378137,0,0) for t in range(61)]+[(60,6378137,31,0)]
        report=track_metrics(points,motion,0,0)
        self.assertFalse(report['position3DObserved']);self.assertEqual(report['maximum3DErrorM'],31)

    def test_height_outlier_cannot_pass_three_dimensional_reception(self):
        motion=np.array([[0,6378137,0,0],[60,6378137,0,0]])
        points=[(t,6378137,0,0) for t in range(30,61)]
        self.assertTrue(track_metrics(points,motion,0,0)['position3DObserved'])
        points[0]=(30,6378179,0,0)
        report=track_metrics(points,motion,0,0)
        self.assertTrue(report['horizontalPathObserved'])
        self.assertFalse(report['position3DObserved'])
        self.assertEqual(report['maximum3DErrorM'],42)
        self.assertEqual(report['error3DP95M'],0)
        self.assertFalse(track_metrics([],motion,0,0)['position3DObserved'])
    def test_user_30_metre_limit_keeps_boundary_and_outlier(self):
        motion=np.array([[0,6378137,0,0],[60,6378137,0,0]])
        points=[(t,6378137,30,0) for t in range(61)]
        self.assertTrue(track_metrics(points,motion,0,0)['position3DObserved'])
        points[-1]=(60,6378137,30.01,0)
        report=track_metrics(points,motion,0,0)
        self.assertFalse(report['position3DObserved']);self.assertEqual(report['maximum3DErrorM'],30.01)

    def test_trajectory_match_retains_late_outlier(self):
        motion = np.array([[0, 6378137, 0, 0], [60, 6378137, 72, 0]])
        points = [(t, 6378137, t*1.2, 0) for t in range(30, 61)]
        report = track_metrics(points, motion, 0, 0)
        self.assertTrue(report['horizontalPathObserved'])
        self.assertAlmostEqual(report['medianHorizontalErrorM'], 0)
        points[-1] = (60, 6378137, 172, 0)
        report = track_metrics(points, motion, 0, 0)
        self.assertFalse(report['horizontalPathObserved'])
        self.assertAlmostEqual(report['maximumHorizontalErrorM'], 100)
        self.assertAlmostEqual(report['horizontalErrorP95M'],0)

    def test_matching_position_requires_current_constellation_evidence(self):
        scenario={'id':'galileo-current','constellationId':6}
        row=dict(type='scenario_match',receivedAtMs=5,scenarioId='galileo-current',constellationId=6,
                 matched=True,consecutiveFixes=3,matchingSvids=4,horizontalErrorM=2)
        self.assertEqual(scenario_matches([row],0,10,scenario),1)
        for change in ({'scenarioId':'previous'},{'constellationId':1},{'matchingSvids':0},
                       {'consecutiveFixes':2},{'horizontalErrorM':float('nan')},{'receivedAtMs':10}):
            self.assertEqual(scenario_matches([dict(row,**change)],0,10,scenario),0)

    def test_only_unterminated_tail_can_be_ignored(self):
        rows, ignored = read_rows(b'{"type":"session"}\n{"incomplete":')
        self.assertEqual(rows, [{'type': 'session'}])
        self.assertEqual(ignored, 14)
        with self.assertRaises(ValueError):
            read_rows(b'{broken}\n')

    def test_intervals_and_sources_are_separate(self):
        def row(t, kind, **data):
            return dict(type=kind, receivedAtMs=t, receivedElapsedRealtimeNanos=t*1000000, **data)
        rows = [row(0, 'session'), row(10, 'location', provider='gps', distanceToTargetCenterM=4000),
                row(20, 'location', provider='network', distanceToTargetCenterM=0),
                row(25, 'satellites', satellites=[dict(constellation='GPS', svid=1, cn0DbHz=45, usedInFix=False)]),
                row(30, 'location', provider='gps', distanceToTargetCenterM=10)]
        report = summarize(rows, 20, 30)['periods']
        self.assertEqual(report['before']['gpsProviderFixes'], 1)
        self.assertEqual(report['during']['gpsProviderFixes'], 0)
        self.assertEqual(report['during']['networkFixes'], 1)
        self.assertEqual(report['during']['gpsFixesWithin50M'], 0)
        self.assertEqual(report['during']['constellations']['GPS']['maximumUsedSvids'], 0)
        self.assertEqual(report['after']['gpsFixesWithin50M'], 1)

    def test_backwards_monotonic_clock_rejected(self):
        with self.assertRaises(ValueError):
            summarize([dict(type='session', receivedElapsedRealtimeNanos=2),
                       dict(type='session_end', receivedElapsedRealtimeNanos=1)], 0, 10)

    def test_galileo_and_glonass_are_counted(self):
        rows=[dict(type='satellites',receivedAtMs=5,receivedElapsedRealtimeNanos=5,satellites=[
            dict(constellation=name,svid=1,cn0DbHz=40,usedInFix=True) for name in ('GAL','GLO')])]
        report=summarize(rows,0,10)['periods']['during']['constellations']
        for name in ('GAL','GLO'):self.assertEqual(report[name]['maximumUsedSvids'],1)


if __name__ == '__main__':
    unittest.main()
