"""Phone/Mac GLONASS converter parity; no USB or radio access."""
from datetime import timedelta
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from glonass_lab import convert
from test_glonass_lab import START,records,glo_models

ROOT=Path(__file__).resolve().parent.parent
JAVA=Path(__import__('os').environ.get('JAVA_HOME', '/Library/Java/JavaVirtualMachines/current/Contents/Home'))/'bin'

class GlonassMobileTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp=tempfile.TemporaryDirectory();cls.folder=Path(cls.tmp.name)
        helper=cls.folder/'GlonassParity.java'
        helper.write_text('''package local.hackrf.controller;
import java.nio.file.*;import java.time.*;
public class GlonassParity {public static void main(String[] a)throws Exception {
Files.writeString(Path.of(a[3]),GlonassNavigation.convert(Files.readString(Path.of(a[0])),Files.readString(Path.of(a[1])),Instant.parse(a[2]),18,Integer.parseInt(a[4])));
}}
''')
        source=ROOT/'mobile-controller/src/main/java/local/hackrf/controller'
        subprocess.run([str(JAVA/'javac'),'-d',str(cls.folder),str(source/'RinexNav.java'),str(source/'GlonassNavigation.java'),str(helper)],check=True)

    @classmethod
    def tearDownClass(cls):cls.tmp.cleanup()

    def phone(self,broadcast,models,start=START,duration=90):
        for name,text in [('broadcast',broadcast),('models',models)]:
            (self.folder/name).write_text(text)
        result=subprocess.run([str(JAVA/'java'),'-cp',str(self.folder),'local.hackrf.controller.GlonassParity',str(self.folder/'broadcast'),str(self.folder/'models'),start.isoformat(),str(self.folder/'output'),str(duration)],capture_output=True,text=True)
        return result,(self.folder/'output').read_text() if result.returncode==0 else None

    def test_valid_vectors_and_zero_reference_match_byte_for_byte(self):
        for model in (glo_models(),glo_models(tow=0,week=0)):
            for duration in (90,180):
                expected,_=convert(records(),model,START,18,duration)
                result,actual=self.phone(records(),model,duration=duration)
                self.assertEqual(result.returncode,0,result.stderr);self.assertEqual(actual,expected)

    def test_both_reject_unhealthy_expired_truncated_and_invalid_models(self):
        cases=[(records(health=1),glo_models()),(records(flags=7),glo_models()),(records(flags=2),glo_models()),(records(shift=-1800),glo_models()),(records(channel=7),glo_models()),(records(channel=0),glo_models()),(records(),glo_models(a1=1e-12)),(records(),glo_models(gps_a0=.002)),(records(),glo_models(week=100)),(records(),glo_models().replace('GLGP','XXXX')),(records().rsplit('\n',2)[0],glo_models())]
        for broadcast,model in cases:
            with self.subTest(broadcast=broadcast[-80:],model=model[:80]):
                with self.assertRaises(ValueError):convert(broadcast,model,START,18,90)
                result,_=self.phone(broadcast,model)
                self.assertNotEqual(result.returncode,0)

    def test_accepted_mac_rf_source_matches_phone_conversion(self):
        manifest=ROOT/'research/output/app-jobs/glonass-mac-rf-20261017-155620/navigation.json'
        if not manifest.exists():self.skipTest('Retained Mac RF source not available')
        from datetime import datetime
        timing=json.loads(manifest.read_text());folder=Path(timing['sourceFolder']);start=datetime.fromisoformat(timing['startUTC'])
        broadcast=(folder/'source-broadcast.rnx').read_text();model=(folder/'source-models.rnx').read_text()
        expected,_=convert(broadcast,model,start,18,90)
        result,actual=self.phone(broadcast,model,start)
        self.assertEqual(result.returncode,0,result.stderr);self.assertEqual(actual,expected)

if __name__=='__main__':unittest.main()
