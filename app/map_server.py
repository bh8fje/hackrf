"""Loopback-only AMap page and fixed-host security-code proxy. Never logs URLs."""
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import json,os,secrets,sys,threading,time,urllib.parse,urllib.request,urllib.error

class Server(ThreadingHTTPServer):
    daemon_threads=True
    allow_reuse_address=False

class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def reply(self,code,data,kind='text/plain; charset=utf-8'):
        self.send_response(code);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.end_headers();self.wfile.write(data)
    def do_GET(self):self.handle_request()
    def do_POST(self):self.handle_request()
    def handle_request(self):
        expected='127.0.0.1:'+str(self.server.server_port)
        if self.headers.get('Host')!=expected:return self.reply(403,b'Forbidden')
        path=urllib.parse.urlsplit(self.path)
        if len(self.path)>16000:return self.reply(414,b'URI too long')
        route=path.path.lstrip('/')
        page_path=self.server.token+'/map.html'
        if route==page_path and self.command=='GET':
            config={'serviceHost':'http://'+expected+'/_AMapService'}
            script='window._AMapSecurityConfig='+json.dumps(config)+';window.__AMAP_KEY='+json.dumps(self.server.config['key'])+';'
            page=self.server.page.replace('<script>','<script>'+script,1)
            return self.reply(200,page.encode(),'text/html; charset=utf-8')
        if not route.startswith('_AMapService/'):return self.reply(404,b'Not found')
        if self.headers.get('Referer')!='http://'+expected+'/'+page_path:return self.reply(403,b'Forbidden')
        suffix=route[len('_AMapService'):]
        if not suffix.startswith(('/v3/','/v4/')) or '..' in suffix or '\\' in suffix or '%' in suffix:return self.reply(400,b'Invalid route')
        host='webapi.amap.com' if suffix.startswith('/v4/map/styles') else 'restapi.amap.com'
        query=[(k,v) for k,v in urllib.parse.parse_qsl(path.query,keep_blank_values=True) if k!='jscode'];query.append(('jscode',self.server.config['securityJsCode']))
        url='https://'+host+suffix+'?'+urllib.parse.urlencode(query)
        try:
            size=int(self.headers.get('Content-Length','0'))
            if size<0 or size>65536:return self.reply(413,b'Request too large')
            body=self.rfile.read(size) if self.command=='POST' else None
            headers={'User-Agent':'Mozilla/5.0 TianshuMap/0.9.4','Referer':'http://'+expected+'/'}
            if body is not None:headers['Content-Type']=self.headers.get('Content-Type','application/octet-stream')
            request=urllib.request.Request(url,data=body,headers=headers,method=self.command)
            with urllib.request.urlopen(request,timeout=12) as response:
                data=response.read(8000001)
                if len(data)>8000000:return self.reply(502,b'Upstream response too large')
                self.reply(response.status,data,response.headers.get('Content-Type','application/octet-stream'))
        except (OSError,ValueError,urllib.error.URLError):self.reply(502,b'Map service unavailable')

def main():
    config=json.loads(Path(sys.argv[1]).read_text());page=Path(sys.argv[2]).read_text()
    if any(not isinstance(config.get(k),str) or len(config[k])!=32 or any(c not in '0123456789abcdefABCDEF' for c in config[k]) for k in ['key','securityJsCode']):raise SystemExit('Invalid map configuration')
    server=Server(('127.0.0.1',0),Handler);server.token=secrets.token_urlsafe(24);server.config=config;server.page=page
    parent=os.getppid()
    def watch_parent():
        while os.getppid()==parent:time.sleep(1)
        server.shutdown()
    threading.Thread(target=watch_parent,daemon=True).start()
    print('http://127.0.0.1:'+str(server.server_port)+'/'+server.token+'/map.html',flush=True)
    try:server.serve_forever()
    finally:server.server_close()
if __name__=='__main__':main()
