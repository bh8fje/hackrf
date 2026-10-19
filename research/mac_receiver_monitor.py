"""Display local GNSS-SDR NMEA output; never contacts phones or starts RF."""
import argparse,json,math,time
from pathlib import Path
from crosscheck import gga


def fields(line):
    body,check=line.strip()[1:].split('*')
    total=0
    for char in body:total^=ord(char)
    if not line.startswith('$') or len(check)!=2 or total!=int(check,16):raise ValueError('Invalid NMEA checksum')
    return body.split(',')


class LocalReceiver:
    def __init__(self):
        self.offset=0;self.identity=None;self.pending=b'';self.path=[];self.fix=None;self.last_epoch=None
        self.used={};self.visible={};self.gsv={};self.hdop=None;self.invalid=0;self.no_fix=False;self.fix_modified=None

    def ingest(self,line):
        try:
            row=fields(line);kind=row[0][-3:];system={'GP':'GPS','BD':'北斗','GB':'北斗','GA':'GAL','GL':'GLO','GN':'GNSS'}.get(row[0][:2],row[0][:2])
            if kind=='GGA':
                fix=gga(line)
                if fix is None:
                    if not self.no_fix and self.path:self.path.append([])
                    self.no_fix=True;return
                self.no_fix=False
                epoch=row[1]
                if abs(fix[0])>85:raise ValueError('Outside supported map latitude')
                if epoch!=self.last_epoch:
                    if self.last_epoch:
                        def seconds(value):return int(value[:2])*3600+int(value[2:4])*60+float(value[4:])
                        delta=(seconds(epoch)-seconds(self.last_epoch))%86400
                        if delta>10 and self.path and self.path[-1]:self.path.append([])
                    self.path.append(list(fix[:2]));self.path=self.path[-3600:];self.last_epoch=epoch
                    self.fix_modified=-1
                self.fix=fix
                value=float(row[8]);self.hdop=value if math.isfinite(value) and value>=0 else None
            elif kind=='GSA' and len(row)>=18:
                self.used[system]=[int(p) for p in row[3:15] if p] if row[2] in ('2','3') else []
            elif kind=='GSV' and len(row)>=4:
                count,index=int(row[1]),int(row[2])
                if not 1<=index<=count<=16:raise ValueError('Invalid GSV sequence')
                if index==1:self.gsv[system]={}
                values={}
                for i in range(4,len(row)-3,4):
                    if not row[i]:continue
                    prn=int(row[i]);cn=float(row[i+3]) if row[i+3] else None
                    if cn is not None and (not math.isfinite(cn) or not 0<=cn<=99):raise ValueError('Invalid C/N0')
                    values[prn]=cn
                self.gsv.setdefault(system,{}).update(values)
                if index==count:self.visible[system]=self.gsv[system].copy()
        except (ValueError,IndexError,OverflowError):self.invalid+=1

    def read(self,file):
        stat=file.stat();identity=(stat.st_dev,stat.st_ino)
        if self.identity!=identity or stat.st_size<self.offset:self.__init__();self.identity=identity
        with file.open('rb') as stream:
            stream.seek(self.offset)
            while block:=stream.read(65536):
                self.offset+=len(block);self.pending+=block
                lines=self.pending.split(b'\n');self.pending=lines.pop()
                for line in lines:
                    try:self.ingest(line.decode('ascii'))
                    except UnicodeError:self.invalid+=1
                if len(self.pending)>4096:self.pending=b'';self.invalid+=1
        if self.fix_modified==-1:self.fix_modified=stat.st_mtime
        return stat.st_mtime

    def snapshot(self,file,modified=None,error=None,now=None):
        age=max(0,(time.time() if now is None else now)-modified) if modified is not None else None
        fix_age=max(0,(time.time() if now is None else now)-self.fix_modified) if self.fix_modified is not None else None
        fresh=not error and self.fix is not None and not self.no_fix and fix_age is not None and fix_age<=3
        satellites=[]
        for system in sorted(set(self.used)|set(self.visible)):
            visible=self.visible.get(system,{})
            levels=[f'{p}: {v:.0f}' for p,v in sorted(visible.items()) if v is not None]
            satellites.append(f'{system} · 参与 {len(self.used.get(system,[]))} · 报告 {len(visible)}'+('\nPRN / C/N₀ (dB-Hz)：'+', '.join(levels) if levels else ''))
        return dict(connected=error is None,device='Mac GNSS-SDR · 本机解码',sourceFile=str(file),
                    state=error or ('本机解码更新中' if fresh else '本机解码已暂停或结束' if self.fix else '等待本机解码定位'),
                    fresh=fresh,ageSeconds=fix_age,path=self.path,latitude=self.fix[0] if self.fix else None,
                    longitude=self.fix[1] if self.fix else None,height=self.fix[2] if self.fix else None,
                    accuracy=None,hdop=self.hdop,mock=False,satellites='\n'.join(satellites) or '尚无解码卫星状态',
                    satelliteAgeSeconds=age,network='',invalidLines=self.invalid)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--file',required=True,type=Path);parser.add_argument('--once',action='store_true');args=parser.parse_args()
    state=LocalReceiver()
    while True:
        modified=None;error=None
        try:modified=state.read(args.file)
        except FileNotFoundError:error='等待本机 GNSS-SDR 输出 fix.nmea'
        except OSError:error='无法读取本机解码文件，请检查路径和权限'
        print(json.dumps(state.snapshot(args.file,modified,error),ensure_ascii=False,allow_nan=False),flush=True)
        if args.once:return
        time.sleep(1)

if __name__=='__main__':main()
