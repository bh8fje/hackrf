"""BDS B1I sampled-motion current-data experiment, with an explicit archive regression path."""
from datetime import datetime, timedelta, timezone
import configparser
import hashlib
import json
import math
from pathlib import Path
import re
import shutil
import subprocess
import time
from preparation import BDS_STATE,bds_lead_seconds,previous_rate,record_duration

import numpy as np
from crosscheck import ecef, gga, NMEA_RMC_PREFIXES
from dynamic import elapsed_seconds
from position import geodetic
from walking import plan, validate, enu_basis, signal_environment

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT / 'SignalSim'
REVISION = 'baba9deb68ed193efb9fd9a362b431ae8c491fa4'
START = datetime(2021, 6, 19, 10, 5, 30, tzinfo=timezone.utc)
RATE = 5_000_000
RECEIVER = Path('gnss-sdr')


def configuration(settings, navigation, output, start=START):
    validate(settings)
    ideal, emitted, preview = plan(settings)
    lat, lon, height = geodetic(emitted[0, 1:])
    config = {
        'version': 1.0, 'description': 'BDS B1I sampled-motion independent-receiver experiment',
        'time': {'type': 'UTC', **{k: getattr(start, k) for k in ('year','month','day','hour','minute','second')}},
        'trajectory': {'name': 'sampled BDS trajectory',
            'initPosition': {'type': 'LLA', 'format': 'd', 'latitude': lat, 'longitude': lon, 'altitude': height},
            'initVelocity': {'type': 'SCU', 'speed': 0, 'course': 0},
            'trajectoryList': [{'type': 'Const', 'time': int(settings['duration'])}]},
        'ephemeris': {'type': 'RINEX', 'name': str(navigation)},
        'output': {'type': 'IFdata', 'format': 'IQ8', 'sampleFreq': RATE/1e6,
            'centerFreq': 1561.098, 'name': str(output), 'config': {'elevationMask': 5},
            'systemSelect': [{'system': s, 'enable': False} for s in ('GPS','Galileo','GLONASS')]
                            + [{'system': 'BDS', 'signal': 'B1I', 'enable': True}]},
        'power': {'noiseFloor': -172, 'initPower': {'unit': 'dBHz', 'value': 47}, 'elevationAdjust': False}}
    preview['estimatedFileMB'] = int(settings['duration']*RATE*2/1e6)
    return config, ideal, emitted, preview


def receiver_config(iq, prns):
    if len(prns)<4 or len(set(prns))!=len(prns) or any(type(p) is not int or not 1<=p<=63 for p in prns):
        raise ValueError('Need distinct BDS PRNs from generator evidence')
    config = configparser.ConfigParser()
    config.optionxform = str
    config.read(ROOT/'gnss-sdr.conf')
    values = dict(config['GNSS-SDR'])
    values = {k.replace('_1C','_B1'): v.replace('GPS_L1_CA','BEIDOU_B1I')
              for k,v in values.items() if not re.fullmatch(r'Channel\d+\.satellite', k)}
    values.update({'GNSS-SDR.internal_fs_sps': str(RATE), 'SignalSource.sampling_frequency': str(RATE),
                   'SignalSource.filename': str(iq), 'Channels_B1.count': str(len(prns)),
                   'Channels.in_acquisition': str(len(prns)), 'Channel.signal': 'B1',
                   'Acquisition_B1.doppler_max': '10000', 'Acquisition_B1.coherent_integration_time_ms': '2',
                   'Tracking_B1.extend_correlation_symbols':'20', 'PVT.iono_model': 'Broadcast', 'PVT.trop_model': 'Saastamoinen'})
    values.update({f'Channel{i}.satellite': str(prn) for i,prn in enumerate(prns)})
    config['GNSS-SDR'] = values
    return config


def generated_prns(log):
    section=log.split('BeiDou B1I with IF ',1)[1].split('Total channels:',1)[0]
    return [int(value) for value in re.findall(r'\|\s*(\d{2})\s*\|\s*[+-]?\d+\s*',section)]


