import contextlib
import fcntl
import hashlib
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import socket
import tempfile
import threading
import time
import unittest
from pathlib import Path
from navigation_download import download


class State:
    def __init__(self):
        self.data=bytes((i*31)&255 for i in range(655367));self.etag='"v1"';self.ranges=[];self.active=0;self.peak=0;self.failures=0;self.bad=False;self.barrier=None;self.lock=threading.Lock();self.head_code=200;self.updates=0;self.version=1;self.heads=0


@contextlib.contextmanager
def server(state):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_HEAD(self):
            state.heads+=1
            if state.head_code!=200:self.send_error(state.head_code);return
            self.send_response(200);self.send_header('Content-Length',str(len(state.data)));self.send_header('ETag',state.etag);self.send_header('Accept-Ranges','bytes');self.end_headers()
        def do_GET(self):
            begin,end=map(int,self.headers['Range'][6:].split('-'))
            if begin==end==0:
                self.send_response(206);self.send_header('ETag',state.etag);self.send_header('Content-Length','1');self.send_header('Content-Range',f'bytes 0-0/{len(state.data)}');self.end_headers();self.wfile.write(state.data[:1]);return
            with state.lock:
                if state.updates and self.headers['If-Range']==state.etag:
                    state.updates-=1;state.version+=1;state.etag=f'"v{state.version}"';state.data=bytes([state.version])+state.data[1:]
                state.ranges.append((begin,end));state.active+=1;state.peak=max(state.peak,state.active)
                broken=begin<len(state.data)//5 and state.failures>0
                if broken:state.failures-=1
            try:
                if state.barrier:state.barrier.wait(3)
                if self.headers['If-Range']!=state.etag:
                    self.send_response(200);self.send_header('ETag',state.etag);self.send_header('Content-Length',str(len(state.data)));self.end_headers();return
                self.send_response(206);self.send_header('ETag',state.etag);self.send_header('Content-Length',str(end-begin+1));self.send_header('Content-Range',f'bytes {begin+int(state.bad)}-{end}/{len(state.data)}');self.end_headers()
                if self.headers['If-Range']!=state.etag:raise AssertionError('Missing version guard')
                for at in range(begin,end+1,32768):
                    self.wfile.write(state.data[at:min(at+32768,end+1)]);self.wfile.flush()
                    if broken:time.sleep(.1);self.connection.shutdown(socket.SHUT_RDWR);return
                    time.sleep(.002)
            except (OSError,threading.BrokenBarrierError):pass
            finally:
                with state.lock:state.active-=1
    host=ThreadingHTTPServer(('127.0.0.1',0),Handler);host.daemon_threads=True;thread=threading.Thread(target=host.serve_forever,daemon=True);thread.start()
    try:yield f'http://127.0.0.1:{host.server_port}/current.gz'
    finally:host.shutdown();thread.join();host.server_close()


