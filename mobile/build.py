"""Build using the existing local Android SDK, shared JDK and cached Gradle."""
import os
import argparse
from pathlib import Path
import subprocess

source = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('project', nargs='?', type=Path, default=source)
parser.add_argument('--release', action='store_true', help='Build non-debug APK without device test components')
args = parser.parse_args()
root = args.project.resolve()
variant = 'release' if args.release else 'debug'
import shutil
java = Path(os.environ['JAVA_HOME'])
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Library/Android/sdk')))
gradle = shutil.which('gradle')
if not gradle: raise SystemExit('Install Gradle 8.10.2 and put it on PATH')
env = dict(os.environ, JAVA_HOME=str(java), ANDROID_HOME=str(sdk))
tests = root / 'build/selftest'
tests.mkdir(parents=True, exist_ok=True)
subprocess.run([str(java / 'bin/javac'), '-d', str(tests),
                str(source / 'src/main/java/local/hackrf/receiver/Geometry.java'),
                str(source / 'tests/GeometryTest.java')], check=True, env=env)
subprocess.run([str(java / 'bin/java'), '-ea', '-cp', str(tests),
                'local.hackrf.receiver.GeometryTest'], check=True, env=env)
native = root / 'build_native.py'
if native.is_file():
    subprocess.run([os.sys.executable, str(native)], check=True, env=env)
protocol = root / 'src/main/java/local/hackrf/controller/HackrfProtocol.java'
if protocol.is_file():
    subprocess.run([os.sys.executable, str(source.parent / 'research/test_unix_compress.py')], check=True, env=env)
    subprocess.run([str(java / 'bin/javac'), '-d', str(tests), str(protocol),
                    str(root / 'tests/HackrfProtocolTest.java'),
                    str(root / 'src/main/java/local/hackrf/controller/NavigationHttp.java'), str(root / 'tests/NavigationHttpTest.java'),
                    str(root / 'src/main/java/local/hackrf/controller/NavigationChunks.java'), str(root / 'tests/NavigationChunksTest.java'),
                    str(root / 'src/main/java/local/hackrf/controller/SampledSatellites.java'), str(root / 'tests/SampledSatellitesTest.java'),
                    str(root / 'src/main/java/local/hackrf/controller/Preparation.java'), str(root / 'tests/PreparationTest.java'), str(root / 'src/main/java/local/hackrf/controller/SignalProfile.java'),
                    str(root / 'src/main/java/local/hackrf/controller/IqPackets.java'),
                    str(root / 'tests/IqPacketsTest.java'), str(root / 'tests/MixedTransportTest.java'),
                    str(root / 'src/main/java/local/hackrf/controller/RinexNav.java'), str(root / 'tests/RinexNavTest.java'),
                    str(root / 'src/main/java/local/hackrf/controller/PlaceCoordinates.java'), str(root / 'tests/PlaceCoordinatesTest.java'),
                    str(root / 'src/main/java/local/hackrf/controller/PhoneMotion.java'), str(root / 'tests/PhoneMotionTest.java'), str(root / 'tests/PersonMotionTest.java')], check=True, env=env)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', str(tests), 'local.hackrf.controller.PlaceCoordinatesTest'], check=True, env=env)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', str(tests), 'local.hackrf.controller.PreparationTest'], check=True, env=env)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', str(tests), 'local.hackrf.controller.NavigationHttpTest'], check=True, env=env)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', str(tests), 'local.hackrf.controller.NavigationChunksTest'], check=True, env=env)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', str(tests), 'local.hackrf.controller.SampledSatellitesTest'], check=True, env=env)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', str(tests),
                    'local.hackrf.controller.HackrfProtocolTest'], check=True, env=env)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', str(tests),
                    'local.hackrf.controller.IqPacketsTest'], check=True, env=env)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', str(tests), 'local.hackrf.controller.MixedTransportTest'], check=True, env=env)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', str(tests),
                    'local.hackrf.controller.RinexNavTest', str(root / 'tests/fixtures')], check=True, env=env)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', str(tests),
                    'local.hackrf.controller.PhoneMotionTest'], check=True, env=env)
    subprocess.run([str(java / 'bin/java'), '-ea', '-cp', str(tests),
                    'local.hackrf.controller.PersonMotionTest'], check=True, env=env)
subprocess.run([str(gradle), '--console=plain', 'assembleRelease' if args.release else 'assembleDebug'],
               cwd=root, env=env, check=True)
outputs = list((root / f'build/outputs/apk/{variant}').glob('*.apk'))
if len(outputs) != 1:
    raise RuntimeError('Expected exactly one debug APK')
subprocess.run([str(sdk / 'build-tools/35.0.0/apksigner'), 'verify', str(outputs[0])], check=True, env=env)
print(outputs[0])
