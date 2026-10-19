"""Independent LNAV word checks before reusing SignalSim GPS in mixed output."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from build_bds_generator import SOURCE, patched_files


class MixedNativeTest(unittest.TestCase):
    def test_lnav_parity_fields_and_shared_time(self):
        # Receiver-style parity masks are independent of SignalSim's nibble tables.
        source = r'''
#include "LNavBit.h"
#include "GnssTime.h"
#include <cmath>
#include <stdexcept>
static void check(bool ok) { if(!ok) throw std::runtime_error("LNAV independent check"); }
static unsigned parity(unsigned data, unsigned previous) {
    const unsigned masks[]={0x3B1F3480,0x1D8F9A40,0x2EC7CD00,0x1763E680,0x2BB1F340,0x0B7A89C0};
    unsigned p=0;
    for(int k=0;k<6;k++)p=(p<<1)|((__builtin_popcount(data&masks[k])+((previous>>(k==0||k==2||k==5?1:0))&1))&1);
    return p;
}
int main() {
    UTC_TIME utc={2026,10,18,8,0,0};
    auto gps=UtcToGpsTime(utc),bds=UtcToBdsTime(utc);
    check(gps.Week-bds.Week==1356 && gps.MilliSeconds-bds.MilliSeconds==14000);
    LNavBit nav;
    GPS_EPHEMERIS e={};e.valid=1;e.sqrtA=5153.7;e.ecc=.01;
    e.iodc=0x303;e.iode=3;e.toc=345600;e.toe=345600;e.health=0;
    for(int sv=1;sv<=32;sv++) {
        check(nav.SetEphemeris(sv,&e)==sv);
        for(int frame=0;frame<125;frame++) {
            GNSS_TIME time={2440,frame*6000,0};int bits[300];
            check(nav.GetFrameData(time,sv,0,bits)==0);
            unsigned previous=0,data[10]={};
            for(int w=0;w<10;w++) {
                unsigned word=0;
                for(int bit=0;bit<30;bit++){check(bits[30*w+bit]==0||bits[30*w+bit]==1);word=(word<<1)|bits[30*w+bit];}
                unsigned raw=word&0x3fffffc0;
                if(previous&1)raw^=0x3fffffc0;
                check(parity(raw,previous)==(word&63));data[w]=raw>>6;previous=word&3;
                if(w==1||w==9)check(previous==0);
            }
            check(data[0]>>16==0x8b);
            check((data[1]>>7)==unsigned(frame+1));
            unsigned subframe=(data[1]>>2)&7;
            check(subframe==unsigned(frame%5+1));
            if(subframe==1) {
                check((data[2]>>14)==(2440&1023));
                check(((data[2]&3)<<8 | (data[7]>>16))==0x303);
                check((data[7]&65535)*16==345600);
            }
            if(subframe==2) {check(data[2]>>16==3);check((data[9]>>8)*16==345600);}
            if(subframe==3)check(data[9]>>16==3);
        }
    }
}
'''
        files=patched_files()
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp)
            for name in ('src/LNavBit.cpp','src/NavBit.cpp','src/GnssTime.cpp'):
                (folder/Path(name).name).write_text(files[name])
            (folder/'check.cpp').write_text(source)
            binary=folder/'check'
            subprocess.run(['c++','-std=c++20','-O2','-I'+str(SOURCE/'inc'),str(folder/'check.cpp'),*[str(folder/name) for name in ('LNavBit.cpp','NavBit.cpp','GnssTime.cpp')],'-o',str(binary)],check=True,capture_output=True)
            subprocess.run([str(binary)],check=True,capture_output=True)


if __name__=='__main__':
    unittest.main()
