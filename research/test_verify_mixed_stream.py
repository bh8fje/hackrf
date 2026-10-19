import unittest
from verify_mixed_stream import stream_config

class StreamReceiverTest(unittest.TestCase):
    def test_subscribers_use_same_bounded_endpoint_without_file_sources(self):
        c=stream_config('unused',[1,2,3,4],[6,7,8,9],['/tmp/rf0','/tmp/rf1'])['GNSS-SDR']
        for rf in (0,1):
            self.assertEqual(c[f'SignalSource{rf}.implementation'],'Fifo_Signal_Source')
            self.assertEqual(c[f'SignalSource{rf}.filename'],'/tmp/rf'+str(rf))
            self.assertEqual(c[f'SignalSource{rf}.sample_type'],'ibyte')
            self.assertEqual(c[f'DataTypeAdapter{rf}.implementation'],'Pass_Through')
        self.assertEqual(c['InputFilter0.IF'],'7672500')
        self.assertEqual(c['InputFilter1.IF'],'-6649500')

if __name__=='__main__':unittest.main()
