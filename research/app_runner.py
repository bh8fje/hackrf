"""JSON-line bridge for local generation, verification and bounded lab transmission."""
from satellite_status import scene_satellites
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import fcntl
import json
import os
import re
import shutil
from pathlib import Path
import signal
import shlex
import subprocess
import sys
import time
import uuid
from zoneinfo import ZoneInfo

import numpy as np
from acquire import N, acquire
from crosscheck import ecef, gga
from dynamic import compare, elapsed_seconds
from scenarios import receiver_config
from walking import DEFAULTS, enu_basis, plan
from live_nav import prepare, launch_delay
from preparation import lead_seconds, previous_rate, record_duration

ROOT = Path(__file__).resolve().parent
active_child = None


def emit(kind, **data):
    print(json.dumps({'type': kind, **data}, ensure_ascii=False, allow_nan=False), flush=True)


def cancel(signum, frame):
    if active_child is not None and active_child.poll() is None:
        active_child.terminate()
        try:
            active_child.wait(timeout=3)
        except subprocess.TimeoutExpired:
            active_child.terminate()
            try:
                active_child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                active_child.kill()
                active_child.wait()
    raise SystemExit(130)


def execute(command, folder, logfile, timeout):
    global active_child
    with (folder / logfile).open('wb') as log:
        active_child = subprocess.Popen(command, cwd=folder, stdout=log, stderr=log)
        try:
            code = active_child.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            active_child.kill()
            active_child.wait()
            raise ValueError(f'步骤超时，详情见 {folder / logfile}')
        finally:
            active_child = None
    if code:
        raise ValueError(f'步骤失败（{code}），详情见 {folder / logfile}')


