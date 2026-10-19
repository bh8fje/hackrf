"""Cross-platform contracts and navigation/cache boundaries, without radio access."""
from pathlib import Path
from datetime import datetime,timedelta,timezone
import hashlib,json,re,subprocess,tempfile,unittest
from unittest.mock import patch
import live_nav,preparation

ROOT=Path(__file__).resolve().parent.parent
FIX=ROOT/'mobile-controller/tests/fixtures'
JAVA=Path(__import__('os').environ.get('JAVA_HOME', '/Library/Java/JavaVirtualMachines/current/Contents/Home'))/'bin'
UTC=timezone.utc

class Frozen(datetime):
    instant=datetime(2026,10,16,16,7,44,tzinfo=UTC)
    @classmethod
    def now(cls,tz=None):return cls.instant

class PlatformSyncTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory();cls.classes=Path(cls.temp.name)
        helper=cls.classes/'Sync.java'
        helper.write_text('''package local.hackrf.controller;import java.nio.file.*;import java.time.*;public class Sync{public static void main(String[] a)throws Exception{if(a[0].equals("offset")){PhoneMotion m=new PhoneMotion();m.seed=Long.parseLong(a[1]);double[] v=m.startupOffset();System.out.print(v[0]+","+v[1]);return;}if(a[0].equals("bdsLead")){System.out.print(Preparation.bdsLeadSeconds(Integer.parseInt(a[1]),Double.parseDouble(a[2])));return;}if(a[0].equals("lead")){System.out.print(Preparation.leadSeconds(Integer.parseInt(a[1]),Double.parseDouble(a[2])));return;}Path p=Path.of(a[0]);System.out.print(RinexNav.convert(Files.readString(p.resolve("broadcast.rnx")),Files.readString(p.resolve("models.rnx")),Instant.parse("2026-10-16T16:08:02Z"),18,Integer.parseInt(a[1])));}}''')
        subprocess.run([str(JAVA/'javac'),'-d',str(cls.classes),str(ROOT/'mobile-controller/src/main/java/local/hackrf/controller/RinexNav.java'),str(ROOT/'mobile-controller/src/main/java/local/hackrf/controller/Preparation.java'),str(ROOT/'mobile-controller/src/main/java/local/hackrf/controller/PhoneMotion.java'),str(helper)],check=True)
    @classmethod
    def tearDownClass(cls):cls.temp.cleanup()
    def java(self,*args):return subprocess.check_output([str(JAVA/'java'),'-cp',str(self.classes),'local.hackrf.controller.Sync',*map(str,args)],text=True)
    def test_navigation_bytes_identical_for_complete_window(self):
        for seconds in (60,600):
            text,_=live_nav.convert((FIX/'broadcast.rnx').read_text(),(FIX/'models.rnx').read_text(),datetime(2026,10,16,16,8,2,tzinfo=UTC),18,seconds)
            self.assertEqual(text,self.java(FIX,seconds))
            roundtrip,_=live_nav.convert(live_nav.broadcast3(text),text,datetime(2026,10,16,16,8,2,tzinfo=UTC),18,seconds)
            self.assertEqual(text,roundtrip)
    def test_startup_offset_identical_and_bounded(self):
        import math
        from walking import startup_offset
        previous=None
        for seed in (0,1,42,123456789,2147483647):
            expected=startup_offset(seed)
            actual=list(map(float,self.java('offset',seed).split(',')))
            for a,b in zip(actual,expected):self.assertAlmostEqual(a,b,places=12)
            self.assertLessEqual(actual[1],0)
            self.assertLessEqual(abs(actual[0]),-actual[1]+1e-8)
            self.assertGreaterEqual(math.hypot(*actual),30)
            self.assertLessEqual(math.hypot(*actual),80+1e-10)
            self.assertAlmostEqual(math.hypot(*actual)*100,round(math.hypot(*actual)*100),places=7)
            self.assertNotEqual(actual,previous)
            previous=actual

    def test_preparation_reserve_identical(self):
        for duration,rate in ((60,.01),(90,.18),(600,.18),(90,.72),(600,3),(60,1e308)):
            self.assertEqual(preparation.lead_seconds(duration,rate),int(self.java('lead',duration,rate)))
        for duration,rate in ((90,.1),(90,2.4),(180,2),(180,20),(600,.1),(600,2),(90,1e308)):
            self.assertEqual(preparation.bds_lead_seconds(duration,rate),int(self.java('bdsLead',duration,rate)))
    def test_signal_profiles_and_map_shared(self):
        swift=(ROOT/'app/WalkLab.swift').read_text();java=(ROOT/'mobile-controller/src/main/java/local/hackrf/controller/SignalProfile.java').read_text()
        policy=json.loads((ROOT/'shared/signal-environments.json').read_text())
        ids=re.findall('"([^"]+)"',re.search(r'IDS=\{([^}]+)',java)[1])
        routes=re.findall('"([^"]+)"',re.search(r'ROUTES=\{([^}]+)',java)[1])
        indices=[int(x) for x in re.search(r'PROFILES=\{([^}]+)',java)[1].split(',')]
        self.assertEqual(ids,[p['id'] for p in policy['profiles']])
        self.assertEqual(dict(zip(routes,[ids[i] for i in indices])),policy['routes'])
        self.assertIn('SignalEnvironments.shared.routes[settings.route]',swift)
        self.assertIn('signal-environments.json',(ROOT/'app/build.py').read_text())
        self.assertIn("shared/map.html",(ROOT/'app/build.py').read_text())
        self.assertIn("shared/map.html",(ROOT/'mobile-controller/build_native.py').read_text())
    def test_domestic_source_templates_and_fresh_hourly(self):
        now=datetime(2026,10,16,16,8,2,tzinfo=UTC)
        urls=live_nav.navigation_sources(now,'gps')
        self.assertIn('geodesy.noaa.gov',urls[0])
        self.assertIn('/2026/288/',urls[3])
        text,_=live_nav.convert((FIX/'broadcast.rnx').read_text(),(FIX/'models.rnx').read_text(),now+timedelta(seconds=78),18,600)
        rows=text.splitlines(True);rows.insert(1,live_nav.header_line('TEST                                  20261016 160000 UTC','PGM / RUN BY / DATE'));text=''.join(rows)
        with tempfile.TemporaryDirectory() as tmp,patch.object(live_nav,'CACHE',Path(tmp)/'cache'),patch.object(live_nav,'datetime',Frozen),patch.object(live_nav,'fetch',side_effect=lambda url:(text,{'url':url,'lastModified':None,'downloadSHA256':hashlib.sha256(text.encode()).hexdigest()})) as network:
            Frozen.instant=now
            folder=Path(tmp)/'job';folder.mkdir()
            nav,meta=live_nav.prepare(folder,60,600,domestic_only=True)
            self.assertTrue(nav.is_file());self.assertEqual(meta['sources'][0]['url'],urls[0]);self.assertGreaterEqual(len(meta['healthyRecentPRNs']),4)
            self.assertTrue(all(call.args[0].startswith('https://geodesy.noaa.gov/') for call in network.call_args_list))

    def test_cache_hit_expiry_corruption_and_offline_only(self):
        text=(FIX/'broadcast.rnx').read_text();source={'url':'https://example.invalid/navigation','lastModified':None,'downloadSHA256':hashlib.sha256(text.encode()).hexdigest()}
        with tempfile.TemporaryDirectory() as tmp,patch.object(live_nav,'CACHE',Path(tmp)),patch.object(live_nav,'datetime',Frozen),patch.object(live_nav,'fetch',return_value=(text,source)) as network:
            Frozen.instant=datetime(2026,10,16,16,7,44,tzinfo=UTC)
            first=live_nav.cached_fetch(source['url'],1800);self.assertFalse(first[1]['cached'])
            self.assertTrue(live_nav.cached_fetch(source['url'],1800)[1]['cached']);self.assertEqual(network.call_count,1)
            cache=next(Path(tmp).glob('*.json'));stored=json.loads(cache.read_text());stored['textSHA256']='bad';cache.write_text(json.dumps(stored))
            self.assertFalse(live_nav.cached_fetch(source['url'],1800)[1]['cached']);self.assertEqual(network.call_count,2)
            Frozen.instant+=timedelta(seconds=901)
            self.assertIsNone(live_nav.cached_fetch(source['url'],1800,cache_only=True));self.assertEqual(network.call_count,2)
            self.assertFalse(live_nav.cached_fetch(source['url'],1800)[1]['cached']);self.assertEqual(network.call_count,3)

    def test_stream_orbits_with_separate_bkg_models(self):
        now=datetime(2026,10,16,16,8,2,tzinfo=UTC)
        orbit=(FIX/'broadcast.rnx').read_text()
        orbit='\n'.join(row for row in orbit.splitlines() if row[60:].strip() not in
                        ('ION ALPHA','ION BETA','DELTA-UTC: A0,A1,T,W','IONOSPHERIC CORR','TIME SYSTEM CORR'))+'\n'
        self.assertFalse(live_nav.has_models(orbit))
        for age,accepted in ((3600,True),(10801,False)):
            models=(FIX/'models.rnx').read_text().splitlines(True)
            models.insert(1,live_nav.header_line('TEST '+(now-timedelta(seconds=age)).strftime('%Y%m%d %H%M%S UTC'),'PGM / RUN BY / DATE'))
            models=''.join(models)
            def download(url):
                if 'BRDC00WRD_S_' in url:text=orbit
                elif 'BRDC00WRD_R_2026289' in url:text=models
                else:raise OSError('unavailable')
                return text,{'url':url,'lastModified':None,'downloadSHA256':hashlib.sha256(text.encode()).hexdigest()}
            with self.subTest(age=age),tempfile.TemporaryDirectory() as tmp,patch.object(live_nav,'CACHE',Path(tmp)/'cache'),patch.object(live_nav,'datetime',Frozen),patch.object(live_nav,'fetch',side_effect=download):
                Frozen.instant=now
                if accepted:
                    _,meta=live_nav.prepare(Path(tmp),60,180)
                    self.assertIn('BRDC00WRD_S_',meta['sources'][0]['url'])
                    self.assertIn('BRDC00WRD_R_',meta['sources'][1]['url'])
                    self.assertGreaterEqual(len(meta['healthyRecentPRNs']),4)
                else:
                    with self.assertRaisesRegex(ValueError,'No navigation source'):live_nav.prepare(Path(tmp),60,180)
    def test_legacy_domestic_flag_uses_unified_sources(self):
        with tempfile.TemporaryDirectory() as tmp,patch.object(live_nav,'CACHE',Path(tmp)/'cache'),patch.object(live_nav,'datetime',Frozen),patch.object(live_nav,'fetch',side_effect=ValueError('offline')) as network:
            Frozen.instant=datetime(2026,10,16,16,7,44,tzinfo=UTC)
            with self.assertRaises(ValueError):live_nav.prepare(Path(tmp),29,90,domestic_only=True)
            self.assertTrue(network.called)
        self.assertTrue(all(c.args[0].startswith(('https://data.gnss.ga.gov.au/','https://igs.bkg.bund.de/','https://geodesy.noaa.gov/')) for c in network.call_args_list))

if __name__=='__main__':unittest.main()
