"""Build the workspace-backed native Mac app without bundling replacement runtimes."""
import os
import argparse
import shutil
from pathlib import Path
import plistlib
import subprocess

root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=root / 'build/天书.app', help='Build a staging bundle without replacing the running app')
args = parser.parse_args()
bundle = args.output.resolve()
contents = bundle / 'Contents'
(contents / 'MacOS').mkdir(parents=True, exist_ok=True)
executable = contents / 'MacOS/WalkLab'
resources = contents / 'Resources'
resources.mkdir(parents=True, exist_ok=True)
shutil.copyfile(root.parent/'shared/map.html', resources/'map.html')
shutil.copyfile(root/'map_server.py', resources/'map_server.py')
(resources/'amap.html').write_text((root/'amap.html').read_text().replace('/*AMAP_SCRIPT*/', (root/'amap.js').read_text()))
shutil.copyfile(root.parent/'shared/navigation-sources.json', resources/'navigation-sources.json')
shutil.copyfile(root.parent/'shared/signal-environments.json', resources/'signal-environments.json')
iconset = root / 'build/AppIcon.iconset'
subprocess.run(['swift', str(root / 'make_icon.swift'), str(iconset), str(root / 'assets/AppIcon.png')], check=True)
subprocess.run(['iconutil', '-c', 'icns', str(iconset), '-o', str(resources / 'AppIcon.icns')], check=True)

subprocess.run(['swiftc', '-parse-as-library', '-swift-version', '6', '-target', 'arm64-apple-macosx14.0',
                '-O', str(root / 'WalkLab.swift'), '-o', str(executable)], check=True)
with (contents / 'Info.plist').open('wb') as stream:
    plistlib.dump({'CFBundleIdentifier': 'local.hackrf.walklab.public', 'CFBundleName': '天书',
                  'CFBundleDisplayName': '天书', 'CFBundleExecutable': 'WalkLab',
                  'CFBundleIconFile': 'AppIcon', 'CFBundlePackageType': 'APPL', 'CFBundleShortVersionString': '0.9.13', 'CFBundleVersion': '62',
                  'LSMinimumSystemVersion': '14.0', 'NSHighResolutionCapable': True,
                  'NSAppTransportSecurity': {'NSAllowsLocalNetworking': True},
                  'NSLocationWhenInUseUsageDescription': '识别当前城市，作为地点文字查找的默认城市。不会改变模拟目标。', 'NSLocationUsageDescription': '识别当前城市用于地点查找。', 'ProjectRoot': str(root.parent)}, stream)
subprocess.run(['codesign', '--force', '--deep', '--sign', '-', str(bundle)], check=True)
subprocess.run([str(executable), '--self-test'], check=True)
print(bundle)
