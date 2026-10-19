"""Build an auditable local timing fix without changing the upstream submodule."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent


def patched_source():
    source = (root / 'gps-sdr-sim/gpssim.c').read_text()
    old = 'subGpsTime(g0, gmin)<0.0 || subGpsTime(gmax, g0)<0.0'
    new = 'subGpsTime(g0, gmin)<-SECONDS_IN_HOUR || subGpsTime(g0, gmax)>SECONDS_IN_HOUR'
    if source.count(old) != 1:
        raise RuntimeError('Upstream timing guard changed; review the local patch')
    source=source.replace(old,new)
    def replace_once(old,new):
        nonlocal source
        if source.count(old)!=1:raise RuntimeError('Generator environment patch anchor changed: '+old[:60])
        source=source.replace(old,new)
    header=(root/'signal_environment.h').read_text().replace('#include "signal_environment_curve.h"',(root/'signal_environment_curve.h').read_text())
    replace_once('int sinTable512[] = {',header+'\nint sinTable512[] = {')
    replace_once('int gain[MAX_CHAN];','int gain[MAX_CHAN];\n\tint environment=-1; uint32_t environment_seed=1;')
    replace_once('e:u:x:g:c:l:o:s:b:L:T:t:d:ipv','e:u:x:g:c:l:o:s:b:L:T:t:d:Q:S:ipv')
    replace_once("case 'e':", "case 'Q':\n\t\t\tenvironment=atoi(optarg); if(environment<0||environment>3){fprintf(stderr, \"Invalid environment\\n\");return 1;} break;\n\t\tcase 'S': environment_seed=(uint32_t)strtoul(optarg,NULL,10);break;\n\t\tcase 'e':")
    replace_once('tstart = clock();','if(environment>=0){env_init(environment_seed);fprintf(stderr,"Environment=%d seed=%u; six strongest protected; relative noise, uncalibrated C/N0\\n",environment,environment_seed); }\n\ttstart = clock();')
    replace_once('\t\tfor (isamp=0; isamp<iq_buff_size; isamp++)', '\t\tif(environment>=0)env_apply(chan,gain,environment,(iumd-1)*.1,environment_seed);\n\t\tfor (isamp=0; isamp<iq_buff_size; isamp++)')
    replace_once('iq8_buff[isamp] = iq_buff[isamp]>>4;', 'iq8_buff[isamp] = environment<0 ? iq_buff[isamp]>>4 : env_sample(iq_buff[isamp],environment);')
    return source


def build():
    # TOC is an orbit reference, not its expiry; retain the existing ±1 hour selection guard.
    folder = root / 'output/generator'
    folder.mkdir(parents=True, exist_ok=True)
    patched = folder / 'gpssim.c'
    patched.write_text(patched_source())
    binary = folder / 'gps-sdr-sim'
    subprocess.run(['clang', '-O3', '-Wall', '-D_FILE_OFFSET_BITS=64', '-DUSER_MOTION_SIZE=6002',
                    '-I', str(root/'gps-sdr-sim'), str(patched), '-lm', '-o', str(binary)], check=True)
    print(binary)


if __name__ == '__main__':
    build()
