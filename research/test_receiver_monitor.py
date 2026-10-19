import json
import unittest
from receiver_monitor import ReceiverState


def payload(*rows):return b''.join(json.dumps(r).encode()+b'\n' for r in rows)


class ReceiverMonitorTest(unittest.TestCase):
    def test_history_has_longer_timeout_than_live_tail(self):
        from unittest.mock import patch
        from types import SimpleNamespace
        from receiver_monitor import read_tail
        reply=SimpleNamespace(stdout=b'{"type":"monitor_source","file":"gnss-1792301507396.jsonl"}\n{}\n')
        with patch('receiver_monitor.subprocess.run',return_value=reply) as request:
            for history,timeout in ((False,4),(True,30)):
                self.assertEqual(read_tail('phone',history),('gnss-1792301507396.jsonl',b'{}\n'))
                self.assertEqual(request.call_args.kwargs['timeout'],timeout)

    def fix(self,t=1000,**changes):
        return dict(type='location',provider='gps',receivedAtMs=t,locationTimeMs=t,latitude=30,longitude=104,mock=False,**changes)

    def test_network_mock_and_old_fixes_are_not_trajectories(self):
        state=ReceiverState();rows=[self.fix(),dict(self.fix(2000),provider='network'),dict(self.fix(3000),mock=True),dict(self.fix(4000),locationTimeMs=1)]
        data=payload(*rows);state.ingest('a',data);state.ingest('a',data)
        self.assertEqual(state.path,[[30,104]])
        self.assertFalse(state.snapshot('phone',now=4000)['fresh'])

    def test_freshness_record_end_and_session_reset(self):
        state=ReceiverState();state.ingest('a',payload(self.fix()))
        self.assertTrue(state.snapshot('phone',now=2000)['fresh'])
        self.assertFalse(state.snapshot('phone',now=5000)['fresh'])
        state.ingest('a',payload(dict(type='session_end',receivedAtMs=2000)))
        self.assertEqual(state.snapshot('phone',now=2000)['state'],'记录已结束')
        self.assertFalse(state.snapshot('phone',now=2000)['fresh'])
        state.ingest('b',payload(self.fix(3000)))
        self.assertEqual(len(state.path),1)
        self.assertTrue(state.snapshot('phone',now=3000)['fresh'])

    def test_partial_tail_invalid_coordinates_and_gap(self):
        state=ReceiverState();state.ingest('a',b'partial\n'+payload(self.fix(),dict(self.fix(2000),latitude=float('nan')))+b'{partial')
        state.ingest('a',payload(self.fix(20000)))
        self.assertEqual(state.path,[[30,104],[],[30,104]])
        self.assertFalse(state.snapshot('phone',now=20000,error='disconnected')['fresh'])

    def test_constellation_and_cn0_are_actual_status(self):
        state=ReceiverState();state.ingest('a',payload(dict(type='satellites',receivedAtMs=1000,satellites=[dict(constellation='北斗',cn0DbHz=42,usedInFix=True),dict(constellation='北斗',cn0DbHz=0,usedInFix=True)])))
        self.assertIn('北斗：报告 2 · 参与 1 · C/N₀ 中位 42.0',state.snapshot('phone',now=1000)['satellites'])


if __name__=='__main__':unittest.main()
