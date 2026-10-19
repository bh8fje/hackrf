"""Bounded mixed transmitter for the existing contained lab setup."""
from datetime import datetime, timedelta, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import select
import signal
import subprocess
import time

from mixed_lab import RATE, FREQUENCY_HZ
from walking import validate

ROOT = Path(__file__).resolve().parent
QUALIFICATION = ROOT/'output/mixed-qualification-current.json'
MIXER = ROOT/'output/mixed-stream-build/mixed_stream'
TRANSFER = ROOT.parent/'host/build/hackrf-tools/src/hackrf_transfer'


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def qualified():
    data = json.loads(QUALIFICATION.read_text())
    if data.get('offlineQualified') is not True:
        raise ValueError('混合信号尚未通过独立联合定位验证')
    if data.get('sampleRate') != RATE or data.get('frequencyHz') != FREQUENCY_HZ:
        raise ValueError('混合采样率或中心频率与验收记录不一致')
    for name, expected in data['filesSHA256'].items():
        if digest(ROOT.parent/name) != expected:
            raise ValueError('混合生成器或验收记录已变化，需要重新验证：'+name)
    verification = json.loads((ROOT.parent/data['verification']).read_text())
    accuracy = json.loads((ROOT.parent/data['accuracy']).read_text())
    if (verification.get('passed') is not True or accuracy.get('passed') is not True
            or accuracy.get('validFixes', 0) < 100 or accuracy.get('maxError', float('inf')) > 30):
        raise ValueError('混合独立定位验收未通过')
    return data


def counters(log, expected):
    underruns = re.findall(r'(\d+) underruns', log)
    transferred = re.findall(r'(\d+) bytes transferred by M0', log)
    modes = re.findall(r'M0 mode (\d+) requested (\d+) error (\d+)', log)
    result = dict(stopConfirmed='hackrf_stop_tx() done' in log,
                  idleConfirmed='Post-stop IDLE confirmed:' in log,
                  underruns=int(underruns[-1]) if underruns else None,
                  m0Bytes=int(transferred[-1]) if transferred else None,
                  txRunObserved=any(int(active)==4 for active, requested, error in modes),
                  firmwareError=any(int(error)!=0 for active, requested, error in modes))
    result['passed'] = (result['stopConfirmed'] and result['idleConfirmed'] and result['underruns']==0
                        and result['m0Bytes'] is not None and result['m0Bytes']>=expected
                        and result['txRunObserved'] and not result['firmwareError'])
    return result


