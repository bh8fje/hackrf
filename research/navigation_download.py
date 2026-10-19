"""Size-aware version-bound HTTP ranges with durable, hash-checked checkpoints."""
from concurrent.futures import ThreadPoolExecutor, wait, FIRST_COMPLETED
import fcntl
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import threading
import time
import urllib.request
import urllib.error

LIMIT=10_000_000
PARTS=3
PARALLEL_THRESHOLD=1_000_000


def metadata(url):
    for attempt in range(2):
        try:
            request=urllib.request.Request(url,method='HEAD',headers={'Accept-Encoding':'identity'})
            with urllib.request.urlopen(request,timeout=15) as response:
                size=int(response.headers.get('Content-Length','-1'));etag=response.headers.get('ETag','')
                if size>LIMIT:raise ValueError('Navigation download exceeds size limit')
                if size<=0 or len(etag)>256 or not re.fullmatch(r'"[^"\r\n]*"',etag) or response.headers.get('Accept-Ranges','').lower()!='bytes':return None
                return size,etag,response.headers.get('Last-Modified')
        except urllib.error.HTTPError as error:
            if error.code in (403,405,501):
                error.close()
                request=urllib.request.Request(url,headers={'Accept-Encoding':'identity','Range':'bytes=0-0'})
                with urllib.request.urlopen(request,timeout=15) as response:
                    if response.status!=206:return None
                    match=re.fullmatch(r'bytes 0-0/([0-9]+)',response.headers.get('Content-Range',''))
                    if not match or len(response.read(2))!=1:raise ValueError('Invalid navigation range probe')
                    size=int(match[1]);etag=response.headers.get('ETag','')
                    if size>LIMIT:raise ValueError('Navigation download exceeds size limit')
                    if size<=0 or len(etag)>256 or not re.fullmatch(r'"[^"\r\n]*"',etag):return None
                    return size,etag,response.headers.get('Last-Modified')
            raise
        except OSError:
            if attempt:raise


def _json(path,value):
    temporary=path.with_name(path.name+'.pending')
    with temporary.open('w') as stream:
        os.chmod(temporary,0o600);json.dump(value,stream);stream.flush();os.fsync(stream.fileno())
    temporary.replace(path)


def _hash(path,count):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        while count:
            block=stream.read(min(count,32768))
            if not block:raise EOFError('Truncated chunk checkpoint')
            digest.update(block);count-=len(block)
    return digest.hexdigest()


def _restore(part,limit):
    count=0
    try:
        state=part.with_suffix('.meta')
        if state.stat().st_size>4096:raise ValueError('Oversized checkpoint')
        saved=json.loads(state.read_text());n=saved['bytes']
        if type(n) is int and 0<=n<=limit and part.stat().st_size>=n and _hash(part,n)==saved['sha256']:count=n
    except (OSError,ValueError,KeyError,TypeError,EOFError):pass
    with part.open('r+b' if part.exists() else 'w+b') as stream:stream.truncate(count)
    os.chmod(part,0o600);return count


def _checkpoint(part,stream):
    stream.flush();os.fsync(stream.fileno());size=part.stat().st_size
    _json(part.with_suffix('.meta'),{'bytes':size,'sha256':_hash(part,size)})


class VersionChanged(ValueError):
    transferred_bytes=0


def download(url,cache,status=None):
    discarded=0
    for attempt in range(2):
        try:
            result=_download(url,cache,status)
            if result is None:return None
            raw,modified,info=result
            return raw,modified,info|{'transferredBytes':info['transferredBytes']+discarded}
        except VersionChanged as error:
            if attempt:raise
            discarded+=error.transferred_bytes
            if status:status('服务器已更新导航文件，重新确认版本一次；不会混用旧分块')
    raise AssertionError('Unreachable navigation version retry')


