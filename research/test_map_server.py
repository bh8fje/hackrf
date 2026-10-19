"""Local proxy isolation tests with fake upstream and fake keys only."""
import http.client,importlib.util,io,json,threading,unittest,urllib.parse
from unittest.mock import patch
from pathlib import Path
spec=importlib.util.spec_from_file_location('map_server',Path(__file__).resolve().parent.parent/'app/map_server.py');server_module=importlib.util.module_from_spec(spec);spec.loader.exec_module(server_module)
class MapServerTest(unittest.TestCase):
    def setUp(self):
        self.server=server_module.Server(('127.0.0.1',0),server_module.Handler);self.server.token='test-session';self.server.config={'key':'a'*32,'securityJsCode':'b'*32};self.server.page='<script>connect-src https:</script>';self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def tearDown(self):self.server.shutdown();self.server.server_close();self.thread.join()
    def request(self,path,headers=None):
        connection=http.client.HTTPConnection('127.0.0.1',self.server.server_port,timeout=3);connection.request('GET',path,headers=({'Referer':'http://127.0.0.1:'+str(self.server.server_port)+'/test-session/map.html'} if headers is None else headers));response=connection.getresponse();body=response.read();code=response.status;connection.close();return code,body
    def test_page_excludes_security_code(self):
        code,body=self.request('/test-session/map.html');self.assertEqual(code,200);self.assertIn(b'a'*32,body);self.assertNotIn(b'b'*32,body);self.assertIn(b'serviceHost',body);self.assertIn(b'/_AMapService',body);self.assertNotIn(b'/test-session/_AMapService',body)
    def test_host_token_and_traversal_boundaries(self):
        self.assertEqual(self.request('/wrong/map.html')[0],404)
        self.assertEqual(self.request('/_AMapService/v4/map/styles',{})[0],403)
        self.assertEqual(self.request('/test-session/map.html',{'Host':'evil.test'})[0],403)
        self.assertEqual(self.request('/_AMapService/v4/../secret')[0],400)
        self.assertEqual(self.request('/_AMapService/v4/%2e%2e/secret')[0],400)
        self.assertEqual(self.request('/_AMapService/http://evil.test')[0],400)
    def test_fixed_upstream_and_code_replacement(self):
        class Response(io.BytesIO):status=200;headers={'Content-Type':'application/json'}
        captured=[]
        def upstream(request,timeout):captured.append(request.full_url);return Response(b'{"status":"1"}')
        with patch.object(server_module.urllib.request,'urlopen',side_effect=upstream):
            self.assertEqual(self.request('/_AMapService/v4/map/styles?key=public&jscode=untrusted')[0],200)
            self.assertEqual(self.request('/_AMapService/v3/place/text?key=public')[0],200)
        self.assertEqual([urllib.parse.urlsplit(u).hostname for u in captured],['webapi.amap.com','restapi.amap.com'])
        for url in captured:self.assertEqual(urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)['jscode'],['b'*32]);self.assertTrue(url.startswith('https://'))
if __name__=='__main__':unittest.main()
