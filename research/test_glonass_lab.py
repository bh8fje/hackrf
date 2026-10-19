import unittest
from datetime import datetime,timedelta,timezone
from pathlib import Path
from glonass_lab import convert,configuration,receiver_config,RECEIVER_FREQUENCIES,fetch_sources,navigation_source
from test_galileo_lab import models
from walking import DEFAULTS
from live_nav import header_line

START=datetime(2026,10,17,11,16,tzinfo=timezone.utc)
def glo_models(tow=518400,week=2440,a1=0,gps_a0=5.5879354477e-9):
    text=models().replace('GAUT','GLUT')
    rows=text.splitlines(True)
    rows=[header_line(f'{"GLUT":4s} {-1.3969838619e-9:17.10E}{a1:16.9E}{tow:7d}{week:5d}','TIME SYSTEM CORR') if r.startswith('GLUT') else r for r in rows]
    rows.insert(-1,header_line(f'{"GLGP":4s} {gps_a0:17.10E}{0:16.9E}{tow:7d}{week:5d}','TIME SYSTEM CORR'))
    return ''.join(rows)
def records(health=0,flags=3,shift=0,channel=None):
    text=models().replace('GAUT','GLUT');toc=START.replace(minute=15)+timedelta(seconds=shift)
    for slot in range(1,5):
        text+=f'R{slot:02d} {toc:%Y %m %d %H %M %S}'+''.join(f'{v:19.12E}'for v in (0,0,518400))+'\n'
        for row in [(15000,2,0,health),(15000,-1,0,RECEIVER_FREQUENCIES[slot]if channel is None else channel),(15000,0,0,0),(151,0,8,flags)]:text+='    '+''.join(f'{v:19.12E}'for v in row)+'\n'
    return text

