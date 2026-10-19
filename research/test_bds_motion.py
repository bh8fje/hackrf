from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parent

class BdsMotionTest(unittest.TestCase):
    def test_native_linear_interpolation_and_rejection(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);source=folder/'check.cpp';binary=folder/'check';csv=folder/'path.csv'
            source.write_text('''#include "bds_motion.h"
#include <iostream>
int main(int argc,char** argv){try{int duration=argc>2?std::stoi(argv[2]):60;BdsMotion m;m.load(argv[1],duration);
auto p=m.at(50);if(std::abs(p.x-6378137.05)>1e-7 || std::abs(p.vx-1)>1e-7)return 2;
int count=0;while(m.next(p))count++;if(count!=duration*1000 || std::abs(p.x-(6378137+duration))>1e-7)return 3;
return 0;}catch(const std::exception& e){std::cerr<<e.what();return 1;}}
''')
            subprocess.run(['c++','-std=c++20','-I'+str(ROOT),'-I'+str(ROOT/'SignalSim/inc'),str(source),'-o',str(binary)],check=True)
            rows=[f'{i/10:.1f},{6378137+i/10:.6f},0,0\n' for i in range(602)]
            csv.write_text(''.join(rows));subprocess.run([str(binary),str(csv)],check=True)
            csv.write_text(''.join(f'{i/10:.1f},{6378137+i/10:.6f},0,0\n' for i in range(6002)))
            subprocess.run([str(binary),str(csv),'600'],check=True)
            self.assertEqual(subprocess.run([str(binary),str(csv),'601'],capture_output=True).returncode,1)
            for bad in (rows[:-1],['0,nan,0,0\n']+rows[1:],rows[:4]+rows[5:],rows+rows[-1:],['0,6378137,0,0,2\n']+rows[1:],['0,6378137,0,0\n','0.1,6378150,0,0\n']+rows[2:]):
                csv.write_text(''.join(bad))
                self.assertEqual(subprocess.run([str(binary),str(csv)],capture_output=True).returncode,1)

if __name__=='__main__':unittest.main()
