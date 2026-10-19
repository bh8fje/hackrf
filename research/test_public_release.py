"""Public release contracts; never opens a radio or reads user settings."""
from pathlib import Path
import re
import ast
import unittest

ROOT = Path(__file__).resolve().parent.parent

class PublicReleaseTest(unittest.TestCase):
    def test_presets_and_android_export(self):
        source = (ROOT/'app/WalkLab.swift').read_text()
        block = source.split('static let presets = [', 1)[1].split('\n    ]', 1)[0]
        places = re.findall(r'SavedLocation\(alias: "([^"]+)", latitude: (-?[\d.]+), longitude: (-?[\d.]+), coordinateSystem: "([^"]+)", referenceElevation: (\d+)\)', block)
        self.assertEqual([p[0] for p in places], ['白宫', '唐宁街10号', '国会山'])
        self.assertEqual(len(places), block.count('SavedLocation('))
        self.assertTrue(all(float(p[2]) < 0 for p in places))
        self.assertIn('longitude: (-?[\\d.]+)', (ROOT/'mobile-controller/build_native.py').read_text())

    def test_negative_default_export(self):
        tree=ast.parse((ROOT/'mobile-controller/build_native.py').read_text())
        function=next(n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='expression')
        context={'ast':ast}
        exec(compile(ast.Module(body=[function],type_ignores=[]),'export','exec'),context)
        self.assertEqual(context['expression']('-77.03637'),-77.03637)

    def test_no_credentials_or_machine_paths(self):
        for folder in ['app','shared','research','mobile','mobile-controller']:
            for path in (ROOT/folder).rglob('*'):
                if any(p in path.parts for p in ['build','.venv','.gradle','__pycache__','gps-sdr-sim','SignalSim']): continue
                if path.suffix not in {'.java','.swift','.py','.xml','.json','.gradle','.js','.html','.md','.conf'}: continue
                text = path.read_text()
                self.assertNotRegex(text, r'/' + r'Users/[A-Za-z0-9_.-]+/', str(path.relative_to(ROOT)))
                self.assertNotRegex(text, r'\b(?:gh[pousr]_[A-Za-z0-9]{20,}|AKIA[A-Z0-9]{16})\b')
                self.assertNotRegex(text, r'(?:key|apikey|securityJsCode)["\s:=]+["\x27]?[a-fA-F0-9]{32}\b')

    def test_receiver_has_no_default_target(self):
        activity=(ROOT/'mobile/src/main/java/local/hackrf/receiver/MainActivity.java').read_text()
        service=(ROOT/'mobile/src/main/java/local/hackrf/receiver/RecordingService.java').read_text()
        self.assertIn('double targetLat = Double.NaN, targetLon = Double.NaN;',service)
        for label in ['目标纬度 WGS-84','目标经度 WGS-84']:
            self.assertIn('field(root, "'+label+'", Double.NaN)',activity)
        self.assertIn('Geometry.targetText(service.targetLat)',activity)
        self.assertIn('Geometry.targetText(service.targetLon)',activity)
        self.assertNotRegex(activity+service,r'\d+°\d+′')

    def test_storage_and_map_contracts(self):
        swift = (ROOT/'app/WalkLab.swift').read_text()
        self.assertIn('WalkLabPublic/amap.json', swift)
        self.assertIn('suiteName: "local.hackrf.walklab.public"', swift)
        java = (ROOT/'mobile-controller/src/main/java/local/hackrf/controller/LocationMap.java').read_text()
        self.assertIn('showOpenMap()', java)
        self.assertIn('MapsInitializer.setApiKey(key)', java)
        self.assertIn('getString("amapKey", "")', java)
        self.assertNotIn('apikey', (ROOT/'mobile-controller/src/main/AndroidManifest.xml').read_text())
        for folder, app in [('mobile-controller','controller'),('mobile','receiver')]:
            self.assertIn("applicationId 'local.hackrf."+app+".public'", (ROOT/folder/'build.gradle').read_text())

if __name__ == '__main__': unittest.main()
