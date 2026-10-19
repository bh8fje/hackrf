import hashlib
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
import tempfile
import unittest
import zipfile

from export_android import export
from walking import DEFAULTS


class AndroidExportTest(unittest.TestCase):
    def test_archive_integrity_and_rejections(self):
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            iq = folder / 'signal.bin'
            with iq.open('wb') as stream:
                stream.truncate(60 * 5_200_000)
            with iq.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            now = datetime.now(timezone.utc)
            result = {'passed': True, 'validFixes': 200, 'maxError': 30.0, 'iqSHA256': digest,
                      'navigation': {'mode': 'current-broadcast', 'startUTC': (now+timedelta(seconds=120)).isoformat(),
                                     'broadcastProducedAtUTC': now.isoformat()}}
            (folder/'result.json').write_text(json.dumps(result))
            (folder/'settings.json').write_text(json.dumps(DEFAULTS))
            package = folder/'scene.hriq'
            manifest = export(folder, package)
            with zipfile.ZipFile(package) as archive:
                self.assertEqual(archive.namelist(), ['manifest.json', 'signal.bin'])
                self.assertEqual(archive.getinfo('signal.bin').file_size, iq.stat().st_size)
                self.assertEqual(json.loads(archive.read('manifest.json')), manifest)
            with self.assertRaises(FileExistsError): export(folder, package)
            result['passed'] = False
            (folder/'result.json').write_text(json.dumps(result))
            with self.assertRaises(ValueError): export(folder, folder/'failed.hriq')
            result['passed'] = True
            result['navigation']['startUTC'] = (now-timedelta(seconds=5)).isoformat()
            (folder/'result.json').write_text(json.dumps(result))
            with self.assertRaises(ValueError): export(folder, folder/'expired.hriq')
            result['navigation']['startUTC'] = (now+timedelta(seconds=120)).isoformat()
            result['maxError'] = 30.01
            (folder/'result.json').write_text(json.dumps(result))
            with self.assertRaises(ValueError): export(folder, folder/'outside.hriq')
            result['maxError'] = 30
            result['iqSHA256'] = '0'*64
            (folder/'result.json').write_text(json.dumps(result))
            with self.assertRaises(ValueError): export(folder, folder/'tampered.hriq')
            self.assertFalse((folder/'tampered.hriq').exists())
