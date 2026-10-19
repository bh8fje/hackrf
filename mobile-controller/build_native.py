"""Build the same pinned generator as an Android arm64 PIE executable."""
from pathlib import Path
import subprocess
import hashlib
import shutil
import sys
import json
import re
import ast
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'research'))
from build_generator import patched_source

root = Path(__file__).resolve().parent
ndk = Path(__import__('os').environ.get('ANDROID_NDK_HOME', str(Path.home()/'Library/Android/sdk/ndk/29.0.14206865')))
clang = ndk/'toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang'
folder = root/'build/nativeLibs/arm64-v8a'
folder.mkdir(parents=True, exist_ok=True)
source = root/'build/gpssim-android.c'
source.write_text(patched_source())
assets = root/'build/nativeAssets'
assets.mkdir(parents=True, exist_ok=True)
(assets/'generator-source.sha256').write_text(hashlib.sha256(source.read_bytes()).hexdigest()+'\n')
shutil.copyfile(root.parent/'research/gps-sdr-sim/LICENSE',assets/'gps-sdr-sim-LICENSE.txt')
shutil.copyfile(root/'data/Leap_Second.dat',assets/'Leap_Second.dat')
shutil.copyfile(root.parent/'shared/map.html',assets/'map.html')
shutil.copyfile(root.parent/'shared/navigation-sources.json',assets/'navigation-sources.json')
policy=json.loads((root.parent/'shared/signal-environments.json').read_text())
shutil.copyfile(root.parent/'shared/signal-environments.json',assets/'signal-environments.json')
java_policy=(root/'src/main/java/local/hackrf/controller/SignalProfile.java').read_text()
def strings(name):
    return re.findall(r'"([^"\n]+)"',re.search(name+r'=\{([^}]+)',java_policy)[1])
assert strings('IDS')==[p['id'] for p in policy['profiles']]
assert strings('LABELS')==[p['label'] for p in policy['profiles']]
indices=[int(v) for v in re.search(r'PROFILES=\{([^}]+)',java_policy)[1].split(',')]
assert dict(zip(strings('ROUTES'),[strings('IDS')[i] for i in indices]))==policy['routes'], 'Motion/environment mapping differs across platforms'

# Bundle the current Mac defaults and locations so Android builds cannot silently lag behind.
mac = (root.parent/'app/WalkLab.swift').read_text()
def expression(value):
    if value=='true': return True
    if value=='false': return False
    node=ast.parse(value,mode='eval').body
    def number(n):
        if isinstance(n,ast.Constant): return n.value
        if isinstance(n,ast.UnaryOp) and isinstance(n.op,ast.USub): return -number(n.operand)
        if isinstance(n,ast.BinOp) and isinstance(n.op,(ast.Add,ast.Div)):
            return number(n.left)+number(n.right) if isinstance(n.op,ast.Add) else number(n.left)/number(n.right)
        raise ValueError('Unsupported Mac default: '+value)
    return number(node)
settings = dict((name,expression(value)) for name,value in re.findall(r'var (\w+) = ([^\n]+)',mac.split('struct SavedLocation:')[0]))
assert len(settings)==23, 'Mac settings changed; update Android mapping and checks'
locations=[]
preset_block=mac.split("static let presets = [",1)[1].split("\n    ]",1)[0]
for alias,lat,lon,system,height in re.findall(r'SavedLocation\(alias: "([^"]+)", latitude: (-?[\d.]+), longitude: (-?[\d.]+), coordinateSystem: "([^"]+)", referenceElevation: (\d+)\)',preset_block):
    locations.append(dict(alias=alias,latitude=float(lat),longitude=float(lon),coordinateSystem=system,referenceElevation=float(height),builtinKey=alias))
assert locations and len(locations)==preset_block.count("SavedLocation("), "Unparsed Mac preset; update Android mapping"
(assets/'mac-defaults.json').write_text(json.dumps(settings,ensure_ascii=False))
(assets/'mac-locations.json').write_text(json.dumps(locations,ensure_ascii=False))
license_file=root/'licenses/eviltransform-LICENSE.txt'
shutil.copyfile(license_file,assets/license_file.name)
subprocess.run([str(clang), '-O3', '-fPIE', '-pie', '-Wl,-z,max-page-size=16384',
                '-D_FILE_OFFSET_BITS=64', '-DUSER_MOTION_SIZE=6002',
                '-I', str(root.parent/'research/gps-sdr-sim'), str(source), '-lm', '-o', str(folder/'libgpssim.so')], check=True)
print(folder/'libgpssim.so')

# The BDS experiment uses the same audited sources and patches as Mac.
from build_bds_generator import build as build_bds
bds_binary=build_bds(android=True)
shutil.copyfile(bds_binary,folder/'libbdssim.so')
shutil.copyfile(bds_binary.with_name('provenance.json'),assets/'bds-generator-provenance.json')

# Same bounded FIFO mixer as Mac; static libc++ avoids another packaged runtime.
mixer_source=root.parent/'research/mixed_stream.cpp'
clang_cpp=clang.with_name(clang.name+'++')
subprocess.run([str(clang_cpp),'-std=c++17','-O3','-ffast-math','-fPIE','-pie','-static-libstdc++',
                '-Wl,-z,max-page-size=16384',str(mixer_source),'-o',str(folder/'libmixedstream.so')],check=True)
(assets/'mixed-stream-source.sha256').write_text(hashlib.sha256(mixer_source.read_bytes()).hexdigest()+'\n')
print(folder/'libmixedstream.so')
