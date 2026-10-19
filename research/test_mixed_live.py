import io
import unittest
from mixed_live import pump, CHUNK, gps_command

class PumpTest(unittest.TestCase):
    def test_exact_stream_and_bounded_buffer(self):
        data=b'ab'*500000;sink=io.BytesIO();report=pump(io.BytesIO(data),sink,len(data))
        self.assertEqual(sink.getvalue(),data)
        self.assertLess(report['maxBufferedBytes'],14_000_000)
        self.assertFalse(report['hardwareOutputAllowed'])

    def test_reject_short_long_or_broken_output(self):
        for raw,expected in ((b'ab',4),(b'abcdef',4)):
            with self.assertRaises(ValueError):pump(io.BytesIO(raw),io.BytesIO(),expected)
        class Short(io.BytesIO):
            def write(self,data):return len(data)-1
        with self.assertRaises(ValueError):pump(io.BytesIO(b'abcd'),Short(),4)

class StreamFailureTest(unittest.TestCase):
    def test_failed_subprocess_reason_reaches_caller(self):
        from pathlib import Path
        from unittest.mock import patch
        import tempfile,numpy as np
        from mixed_live import run
        from walking import DEFAULTS
        class Failed:
            stdout=io.BytesIO(b'')
            def poll(self):return 1
            def wait(self,timeout=None):return 1
        def launch(*args,**kwargs):
            log=kwargs.get('stderr');log.write(b'native duration rejected\n');log.flush()
            return Failed()
        with tempfile.TemporaryDirectory() as tmp:
            folder=Path(tmp);nav=folder/'nav.rnx';nav.write_text('test')
            config={'output':{},'systemSelect':[]};config['output']['systemSelect']=[]
            points=np.zeros((2,4))
            with patch('mixed_live.configuration',return_value=(config,points,points,{})),patch('mixed_live.gps_command',return_value=(['fake'],{})),patch('mixed_live.subprocess.Popen',side_effect=launch):
                with self.assertRaisesRegex(ValueError,'混合器退出 1：native duration rejected'):
                    run(DEFAULTS,folder/'run',nav,{'startUTC':'2026-10-18T15:30:04+00:00','gpsMinusUTCSeconds':18},Path('/fake/mixer'),paced=False)

class GeneratorCommandTest(unittest.TestCase):
    def test_original_gps_command_uses_common_gpst_and_motion(self):
        from pathlib import Path
        import tempfile
        from mixed_navigation import combine
        from test_mixed_navigation import gps_input, START, FIX
        gps, models = gps_input()
        nav, timing = combine(gps, models, (FIX/'bds-broadcast.rnx').read_text(),
                              (FIX/'bds-models.rnx').read_text(), START, 18, 90)
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp); source = folder/'broadcast-mixed.rnx'; source.write_text(nav)
            command, coverage = gps_command(folder, source, START, 18, 90,
                                            {'profileIndex': 2, 'seed': 42}, folder/'gps.fifo')
            self.assertTrue(command[0].endswith('/output/generator/gps-sdr-sim'))
            args = dict(zip(command[1::2], command[2::2]))
            self.assertEqual(args, {'-e':'broadcast-gps.rnx', '-u':'emitted.csv',
                                  '-t':'2026/10/17,06:27:43', '-Q':'2', '-S':'42',
                                  '-d':'90.1', '-s':'2600000', '-b':'8', '-o':str(folder/'gps.fifo')})
            self.assertTrue((folder/'broadcast-gps.rnx').read_text().startswith('     2.11'))
            self.assertEqual(coverage['healthyRecentPRNs'], [1,2,3,4])
            self.assertEqual(source.read_text(), nav)
            for bad_offset in (None, True, -1, 101):
                with self.assertRaises(ValueError):
                    gps_command(folder, source, START, bad_offset, 90,
                                {'profileIndex': 2, 'seed': 42}, folder/'gps.fifo')

if __name__=='__main__':unittest.main()