def transmit(payload):
    if set(payload) != {'folder', 'gain', 'amplifier'}:
        raise ValueError('发送参数必须包含 folder、gain、amplifier')
    gain, amplifier = payload['gain'], payload['amplifier']
    if type(gain) is not int or not 0 <= gain <= 47 or type(amplifier) is not bool:
        raise ValueError('TX 增益必须为 0–47 整数，放大器开关必须为布尔值')
    folder = Path(payload['folder']).resolve()
    jobs = (ROOT / 'output/app-jobs').resolve()
    if folder.parent != jobs:
        raise ValueError('只能发送应用生成并验证的场景')
    result = json.loads((folder / 'result.json').read_text())
    if result.get('signal') in ('GAL-E1','GLO-G1'):
        raise ValueError('已取消该导航系统，仅支持 GPS 和北斗')
    if result.get('hardwareOutputAllowed') is False:
        raise ValueError('该研究场景尚未开放硬件输出')
    metadata = result.get('navigation', {})
    launch_delay(metadata)
    settings = json.loads((folder / 'settings.json').read_text())
    plan(settings)
    duration = int(settings['duration'])
    iq = folder / 'signal.bin'
    rate,frequency=2_600_000,1_575_420_000
    sampled_profiles={'BDS-B1I':(5000000,1561098000,'broadcast-bds.rnx'),
                      'GAL-E1':(16000000,1575420000,'broadcast-galileo.rnx'),
                      'GLO-G1':(10000000,1602000000,'broadcast-glonass.rnx')}
    if result.get('signal') in sampled_profiles:
        galileo=result['signal']=='GAL-E1'
        expected_rate,expected_frequency,nav_name=sampled_profiles[result['signal']]
        if (result.get('hardwareOutputAllowed') is not True or result.get('sampleRate')!=expected_rate
                or result.get('frequencyHz')!=expected_frequency or result.get('durationSeconds')!=duration):
            raise ValueError('导航信号发送参数不匹配')
        expected=result.get('preparedFilesSHA256',{})
        if set(expected)!={'scenario.json',nav_name,'emitted.csv'} or any(hashlib.sha256((folder/name).read_bytes()).hexdigest()!=digest for name,digest in expected.items()):
            raise ValueError('配置、导航或轨迹文件已变化')
        if galileo:
            from galileo_lab import convert
        elif result['signal']=='GLO-G1':
            from glonass_lab import convert
        else:
            from bds_navigation import convert
        from live_nav import gps_utc_offset
        start=datetime.fromisoformat(metadata['startUTC'])
        leaps=(folder/'source-iers.dat').read_text();offset=gps_utc_offset(leaps,start)
        if gps_utc_offset(leaps,start+timedelta(seconds=duration))!=offset:raise ValueError('Navigation scenario crosses leap transition')
        nav=(folder/nav_name).read_text();convert(nav,nav,start,offset,duration)
        rate,frequency=expected_rate,expected_frequency
    if result.get('passed') is not True or iq.stat().st_size != duration * rate * 2:
        raise ValueError('场景未通过独立定位验证，或文件长度已变化')
    with iq.open('rb') as stream:
        if hashlib.file_digest(stream, 'sha256').hexdigest() != result.get('iqSHA256'):
            raise ValueError('I/Q 文件已变化，请重新生成验证')
    command = [str(ROOT.parent / 'host/build/hackrf-tools/src/hackrf_transfer'),
               '-t', str(iq), '-f', str(frequency), '-s', str(rate),
               '-n', str(duration * rate), '-x', str(gain), '-a', str(int(amplifier)), '-p', '0', '-B']
    if result.get('signal')=='GLO-G1':command+=['-b','10000000']
    with (jobs / '.transmit.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('已有应用发送任务正在运行')
        name = 'transmit-' + uuid.uuid4().hex[:8]
        record = {'command': command, 'durationSeconds': duration, 'status': 'starting',
                  'requestedAt': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),
                  'scheduledStartUTC': metadata['startUTC'],
                  'phoneReceptionVerified': False}
        path = folder / (name + '.json')
        path.write_text(json.dumps(record, ensure_ascii=False, indent=2))
        try:
            wait_until_start(metadata)
            record['startedAt'] = datetime.now(ZoneInfo('Asia/Shanghai')).isoformat()
            path.write_text(json.dumps(record, ensure_ascii=False, indent=2))
            emit('progress', fraction=0, message=f'正在启动 HackRF：单次 {duration} 秒，可随时停止')
            execute(command, folder, name + '.log', duration + 5)
            log = (folder / (name + '.log')).read_text(errors='replace')
            if 'hackrf_stop_tx() done' not in log:
                raise ValueError('工具结束，但未确认正常停止发射；请现场检查设备')
            if result.get('signal') in sampled_profiles:
                underruns=re.findall(r'(\d+) underruns',log)
                transferred=re.findall(r'(\d+) bytes transferred by M0',log)
                modes=re.findall(r'M0 mode (\d+) requested (\d+) error (\d+)',log)
                record.update(signal=result['signal'],sampleRate=rate,frequencyHz=frequency,
                              underruns=int(underruns[-1]) if underruns else None,m0Bytes=int(transferred[-1]) if transferred else None,
                              txRunObserved=any(int(active)==4 for active,requested,error in modes))
                if not underruns or int(underruns[-1])!=0 or not transferred or int(transferred[-1])<duration*rate*2:
                    raise ValueError('导航信号发送未通过样本计数和零欠载检查，详情见发送日志')
                if not any(int(active)==4 for active,requested,error in modes) or any(int(error)!=0 for active,requested,error in modes):
                    raise ValueError('导航信号发送未确认固件TX_RUN状态或固件报告错误，详情见日志')
            record['status'] = 'completed'
            emit('transmitted', fraction=1, message='HackRF 发送任务已结束并停止；接收端结果待现场记录')
        except BaseException as error:
            record['status'] = 'stopped' if isinstance(error, SystemExit) else 'failed'
            record['error'] = str(error)
            raise
        finally:
            record['endedAt'] = datetime.now(ZoneInfo('Asia/Shanghai')).isoformat()
            path.write_text(json.dumps(record, ensure_ascii=False, indent=2))


