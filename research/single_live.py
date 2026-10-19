"""Single-system automatic starts: bounded native generation directly into TX.

Current-stream decoding is not claimed. Pinned generator qualification, complete
current navigation and transport acceptance remain distinct from live reception.
"""
from datetime import datetime, timezone
from pathlib import Path
import fcntl
import json
import math
import os
import select
import signal
import subprocess
import time
import numpy as np
from mixed_live import pump
from mixed_transmit import RadioSink, QUALIFICATION, digest
from walking import validate, plan, signal_environment

ROOT=Path(__file__).resolve().parent
PROFILES={'gps':(2600000,1575420000,'GPS-L1CA','output/generator/gps-sdr-sim'),
          'bds':(5000000,1561098000,'BDS-B1I','output/bds-build-current/IFdataGen')}


def qualification(system):
    if system not in PROFILES: raise ValueError('Only GPS and BDS single streams are supported')
    data=json.loads(QUALIFICATION.read_text())
    if data.get('offlineQualified') is not True: raise ValueError('固定生成器尚未通过独立接收资格检查')
    required=['research/'+PROFILES[system][3],'research/walking.py','shared/signal-environments.json',
              'host/build/hackrf-tools/src/hackrf_transfer',data['verification'],data['accuracy']]
    for name in required:
        if data.get('filesSHA256',{}).get(name)!=digest(ROOT.parent/name):
            raise ValueError('单系统固定生成器或验收证据已变化：'+name)
    verification=json.loads((ROOT.parent/data['verification']).read_text())
    accuracy=json.loads((ROOT.parent/data['accuracy']).read_text())
    if (verification.get('passed') is not True or accuracy.get('passed') is not True
            or accuracy.get('validFixes',0)<100 or accuracy.get('maxError',float('inf'))>30):
        raise ValueError('固定生成器独立定位资格证据未通过')
    return {'evidence':str(QUALIFICATION),'generatorSHA256':data['filesSHA256'][required[0]],
            'kind':'fixed-generator-prior-qualified','currentStreamDecoded':False}


def command(system,settings,folder,navigation,timing):
    rate,frequency,label,binary=PROFILES[system];start=datetime.fromisoformat(timing['startUTC'])
    if system=='gps':
        ideal,emitted,preview=plan(settings)
        args=[str(ROOT/binary),'-e',Path(navigation).name,'-u','emitted.csv','-t',timing['startGPS'],
              '-d',str(settings['duration']+.1),'-s',str(rate),'-b','8','-o','signal.fifo']
    else:
        from bds_lab import configuration
        config,ideal,emitted,preview=configuration(settings,navigation,folder/'signal.fifo',start)
        (folder/'scenario.json').write_text(json.dumps(config,indent=2)+'\n')
        args=[str(ROOT/binary),'-c','scenario.json','-u','emitted.csv','-mt']
    environment=signal_environment(settings)
    args+=['-Q',str(environment['profileIndex']),'-S',str(environment['seed'])]
    for name,points in [('ideal.csv',ideal),('emitted.csv',emitted)]:
        np.savetxt(folder/name,points,delimiter=',',fmt=['%.1f','%.6f','%.6f','%.6f'])
    preview.update(streamMBps=rate*2/1e6,streamSampleRate=rate,sampleRate=rate,frequencyHz=frequency)
    return args,preview


class GeneratorStream:
    """A FIFO reader that distinguishes producer startup from premature EOF."""
    def __init__(self,fd,process):self.fd=fd;self.process=process;self.closed=False;self.received=0
    def read(self,size):
        while not self.closed:
            try:block=os.read(self.fd,size)
            except BlockingIOError:block=None
            if block:
                self.received+=len(block);return block
            if block==b'':
                if self.received or self.process.poll() is not None:return b''
                time.sleep(.001)
            else:select.select([self.fd],[],[],.001)
        raise InterruptedError('Generation stream closed')
    def close(self):
        self.closed=True
        try:os.close(self.fd)
        except OSError:pass