class RadioSink:
    """Start after prefill; interrupt a stalled pipe and always collect stop evidence."""
    def __init__(self, folder, timing, duration, gain, amplifier, emit=None, profile="mixed"):
        profiles={'mixed':(RATE,FREQUENCY_HZ,'GPS＋北斗混合'), 'gps':(2600000,1575420000,'GPS'), 'bds':(5000000,1561098000,'北斗')}
        if profile not in profiles: raise ValueError('Unsupported stream profile')
        self.rate,self.frequency,self.label=profiles[profile];self.profile=profile
        self.folder=Path(folder); self.timing=timing; self.duration=duration
        self.gain=gain; self.amplifier=amplifier; self.process=None; self.log=None
        self.emit=emit; self.bytes=0; self.progress_at=0
        self.record=dict(status='armed', rfEnabled=False, stopConfirmed=False,
                         sampleRate=self.rate, frequencyHz=self.frequency,
                         durationSeconds=duration, scheduledStartUTC=timing['startUTC'])

    def __enter__(self):
        return self

    def start(self):
        from live_nav import launch_delay
        if self.profile=='mixed':
            for source in self.timing['sources'].values():
                launch_delay(dict(source, startUTC=self.timing['startUTC']))
        else:
            launch_delay(self.timing)
            age=(datetime.now(timezone.utc)-datetime.fromisoformat(self.timing['modelProducedAtUTC'])).total_seconds()
            if not -60<=age<=10800: raise ValueError('UTC/电离层模型在预缓冲后已过期')
        start=datetime.fromisoformat(self.timing['startUTC'])
        while True:
            remaining=(start-datetime.now(timezone.utc)).total_seconds()
            if remaining<=0: break
            time.sleep(min(.05, remaining))
        if (datetime.now(timezone.utc)-start).total_seconds()>1:
            raise ValueError(self.label+'流预缓冲未及时完成，未启动发射')
        command=[str(TRANSFER), '-t', '-', '-f', str(self.frequency), '-s', str(self.rate),
                 '-n', str(self.duration*self.rate), '-x', str(self.gain),
                 '-a', str(int(self.amplifier)), '-p', '0', '-B']
        if self.profile=='mixed': command+=['-b','20000000']
        self.log=(self.folder/'transmitter.log').open('wb')
        self.process=subprocess.Popen(command, stdin=subprocess.PIPE, stdout=self.log,
                                      stderr=self.log, bufsize=0, start_new_session=True)
        os.set_blocking(self.process.stdin.fileno(), False)
        self.record.update(status='transmitting', rfEnabled=True, command=command,
                           startedAtUTC=datetime.now(timezone.utc).isoformat())
        self.save()
        if self.emit: self.emit('progress',fraction=.25,message='正在启动 HackRF：'+self.label+'边生成边发送')

    def save(self):
        (self.folder/'transmitter.json').write_text(json.dumps(self.record,ensure_ascii=False,indent=2)+'\n')

    def write(self, block):
        if self.process is None: self.start()
        view=memoryview(block); count=0; deadline=time.monotonic()+3
        while count<len(view):
            if self.process.poll() is not None:
                raise ValueError('HackRF 已结束或断开，停止供数')
            remaining=deadline-time.monotonic()
            if remaining<=0: raise ValueError('HackRF USB 连续三秒无法接收数据，停止发送')
            if not select.select([], [self.process.stdin], [], min(.1, remaining))[1]: continue
            try: accepted=os.write(self.process.stdin.fileno(), view[count:])
            except BlockingIOError: continue
            if accepted<=0: raise ValueError('HackRF 数据管道已关闭')
            count+=accepted; deadline=time.monotonic()+3
        self.bytes+=count
        if self.emit and time.monotonic()-self.progress_at>=1:
            self.progress_at=time.monotonic()
            seconds=self.bytes/(self.rate*2)
            self.emit('progress',fraction=.25+.7*seconds/self.duration,
                      message=f'{self.label}供数 {seconds:.1f}/{self.duration} 秒 · {self.rate*2/1e6:g} MB/s · 不保存 I/Q')
        return count

    def flush(self):
        pass

    def __exit__(self, kind, error, traceback):
        failure=None
        try:
            if self.process is not None:
                self.process.stdin.close()
                if kind is not None and self.process.poll() is None:
                    self.process.send_signal(signal.SIGINT)
                try: code=self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.process.send_signal(signal.SIGINT)
                    try: code=self.process.wait(timeout=3)
                    except subprocess.TimeoutExpired:
                        self.process.kill(); code=self.process.wait()
                self.log.close()
                stats=counters((self.folder/'transmitter.log').read_text(errors='replace'), self.duration*self.rate*2)
                self.record.update(stats, exitCode=code,
                                   status='completed' if kind is None and code==0 and stats['passed'] else 'failed')
                if kind is None and self.record['status']!='completed':
                    failure=ValueError(self.label+'发送未通过零欠载、样本计数或停止确认检查，详见 transmitter.log')
            else: self.record['status']='not-started'
            if error is not None: self.record['error']=str(error)
        finally:
            self.record['endedAtUTC']=datetime.now(timezone.utc).isoformat(); self.save()
        if failure: raise failure


def preview(settings):
    from walking import plan
    result=plan(settings)[2]
    result.update(estimatedFileMB=0, streamMBps=RATE*2/1e6, streamSampleRate=RATE, sampleRate=RATE, frequencyHz=FREQUENCY_HZ)
    return result


def run(payload, folder, emit):
    from mixed_navigation import prepare
    from mixed_live import run as stream_run
    if set(payload)!={'settings','gain','amplifier'}:
        raise ValueError('启动参数必须包含 settings、gain、amplifier')
    settings=payload['settings']; validate(settings)
    gain=payload['gain']; amplifier=payload['amplifier']
    if type(gain) is not int or not 0<=gain<=47 or type(amplifier) is not bool:
        raise ValueError('TX 增益或放大器参数无效')
    qualified()
    folder=Path(folder).resolve(); folder.parent.mkdir(parents=True,exist_ok=True)
    with (folder.parent/'.transmit.lock').open('a') as lock:
        try: fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError: raise ValueError('已有发送任务正在运行')
        emit('progress',fraction=.05,message='正在获取 GPS 与北斗当前星历；完成后直接流式混合发送')
        nav,timing=prepare(folder.parent/(folder.name+'-navigation'),int(settings['duration']),lead_seconds=5)
        emit('progress',fraction=.2,message='星历覆盖已通过，正在预缓冲 GPS 主、北斗辅混合流')
        sink=RadioSink(folder,timing,int(settings['duration']),gain,amplifier,emit)
        report=stream_run(settings,folder,nav,timing,MIXER,paced=False,output=sink)
        result=dict(folder=str(folder),passed=True,validFixes=0,decodedPath=[],rfEnabled=True,
                    startUTC=timing['startUTC'],mode='current-broadcast',hardwareOutputAllowed=True,
                    verificationKind='hardware-stream',
                    phoneReceptionVerified=False,storedIQBytes=0,**{'sampleRate':RATE,'frequencyHz':FREQUENCY_HZ})
        (folder/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        emit('result',result=result)
        emit('transmitted',fraction=1,message='GPS+北斗混合发送结束并确认停止；接收结果单独验收')
        return result
