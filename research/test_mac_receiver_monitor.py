"""Local decoder bridge checks: no hardware, phones or network."""
import json,os,subprocess,sys,tempfile,unittest
from pathlib import Path
from mac_receiver_monitor import LocalReceiver

def nmea(body):
    check=0
    for char in body:check^=ord(char)
    return '$'+body+'*'+f'{check:02X}'+'\n'

def fix(epoch='120000.00',quality=1):return nmea(f'GPGGA,{epoch},3039.4918508,N,10406.2209907,E,{quality},04,1.0,404.338,M,2.000,M,0.0,')

class LocalReceiverTest(unittest.TestCase):
    def test_partial_append_gaps_stale_and_rotation(self):
        with tempfile.TemporaryDirectory() as temp:
            file=Path(temp)/'fix.nmea';file.write_text(fix()[:-4]);state=LocalReceiver();state.read(file);self.assertFalse(state.path)
            with file.open('a') as out:out.write(fix()[-4:])
            state.read(file);value=state.snapshot(file,file.stat().st_mtime,now=file.stat().st_mtime)
            self.assertTrue(value['fresh']);self.assertAlmostEqual(value['height'],406.338);self.assertIsNone(value['accuracy']);self.assertEqual(value['hdop'],1)
            stamp=state.fix_modified
            with file.open('a') as out:out.write(nmea('BDGSA,A,3,10,24,25,34,,,,,,,,,4.0,3.1,2.6,3'))
            os.utime(file,(stamp+20,stamp+20));state.read(file)
            self.assertFalse(state.snapshot(file,stamp+20,now=stamp+20)['fresh'])
            with file.open('a') as out:out.write(fix('120015.00'))
            state.read(file);self.assertEqual(state.path[1],[])
            with file.open('a') as out:out.write(fix('120016.00',0))
            state.read(file);self.assertFalse(state.snapshot(file,file.stat().st_mtime)['fresh'])
            replacement=Path(temp)/'replacement';replacement.write_text(fix('000001.00'));replacement.replace(file);state.read(file)
            self.assertEqual(len(state.path),1)
    def test_checksum_and_satellites(self):
        state=LocalReceiver();state.ingest(fix().replace('*','*00'))
        self.assertFalse(state.path);self.assertEqual(state.invalid,1)
        state.ingest(nmea('BDGSA,A,3,10,24,25,34,,,,,,,,,4.0,3.1,2.6,3'))
        state.ingest(nmea('BDGSV,1,1,04,10,40,179,43,24,73,263,43,25,20,317,44,34,43,263,44,1'))
        snapshot=state.snapshot('test');self.assertIn('北斗 · 参与 4 · 报告 4',snapshot['satellites']);self.assertIn('10: 43',snapshot['satellites'])
    def test_missing_file_cli_and_no_phone_calls(self):
        script=Path(__file__).with_name('mac_receiver_monitor.py')
        result=subprocess.check_output([sys.executable,str(script),'--file','/does/not/exist.nmea','--once'],text=True)
        value=json.loads(result);self.assertFalse(value['connected']);self.assertEqual(value['path'],[])
        source=(Path(__file__).resolve().parent.parent/'app/WalkLab.swift').read_text()
        self.assertNotIn('/research/receiver_monitor.py',source);self.assertNotIn('receiverSerial',source)
    def test_navigation_midnight_not_map_jump(self):
        state=LocalReceiver();state.ingest(fix('235959.00'));state.ingest(fix('000000.00'));self.assertEqual(len(state.path),2)
        state.ingest(fix('000001.00').replace('3039.4918508','9999.4918508'));self.assertEqual(len(state.path),2)

if __name__=='__main__':unittest.main()