def summarize(nmea, ideal, emitted, duration, start=START):
    fixes=[];date_verified=False;geometry=[]
    for line in nmea.splitlines():
        if line.startswith(tuple(f'${system}GSA,' for system in ('GP','GN','GA','GL','GB','BD'))):
            body,checksum=line.strip()[1:].split('*');actual=0
            for char in body:actual^=ord(char)
            if len(checksum)!=2 or actual!=int(checksum,16):raise ValueError('GSA checksum mismatch')
            fields=body.split(',')
            if fields[2] in ('2','3'):
                dop=[float(v)for v in fields[15:18]]
                if len(dop)!=3 or any(not math.isfinite(v) or v<0 for v in dop):raise ValueError('Invalid receiver DOP')
                geometry.append(dop)
        if line.startswith(NMEA_RMC_PREFIXES):
            body,checksum=line.strip()[1:].split('*');actual=0
            for char in body:actual^=ord(char)
            if len(checksum)!=2 or actual!=int(checksum,16):raise ValueError('RMC checksum mismatch')
            fields=body.split(',')
            if fields[2]=='A':
                elapsed=elapsed_seconds(fields[1],start.strftime('%H%M%S'))
                expected=(start+timedelta(seconds=elapsed)).strftime('%d%m%y')
                if not 0<=elapsed<=duration or fields[9]!=expected:raise ValueError('Receiver calendar date differs from scenario')
                date_verified=True
        fix=gga(line)
        if fix is not None:
            elapsed=elapsed_seconds(line.split(',')[1],start.strftime('%H%M%S'))
            if not 0 <= elapsed <= duration:
                raise ValueError('Receiver UTC outside scenario')
            if fixes and elapsed <= fixes[-1][0]:
                raise ValueError('Receiver epochs are not increasing')
            fixes.append((elapsed,*ecef(fix)))
    def errors_to(truth):
        truth=np.asarray(truth)
        if truth.shape==(3,):return [math.dist(p[1:],truth) for p in fixes]
        if (truth.shape!=(duration*10+2,4) or not np.isfinite(truth).all()
                or not np.allclose(truth[:,0],np.arange(duration*10+2)/10,rtol=0,atol=1e-7)):
            raise ValueError('Invalid 10 Hz ECEF reference trajectory')
        return [math.dist(p[1:],[np.interp(p[0],truth[:,0],truth[:,axis]) for axis in (1,2,3)]) for p in fixes]
    errors=errors_to(emitted)
    ideal_errors=errors_to(ideal)
    reference=np.asarray(emitted)
    expected=np.tile(reference,(len(fixes),1)) if reference.shape==(3,) else np.column_stack([np.interp([p[0] for p in fixes],reference[:,0],reference[:,axis]) for axis in (1,2,3)])
    lat,lon,_=geodetic(reference if reference.shape==(3,) else reference[0,1:])
    enu=(np.asarray([p[1:] for p in fixes]).reshape(-1,3)-expected)@enu_basis(lat,lon).T
    horizontal=np.linalg.norm(enu[:,:2],axis=1)
    return {'passed':date_verified and len(fixes)>=100 and max(errors,default=math.inf)<=30, 'receiverUTCDateVerified':date_verified,'maximumAllowed3DErrorM':30,
            'validFixes':len(fixes),
            'measuredEndpointDistance':math.dist(fixes[0][1:],fixes[-1][1:]) if len(fixes)>1 else None, 'medianError':float(np.median(errors)) if errors else None,
            'maxError':max(errors,default=None),
            'horizontalErrorP95M':float(np.percentile(horizontal,95)) if fixes else None,
            'verticalAbsErrorP95M':float(np.percentile(np.abs(enu[:,2]),95)) if fixes else None,
            'error3DP95M':float(np.percentile(errors,95)) if fixes else None,
            'medianHorizontalErrorM':float(np.median(horizontal)) if fixes else None,
            'verticalBiasM':float(np.mean(enu[:,2])) if fixes else None,
            'accuracyStatistics':'Empirical percentiles of this simulation window; not official service compliance or confidence intervals.',
            'geometry':{'gsaMessages':len(geometry),'medianPDOP':float(np.median([d[0]for d in geometry])) if geometry else None,
                        'maximumPDOP':max((d[0]for d in geometry),default=None),
                        'medianHDOP':float(np.median([d[1]for d in geometry])) if geometry else None,
                        'medianVDOP':float(np.median([d[2]for d in geometry])) if geometry else None,
                        'messagesWithPDOPAtMost6':sum(d[0]<=6 for d in geometry)},
            'medianErrorToHumanPath':float(np.median(ideal_errors)) if fixes else None,
            'firstFixSeconds':fixes[0][0] if fixes else None,
            'lastFixSeconds':fixes[-1][0] if fixes else None,
            'fixEpochCoverageFraction':len(fixes)/(duration*10),
            'maxGapSeconds':float(max(np.diff([0]+[p[0] for p in fixes]+[duration]),default=duration))}


