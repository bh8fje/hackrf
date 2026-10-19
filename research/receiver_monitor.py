"""Read actual receiver logs over authorized ADB; never starts recording or RF."""
import argparse
import json
import math
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import time

ADB=shutil.which('adb') or str(Path.home()/'Android/sdk/platform-tools/adb')


def number(value):
    return isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value)


class ReceiverState:
    def __init__(self):
        self.file='';self.seen=set();self.path=[];self.fix=None;self.network=None
        self.satellites=[];self.satellite_time=0;self.last_event=0;self.closed=False;self.last_fix_time=0

    def ingest(self,name,payload):
        if name!=self.file:
            self.__init__();self.file=name
        lines=payload.split(b'\n')[:-1]
        for line in lines:
            if line in self.seen:continue
            try:row=json.loads(line)
            except (ValueError,UnicodeError):continue
            if not isinstance(row,dict) or not number(row.get('receivedAtMs')):continue
            self.last_event=max(self.last_event,row['receivedAtMs'])
            kind=row.get('type')
            if kind=='session_end':self.closed=True
            if kind=='satellites' and isinstance(row.get('satellites'),list):
                self.satellites=[s for s in row['satellites'] if isinstance(s,dict)];self.satellite_time=row['receivedAtMs']
            if kind=='location':
                if row.get('provider')=='network':self.network=row
                if row.get('provider')!='gps':continue
                lat,lon=row.get('latitude'),row.get('longitude')
                if not(number(lat) and number(lon) and abs(lat)<=85 and abs(lon)<=180):continue
                self.fix=row
                timestamp=row.get('locationTimeMs')
                if (row.get('mock') is False and number(timestamp) and timestamp>self.last_fix_time
                        and -1000<=row['receivedAtMs']-timestamp<=10000):
                    if self.path and timestamp-self.last_fix_time>10000:self.path.append([])
                    self.path.append([lat,lon]);self.path=self.path[-3600:];self.last_fix_time=timestamp
        self.seen=set(lines)

    def snapshot(self,device,now=None,error=None):
        now=time.time()*1000 if now is None else now
        fix=self.fix or {};stamp=fix.get('locationTimeMs')
        age=max(0,(now-stamp)/1000) if number(stamp) else None
        fresh=not error and not self.closed and fix.get('mock') is False and stamp==self.last_fix_time and age is not None and age<=3 and stamp<=now+1000
        state=error or ('尚无记录，请在手机 GNSS 接收实验室开始记录' if not self.file else
                       '记录已结束' if self.closed else 'GNSS 定位' if fresh else
                       '等待新定位' if now-self.last_event<=5000 else '接收记录未更新')
        satellite_text=[]
        for name in ('GPS','北斗','GAL','GLO'):
            group=[s for s in self.satellites if s.get('constellation')==name]
            if not group:continue
            levels=[s['cn0DbHz'] for s in group if number(s.get('cn0DbHz')) and s['cn0DbHz']>0]
            used=sum(s.get('usedInFix') is True and number(s.get('cn0DbHz')) and s['cn0DbHz']>0 for s in group)
            satellite_text.append(f'{name}：报告 {len(group)} · 参与 {used}'+(f' · C/N₀ 中位 {statistics.median(levels):.1f} dB-Hz' if levels else ' · 无有效信号'))
        return dict(connected=not bool(error),device=device,sourceFile=self.file,state=state,
                    fresh=fresh,ageSeconds=age,path=self.path,latitude=fix.get('latitude'),longitude=fix.get('longitude'),
                    height=fix.get('ellipsoidHeightM') if number(fix.get('ellipsoidHeightM')) else None,
                    accuracy=fix.get('horizontalAccuracyM') if number(fix.get('horizontalAccuracyM')) else None,mock=fix.get('mock',False) is True,
                    satellites='\n'.join(satellite_text) or '尚无卫星状态',
                    satelliteAgeSeconds=max(0,(now-self.satellite_time)/1000) if self.satellite_time else None,
                    network='网络定位单独记录，不绘入 GNSS 轨迹' if self.network else '尚无网络定位',
                    receivedAtMs=self.last_event)


def read_tail(serial,history=False):
    base=[ADB,'-s',serial]
    result=subprocess.run(base+['exec-out','content','read','--uri','content://local.hackrf.receiver.public.logs/'+('history' if history else 'tail')],capture_output=True,timeout=30 if history else 4)
    first,_,payload=result.stdout.partition(b'\n')
    try:
        header=json.loads(first)
        if header.get('type')=='monitor_source':return header['file'],payload
    except (ValueError,AttributeError):pass
    # Older debug receiver versions remain readable during an in-place upgrade.
    files=subprocess.check_output(base+['shell','run-as','local.hackrf.receiver.public','ls','files'],timeout=4,text=True)
    names=[s for s in files.splitlines() if re.fullmatch(r'gnss-[0-9]{13}\.jsonl',s)]
    if not names:return '',b''
    name=max(names)
    payload=subprocess.check_output(base+['exec-out','run-as','local.hackrf.receiver.public','tail','-c','20001000' if history else '262144','files/'+name],timeout=30 if history else 4)
    return name,payload


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--serial');parser.add_argument('--once',action='store_true');args=parser.parse_args()
    state=ReceiverState();selected=''
    while True:
        started=time.monotonic()
        try:
            listing=subprocess.check_output([ADB,'devices'],timeout=4,text=True)
            devices=[r.split()[0] for r in listing.splitlines()[1:] if len(r.split())==2 and r.split()[1]=='device']
            if args.serial:
                if args.serial not in devices:raise ValueError('指定接收手机未连接')
                device=args.serial
            else:
                if len(devices)!=1:raise ValueError('请连接并授权一台接收手机' if not devices else '多台手机已连接，请填写接收手机 ADB 标识')
                device=devices[0]
            if selected!=device:state=ReceiverState();selected=device
            name,payload=read_tail(device)
            if name and name!=state.file:name,payload=read_tail(device,history=True)
            state.ingest(name,payload)
            snapshot=state.snapshot(device)
        except (OSError,ValueError,subprocess.SubprocessError) as failure:
            snapshot=state.snapshot(selected,error='接收连接不可用：'+str(failure)[:180])
        print(json.dumps(snapshot,ensure_ascii=False,allow_nan=False),flush=True)
        if args.once:return
        time.sleep(max(.1,1-(time.monotonic()-started)))


if __name__=='__main__':main()