def run(payload,folder,emit,system='gps'):
    if system not in PROFILES:raise ValueError('尚未开放该导航系统')
    if set(payload)!={'settings','gain','amplifier'}:raise ValueError('启动参数必须包含 settings、gain、amplifier')
    settings=payload['settings'];validate(settings);gain=payload['gain'];amplifier=payload['amplifier']
    if type(gain) is not int or not 0<=gain<=47 or type(amplifier) is not bool:raise ValueError('TX增益或放大器参数无效')
    fixed=qualification(system);rate,frequency,label,binary=PROFILES[system]
    folder=Path(folder).resolve();folder.parent.mkdir(parents=True,exist_ok=True)
    state=folder.parent/('single-stream-'+system+'-timing.json');reserve=2 if system=='gps' else 3
    try:
        elapsed=float(json.loads(state.read_text())['prefillSeconds'])
        if math.isfinite(elapsed) and elapsed>=0:reserve=math.ceil(max(2,min(8,elapsed*1.5+1)))
    except (OSError,ValueError,KeyError,TypeError):pass
    with (folder.parent/'.transmit.lock').open('a') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise ValueError('已有发送任务正在运行')
        folder.mkdir(exist_ok=False);process=None;stream=None;sink=None;fifo=folder/'signal.fifo';record={}
        (folder/'settings.json').write_text(json.dumps(settings,indent=2)+'\n')
        try:
            emit('progress',fraction=.05,message='正在检查'+label+'当前星历；完成后边生成边发送')
            if system=='gps':
                from live_nav import prepare
                navigation,timing=prepare(folder,lead_seconds=reserve,duration=int(settings['duration']))
            else:
                from bds_navigation import prepare
                navigation,timing=prepare(folder,int(settings['duration']),lead=reserve)
            args,preview=command(system,settings,folder,navigation,timing);emit('preview',preview=preview)
            os.mkfifo(fifo,0o600);fd=os.open(fifo,os.O_RDONLY|os.O_NONBLOCK)
            with (folder/'generator.log').open('wb') as log:
                try:
                    process=subprocess.Popen(args,cwd=folder,stdout=log,stderr=log,start_new_session=True,
                        env=dict(os.environ,OMP_NUM_THREADS='4',OMP_WAIT_POLICY='PASSIVE',OMP_DYNAMIC='FALSE'))
                except BaseException:os.close(fd);raise
                stream=GeneratorStream(fd,process)
                sink=RadioSink(folder,timing,int(settings['duration']),gain,amplifier,emit,profile=system)
                with sink:
                    record=pump(stream,sink,int(settings['duration'])*rate*2,paced=False,stall_seconds=5)
                    if process.wait(timeout=10):raise ValueError('原生生成器未正常完成，停止输出')
                state.write_text(json.dumps({'prefillSeconds':record['prefillSeconds']})+'\n')
                record.update(folder=str(folder),passed=True,status='hardware-stream-complete',signal=label,
                    sampleRate=rate,frequencyHz=frequency,durationSeconds=int(settings['duration']),navigation=timing,
                    mode='current-broadcast',startUTC=timing['startUTC'],
                    storedIQBytes=0,verificationKind='hardware-stream',softwareReceiverVerified=False,
                    phoneReceptionVerified=False,fixedGeneratorQualification=fixed,rfEnabled=sink.record['rfEnabled'],
                    hardwareOutputAllowed=True,stopConfirmed=sink.record['stopConfirmed'],validFixes=0,decodedPath=[])
                (folder/'result.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
                emit('result',result=record);emit('transmitted',fraction=1,message=label+'流式发送结束并确认停止；接收结果单独验收')
                return record
        except BaseException as error:
            record.update(passed=False,status='failed',error=str(error),verificationKind='hardware-stream',
                          softwareReceiverVerified=False,phoneReceptionVerified=False,storedIQBytes=0,
                          hardwareOutputAllowed=False,rfEnabled=bool(sink and sink.record['rfEnabled']))
            raise
        finally:
            if process is not None and process.poll() is None:
                os.killpg(process.pid,signal.SIGTERM)
                try:process.wait(timeout=3)
                except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
            if stream is not None:stream.close()
            fifo.unlink(missing_ok=True)
            (folder/'experiment.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