def sync_phone(folder, phase, serial=None):
    manifest = folder / 'phone-scenario.json'
    adb = shutil.which('adb') or str(Path.home() / 'Android/sdk/platform-tools/adb')
    if not manifest.exists() or not adb:
        return
    guard = None
    try:
        if phase == 'prepared':
            guard = (folder.parent / '.transmit.lock').open('a')
            try:
                fcntl.flock(guard, fcntl.LOCK_SH | fcntl.LOCK_NB)
            except BlockingIOError:
                emit('progress', message='已有发送任务：保留手机正在接收的场景信息')
                return
        scenario = json.loads(manifest.read_text())
        scenario['phase'] = phase
        manifest.write_text(json.dumps(scenario, ensure_ascii=False))
        output = subprocess.check_output([adb, 'devices'], text=True, timeout=5)
        devices = [line.split()[0] for line in output.splitlines()[1:] if len(line.split()) >= 2 and line.split()[1] == 'device']
        if (serial is None and len(devices) != 1) or (serial is not None and serial not in devices):
            raise ValueError('接收手机未连接或未明确选择接收手机')
        remote=shlex.join(['content','call','--uri','content://local.hackrf.receiver.public.logs','--method','setScenario',
                           '--arg',json.dumps(scenario,ensure_ascii=False,separators=(',',':'))])
        response=subprocess.run([adb,'-s',serial or devices[0],'shell',remote],capture_output=True,text=True,timeout=5,check=True)
        if not re.search(r'\baccepted=true(?:[,}\]])',response.stdout):raise ValueError('接收实验室未确认当前场景，请更新接收 App')
        emit('progress', message='接收手机场景信息已同步')
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        emit('progress', message=f'手机场景同步未完成：{error}；不影响记录原始 GNSS')
    finally:
        if guard is not None:
            guard.close()


def wait_until_start(metadata):
    deadline = time.monotonic() + launch_delay(metadata)
    while (remaining := deadline - time.monotonic()) > 0:
        emit('progress', fraction=0, message=f'等待与导航电文对齐的发送时刻：约 {remaining:.0f} 秒，可停止')
        time.sleep(min(1, remaining))
    launch_delay(metadata)


class PreparationLate(ValueError):pass


def run(settings, folder):
    for attempt in range(2):
        target=folder if attempt==0 else folder.with_name(folder.name+'-retry')
        try:return run_once(settings,target)
        except PreparationLate:
            if attempt:raise ValueError('准备仍超过安全余量，请稍后重试')
            emit('progress',fraction=.02,message='准备耗时超过余量，正在重新生成当前时间场景')