class GlonassTest(unittest.TestCase):
    def test_model_fallback_rejects_missing_invalid_and_stale_sources(self):
        import tempfile,json
        from unittest.mock import patch
        candidates=['missing','invalid','stale','complete']
        responses=[(records(),{'url':'snapshot'}),(models(),{}),(glo_models(a1=1e-12),{}),ValueError('Navigation publication is stale'),(glo_models(),{'url':'complete'})]
        with tempfile.TemporaryDirectory() as tmp,patch('glonass_lab.navigation_sources',return_value=candidates),patch('glonass_lab.cached_fetch',side_effect=responses) as fetch:
            folder=fetch_sources(Path(tmp)/'sources')
            self.assertEqual((folder/'source-models.rnx').read_text(),glo_models())
            audit=json.loads((folder/'navigation-source-audit.json').read_text())
            self.assertEqual([v['url'] for v in audit],candidates[:3])
            self.assertEqual(fetch.call_args.args,('complete',10800))
            self.assertEqual(fetch.call_args_list[0].args[1],1800)
        with tempfile.TemporaryDirectory() as tmp,patch('glonass_lab.navigation_sources',return_value=['missing']),patch('glonass_lab.cached_fetch',side_effect=[(records(),{}),(models(),{})]):
            folder=Path(tmp)/'sources'
            with self.assertRaisesRegex(ValueError,'No complete current GLONASS models'):fetch_sources(folder)
            self.assertFalse((folder/'source-models.rnx').exists())
            self.assertTrue((folder/'navigation-source-audit.json').exists())

    def test_stream_fallback_only_when_existing_sources_do_not_cover(self):
        import tempfile,json
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp)
            (folder/'source-models.rnx').write_text(glo_models())
            (folder/'source-broadcast.rnx').write_text(records())
            with patch('glonass_lab.production',return_value=START),patch('glonass_lab.cached_fetch') as fetch:
                _,info,_,_=navigation_source(folder,START,18,90)
                self.assertEqual(info['selectedSourceFile'],'source-broadcast.rnx');fetch.assert_not_called()
            (folder/'source-broadcast.rnx').write_text(records(shift=-1800))
            with patch('glonass_lab.production',return_value=START),patch('glonass_lab.cached_fetch',return_value=(records(),{'url':'stream'})) as fetch:
                _,info,_,_=navigation_source(folder,START,18,90)
                self.assertEqual(info['selectedSourceFile'],'source-stream.rnx')
                self.assertIn('BRDC00WRD_S_',fetch.call_args.args[0])
                self.assertEqual(json.loads((folder/'source-stream-metadata.json').read_text())['url'],'stream')
            with patch('glonass_lab.production',return_value=START-timedelta(hours=4)),patch('glonass_lab.cached_fetch') as fetch:
                with self.assertRaisesRegex(ValueError,'model publication is stale'):
                    navigation_source(folder,START,18,90)
                fetch.assert_not_called()
            (folder/'source-stream.rnx').write_text(records(shift=-1800))
            with patch('glonass_lab.production',return_value=START),patch('glonass_lab.cached_fetch') as fetch:
                with self.assertRaisesRegex(ValueError,'No complete current GLONASS source'):
                    navigation_source(folder,START,18,90)
                fetch.assert_not_called()

    def test_live_snapshot_keeps_sources_and_requires_fresh_download(self):
        import tempfile,json
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp,patch('glonass_lab.cached_fetch',side_effect=[(records(),{'url':'snapshot','downloadSHA256':'a'}),(glo_models(),{'url':'models','downloadSHA256':'b'})]) as fetch:
            folder=fetch_sources(Path(tmp)/'source')
            self.assertEqual([r['downloadSHA256']for r in json.loads((folder/'source-metadata.json').read_text())],['a','b'])
            self.assertIn('brdc_last.rnx.Z',fetch.call_args_list[0].args[0])
            self.assertTrue(all(c.kwargs.get('force')for c in fetch.call_args_list))
            _,info=convert((folder/'source-broadcast.rnx').read_text(),(folder/'source-models.rnx').read_text(),START,18,90)
            self.assertEqual(info['healthyCoveredSlots'],[1,2,3,4])
    def test_rolling_orbits_precede_usable_model_archive(self):
        import tempfile
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp)
            (folder/'source-broadcast.rnx').write_text(records(shift=-1800))
            (folder/'source-models.rnx').write_text(glo_models()+records().split('END OF HEADER\n',1)[1])
            with patch('glonass_lab.production',return_value=START),patch('glonass_lab.cached_fetch',return_value=(records(),{'url':'stream'})):
                _,info,_,_=navigation_source(folder,START,18,90)
                self.assertEqual(info['selectedSourceFile'],'source-stream.rnx')

    def test_utc_health_frequency_and_whole_window(self):
        raw=records();nav,info=convert(raw,glo_models(),START,18,90)
        self.assertEqual(info['healthyCoveredSlots'],[1,2,3,4]);self.assertEqual(info['epochTimeScale'],'UTC');self.assertIn('R01 2026 10 17 11 15 00',nav)
        self.assertEqual(len(nav[nav.index('R01 '):].splitlines()),16)
        for bad in (records(health=1),records(flags=7),records(flags=2),records(shift=-1800),records(channel=7),records(channel=0)):
            with self.assertRaises(ValueError):convert(bad,glo_models(),START,18,90)
    def test_expired_orbits_report_reference_and_required_end(self):
        with self.assertRaisesRegex(ValueError,r'usable=0, latestHealthyReferenceUTC=2026-10-17T10:45:00\+00:00, requiredThroughUTC='):
            convert(records(shift=-1800),glo_models(),START,18,90)

    def test_broadcast_time_sign_scale_and_reference(self):
        nav,info=convert(records(),glo_models(tow=0,week=0),START,18,90)
        self.assertIn('GLGP',nav);self.assertAlmostEqual(info['tauCSeconds'],1.3969838619e-9,places=19)
        self.assertAlmostEqual(info['tauGPSSeconds'],-5.5879354477e-9,places=19)
        for bad in (glo_models(a1=1e-12),glo_models(gps_a0=.002),glo_models(week=100),glo_models().replace('GLGP','XXXX')):
            with self.assertRaises(ValueError):convert(records(),bad,START,18,90)
    def test_g1_bandwidth_and_independent_receiver(self):
        config,_,_,preview=configuration(dict(DEFAULTS,duration=90),Path('/nav'),Path('/iq'),START)
        self.assertEqual(config['output']['sampleFreq'],10);self.assertEqual(config['output']['centerFreq'],1602)
        self.assertEqual([e['system']for e in config['output']['systemSelect']if e['enable']],['GLONASS']);self.assertEqual(preview['estimatedFileMB'],1800)
        rx=receiver_config(Path('/iq'),[1,2,3,4])['GNSS-SDR'];self.assertEqual(rx['Channel.signal'],'1G');self.assertEqual(rx['Tracking_1G.implementation'],'GLONASS_L1_CA_DLL_PLL_Tracking')
        self.assertEqual(rx['Channels.in_acquisition'],'4')
        self.assertEqual(rx['PVT.elevation_mask'],'5');self.assertEqual(rx['Tracking_1G.extend_correlation_symbols'],'10')
        self.assertEqual(rx['Tracking_1G.dll_bw_narrow_hz'],'0.5')
        with self.assertRaises(ValueError):receiver_config(Path('/iq'),[1,2,3,25])
