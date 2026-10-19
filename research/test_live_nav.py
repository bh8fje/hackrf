from datetime import datetime, timedelta, timezone
import unittest
from unittest.mock import patch

from dynamic import elapsed_seconds
from live_nav import GPS_EPOCH, convert, gps_utc_offset, header_line, launch_delay

UTC = timezone.utc


class CurrentNavigationTest(unittest.TestCase):
    def test_valid_text_cache_reports_zero_new_transfer_and_rejects_corruption(self):
        import json,tempfile
        from pathlib import Path
        from live_nav import cached_fetch
        now=datetime.now(UTC);url='https://navigation.invalid/current'
        with tempfile.TemporaryDirectory() as tmp,patch('live_nav.CACHE',Path(tmp)),patch('live_nav.publication',return_value=now),patch('live_nav.fetch',return_value=('navigation',{'url':url,'downloadBytes':100,'transferredBytes':100})) as fetch:
            cached_fetch(url,10800)
            _,info=cached_fetch(url,10800);self.assertTrue(info['cached']);self.assertEqual(info['transferredBytes'],0);self.assertEqual(info['resumedBytes'],100);self.assertEqual(fetch.call_count,1)
            file=next(Path(tmp).glob('*.json'));stored=json.loads(file.read_text());stored['text']='corrupted';file.write_text(json.dumps(stored))
            self.assertFalse(cached_fetch(url,10800)[1]['cached']);self.assertEqual(fetch.call_count,2)
    def test_domestic_bds_source_is_first_without_changing_other_systems(self):
        from live_nav import navigation_sources
        now=datetime(2026,10,17,13,35,tzinfo=UTC)
        urls=navigation_sources(now,'bds')
        self.assertEqual(urls[0],'https://www.csno-tarc.cn/data/upload/brdc/2026/tarc2900.26b')
        self.assertIn('/NTRIP/BRDC/brdc_last.rnx.Z',urls[1])
        self.assertIn('BRDC00WRD_R_',urls[2])
        self.assertEqual(len(urls),len(set(urls)))
        self.assertFalse(any('csno-tarc.cn' in u for u in navigation_sources(now)))

    def test_noaa_is_first_only_for_gps(self):
        from live_nav import navigation_sources
        now=datetime(2026,10,18,tzinfo=UTC)
        urls=navigation_sources(now,'gps')
        self.assertEqual(urls[0],'https://geodesy.noaa.gov/corsdata/rinex/2026/291/brdc2910.26n.gz')
        self.assertTrue(all('noaa.gov' not in u for u in navigation_sources(now)+navigation_sources(now,'bds')))

    @patch('live_nav.chunk_download',return_value=None)
    def test_ga_archive_resolver_preserves_bytes_and_rejects_foreign_links(self,_chunks):
        import io,json,gzip,hashlib
        from unittest.mock import patch
        from live_nav import fetch,publication,navigation_sources
        class Response(io.BytesIO):headers={}
        name='PERT00AUS_R_20262901200_01H_MN.rnx.gz';url='https://data.gnss.ga.gov.au/api/rinexFiles/'+name+'?rinexVersion=3'
        published=datetime.now(UTC).replace(microsecond=0)
        entry=dict(metadataStatus='valid',fileType='nav',filePeriod='01H',rinexVersion='3',createdAt=published.isoformat(),fileLocation='https://geodesy-archive-permanent-storage-prod.s3.ap-southeast-2.amazonaws.com/default/'+name+'?signature=temporary')
        body=gzip.compress(b'navigation')
        with patch('live_nav.urllib.request.urlopen',side_effect=[Response(json.dumps([entry]).encode()),Response(body)]) as network:
            text,info=fetch(url)
            self.assertEqual(text,'navigation');self.assertEqual(info['url'],url)
            self.assertEqual(info['downloadSHA256'],hashlib.sha256(body).hexdigest());self.assertNotIn('?',info['resolvedURL'])
            self.assertEqual(info['downloadBytes'],len(body)+info['indexBytes']);self.assertEqual(info['expandedBytes'],len(b'navigation'));self.assertGreaterEqual(info['downloadMillis'],0)
            self.assertEqual(publication(text,info),published);self.assertEqual(network.call_count,2)
            self.assertIn('.s3.dualstack.ap-southeast-2.amazonaws.com/public/hourly/2026/290/12/',network.call_args.args[0])
        import urllib.error
        with patch('live_nav.urllib.request.urlopen',side_effect=[Response(json.dumps([entry]).encode()),urllib.error.HTTPError('public',404,'missing',{},None),Response(body)]) as network:
            text,info=fetch(url)
            self.assertEqual(text,'navigation');self.assertEqual(network.call_args.args[0],entry['fileLocation'])
            self.assertNotIn('signature',info['resolvedURL'])
        for bad in [dict(entry,fileLocation='https://untrusted.invalid/'+name),dict(entry,metadataStatus='invalid'),dict(entry,fileType='obs'),dict(entry,createdAt=(published+timedelta(minutes=2)).isoformat())]:
            with patch('live_nav.urllib.request.urlopen',return_value=Response(json.dumps([bad]).encode())) as network:
                with self.assertRaises(ValueError):fetch(url)
                self.assertEqual(network.call_count,1)
        with patch('live_nav.urllib.request.urlopen',return_value=Response(b'x'*65537)):
            with self.assertRaisesRegex(ValueError,'size limit'):fetch(url)
        import urllib.error
        with patch('live_nav.urllib.request.urlopen',side_effect=urllib.error.HTTPError(url,404,'missing',{},None)) as network:
            with self.assertRaises(urllib.error.HTTPError):fetch(url)
            self.assertEqual(network.call_count,1)
        with patch('live_nav.Path.read_text',return_value=json.dumps({'broadcast':['{previousHourYear}/{previousHourDoy}/{previousHour}']})):
            self.assertEqual(navigation_sources(datetime(2027,1,1,0,10,tzinfo=UTC)),['2026/365/23'])
        with patch('live_nav.Path.read_text',return_value=json.dumps({'broadcast':['{olderHourYear}/{olderHourDoy}/{olderHour}']})):
            self.assertEqual(navigation_sources(datetime(2027,1,1,0,10,tzinfo=UTC)),['2026/365/22'])

    @patch('live_nav.chunk_download',return_value=None)
    def test_transport_retry_is_bounded_and_preserves_limits(self,_chunks):
        import io,urllib.error
        from unittest.mock import patch
        from live_nav import fetch
        class Response(io.BytesIO):headers={}
        with patch('live_nav.urllib.request.urlopen',side_effect=[urllib.error.URLError('TLS EOF'),Response(b'navigation')]) as network:
            self.assertEqual(fetch('https://example.invalid/nav')[0],'navigation')
            self.assertEqual(network.call_count,2)
        with patch('live_nav.urllib.request.urlopen',side_effect=TimeoutError('offline')) as network:
            with self.assertRaises(TimeoutError):fetch('https://example.invalid/nav')
            self.assertEqual(network.call_count,2)
        with patch('live_nav.urllib.request.urlopen',side_effect=urllib.error.HTTPError('url',404,'missing',{},None)) as network:
            with self.assertRaises(urllib.error.HTTPError):fetch('https://example.invalid/nav')
            self.assertEqual(network.call_count,1)
        with patch('live_nav.urllib.request.urlopen',return_value=Response(b'x'*10_000_001)):
            with self.assertRaisesRegex(ValueError,'size limit'):fetch('https://example.invalid/nav')
        class ShortResponse(Response):headers={'Content-Length':'20'}
        with patch('live_nav.urllib.request.urlopen',side_effect=lambda *a,**k:ShortResponse(b'partial')) as network:
            with self.assertRaisesRegex(urllib.error.URLError,'Incomplete'):fetch('https://example.invalid/nav')
            self.assertEqual(network.call_count,2)

    @patch('live_nav.chunk_download',return_value=None)
    def test_unix_compress_snapshot_is_decoded_with_expansion_limit(self,_chunks):
        import io,shutil
        from unittest.mock import patch,MagicMock
        from live_nav import fetch
        class Response(io.BytesIO):headers={}
        raw=bytes.fromhex('1f9d906ec2d84973260c9d346fdc00')
        if shutil.which('uncompress'):
            with patch('live_nav.urllib.request.urlopen',return_value=Response(raw)):
                self.assertEqual(fetch('https://example.invalid/nav.Z')[0],'navigation')
        decoder=MagicMock();decoder.__enter__.return_value=decoder
        decoder.stdout.read.return_value=b'x'*20_000_001;decoder.wait.return_value=-9
        with patch('live_nav.urllib.request.urlopen',return_value=Response(raw)),patch('live_nav.subprocess.Popen',return_value=decoder):
            with self.assertRaisesRegex(ValueError,'Expanded'):fetch('https://example.invalid/nav.Z')
            decoder.kill.assert_called_once();decoder.stdout.read.assert_called_once_with(20_000_001)

    def test_https_sources_fail_closed(self):
        from unittest.mock import patch
        from tempfile import TemporaryDirectory
        from pathlib import Path
        from live_nav import prepare
        with TemporaryDirectory() as folder, patch('live_nav.cached_fetch', side_effect=OSError('offline')) as fetch:
            with self.assertRaisesRegex(ValueError, 'No navigation source'):
                prepare(Path(folder))
            urls = [call.args[0] for call in fetch.call_args_list]
            self.assertEqual(len(urls), 8)
            self.assertIn('geodesy.noaa.gov', urls.pop(0))
            self.assertIn('igs.bkg.bund.de', urls[0])
            self.assertIn('BRDC00WRD_R_', urls[0])
            self.assertIn('BRDC00WRD_S_', urls[1])
            self.assertIn('BRDC00WRD_R_', urls[2])
            self.assertIn('ALIC00AUS',urls[3]);self.assertIn('PERT00AUS',urls[4])
            self.assertIn('ALIC00AUS',urls[5]);self.assertIn('PERT00AUS',urls[6])
            self.assertTrue(all(url.startswith('https://') for url in urls))

    def test_rinex3_model_header_conversion(self):
        from live_nav import model_headers, has_models
        text=header_line('     3.05           N: GNSS NAV DATA', 'RINEX VERSION / TYPE')
        for tag in ('GPSA','GPSB'):
            text+=header_line(tag+' '+''.join(f'{v:12.4E}' for v in [1e-8,2e-8,3e-8,4e-8]),'IONOSPHERIC CORR')
        text+=header_line('GPUT '+f'{1e-9:17.10E}{2e-15:16.9E}{589824:7d}{2440:5d}', 'TIME SYSTEM CORR')
        normalized=model_headers(text)
        self.assertTrue(has_models(text))
        self.assertIn('ION ALPHA',normalized)
        self.assertIn('ION BETA',normalized)
        delta=next(l for l in normalized.splitlines() if 'DELTA-UTC' in l)
        self.assertEqual(int(delta[41:50]),589824)
        self.assertEqual(int(delta[50:59]),2440)
        self.assertAlmostEqual(float(delta[3:22]),1e-9)

    def test_leap_table_expiration_and_offsets(self):
        table = '# File expires on 28 June 2027\n57754.0 1 1 2017 37\n'
        self.assertEqual(gps_utc_offset(table, datetime(2026, 10, 16, tzinfo=UTC)), 18)
        with self.assertRaisesRegex(ValueError, 'expired'):
            gps_utc_offset(table, datetime(2028, 1, 1, tzinfo=UTC))

    def test_real_start_time_and_midnight_alignment(self):
        self.assertAlmostEqual(elapsed_seconds('113001.2', '113000'), 1.2)
        self.assertAlmostEqual(elapsed_seconds('000001.2', '235959'), 2.2)
        with self.assertRaises(ValueError):
            elapsed_seconds('120000', '250000')

    def test_stale_and_historical_transmission_rejected(self):
        now = datetime(2026, 10, 16, 11, 20, tzinfo=UTC)
        metadata = dict(mode='current-broadcast', startUTC=(now + timedelta(seconds=30)).isoformat(),
                        broadcastProducedAtUTC=(now - timedelta(minutes=15)).isoformat())
        self.assertEqual(launch_delay(metadata, now), 30)
        with self.assertRaises(ValueError):
            launch_delay({}, now)
        with self.assertRaises(ValueError):
            launch_delay(metadata, now + timedelta(minutes=2))
        with self.assertRaises(ValueError):
            launch_delay(dict(metadata, broadcastProducedAtUTC=(now-timedelta(hours=2)).isoformat()), now)

    def test_conversion_preserves_orbit_epochs(self):
        start = datetime(2026, 10, 16, 11, 30, tzinfo=UTC)
        seconds = (start - GPS_EPOCH).total_seconds()
        week, tow = divmod(seconds, 604800)
        models = header_line('     2.11           N: GPS NAV DATA', 'RINEX VERSION / TYPE')
        models += ''.join(header_line('  '+''.join(f'{0:12.4E}' for _ in range(4)), label) for label in ('ION ALPHA', 'ION BETA'))
        models += header_line('   '+f'{0:19.11E}{0:19.11E}{4096:9d}{int(week):9d}', 'DELTA-UTC: A0,A1,T,W')
        header = header_line('     3.05           N: GNSS NAV DATA', 'RINEX VERSION / TYPE') + header_line('', 'END OF HEADER')
        blocks = []
        for epoch in (start-timedelta(minutes=30), start+timedelta(minutes=30)):
            for prn in range(1, 5):
                values = [[0.0]*4 for _ in range(7)]
                values[2][0] = tow
                values[4][2] = week
                block = f'G{prn:02d} {epoch:%Y %m %d %H %M %S}' + ''.join(f'{0.0:19.12E}' for _ in range(3)) + '\n'
                block += ''.join('    '+''.join(f'{v:19.12E}' for v in row)+'\n' for row in values[:6])
                block += '    ' + f'{0:19.12E}' + '\n'
                blocks.append(block)
        text, metadata = convert(header+''.join(blocks), models, start, 18)
        self.assertEqual(metadata['healthyRecentPRNs'], [1, 2, 3, 4])
        self.assertEqual(metadata['gpsRecordCount'], 8)
        self.assertIn(' 1 26 10 16 11  0  0.0', text)
        self.assertIn(' 1 26 10 16 12  0  0.0', text)
        _, after_toc = convert(header+''.join(blocks), models, start+timedelta(minutes=45), 18)
        self.assertEqual(after_toc['healthyRecentPRNs'], [1, 2, 3, 4])
        # A ten-minute run may cross TOC+1h while remaining inside TOE +/-2h.
        _, covered = convert(header+''.join(blocks), models, start+timedelta(minutes=85), 18, 600)
        self.assertEqual(covered['healthyRecentPRNs'], [1, 2, 3, 4])
        with self.assertRaises(ValueError):
            convert(header+''.join(blocks), models, start+timedelta(minutes=100), 18, 600)
        with self.assertRaises(ValueError):
            convert(header+''.join(blocks), models, start+timedelta(days=1), 18)


if __name__ == '__main__':
    unittest.main()
