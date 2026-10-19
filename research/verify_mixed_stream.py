"""Offline streamed GPS+BDS receive check; wideband bytes never reach a disk file."""
import argparse
import errno
import select
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time

from mixed_lab import RATE, BASEBAND_RATES
from mixed_receiver import receiver_config, generated_prns, mixed_solution_evidence, mixed_nmea_solution_evidence

ROOT=Path(__file__).resolve().parent
RECEIVER=Path('gnss-sdr')


class Publisher:
    """Bounded FIFO fanout preserves every byte and lets the slower RF branch backpressure."""
    def __init__(self,folder):
        self.paths=[Path(folder)/('rf'+str(rf)+'.fifo') for rf in (0,1)]
        self.fds=[]
        for path in self.paths:os.mkfifo(path,0o600)

    def wait_subscribers(self,process,timeout=30):
        end=time.monotonic()+timeout
        for path in self.paths:
            while True:
                if process.poll() is not None:raise RuntimeError('Receiver exited before both FIFO readers')
                if time.monotonic()>end:raise TimeoutError('Receiver did not open both RF FIFOs')
                try:self.fds.append(os.open(path,os.O_WRONLY|os.O_NONBLOCK));break
                except OSError as error:
                    if error.errno!=errno.ENXIO:raise
                    time.sleep(.02)
        return len(self.fds)

    def send(self,data):
        positions=[0,0];end=time.monotonic()+15
        while min(positions)<len(data):
            pending=[fd for fd,position in zip(self.fds,positions) if position<len(data)]
            if time.monotonic()>end:raise TimeoutError('FIFO consumer stalled; stream was not dropped')
            _,ready,_=select.select([],pending,[],.25)
            for fd in ready:
                index=self.fds.index(fd)
                try:positions[index]+=os.write(fd,data[positions[index]:])
                except BlockingIOError:pass

    def close(self):
        for fd in self.fds:os.close(fd)
        self.fds=[]
        for path in self.paths:path.unlink(missing_ok=True)


def stream_config(iq,gps,bds,paths):
    config=receiver_config(iq,gps,bds);values=config['GNSS-SDR']
    for rf in (0,1):
        prefix=f'SignalSource{rf}.'
        for key in list(values):
            if key.startswith(prefix):del values[key]
        values.update({prefix+'implementation':'Fifo_Signal_Source',prefix+'sample_type':'ibyte',prefix+'filename':str(paths[rf]),prefix+'dump':'false',f'DataTypeAdapter{rf}.implementation':'Pass_Through'})
    return config


def run(folder,mixer,output,drain_seconds=10):
    folder=Path(folder).resolve();output=Path(output).resolve();output.mkdir(parents=True,exist_ok=False)
    gps=folder/'gps.bin';bds=folder/'bds.bin';expected=gps.stat().st_size*RATE//BASEBAND_RATES['GPS']
    if gps.stat().st_size*50 != bds.stat().st_size*26:raise ValueError('Baseband durations differ')
    logs=(folder/'generator-gps.log').read_text()+'\n'+(folder/'generator-bds.log').read_text()
    if 'GPS L1CA with IF ' in logs:
        prns=generated_prns(logs)
    else:
        import numpy as np
        from acquire import N, acquire
        from bds_lab import generated_prns as bds_prns
        with gps.open('rb') as source:raw=np.frombuffer(source.read(N*20),dtype=np.int8)
        acquisition=acquire(raw[::2].astype(float)+1j*raw[1::2])
        (output/'gps-acquisition.json').write_text(json.dumps(acquisition,indent=2)+'\n')
        prns={'gps':[item['prn'] for item in acquisition if item['detected']], 'bds':bds_prns(logs)}
    publisher=Publisher(output);receiver=None;producer=None;sent=0;started=time.monotonic()
    result={'passed':False,'rfEnabled':False,'widebandSaved':False,'expectedWidebandBytes':expected,'gpsPRNs':prns['gps'],'bdsPRNs':prns['bds'],'status':'starting'}
    try:
        config=stream_config(gps,prns['gps'],prns['bds'],publisher.paths)
        with (output/'receiver.conf').open('w') as f:config.write(f)
        with (output/'receiver.log').open('wb') as receive_log,(output/'mixer.log').open('wb') as mixer_log:
            receiver=subprocess.Popen([str(RECEIVER),'--config_file='+str(output/'receiver.conf'),'--log_dir='+str(output)],cwd=output,stdout=receive_log,stderr=subprocess.STDOUT)
            result['subscribers']=publisher.wait_subscribers(receiver)
            producer=subprocess.Popen([str(Path(mixer).resolve()),str(gps),str(bds)],stdout=subprocess.PIPE,stderr=mixer_log)
            while True:
                data=producer.stdout.read(65536)
                if not data:break
                if receiver.poll() is not None:raise RuntimeError('Receiver stopped before stream ended')
                publisher.send(data);sent+=len(data)
            if producer.wait(timeout=5):raise RuntimeError('Mixer failed')
            if sent!=expected:raise RuntimeError('Mixer produced wrong byte count')
            publisher.close()
            # FIFO source waits for future writers after EOF; drain bounded queues before SIGINT.
            try:receiver.wait(timeout=drain_seconds)
            except subprocess.TimeoutExpired:receiver.send_signal(signal.SIGINT);receiver.wait(timeout=15)
        result.update(status='stream-completed',bytesPublished=sent,receiverExitCode=receiver.returncode,elapsedSeconds=time.monotonic()-started)
        log=(output/'receiver.log').read_text(errors='replace')
        result['trackingStarted']={name:sorted(set(map(int,re.findall(pattern,log)))) for name,pattern in [('gps',r'Tracking of GPS L1 C/A signal started.*?PRN (\d+)'),('bds',r'Tracking of Beidou B1I signal started.*?PRN (\d+)')]}
        status='\n'.join(p.read_text(errors='replace') for p in output.glob('*.stat'))
        nmea='\n'.join(p.read_text(errors='replace') for p in sorted(output.glob('*.nmea')))
        result.update(mixed_nmea_solution_evidence(nmea))
        result['rtklibStatusEvidence']=mixed_solution_evidence(status,result['validFixes'])

    except Exception as error:
        result.update(status='failed',error=str(error),bytesPublished=sent)
        raise
    finally:
        if producer and producer.poll() is None:producer.terminate();producer.wait(timeout=5)
        if receiver and receiver.poll() is None:
            receiver.send_signal(signal.SIGINT)
            try:receiver.wait(timeout=5)
            except subprocess.TimeoutExpired:receiver.kill();receiver.wait()
        publisher.close();(output/'verification.json').write_text(json.dumps(result,indent=2)+'\n')
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('folder',type=Path);parser.add_argument('--mixer',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--drain-seconds',type=float,default=10)
    args=parser.parse_args();print(json.dumps(run(args.folder,args.mixer,args.output,args.drain_seconds),indent=2))
