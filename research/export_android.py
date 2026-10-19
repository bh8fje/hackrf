"""Export a current Mac-validated I/Q scene for the Android USB controller."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

from live_nav import launch_delay
from walking import validate


def export(folder, destination):
    folder, destination = Path(folder), Path(destination)
    result = json.loads((folder / 'result.json').read_text())
    settings = json.loads((folder / 'settings.json').read_text())
    validate(settings)
    if result.get('passed') is not True or result.get('validFixes', 0) < 100 or not 0 <= result.get('maxError', float('inf')) <= 30:
        raise ValueError('Scene has not passed independent receiver validation')
    launch_delay(result['navigation'])
    iq = folder / 'signal.bin'
    if iq.stat().st_size != int(settings['duration']) * 5_200_000:
        raise ValueError('I/Q length changed')
    with iq.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if digest != result.get('iqSHA256'):
        raise ValueError('I/Q SHA-256 changed')
    manifest = {'schema': 1, 'type': 'hackrf-gps-iq', 'format': 's8-iq', 'sampleRate': 2600000,
                'frequencyHz': 1575420000, 'durationSeconds': int(settings['duration']),
                'iqBytes': iq.stat().st_size, 'iqSHA256': digest, 'passed': True,
                'validatedBy': 'GNSS-SDR', 'validFixes': result['validFixes'],
                'maximumError3dM': result['maxError'], 'navigation': result['navigation'], 'settings': settings}
    # Stored ZIP avoids recompressing several GB on the control phone.
    with destination.open('xb') as output:
        try:
            with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
                archive.writestr('manifest.json', json.dumps(manifest, ensure_ascii=False, allow_nan=False))
                archive.write(iq, 'signal.bin')
        except BaseException:
            destination.unlink(missing_ok=True)
            raise
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    export(args.folder, args.destination)
    print(args.destination)
