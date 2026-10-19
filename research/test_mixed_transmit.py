import json
from datetime import datetime, timezone
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch
from mixed_transmit import RadioSink, counters, preview, qualified
from walking import DEFAULTS

GOOD='M0 mode 4 requested 4 error 0\n0 underruns\n40000 bytes transferred by M0\nhackrf_stop_tx() done\nPost-stop IDLE confirmed:\n'

class MixedTransmitTest(unittest.TestCase):
    def test_qualification_rejects_different_transport(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'qualification.json'
            path.write_text(json.dumps({'offlineQualified':True,'sampleRate':20000000,'frequencyHz':1567747500}))
            with patch('mixed_transmit.QUALIFICATION',path), self.assertRaisesRegex(ValueError,'采样率'):
                qualified()

    def test_counter_gate_and_preview(self):
        self.assertTrue(counters(GOOD,40000)['passed'])
        for log in (GOOD.replace('0 underruns','1 underruns'),GOOD.replace('error 0','error 1'),
                    GOOD.replace('40000 bytes','39999 bytes'),GOOD.replace('hackrf_stop_tx() done',''),
                    GOOD.replace('M0 mode 4','M0 mode 2'), GOOD.replace('Post-stop IDLE confirmed:','')):
            self.assertFalse(counters(log,40000)['passed'])
        result=preview(DEFAULTS)
        self.assertEqual(result['estimatedFileMB'],0)
        self.assertEqual(result['streamSampleRate'],18000000)

    def test_lazy_launch_pipe_and_confirmed_stop_without_radio(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp); fake=folder/'fake-transfer'
            fake.write_text('#!/usr/bin/env python3\nimport sys\ndata=sys.stdin.buffer.read()\nprint("M0 mode 4 requested 4 error 0")\nprint("0 underruns")\nprint(str(len(data))+" bytes transferred by M0")\nprint("hackrf_stop_tx() done")\nprint("Post-stop IDLE confirmed:")\n')
            fake.chmod(0o700)
            timing={'startUTC':datetime.now(timezone.utc).isoformat(),'sources':{}}
            with patch('mixed_transmit.TRANSFER',fake):
                sink=RadioSink(folder,timing,.001,0,False)
                with sink:
                    self.assertIsNone(sink.process)
                    self.assertEqual(sink.write(b'x'*40000),40000)
                self.assertTrue(sink.record['passed'])
                self.assertTrue(json.loads((folder/'transmitter.json').read_text())['stopConfirmed'])
                self.assertEqual(sink.process.returncode,0)

    def test_cancel_stops_sender_and_retains_stop_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp); fake=folder/'fake-transfer'
            fake.write_text('''#!/usr/bin/env python3
import os,signal
from pathlib import Path
def stop(*args):raise KeyboardInterrupt()
signal.signal(signal.SIGINT,stop)
Path(__file__).with_name('ready').touch()
count=0
try:
 while True:
  data=os.read(0,4096)
  if not data:break
  count+=len(data)
except KeyboardInterrupt:pass
print('M0 mode 4 requested 4 error 0\\n0 underruns')
print(str(count)+' bytes transferred by M0')
print('hackrf_stop_tx() done')
''')
            fake.chmod(0o700)
            sink=RadioSink(folder,{'startUTC':datetime.now(timezone.utc).isoformat(),'sources':{}},.001,0,False)
            with patch('mixed_transmit.TRANSFER',fake),self.assertRaisesRegex(RuntimeError,'cancel'):
                with sink:
                    sink.write(b'x'*40000)
                    deadline=time.monotonic()+2
                    while not (folder/'ready').exists() and time.monotonic()<deadline:time.sleep(.01)
                    self.assertTrue((folder/'ready').exists())
                    raise RuntimeError('cancel')
            self.assertIsNotNone(sink.process.poll())
            self.assertTrue(sink.record['stopConfirmed'])
            self.assertEqual(sink.record['status'],'failed')

if __name__=='__main__':unittest.main()
