from datetime import datetime,timedelta,timezone
from pathlib import Path
import subprocess,tempfile,unittest
from bds_navigation import convert, prepare
from unittest.mock import patch
import bds_navigation

ROOT=Path(__file__).resolve().parent.parent
FIX=ROOT/'mobile-controller/tests/fixtures'
START=datetime(2026,10,17,6,27,25,tzinfo=timezone.utc)
JAVA=Path(__import__('os').environ.get('JAVA_HOME', '/Library/Java/JavaVirtualMachines/current/Contents/Home'))/'bin'

class BdsNavigationTest(unittest.TestCase):
    def test_time_coverage_reserved_fields_and_model_integrity(self):
        raw=(FIX/'bds-broadcast.rnx').read_text();models=(FIX/'bds-models.rnx').read_text()
        text,meta=convert(raw,models,START,18,90)
        self.assertEqual(meta['startBDT'],'2026-10-17T06:27:29')
        self.assertEqual(len(convert(raw,models,START,18,600)[1]['healthyCoveredPRNs']),4)
        with self.assertRaises(ValueError):convert(raw,models,START,18,601)
        self.assertEqual(len(meta['healthyCoveredPRNs']),4)
        self.assertIn(raw.split('END OF HEADER\n')[1],text)
        self.assertEqual(len(convert(raw,models,START+timedelta(hours=1),18,90)[1]["healthyCoveredPRNs"]),4)
        with self.assertRaises(ValueError):convert(raw,models,START+timedelta(hours=2),18,90)
        with self.assertRaises(ValueError):convert(raw,models.replace('BDSB','GPSB'),START,18,90)
        with self.assertRaises(ValueError):convert(raw,models,START,17,90)
        rows=raw.splitlines(True);i=next(i for i,r in enumerate(rows) if r.startswith('C'))
        # Reserved RINEX BDS cells may be blank; health and required fields may not.
        rows[i+5]=rows[i+5][:23]+' '*19+rows[i+5][42:61]+' '*19+'\n'
        self.assertEqual(convert(''.join(rows),models,START,18,90)[1]['healthyCoveredPRNs'],meta['healthyCoveredPRNs'])
        rows[i+6]=rows[i+6][:23]+f'{1:19.12E}'+rows[i+6][42:]
        with self.assertRaises(ValueError):convert(''.join(rows),models,START,18,90)

    def test_current_fallback_checks_orbit_coverage(self):
        class Frozen(datetime):
            @classmethod
            def now(cls,tz=None):return START
        raw=(FIX/'bds-broadcast.rnx').read_text();models=(FIX/'bds-models.rnx').read_text()
        # Keep the real header boundary, but remove every orbit record.
        bad='\n'.join(raw.splitlines()[:next(i for i,r in enumerate(raw.splitlines()) if r[60:].strip()=='END OF HEADER')+1])+'\n'
        urls=['https://igs.bkg.bund.de/stream','https://igs.bkg.bund.de/mixed']
        def download(url,*args,**kwargs):return ((bad if url==urls[0] else models+raw.split('END OF HEADER\n')[1]),{'url':url})
        with tempfile.TemporaryDirectory() as tmp,patch.object(bds_navigation,'datetime',Frozen),patch.object(bds_navigation,'navigation_sources',return_value=urls),patch.object(bds_navigation,'publication',return_value=START),patch.object(bds_navigation,'gps_utc_offset',return_value=18),patch.object(bds_navigation,'cached_fetch',side_effect=download):
            nav,meta=prepare(Path(tmp),90)
            self.assertEqual(meta['sources'][0]['url'],urls[1])
            self.assertTrue(nav.is_file())
            self.assertEqual(meta['startUTC'],START.isoformat())

    def test_domestic_orbits_refresh_while_models_allow_validated_cache(self):
        class Frozen(datetime):
            @classmethod
            def now(cls,tz=None):return START
        raw=(FIX/'bds-broadcast.rnx').read_text();models=(FIX/'bds-models.rnx').read_text()
        orbit='\n'.join(r for r in raw.splitlines() if r[:4] not in ('BDSA','BDSB','BDUT'))+'\n'
        urls=['https://www.csno-tarc.cn/orbits','https://igs.bkg.bund.de/models']
        def download(url,*args,**kwargs):return (orbit if url==urls[0] else models,{'url':url,'cached':not kwargs['force']})
        with tempfile.TemporaryDirectory() as tmp,patch.object(bds_navigation,'datetime',Frozen),patch.object(bds_navigation,'navigation_sources',return_value=urls),patch.object(bds_navigation,'publication',return_value=START),patch.object(bds_navigation,'gps_utc_offset',return_value=18),patch.object(bds_navigation,'cached_fetch',side_effect=download) as network:
            _,meta=prepare(Path(tmp),90)
            self.assertEqual([c.kwargs['force'] for c in network.call_args_list],[True,False])
            self.assertEqual(meta['sources'][0]['url'],urls[0]);self.assertTrue(meta['sources'][1]['cached'])
            self.assertEqual(len(meta['healthyCoveredPRNs']),4)

    def test_java_identical_and_rejects_stale(self):
        source=ROOT/'mobile-controller/src/main/java/local/hackrf/controller'
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);helper=folder/'BdsCheck.java'
            helper.write_text('''package local.hackrf.controller;import java.nio.file.*;import java.time.*;public class BdsCheck{public static void main(String[] a)throws Exception{Path p=Path.of(a[0]);System.out.print(BdsNavigation.convert(Files.readString(p.resolve("bds-broadcast.rnx")),Files.readString(p.resolve("bds-models.rnx")),Instant.parse(a[1]),18,a.length>2?Integer.parseInt(a[2]):90));}}''')
            subprocess.run([str(JAVA/'javac'),'-d',tmp,str(source/'RinexNav.java'),str(source/'BdsNavigation.java'),str(helper)],check=True)
            expected=convert((FIX/'bds-broadcast.rnx').read_text(),(FIX/'bds-models.rnx').read_text(),START,18,90)[0]
            command=[str(JAVA/'java'),'-cp',tmp,'local.hackrf.controller.BdsCheck',str(FIX)]
            self.assertEqual(subprocess.check_output(command+[START.isoformat()],text=True),expected)
            for duration in (181,300,600):
                self.assertEqual(subprocess.check_output(command+[START.isoformat(),str(duration)],text=True),convert((FIX/'bds-broadcast.rnx').read_text(),(FIX/'bds-models.rnx').read_text(),START,18,duration)[0])
            self.assertNotEqual(subprocess.run(command+[START.isoformat(),'601'],capture_output=True).returncode,0)
            later=START+timedelta(hours=1)
            self.assertEqual(subprocess.check_output(command+[later.isoformat()],text=True),convert((FIX/'bds-broadcast.rnx').read_text(),(FIX/'bds-models.rnx').read_text(),later,18,90)[0])
            self.assertNotEqual(subprocess.run(command+[(START+timedelta(hours=2)).isoformat()],capture_output=True).returncode,0)

    def test_native_model_patch_anchors(self):
        from build_bds_generator import patched_files
        files=patched_files();rinex=files['src/Rinex.cpp']
        self.assertIn('strstr(str, "BDSB")',rinex)
        self.assertIn('if (fabs(delta_t) > 7200.0)',files['src/Coordinate.cpp'])
        self.assertNotIn('fabs(delta_t) > 3600.0',files['src/Coordinate.cpp'])
        self.assertIn('&(Iono->b3)',rinex)
        self.assertIn('Initialize(BdsSystem, BdsEph[i], &BdsIonoModel',files['IFdataGen.cpp'])
        self.assertIn('BdsTime.MilliSeconds / 3600000',files['IFdataGen.cpp'])
        self.assertIn('if(TotalChannelNumber==0)',files['IFdataGen.cpp'])
        self.assertIn('refusing noise-only output',files['IFdataGen.cpp'])

if __name__=='__main__':unittest.main()