class NavigationDownloadTest(unittest.TestCase):
    def test_large_file_uses_three_ranges_and_resumes_only_missing_bytes(self):
        s=State();s.data=s.data*2;s.barrier=threading.Barrier(3)
        with tempfile.TemporaryDirectory() as tmp,server(s) as url:
            raw,_,meta=download(url,tmp)
            self.assertEqual(raw,s.data);self.assertEqual(meta['parallelParts'],3)
            self.assertEqual(s.peak,3);self.assertEqual(len(s.ranges),3)
            s.barrier=None;s.ranges.clear()
            raw,_,meta=download(url,tmp);self.assertEqual(meta['transferredBytes'],0);self.assertEqual(s.ranges,[])
            folder=Path(tmp)/hashlib.sha256(url.encode()).hexdigest()
            (folder/'complete.bin').unlink();(folder/'1.part').write_bytes(b'broken')
            raw,_,meta=download(url,tmp);self.assertEqual(raw,s.data);self.assertEqual(len(s.ranges),1)
            self.assertEqual(s.ranges[0],(len(s.data)//3,2*len(s.data)//3-1))

    def test_three_part_interruption_keeps_finished_ranges(self):
        s=State();s.data*=2;s.failures=2
        with tempfile.TemporaryDirectory() as tmp,server(s) as url:
            with self.assertRaises((OSError,EOFError)):download(url,tmp)
            s.ranges.clear();raw,_,meta=download(url,tmp)
            self.assertEqual(raw,s.data);self.assertEqual(meta['parallelParts'],3)
            self.assertEqual(s.ranges,[(65536,len(s.data)//3-1)])
            self.assertLess(meta['transferredBytes'],len(s.data)//3)

    def test_exact_megabyte_stays_single_and_legacy_resume_is_preserved(self):
        from navigation_download import _json, _checkpoint
        s=State();s.data=(s.data*2)[:1_000_000]
        with tempfile.TemporaryDirectory() as tmp,server(s) as url:
            self.assertEqual(download(url,tmp)[2]['parallelParts'],1)
        s.data+=b'one';s.ranges.clear()
        with tempfile.TemporaryDirectory() as tmp,server(s) as url:
            folder=Path(tmp)/hashlib.sha256(url.encode()).hexdigest();folder.mkdir()
            _json(folder/'version.json',{'length':len(s.data),'etag':s.etag})
            with (folder/'0.part').open('wb') as stream:
                stream.write(s.data[:65536]);_checkpoint(folder/'0.part',stream)
            raw,_,meta=download(url,tmp)
            self.assertEqual(raw,s.data);self.assertEqual(meta['resumedBytes'],65536)
            self.assertEqual(s.ranges,[(65536,len(s.data)-1)])

    def test_one_mid_transfer_version_refresh_and_bounded_repeated_changes(self):
        from navigation_download import VersionChanged
        s=State();s.updates=1
        with tempfile.TemporaryDirectory() as tmp,server(s) as url:
            messages=[];raw,_,info=download(url,tmp,messages.append)
            self.assertEqual(raw,s.data);self.assertEqual(s.heads,2)
            self.assertGreaterEqual(info['transferredBytes'],len(s.data))
            self.assertEqual(sum('重新确认版本一次' in x for x in messages),1)
        s=State();s.updates=2
        with tempfile.TemporaryDirectory() as tmp,server(s) as url:
            with self.assertRaises(VersionChanged):download(url,tmp)
            self.assertEqual(s.heads,2)
            self.assertFalse(list(Path(tmp).glob('*/complete.bin')))

    def test_get_only_signed_download_can_probe_one_byte_without_head(self):
        s=State();s.head_code=403
        with tempfile.TemporaryDirectory() as tmp,server(s) as url:
            raw,_,info=download(url,tmp);self.assertEqual(raw,s.data);self.assertEqual(info['parallelParts'],1);self.assertEqual(len(s.ranges),1)
    def test_small_file_uses_persistent_cache(self):
        s=State();s.data=s.data[:1024]
        with tempfile.TemporaryDirectory() as tmp,server(s) as url:
            raw,_,meta=download(url,tmp);self.assertEqual(raw,s.data);self.assertEqual(meta['parallelParts'],1)
            s.ranges.clear();raw,_,meta=download(url,tmp);self.assertEqual(raw,s.data);self.assertEqual(s.ranges,[])

    def test_single_stream_reuse_version_and_corruption(self):
        s=State();s.barrier=threading.Barrier(1)
        with tempfile.TemporaryDirectory() as tmp,server(s) as url:
            root=Path(tmp);raw,_,meta=download(url,root)
            self.assertEqual(raw,s.data);self.assertEqual(s.peak,1);self.assertEqual(meta['parallelParts'],1);self.assertEqual(len(s.ranges),1)
            s.barrier=None;s.ranges.clear();raw,_,meta=download(url,root)
            self.assertEqual(s.ranges,[]);self.assertEqual(meta['transferredBytes'],0);self.assertEqual(meta['resumedBytes'],len(s.data))
            s.etag='"v2"';s.data=b'x'+s.data[1:];raw,_,meta=download(url,root)
            self.assertEqual(raw,s.data);self.assertEqual(len(s.ranges),1)
            folder=root/hashlib.sha256(url.encode()).hexdigest()
            (folder/'complete.bin').write_bytes(bytes(len(s.data)));part=folder/'0.part';part.write_bytes(bytes(part.stat().st_size));s.ranges.clear()
            raw,_,meta=download(url,root);self.assertEqual(raw,s.data);self.assertEqual(len(s.ranges),1)
            with (folder/'.lock').open('a+b') as lock:
                fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
                with self.assertRaises(BlockingIOError):download(url,root)
            s.etag='"v3"';s.bad=True
            with self.assertRaisesRegex(ValueError,'version or range'):download(url,root)

    def test_interruption_preserves_completed_parts_and_partial_offset(self):
        s=State();s.failures=2
        with tempfile.TemporaryDirectory() as tmp,server(s) as url:
            with self.assertRaises((OSError,EOFError)):download(url,tmp)
            s.ranges.clear();raw,_,meta=download(url,tmp)
            self.assertEqual(raw,s.data);self.assertEqual(len(s.ranges),1);self.assertEqual(s.ranges[0][0],65536)
            self.assertGreater(meta['resumedBytes'],0);self.assertLess(meta['transferredBytes'],len(s.data))

    def test_cancel_joins_workers_and_checkpoints_are_reusable(self):
        s=State()
        def cancel(message):
            if message.startswith('单连接续传'):raise KeyboardInterrupt()
        with tempfile.TemporaryDirectory() as tmp,server(s) as url:
            with self.assertRaises(KeyboardInterrupt):download(url,tmp,cancel)
            raw,_,meta=download(url,tmp);self.assertEqual(raw,s.data);self.assertGreater(meta['resumedBytes'],0)


if __name__=='__main__':unittest.main()
