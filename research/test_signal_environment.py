"""Mild reception model regressions; never opens a radio device."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from build_generator import patched_source

ROOT = Path(__file__).resolve().parent

class SignalEnvironmentTest(unittest.TestCase):
    def test_mild_bounds_noise_and_repeatability(self):
        with tempfile.TemporaryDirectory() as tmp:
            executable=Path(tmp)/'environment-test'
            subprocess.run(['clang','-O2','-I',str(ROOT),str(ROOT/'test_signal_environment.c'),'-lm','-o',str(executable)],check=True)
            subprocess.run([str(executable)],check=True)

    def test_shared_curve_keeps_gps_pi_precision(self):
        header=(ROOT/'gps-sdr-sim/gpssim.h').read_text()
        pi=next(line.split()[-1] for line in header.splitlines() if line.startswith('#define PI '))
        self.assertIn('sin('+pi+'*x)',(ROOT/'signal_environment_curve.h').read_text())

    def test_curve_is_identical_in_c_and_cpp(self):
        # Compare the full sampled curve, not just bounds, across both native engines.
        source = r'''#include <stdio.h>
#include "signal_environment_curve.h"
int main(void) {
    for(int p=0;p<4;p++)printf("noise=%.17g\\n",env_noise_sigma(p));
    for(int p=0;p<4;p++)for(int n=4;n<=16;n++)for(int rank=0;rank<n;rank++)
        for(int t=0;t<=6000;t+=7)
            printf("%.17g\n",env_attenuation(p,rank,n,rank+1,t*.1,42));
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            src=Path(tmp)/'curve.c';src.write_text(source)
            outputs=[]
            for compiler,language in [('clang','c'),('clang++','c++')]:
                binary=Path(tmp)/language
                subprocess.run([compiler,'-x',language,'-O2','-I',str(ROOT),str(src),'-lm','-o',str(binary)],check=True)
                outputs.append(subprocess.check_output([str(binary)]))
            self.assertEqual(outputs[0],outputs[1])

    def test_patch_remains_opt_in_and_keeps_phase_updates(self):
        source=patched_source()
        self.assertIn('int environment=-1',source)
        self.assertIn('environment<0 ? iq_buff[isamp]>>4',source)
        self.assertIn('computeCodePhase(&chan[i], rho, 0.1)',source)
        self.assertIn('subGpsTime(g0, gmin)<-SECONDS_IN_HOUR',source)

if __name__=='__main__':unittest.main()
