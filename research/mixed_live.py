"""Bounded generation→mixing stream; offline unless an explicit sink is supplied."""
import copy
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import queue
import signal
import subprocess
import threading
import time
import numpy as np
from mixed_lab import configuration, BASEBAND_RATES, CARRIERS_HZ, RATE
from walking import validate, signal_environment

ROOT = Path(__file__).resolve().parent
CHUNK = 262144
PREFILL = 8_000_000
QUEUE_CHUNKS = 16


def pump(stream, sink, expected, paced=False, stall_seconds=2):
    """Bounded reader decouples generation from an output paced like a radio."""
    blocks = queue.Queue(QUEUE_CHUNKS)
    cancelled = threading.Event()
    def enqueue(value):
        while not cancelled.is_set():
            try: blocks.put(value, timeout=.1); return
            except queue.Full: pass
    def read():
        try:
            while not cancelled.is_set():
                block = stream.read(CHUNK)
                if not block: break
                enqueue(block)
        except Exception as error: enqueue(error)
        finally: enqueue(None)
    thread = threading.Thread(target=read, daemon=True); began = time.monotonic(); thread.start()
    prefix = bytearray(); written = 0; first = None
    try:
        while len(prefix) < min(PREFILL, expected):
            try: block = blocks.get(timeout=stall_seconds)
            except queue.Empty: raise ValueError('Generator stalled before prefill')
            if isinstance(block, Exception): raise block
            if block is None: raise ValueError('Generator ended before prefill')
            if first is None: first = time.monotonic()-began
            prefix.extend(block)
        ready = time.monotonic()
        while written < expected:
            if prefix: block = prefix; prefix = bytearray()
            else:
                try: block = blocks.get(timeout=stall_seconds)
                except queue.Empty: raise ValueError('Generation stream stalled; output stopped')
                if isinstance(block, Exception): raise block
                if block is None: raise ValueError('Generation stream ended early')
            if written+len(block)>expected: raise ValueError('Generation stream exceeded declared length')
            if paced:
                remaining = ready+written/(RATE*2)-time.monotonic()
                if remaining>0: time.sleep(remaining)
            count = sink.write(block)
            if count is not None and count!=len(block): raise ValueError('Output sink accepted a short write')
            written += len(block)
        try: extra = blocks.get(timeout=stall_seconds)
        except queue.Empty: raise ValueError('Generation stream failed to terminate')
        if extra is not None: raise ValueError('Generation stream exceeded declared length')
        sink.flush()
        return dict(bytes=written, firstByteSeconds=first, prefillSeconds=ready-began,
                    elapsedSeconds=time.monotonic()-began, paced=paced,
                    maxBufferedBytes=PREFILL+(QUEUE_CHUNKS+3)*CHUNK,
                    hardwareOutputAllowed=False, rfEnabled=False)
    finally:
        cancelled.set()


def gps_command(folder, navigation, start, offset, duration, environment, fifo):
    """Keep the accepted GPS generator while preserving the mixed epoch and path."""
    from live_nav import convert
    if type(offset) is not int or not 0 <= offset <= 100:
        raise ValueError('Invalid common GPS minus UTC offset')
    text = Path(navigation).read_text()
    gps_start = start+timedelta(seconds=offset)
    rinex2, coverage = convert(text, text, gps_start, offset, duration)
    target = folder/'broadcast-gps.rnx'
    target.write_text(rinex2)
    return [str(ROOT/'output/generator/gps-sdr-sim'), '-e', target.name,
            '-u', 'emitted.csv', '-t', gps_start.strftime('%Y/%m/%d,%H:%M:%S'),
            '-Q', str(environment['profileIndex']), '-S', str(environment['seed']),
            '-d', str(duration+.1), '-s', '2600000', '-b', '8', '-o', str(fifo)], coverage


