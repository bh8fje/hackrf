"""Native attenuation integration; uses a retained local Galileo navigation fixture, never RF."""
import hashlib
import json
import re
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from build_bds_generator import patched_files

ROOT=Path(__file__).resolve().parent

class SampledEnvironmentTest(unittest.TestCase):
    def test_production_commands_apply_shared_route_environment(self):
        from unittest.mock import patch
        from datetime import datetime,timezone
        import bds_lab,galileo_lab,glonass_lab
        from walking import DEFAULTS,signal_environment
        class Captured(Exception):pass
        start=datetime(2026,10,17,16,22,58,tzinfo=timezone.utc)
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);build=root/'output/bds-build-current';build.mkdir(parents=True);(build/'provenance.json').write_text('{}')
            (root/'gnss-sdr.conf').write_text((ROOT/'gnss-sdr.conf').read_text())
            nav=root/'nav.rnx';nav.write_text('fixture');sources=root/'sources';sources.mkdir();(sources/'source-iers.dat').write_text('fixture')
            for module,route,index in [(bds_lab,'stationary',3),(galileo_lab,'circle',0),(glonass_lab,'line',2)]:
                settings=DEFAULTS|{'duration':90,'route':route,'seed':123}
                self.assertEqual(signal_environment(settings)['profileIndex'],index)
                def execute(command,folder,log,timeout):
                    if log=='visibility.log':(folder/log).write_text('fixture')
                    if log=='generator.log':
                        self.assertEqual(command[command.index('-Q')+1],str(index));self.assertEqual(command[command.index('-S')+1],'123')
                        if module in (bds_lab,glonass_lab,galileo_lab):
                            self.assertIn('-mt',command);self.assertIn('OMP_NUM_THREADS=4',command);self.assertIn('OMP_DYNAMIC=FALSE',command)
                        raise Captured()
                with patch.object(module,'ROOT',root):
                    if module is bds_lab:
                        with patch('bds_navigation.prepare',return_value=(nav,{'startUTC':start.isoformat(),'mode':'current-broadcast'})):
                            with self.assertRaises(Captured):module.run(settings,root/'bds',execute,lambda *a,**k:None)
                    elif module is galileo_lab:
                        with patch.object(module,'prepare',return_value=(nav,{'startUTC':start.isoformat()})):
                            with self.assertRaises(Captured):module.run(settings,root/'galileo',execute,lambda *a,**k:None)
                    else:
                        with patch.object(module,'navigation_source',return_value=('fixture',{},'fixture','fixture')),patch.object(module,'gps_utc_offset',return_value=18),patch.object(module,'production',return_value=start),patch.object(module,'generated_slots',return_value=[1,2,3,4]):
                            with self.assertRaises(Captured):module.run(settings,root/'glonass',sources,execute,lambda *a,**k:None)

    def test_patch_only_changes_amplitude_when_enabled(self):
        files=patched_files()
        self.assertIn('int Environment=-1;',files['IFdataGen.cpp'])
        self.assertIn('if(attenuationDb!=0) Amp *= pow(10,-attenuationDb/20)',files['src/SatIfSignal.cpp'])
        self.assertEqual(files['inc/SignalEnvironmentCurve.h'],(ROOT/'signal_environment_curve.h').read_text())

    def test_native_iq_and_argument_validation(self):
        binary=ROOT/'output/bds-build-current/IFdataGen'
        case=ROOT/'output/phone-galileo-rf-build23-20261017'
        if not binary.exists() or not (case/'scenario.json').exists():
            self.skipTest('Requires native build and retained local Galileo fixture')
        files=patched_files()
        digest=hashlib.sha256(''.join(name+'\0'+text for name,text in sorted(files.items())).encode()).hexdigest()
        provenance=json.loads((binary.parent/'provenance.json').read_text())
        self.assertEqual(digest,provenance['patchedSourceSHA256'],'Rebuild native binary before integration check')
        config=json.loads((case/'scenario.json').read_text())
        config['ephemeris']['name']=str(case/'broadcast-galileo.rnx')
        config['trajectory']['trajectoryList'][0]['time']=.05
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);settings=folder/'scenario.json';settings.write_text(json.dumps(config,indent=2))
            outputs=[]
            for name,args,mode in [('baseline',[],'-st'),('repeat',[],'-st'),('office',['-Q','3','-S','42'],'-st'),('office-repeat',['-Q','3','-S','42'],'-st'),('other-seed',['-Q','3','-S','43'],'-st'),('baseline-parallel',[],'-mt'),('office-parallel',['-Q','3','-S','42'],'-mt')]:
                target=folder/(name+'.bin')
                generated=subprocess.run([str(binary),'-c',str(settings),'-o',str(target),mode,*args],capture_output=True,check=True,timeout=30,env=dict(os.environ,OMP_NUM_THREADS='4',OMP_WAIT_POLICY='PASSIVE'))
                if mode=='-mt':self.assertIn('PARALLEL execution (4 threads',generated.stdout.decode())
                if name=='office':
                    ranks=[(int(cn0),float(elevation),int(svid),int(rank)) for svid,cn0,elevation,rank in re.findall(r'ENV RANK svid=(\d+) cn0=(\d+) elevation=([\d.e+-]+) rank=(\d+)',generated.stdout.decode())]
                    self.assertGreaterEqual(len(ranks),4)
                    self.assertEqual([r[3] for r in sorted(ranks,key=lambda r:(-r[0],-r[1],r[2]))],list(range(len(ranks))))
                outputs.append(target.read_bytes())
            self.assertEqual(len(outputs[0]),1600000)
            self.assertEqual(outputs[0],outputs[1])
            self.assertNotEqual(outputs[0],outputs[2])
            self.assertEqual(outputs[2],outputs[3])
            self.assertNotEqual(outputs[2],outputs[4])
            self.assertEqual(outputs[0],outputs[5])
            self.assertEqual(outputs[2],outputs[6])
        for args in [('-Q','4'),('-Q','-1'),('-S','x'),('-S','2147483648'),('-Q',)]:
            self.assertEqual(subprocess.run([str(binary),*args],capture_output=True,timeout=10).returncode,1)

if __name__=='__main__':unittest.main()