def _download(url,cache,status=None):
    began=time.monotonic();folder=Path(cache)/hashlib.sha256(url.encode()).hexdigest();folder.mkdir(parents=True,exist_ok=True,mode=0o700)
    with (folder/'.lock').open('a+b') as guard:
        fcntl.flock(guard,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if status:status('正在检查导航文件版本 · '+url.split('?',1)[0].rsplit('/',1)[-1])
        info=metadata(url)
        if info is None:return None
        size,etag,modified=info;manifest=folder/'version.json'
        try:stored=json.loads(manifest.read_text())
        except (OSError,ValueError):stored={}
        same=stored.get('length')==size and stored.get('etag')==etag
        # Preserve an existing range layout so upgrades never discard resumable bytes.
        parts=stored.get('parts',1) if same and (folder/'0.meta').exists() else (PARTS if size>PARALLEL_THRESHOLD else 1)
        if parts not in (1,PARTS):raise ValueError('Invalid navigation checkpoint layout')
        if not same:
            for i in range(5):
                for suffix in ('.part','.meta','.meta.pending'):(folder/f'{i}{suffix}').unlink(missing_ok=True)
            for name in ('complete.bin','complete.json','complete.pending'):(folder/name).unlink(missing_ok=True)
        _json(manifest,{'length':size,'etag':etag,'parts':parts})
        complete=folder/'complete.bin'
        try:
            saved=json.loads((folder/'complete.json').read_text())
            if complete.stat().st_size==size and _hash(complete,size)==saved['sha256']:
                if status:status(f'服务器确认文件未变化，复用完整文件缓存 · {size/1e6:.2f} MB')
                return complete.read_bytes(),modified,{'parallelParts':parts,'resumedBytes':size,'transferredBytes':0}
        except (OSError,ValueError,KeyError,EOFError):pass
        part_files=[folder/f'{i}.part' for i in range(parts)]
        progress=[_restore(part,(i+1)*size//parts-i*size//parts) for i,part in enumerate(part_files)];initial=sum(progress)
        lock=threading.Lock();stop=threading.Event();active=set();transferred=0
        def transfer(i):
            nonlocal transferred
            start=i*size//parts;end=(i+1)*size//parts-1;part=part_files[i]
            for attempt in range(2):
                if stop.is_set():raise InterruptedError('Navigation download cancelled')
                saved=part.stat().st_size
                if saved==end-start+1:return
                request=urllib.request.Request(url,headers={'Accept-Encoding':'identity','Range':f'bytes={start+saved}-{end}','If-Range':etag})
                try:
                    deadline=time.monotonic()+120
                    with urllib.request.urlopen(request,timeout=15) as response:
                        with lock:active.add(response)
                        try:
                            returned_etag=response.headers.get('ETag','')
                            if returned_etag!=etag and re.fullmatch(r'"[^"\r\n]*"',returned_etag):raise VersionChanged('Navigation chunk version changed')
                            if response.status!=206 or returned_etag!=etag or response.headers.get('Content-Range')!=f'bytes {start+saved}-{end}/{size}':raise ValueError('Navigation chunk version or range changed')
                            declared=int(response.headers.get('Content-Length','-1'))
                            if declared>=0 and declared!=end-start+1-saved:raise ValueError('Navigation chunk length mismatch')
                            with part.open('r+b') as out:
                                out.seek(saved);checkpoint=saved
                                try:
                                    while True:
                                        if stop.is_set():raise InterruptedError('Navigation download cancelled')
                                        block=response.read(32768)
                                        if not block:break
                                        if stop.is_set():raise InterruptedError('Navigation download cancelled')
                                        if time.monotonic()>deadline:raise TimeoutError('Navigation chunk deadline exceeded')
                                        if out.tell()+len(block)>end-start+1:raise ValueError('Oversized navigation chunk')
                                        out.write(block)
                                        with lock:progress[i]=out.tell();transferred+=len(block)
                                        if out.tell()-checkpoint>=65536:_checkpoint(part,out);checkpoint=out.tell()
                                    if out.tell()!=end-start+1:raise EOFError('Incomplete navigation chunk')
                                finally:_checkpoint(part,out)
                        finally:
                            with lock:active.discard(response)
                    return
                except (OSError,EOFError,http.client.IncompleteRead):
                    if stop.is_set() or attempt:raise
        workers=ThreadPoolExecutor(max_workers=parts)
        futures=[workers.submit(transfer,i) for i in range(parts)];pending=set(futures);last=0;name=url.split('?',1)[0].rsplit('/',1)[-1]
        changed=None
        try:
            while pending:
                done,pending=wait(pending,timeout=.2,return_when=FIRST_COMPLETED)
                for future in done:future.result()
                now=time.monotonic()
                if status and now-last>=1:
                    with lock:received=sum(progress);network=transferred
                    status(f'{"3路分块续传" if parts==3 else "单连接续传"} {received*100/size:.1f}% · 正在下载 {name} · {received/1e6:.2f}/{size/1e6:.2f} MB · {network/1e6/max(.001,now-began):.2f} MB/s · {now-began:.1f}秒');last=now
        except VersionChanged as error:
            changed=error;raise
        finally:
            stop.set()
            for future in futures:future.cancel()
            with lock:responses=list(active)
            for response in responses:response.close()
            workers.shutdown(wait=True,cancel_futures=True)
            if changed is not None:changed.transferred_bytes=transferred
        temporary=folder/'complete.pending'
        with temporary.open('wb') as out:
            os.chmod(temporary,0o600)
            for part in part_files:
                with part.open('rb') as source:
                    while block:=source.read(32768):out.write(block)
            out.flush();os.fsync(out.fileno())
        if temporary.stat().st_size!=size:raise ValueError('Assembled navigation length mismatch')
        checksum=_hash(temporary,size);temporary.replace(complete);_json(folder/'complete.json',{'sha256':checksum})
        if status:status(f'下载完成 · {name} · {size/1e6:.2f} MB · 本次传输 {transferred/1e6:.2f} MB · {time.monotonic()-began:.1f}秒')
        return complete.read_bytes(),modified,{'parallelParts':parts,'resumedBytes':initial,'transferredBytes':transferred}