def run(settings, folder, navigation, timing, mixer, paced=True, output=None):
    validate(settings)
    duration = int(settings['duration']); folder = Path(folder).resolve(); folder.mkdir(parents=True, exist_ok=False)
    start = datetime.fromisoformat(timing['startUTC'])
    nav = folder/'broadcast-mixed.rnx'; nav.write_bytes(Path(navigation).read_bytes())
    config, ideal, emitted, preview = configuration(settings, nav, folder/'not-written.bin', start)
    np.savetxt(folder/'emitted.csv', emitted, delimiter=',', fmt=['%.1f','%.6f','%.6f','%.6f'])
    (folder/'settings.json').write_text(json.dumps(settings,indent=2)+'\n')
    environment = signal_environment(settings)
    processes = []; logs = []; pipes = []; report = dict(status='starting',rfEnabled=False,hardwareOutputAllowed=False)
    (folder/'experiment.json').write_text(json.dumps(report)+'\n')
    try:
        for system, rate in BASEBAND_RATES.items():
            fifo = folder/(system.lower()+'.fifo'); os.mkfifo(fifo,0o600); pipes.append(fifo)
            log = (folder/('generator-'+system.lower()+'.log')).open('wb'); logs.append(log)
            if system == 'GPS':
                command, gps_coverage = gps_command(folder, nav, start, timing['gpsMinusUTCSeconds'], duration, environment, fifo)
                (folder/'gps-navigation-coverage.json').write_text(json.dumps(gps_coverage,indent=2)+'\n')
            else:
                component = copy.deepcopy(config)
                component['output'].update(sampleFreq=rate/1e6,centerFreq=CARRIERS_HZ[system]/1e6,name=str(fifo))
                for entry in component['output']['systemSelect']: entry['enable'] = entry['system']==system
                scenario = folder/('scenario-'+system.lower()+'.json'); scenario.write_text(json.dumps(component,indent=2)+'\n')
                command = [str(ROOT/'output/bds-build-current/IFdataGen'),'-c',str(scenario),'-u',str(folder/'emitted.csv'),'-mt','-Q',str(environment['profileIndex']),'-S',str(environment['seed'])]
            processes.append(subprocess.Popen(command,stdout=log,stderr=log,env=dict(os.environ,OMP_NUM_THREADS='2',OMP_WAIT_POLICY='PASSIVE',OMP_DYNAMIC='FALSE'),start_new_session=True,cwd=folder))
        log = (folder/'mixer.log').open('wb'); logs.append(log)
        command = [str(Path(mixer).resolve()),*[str(p) for p in pipes],'-','.35','.25','--duration-seconds',str(duration)]
        process = subprocess.Popen(command,stdout=subprocess.PIPE,stderr=log,start_new_session=True); processes.append(process)
        with (output if output is not None else open(os.devnull,'wb',buffering=0)) as sink:
            report = pump(process.stdout,sink,duration*RATE*2,paced=paced,stall_seconds=5)
        for process in processes:
            if process.wait(timeout=10): raise ValueError('Streaming subprocess failed')
        report.update(status='offline-stream-complete' if output is None else 'output-stream-complete',durationSeconds=duration,storedIQBytes=0,
                      navigationSHA256=hashlib.sha256(nav.read_bytes()).hexdigest(),
                      softwareReceiverVerified=False, jointPositioningVerified=False,
                      note='No RF device was opened; pacing /dev/null is a throughput check, not GNSS or USB acceptance.' if output is None else 'See transmitter.json for hardware counters and confirmed stop.')
        return report
    except BaseException as error:
        details=[]
        for label,child in zip(('GPS','BDS','混合器'),processes):
            code=child.poll()
            if code is not None and code!=0:
                name='mixer.log' if label=='混合器' else 'generator-'+label.lower()+'.log'
                text=(folder/name).read_text(errors='replace').strip().splitlines()
                details.append(label+'退出 '+str(code)+('：'+text[-1][:250] if text else ''))
        message=str(error)+('；'+ '；'.join(details) if details else '')
        report.update(status='failed',error=message)
        if details:raise ValueError(message) from error
        raise
    finally:
        for process in processes:
            if process.poll() is None:
                os.killpg(process.pid,signal.SIGTERM)
                try: process.wait(timeout=3)
                except subprocess.TimeoutExpired: os.killpg(process.pid,signal.SIGKILL); process.wait()
        for log in logs: log.close()
        for pipe in pipes: pipe.unlink(missing_ok=True)
        if output is not None:
            report.update(rfEnabled=output.record['rfEnabled'],hardwareOutputAllowed=True,
                          stopConfirmed=output.record['stopConfirmed'])
        report.update(sourceGenerators={'GPS':'gps-sdr-sim accepted GPS path','BDS':'SignalSim IFdataGen B1I'},
                      relativeRfPowerVerified=False,
                      powerNote='Different generator gain/noise models and mixer weights do not certify a per-satellite 3 dB RF power difference.')
        (folder/'experiment.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