def run_once(settings, folder):
    ideal, emitted, preview = plan(settings)
    emit('preview', preview=preview)
    folder.mkdir(parents=True, exist_ok=False)
    emit('receiver-source', receiverFile=str(folder / 'fix.nmea'))
    (folder / 'settings.json').write_text(json.dumps(settings, ensure_ascii=False, indent=2) + '\n')
    emit('progress', fraction=0.03, message='正在获取当前广播星历、UTC 模型和闰秒表；不会回退到历史星历')
    if shutil.disk_usage(folder).free < int(settings['duration']) * 5_200_000 + 500_000_000:
        raise ValueError('磁盘空间不足，无法生成所选时长的 I/Q')
    environments=json.loads((ROOT.parent/'shared/signal-environments.json').read_text())
    profiles=tuple(p['id'] for p in environments['profiles'])
    profile=environments['routes'][settings['route']]
    domestic=os.environ.get('WALKLAB_DOMESTIC_ONLY','false')
    if domestic not in ('true','false'):raise ValueError('国内网络配置无效')
    navigation, timing = prepare(folder,lead_seconds=lead_seconds(int(settings['duration']),previous_rate()),duration=int(settings['duration']),domestic_only=domestic=='true')
    prepared_at=time.monotonic()
    execute([sys.executable, str(ROOT / 'build_generator.py')], folder, 'build-generator.log', 60)
    emit('progress', fraction=0.08, message=f'最新广播星历已获取；计划发送 UTC {timing["startUTC"]}')
    for name, motion in (('ideal', ideal), ('emitted', emitted)):
        np.savetxt(folder / f'{name}.csv', motion, delimiter=',', fmt=['%.1f', '%.6f', '%.6f', '%.6f'])
    emit('progress', fraction=0.12, message='轨迹已生成；正在生成离线 I/Q 文件')
    iq = folder / 'signal.bin'
    execute([str(ROOT / 'output/generator/gps-sdr-sim'), '-e', navigation.name,
             '-u', 'emitted.csv', '-t', timing['startGPS'], '-Q', str(profiles.index(profile)), '-S', str(int(settings['seed'])),
             '-d', str(settings['duration'] + 0.1), '-s', '2600000', '-b', '8', '-o', iq.name],
            folder, 'generator.log', max(120, settings['duration']))
    if iq.stat().st_size != int(settings['duration']) * 2_600_000 * 2:
        raise ValueError('生成文件的实际样本数不符合预期')
    emit('progress', fraction=0.48, message='正在独立捕获卫星信号')
    with iq.open('rb') as stream:
        raw = np.frombuffer(stream.read(N * 20), dtype=np.int8)
    prns = [a['prn'] for a in acquire(raw[::2].astype(float) + 1j * raw[1::2]) if a['detected']]
    config = receiver_config(iq, prns)
    config.set('GNSS-SDR', 'PVT.iono_model', 'Broadcast')
    with (folder / 'receiver.conf').open('w') as stream:
        config.write(stream)
    emit('progress', fraction=0.62, message=f'捕获 {len(prns)} 个通道；正在用 GNSS-SDR 解码')
    binary = 'gnss-sdr'
    execute([binary, f'--config_file={folder / "receiver.conf"}', '--log_dir=.'], folder, 'receiver.log', max(180, settings['duration'] * 2))
    emit('progress', fraction=0.88, message='正在对齐 UTC 时间并验证轨迹误差')
    nmea_path = folder / 'fix.nmea'
    nmea = nmea_path.read_bytes() if nmea_path.exists() else b''
    observations = []
    for line in nmea.decode().splitlines():
        fix = gga(line)
        if fix is not None:
            start_utc = datetime.fromisoformat(timing['startUTC']).strftime('%H%M%S')
            observations.append((elapsed_seconds(line.split(',')[1], start_utc), *ecef(fix)))
    stats = compare(observations, emitted)
    ideal_stats = compare(observations, ideal)
    basis = enu_basis(preview['wgs84Latitude'], preview['wgs84Longitude'])
    origin = np.array(ecef((preview['wgs84Latitude'], preview['wgs84Longitude'], preview['ellipsoidHeight'])))
    decoded = (np.asarray(observations)[:, 1:] - origin) @ basis.T if observations else np.empty((0, 3))
    with iq.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    result = {'folder': str(folder), 'passed': stats['passed'], 'maximumAllowed3DErrorM': 30, 'validFixes': stats['valid_fixes'],
              'medianError': stats.get('median_error_3d_m'), 'maxError': stats.get('maximum_error_3d_m'),
              'medianErrorToHumanPath': ideal_stats.get('median_error_3d_m'),
              'decodedPath': decoded[::max(1, len(decoded)//240), :2].tolist(),
              'iqSHA256': digest, 'rfEnabled': False,
              'sceneSatellites': scene_satellites((folder/'generator.log').read_text()),
              'receiverVersion': subprocess.check_output([binary, '--version'], text=True).strip(),
              'ephemerisDate': timing['broadcastProducedAtUTC'][:10],
              'gpsMinusUTCSeconds': timing['gpsMinusUTCSeconds'], 'navigation': timing,
              'startUTC': timing['startUTC'],
              'coordinateConversionCalibrated': False, 'baselineHeightConfirmed': False,
              'signalEnvironment': {'profile':profile,'label':environments['profiles'][profiles.index(profile)]['label'],'motionRoute':settings['route'],'seed':int(settings['seed']),'absoluteCN0Calibrated':False},
              'driftModel': 'coherent position bias; relative per-satellite fading/noise; not calibrated multipath'}
    elapsed=time.monotonic()-prepared_at
    record_duration(int(settings['duration']),elapsed)
    timing['preparationMillis']=round(elapsed*1000)
    (folder/'navigation.json').write_text(json.dumps(timing,ensure_ascii=False,indent=2)+'\n')
    if result['passed'] and (datetime.fromisoformat(timing['startUTC'])-datetime.now(timezone.utc)).total_seconds()<3:
        iq.unlink()
        raise PreparationLate('准备超过计划时刻')
    (folder / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    basis = enu_basis(preview['wgs84Latitude'], preview['wgs84Longitude'])
    points = (emitted[:, 1:] - origin) @ basis.T
    scenario = {'id': folder.name, 'phase': 'prepared', 'startUTC': timing['startUTC'],
                'durationSeconds': int(settings['duration']),
                'targetLatitude': preview['wgs84Latitude'], 'targetLongitude': preview['wgs84Longitude'],
                'gpsPRNs': prns,
                'points': [[float(emitted[i, 0]), float(points[i, 0]), float(points[i, 1])] for i in range(0, len(emitted), 10)]}
    (folder / 'phone-scenario.json').write_text(json.dumps(scenario, ensure_ascii=False))
    emit('result', result=result)
    return result


def start_simulation(payload, folder,system='gps'):
    if system not in ('gps','bds'):raise ValueError('尚未开放该导航系统')
    if set(payload) != {'settings', 'gain', 'amplifier'}:
        raise ValueError('启动参数必须包含 settings、gain、amplifier')
    if type(payload['gain']) is not int or not 0 <= payload['gain'] <= 47 or type(payload['amplifier']) is not bool:
        raise ValueError('TX 增益或放大器参数无效')
    from single_live import run as stream_run
    return stream_run(payload,folder,emit,system=system)


def main():
    import live_nav
    live_nav.download_status=lambda message:emit('progress',message=message)
    signal.signal(signal.SIGTERM, cancel)
    signal.signal(signal.SIGINT, cancel)
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['preview', 'run', 'device', 'transmit', 'start', 'bds', 'bds-preview', 'bds-archive', 'bds-plan', 'bds-start','mixed-preview','mixed-start','galileo','galileo-preview','galileo-start','glonass','glonass-preview','glonass-start'])
    args = parser.parse_args()
    if args.action.startswith(('galileo','glonass')):
        raise ValueError('已取消该导航系统，仅支持 GPS 和北斗')
    if args.action == 'device':
        process = subprocess.run([str(ROOT.parent / 'host/build/hackrf-tools/src/hackrf_info')],
                                 capture_output=True, text=True, timeout=10)
        emit('device', message=process.stdout + process.stderr)
        return
    payload = sys.stdin.buffer.read(16385)
    if len(payload) > 16384:
        raise ValueError('参数数据过大')
    settings = json.loads(payload)
    if not isinstance(settings, dict):
        raise ValueError('参数必须为 JSON 对象')
    if args.action == 'transmit':
        transmit(settings)
        return
    if args.action == 'mixed-preview':
        from mixed_transmit import preview
        emit('preview',preview=preview(settings))
        return
    if args.action == 'bds-preview':
        from bds_lab import configuration
        preview=configuration(settings,Path('archive.rnx'),Path('signal.bin'))[3]
        preview.update(streamMBps=10.0,streamSampleRate=5000000)
        emit('preview',preview=preview)
        return
    if args.action == 'galileo-preview':
        from galileo_lab import configuration
        emit('preview',preview=configuration(settings,Path('broadcast-galileo.rnx'),Path('signal.bin'),datetime.now(timezone.utc))[3])
        return
    if args.action == 'glonass-preview':
        from glonass_lab import configuration
        emit('preview',preview=configuration(settings,Path('broadcast-glonass.rnx'),Path('signal.bin'),datetime.now(timezone.utc))[3])
        return
    if args.action == 'preview':
        preview=plan(settings)[2]
        preview.update(streamMBps=5.2,streamSampleRate=2600000)
        emit('preview', preview=preview)
        return
    name = datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:6]
    folder = ROOT / 'output/app-jobs' / name
    emit('receiver-source', receiverFile=str(folder / 'fix.nmea'))
    if args.action == 'mixed-start':
        from mixed_transmit import run as mixed_run
        mixed_run(settings,folder,emit)
        return
    if args.action in ('bds','bds-archive','bds-plan'):
        from bds_lab import run as bds_run
        bds_run(settings,folder,execute,emit,archive=args.action=='bds-archive',planned=args.action=='bds-plan')
    elif args.action=='galileo':
        from galileo_lab import run as galileo_run
        galileo_run(settings,folder,execute,emit)
    elif args.action=='glonass':
        from glonass_lab import prepare
        prepare(settings,folder,execute,emit)
    elif args.action in ('start','bds-start','galileo-start','glonass-start'):
        start_simulation(settings, folder,system={'start':'gps','bds-start':'bds','galileo-start':'galileo','glonass-start':'glonass'}[args.action])
    else:
        run(settings, folder)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        emit('error', message=str(error))
        raise SystemExit(1)
