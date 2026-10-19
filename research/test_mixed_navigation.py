from datetime import datetime, timedelta, timezone
from pathlib import Path
import unittest
from mixed_navigation import combine
from live_nav import GPS_EPOCH, header_line

FIX = Path(__file__).resolve().parent.parent/'mobile-controller/tests/fixtures'
START = datetime(2026,10,17,6,27,25,tzinfo=timezone.utc)


def gps_input():
    gps = START+timedelta(seconds=18)
    week, tow = divmod((gps-GPS_EPOCH).total_seconds(), 604800)
    model = header_line('     2.11           N: GPS NAV DATA','RINEX VERSION / TYPE')
    for label in ('ION ALPHA','ION BETA'):
        model += header_line('  '+''.join(f'{0:12.4E}' for _ in range(4)),label)
    model += header_line('   '+f'{0:19.11E}{0:19.11E}{int(tow)//4096*4096:9d}{int(week):9d}','DELTA-UTC: A0,A1,T,W')
    model += header_line(f'{18:6d}','LEAP SECONDS')+header_line('','END OF HEADER')
    nav = header_line('     3.05           N: GNSS NAV DATA','RINEX VERSION / TYPE')+header_line('','END OF HEADER')
    for prn in range(1,5):
        values = [[0.0]*4 for _ in range(7)];values[2][0]=tow;values[4][2]=week;values[6][1]=4
        nav += f'G{prn:02d} {gps:%Y %m %d %H %M %S}'+''.join(f'{0:19.12E}' for _ in range(3))+'\n'
        nav += ''.join('    '+''.join(f'{v:19.12E}' for v in row)+'\n' for row in values)
    return nav,model


class MixedNavigationTest(unittest.TestCase):
    def test_common_epoch_models_and_orbit_bytes(self):
        gps, models = gps_input();bds=(FIX/'bds-broadcast.rnx').read_text();bm=(FIX/'bds-models.rnx').read_text()
        text, meta = combine(gps,models,bds,bm,START,18,90)
        self.assertEqual(meta['startUTC'],START.isoformat())
        self.assertEqual(meta['bdtMinusUTCSeconds'],4)
        self.assertEqual(meta['gps']['healthyRecentPRNs'],[1,2,3,4])
        self.assertEqual(len(meta['bds']['healthyCoveredPRNs']),4)
        self.assertIn(gps.split('END OF HEADER\n')[1],text)
        self.assertIn(bds.split('END OF HEADER\n')[1],text)
        self.assertEqual(text.count('END OF HEADER'),1)
        self.assertIn('GPSA',text);self.assertIn('BDSA',text)

    def test_reject_mismatched_models_or_stale_constellation(self):
        gps,models=gps_input();bds=(FIX/'bds-broadcast.rnx').read_text();bm=(FIX/'bds-models.rnx').read_text()
        for changed,start in ((bm.replace('BDUT','GPUT'),START),(bm,START+timedelta(hours=3))):
            with self.assertRaises(ValueError):combine(gps,models,bds,changed,start,18,90)
        with self.assertRaises(ValueError):combine(gps,models,bds,bm,START,17,90)

if __name__=='__main__':unittest.main()