def run(settings, folder, execute, emit, archive=False, planned=False):
    if archive and planned:raise ValueError("Archive BDS cannot be scheduled for transmission")
    folder.parent.mkdir(parents=True,exist_ok=True)
    if shutil.disk_usage(folder.parent).free < settings['duration']*RATE*2+500_000_000:
        raise ValueError('Insufficient storage for BDS experiment')
    folder.mkdir(exist_ok=False)
    (folder/'settings.json').write_text(json.dumps(settings,indent=2))
    emit('progress',fraction=.02,message='正在构建两端共用的北斗生成器')
    execute([str(ROOT/'.venv/bin/python'),str(ROOT/'build_bds_generator.py')],folder,'build.log',240)
    if archive:
        start=START;navigation=SOURCE/'EphData/BRDC00IGS_R_20211700000_01D_MN.rnx'
        timing={'mode':'offline-archive','startUTC':start.isoformat()}
    else:
        from bds_navigation import prepare
        emit('progress',fraction=.08,message='正在获取最新北斗广播星历和BDS模型，检查整个实验时段')
        navigation,timing=prepare(folder,int(settings['duration']),lead=bds_lead_seconds(int(settings['duration']),previous_rate(BDS_STATE) if BDS_STATE.exists() else 1.6) if planned else 0)
        start=datetime.fromisoformat(timing['startUTC'])
    prepared_at=time.monotonic()
    execute([str(ROOT/'output/bds-build-current/BdsNativeCheck'),str(navigation)],folder,'native-check.log',30)
    iq=folder/'signal.bin'
    config,ideal,emitted,preview=configuration(settings,navigation,iq,start)
    for name,points in [('ideal.csv',ideal),('emitted.csv',emitted)]:
        np.savetxt(folder/name,points,delimiter=',',fmt=['%.1f','%.6f','%.6f','%.6f'])
    environment=signal_environment(settings)
    metadata={'mode':timing['mode'],'signal':'BDS-B1I','startUTC':start.isoformat(),'signalEnvironment':environment,
              'sourceSHA256':hashlib.sha256(navigation.read_bytes()).hexdigest(),
              'generator':json.loads((ROOT/'output/bds-build-current/provenance.json').read_text()),
              'sampleRate':RATE,'frequencyHz':1561098000,'settings':settings,
              'navigation':timing,'hardwareOutputAllowed':False,
              'limitations':['Motion uses 10 Hz linear ECEF interpolation; relative signal environment is not a calibrated RF channel.',
                             'Klobuchar/troposphere model is not a calibrated real RF channel.'],
              'startupOffsetEast':preview['startupOffsetEast'],'startupOffsetNorth':preview['startupOffsetNorth']}
    (folder/'experiment.json').write_text(json.dumps(metadata,indent=2))
    (folder/'scenario.json').write_text(json.dumps(config,indent=2))
    emit('preview',preview=preview)
    build=ROOT/'output/bds-build-current'
    emit('progress',fraction=.15,message='正在生成北斗 B1I 离线 I/Q，应用路线、固定偏移与缓慢漂移')
    execute(['/usr/bin/env','OMP_NUM_THREADS=4','OMP_WAIT_POLICY=PASSIVE','OMP_DYNAMIC=FALSE',str(build/'IFdataGen'),'-c',str(folder/'scenario.json'),'-u',str(folder/'emitted.csv'),'-mt','-Q',str(environment['profileIndex']),'-S',str(environment['seed'])],folder,'generator.log',max(180,settings['duration']*4))
    if iq.stat().st_size != settings['duration']*RATE*2:
        raise ValueError('BDS generated file length mismatch')
    prns=generated_prns((folder/'generator.log').read_text())
    with (folder/'receiver.conf').open('w') as stream:receiver_config(iq,prns).write(stream)
    emit('progress',fraction=.6,message='GNSS-SDR 正在独立接收北斗 B1I（仅BDS通道）')
    execute([str(RECEIVER),'--config_file='+str(folder/'receiver.conf'),'--log_dir='+str(folder)],folder,'receiver.log',max(180,settings['duration']*4))
    nmea=(folder/'fix.nmea').read_text() if (folder/'fix.nmea').exists() else ''
    stats=summarize(nmea,ideal,emitted,settings['duration'],start)
    with iq.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
    origin=np.array(ecef((preview['wgs84Latitude'],preview['wgs84Longitude'],preview['ellipsoidHeight'])))
    fixes=[ecef(fix) for line in nmea.splitlines() if (fix:=gga(line)) is not None]
    decoded=(np.asarray(fixes)-origin) @ enu_basis(preview['wgs84Latitude'],preview['wgs84Longitude']).T if fixes else np.empty((0,3))
    elapsed=time.monotonic()-prepared_at
    record_duration(int(settings['duration']),elapsed,BDS_STATE)
    timing['preparationMillis']=round(elapsed*1000)
    (folder/'navigation.json').write_text(json.dumps(timing,indent=2)+'\n')
    if planned and (start-datetime.now(timezone.utc)).total_seconds()<3:raise ValueError('BDS preparation missed planned start; regenerate')
    result=dict(stats,folder=str(folder),rfEnabled=False,decodedPath=decoded[::max(1,len(decoded)//240),:2].tolist(),signal='BDS-B1I',signalEnvironment=environment,
                mode=timing['mode'],iqSHA256=digest,experimentStartUTC=start.isoformat(),hardwareOutputAllowed=bool(planned and stats['passed']),navigation=timing,
                sampleRate=RATE,frequencyHz=1561098000,durationSeconds=int(settings['duration']),startUTC=start.isoformat() if planned else None,
                preparedFilesSHA256={name:hashlib.sha256((folder/name).read_bytes()).hexdigest() for name in ('scenario.json','broadcast-bds.rnx','emitted.csv')},
                receiverVersion=subprocess.check_output([str(RECEIVER),'--version'],text=True).strip())
    metadata.update(hardwareOutputAllowed=result['hardwareOutputAllowed'],plannedTransmission=planned,softwareReceiverVerified=stats['passed'])
    (folder/'experiment.json').write_text(json.dumps(metadata,indent=2)+'\n')
    points=(emitted[:,1:]-origin)@enu_basis(preview['wgs84Latitude'],preview['wgs84Longitude']).T
    scenario={'id':folder.name,'phase':'prepared','startUTC':timing['startUTC'],'durationSeconds':int(settings['duration']),
              'targetLatitude':preview['wgs84Latitude'],'targetLongitude':preview['wgs84Longitude'],'constellationId':5,'svids':prns,
              'points':[[float(emitted[i,0]),float(points[i,0]),float(points[i,1])] for i in range(0,len(emitted),10)]}
    (folder/'phone-scenario.json').write_text(json.dumps(scenario,ensure_ascii=False))
    (folder/'result.json').write_text(json.dumps(result,indent=2)+'\n')
    emit('result',result=result)
    emit('progress',fraction=1,message='BDS 离线轨迹定位'+('验证通过' if stats['passed'] else '未通过')+('；归档回归，不提供发送' if archive else ('；计划时刻前已验证，等待受控发送' if planned and stats['passed'] else '；最新广播数据，不提供发送')))
    return result


def verify_phone(folder,execute):
    manifest=json.loads((folder/'experiment.json').read_text())
    duration=manifest['durationSeconds'];iq=folder/'signal.bin'
    galileo=manifest.get('signal')=='GAL-E1';glonass=manifest.get('signal')=='GLO-G1'
    if manifest.get('signal') not in (None,'BDS-B1I','GAL-E1','GLO-G1'):raise ValueError('Unsupported phone navigation signal')
    if galileo:
        from galileo_lab import RATE as rate,generated_prns as read_prns,receiver_config as receiver_builder,convert
    elif glonass:
        from glonass_lab import RATE as rate,generated_slots as read_prns,receiver_config as receiver_builder,convert
    else:
        from bds_navigation import convert
        rate,read_prns,receiver_builder=RATE,generated_prns,receiver_config
    signal='GAL-E1' if galileo else 'GLO-G1' if glonass else 'BDS-B1I'
    if (type(duration) is not int or not 90<=duration<=600 or manifest.get('type')!=('galileo-research-iq' if galileo else 'glonass-research-iq' if glonass else 'bds-research-iq')
            or manifest.get('mode')!='current-broadcast' or manifest.get('hardwareOutputAllowed') is not False
            or manifest.get('sampleRate')!=rate or manifest.get('frequencyHz')!=(1575420000 if galileo else 1602000000 if glonass else 1561098000)
            or iq.stat().st_size!=duration*rate*2):raise ValueError('Invalid phone GNSS research artifact')
    from live_nav import gps_utc_offset
    start=datetime.fromisoformat(manifest['navigation']['startUTC']);leaps=(folder/'source-iers.dat').read_text();offset=gps_utc_offset(leaps,start)
    if gps_utc_offset(leaps,start+timedelta(seconds=duration))!=offset:raise ValueError('Phone scenario crosses leap transition')
    nav=(folder/('broadcast-galileo.rnx' if galileo else 'broadcast-glonass.rnx' if glonass else 'broadcast-bds.rnx')).read_text();convert(nav,nav,start,offset,duration)
    with iq.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
    if digest!=manifest['iqSHA256']:raise ValueError('Phone I/Q hash mismatch')
    if manifest.get('schema')==2:
        truth=[]
        for name in ('ideal.csv','emitted.csv'):
            if hashlib.sha256((folder/name).read_bytes()).hexdigest()!=manifest['trajectorySHA256'][name]:
                raise ValueError('Phone trajectory hash mismatch')
            truth.append(np.loadtxt(folder/name,delimiter=','))
    elif manifest.get('schema')==1:truth=[manifest['idealECEF'],manifest['emittedECEF']]
    else:raise ValueError('Unsupported BDS research artifact schema')
    prns=read_prns((folder/'generator.log').read_text())
    if galileo:
        from galileo_lab import decode
        stats,_=decode(iq,folder,prns,*truth,duration,start,execute)
    else:
        with (folder/'receiver.conf').open('w') as stream:receiver_builder(iq,prns).write(stream)
        execute([str(RECEIVER),'--config_file='+str(folder/'receiver.conf'),'--log_dir='+str(folder)],folder,'receiver.log',max(240,duration*4))
        nmea=(folder/'fix.nmea').read_text() if (folder/'fix.nmea').exists() else ''
        stats=summarize(nmea,*truth,duration,start)
    stats.update(iqSHA256=digest,signal=signal,softwareReceiverVerified=stats['passed'],rfEnabled=False,
                 navigation=manifest['navigation'],generator=manifest['generator'])
    (folder/'independent-report.json').write_text(json.dumps(stats,indent=2)+'\n')
    return stats

if __name__=='__main__':
    import argparse
    from app_runner import execute
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-phone',type=Path,required=True)
    result=verify_phone(parser.parse_args().verify_phone.resolve(),execute)
    print(json.dumps(result,indent=2))
    raise SystemExit(0 if result['passed'] else 1)
