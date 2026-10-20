"""GLONASS G1 offline prototype; hardware output stays disabled."""
from datetime import datetime,timedelta,timezone
import configparser,hashlib,json,math,re,shutil,time
from pathlib import Path
import numpy as np
from bds_lab import ROOT,RECEIVER,configuration as bds_configuration,summarize
from live_nav import header_line,production,gps_utc_offset,cached_fetch,navigation_sources
from preparation import bds_lead_seconds,previous_rate,record_duration
from crosscheck import ecef
from walking import enu_basis,signal_environment

UTC=timezone.utc
RATE=10000000
STATE=ROOT/'output/preparation-glonass-g1-mac.json'
# GNSS-SDR's acquisition currently uses this fixed slot-to-FDMA table.
RECEIVER_FREQUENCIES=dict(enumerate((1,-4,5,6,1,-4,5,6,-2,-7,0,-1,-2,-7,0,-1,4,-3,3,2,4,-3,3,2),1))


def fetch_sources(folder):
    now=datetime.now(UTC);folder=Path(folder);folder.mkdir(parents=True,exist_ok=False);sources=[]
    text,info=cached_fetch('https://igs.bkg.bund.de/root_ftp/NTRIP/BRDC/brdc_last.rnx.Z',1800,force=True)
    (folder/'source-broadcast.rnx').write_text(text);sources.append(info)
    leaps=(ROOT.parent/'mobile-controller/data/Leap_Second.dat').read_text()
    (folder/'source-iers.dat').write_text(leaps)
    failures=[]
    for url in navigation_sources(now):
        try:
            text,info=cached_fetch(url,10800,force=True)
            model_parameters(text,now,gps_utc_offset(leaps,now))
            (folder/'source-models.rnx').write_text(text);sources.append(info);break
        except (OSError,ValueError) as error:failures.append({'url':url,'error':str(error)})
    (folder/'navigation-source-audit.json').write_text(json.dumps(failures,indent=2))
    if len(sources)!=2:raise ValueError('No complete current GLONASS models: '+json.dumps(failures))
    (folder/'source-metadata.json').write_text(json.dumps(sources,indent=2));return folder


def model_parameters(models,start,offset):
    headers=[];tags=set();leaps=None;time_offsets={}
    for row in models.splitlines():
        label=row[60:].strip();tag=row[:4].strip()
        if label=='END OF HEADER':break
        if label=='IONOSPHERIC CORR' and tag in ('GPSA','GPSB'):
            if not all(math.isfinite(float(row[5+j*12:17+j*12].replace('D','E'))) for j in range(4)):raise ValueError('Invalid channel ionosphere model')
            tags.add(tag);headers.append(row)
        elif label=='TIME SYSTEM CORR' and tag in ('GLUT','GLGP','GPUT'):
            values=[float(row[5:22].replace('D','E')),float(row[22:38].replace('D','E'))]
            tow=int(row[38:45]);week=int(row[45:50])
            if tag in tags or not all(math.isfinite(v) for v in values) or not 0<=tow<604800 or not 0<=week<=32767:raise ValueError('Invalid time model')
            ref=datetime(1980,1,6,tzinfo=UTC)+timedelta(weeks=week,seconds=tow)
            zero_reference=tag in ('GLUT','GLGP') and tow==week==0
            if not zero_reference and abs((start+timedelta(seconds=offset)-ref).total_seconds())>14*86400:raise ValueError('Outdated time model')
            if tag in ('GLUT','GLGP'):
                scale,bits=(31,31) if tag=='GLUT' else (30,21)
                if values[1]!=0 or round(abs(values[0])*2**scale)>=2**bits:raise ValueError('Unsupported GLONASS time offset')
                time_offsets['tauCSeconds' if tag=='GLUT' else 'tauGPSSeconds']=-values[0]
            tags.add(tag);headers.append(row)
        elif label=='LEAP SECONDS':leaps=int(row[:6])
    if not {'GPSA','GPSB','GLUT','GLGP'}<=tags or leaps!=offset:raise ValueError('Incomplete GLONASS/channel models or inconsistent leap seconds')
    return headers,time_offsets


def convert(broadcast,models,start,offset,duration):
    if type(duration) is not int or not 90<=duration<=180:raise ValueError('GLONASS duration must be 90-180 seconds')
    rows=broadcast.splitlines();version=float(rows[0][:9])
    if not 3<=version<4:raise ValueError('GLONASS requires RINEX3')
    end=next((i for i,r in enumerate(rows) if r[60:].strip()=='END OF HEADER'),None)
    if end is None:raise ValueError('Missing navigation header')
    headers,time_offsets=model_parameters(models,start,offset)
    chosen={};latest_healthy=None;finish=start+timedelta(seconds=duration)
    for i,row in enumerate(rows[end+1:],end+1):
        if not re.match(r'^R\d{2} \d{4} ',row):continue
        length=5 if version>=3.05 else 4;block=rows[i:i+length]
        if len(block)!=length or any(not r.startswith('    ') for r in block[1:]):raise ValueError('Truncated GLONASS record')
        slot=int(row[1:3]);toc=datetime(*map(int,row[4:23].split()),tzinfo=UTC)
        values=[float(row[23+j*19:42+j*19].replace('D','E')) for j in range(3)]
        data=[[float(r[4+j*19:23+j*19].replace('D','E')) if r[4+j*19:23+j*19].strip() else None for j in range(4)] for r in block[1:]]
        if any(v is None or not math.isfinite(v) for v in values+[v for r in data[:3] for v in r]):raise ValueError('Invalid GLONASS state vector')
        channel=data[1][3]
        if not channel.is_integer() or not -7<=channel<=6:raise ValueError('Unsupported GLONASS FDMA frequency')
        if not 20000<math.sqrt(sum(r[0]**2 for r in data[:3]))<30000 or any(abs(r[1])>5 or abs(r[2])>1e-4 for r in data[:3]):raise ValueError('Invalid GLONASS position/velocity/acceleration')
        if toc.minute%15 or toc.second:raise ValueError('GLONASS native generator requires quarter-hour reference epochs')
        healthy=data[0][3]==0
        if length==5 and data[3][3] is not None:
            flags=data[3][3]
            if not math.isfinite(flags) or not flags.is_integer() or not 0<=flags<8:raise ValueError('Invalid GLONASS health flags')
            healthy=healthy and not int(flags)&4 and not (int(flags)&2 and not int(flags)&1)
        if 1<=slot<=24 and healthy and (latest_healthy is None or toc>latest_healthy):latest_healthy=toc
        if not 1<=slot<=24 or not healthy or any(abs((t-toc).total_seconds())>=1800 for t in (start,finish)):continue
        if channel!=RECEIVER_FREQUENCIES[slot]:raise ValueError('Current FDMA assignment differs from independent receiver table')
        if slot not in chosen or toc>chosen[slot][0]:chosen[slot]=(toc,block[:4],int(channel))
    if len(chosen)<4:raise ValueError(f'Fewer than four healthy GLONASS records cover the complete scenario: usable={len(chosen)}, latestHealthyReferenceUTC={latest_healthy.isoformat() if latest_healthy else None}, requiredThroughUTC={finish.isoformat()}')
    # shortcut: pinned native parser reads four state-vector lines; retain 3.05 health checks above until it supports all extension fields.
    text=header_line('     3.04           N: GNSS NAV DATA    R: GLO','RINEX VERSION / TYPE')+'\n'.join(headers)+'\n'+header_line(f'{offset:6d}','LEAP SECONDS')+header_line('','END OF HEADER')
    text+=''.join('\n'.join(chosen[p][1])+'\n' for p in sorted(chosen))
    return text,{'healthyCoveredSlots':sorted(chosen),'frequencyChannels':{str(p):chosen[p][2] for p in chosen},'coverageCheckedThroughUTC':finish.isoformat(),'epochTimeScale':'UTC',**time_offsets}


def configuration(settings,navigation,iq,start):
    config,ideal,emitted,preview=bds_configuration(settings,navigation,iq,start)
    config['description']='GLONASS G1 current-data offline prototype'
    config['output'].update(sampleFreq=10.0,centerFreq=1602.0)
    for entry in config['output']['systemSelect']:
        entry['enable']=entry['system']=='GLONASS'
        if entry['system']=='GLONASS':entry['signal']='G1'
    preview['estimatedFileMB']=int(settings['duration']*RATE*2/1e6)
    return config,ideal,emitted,preview


def receiver_config(iq,slots):
    if len(slots)<4 or len(set(slots))!=len(slots) or any(type(s) is not int or s not in RECEIVER_FREQUENCIES for s in slots):raise ValueError('Invalid GLONASS slots')
    config=configparser.ConfigParser();config.optionxform=str;config.read(ROOT/'gnss-sdr.conf')
    values={k.replace('_1C','_1G'):v.replace('GPS_L1_CA','GLONASS_L1_CA') for k,v in config['GNSS-SDR'].items() if not re.fullmatch(r'Channel\d+\.satellite',k)}
    values.update({'GNSS-SDR.internal_fs_sps':str(RATE),'SignalSource.sampling_frequency':str(RATE),'SignalSource.filename':str(iq),'Channels_1G.count':str(len(slots)),'Channels.in_acquisition':str(min(8,len(slots))),'Channel.signal':'1G','PVT.elevation_mask':'5','PVT.trop_model':'Saastamoinen',
                   'Tracking_1G.extend_correlation_symbols':'10','Tracking_1G.pll_bw_narrow_hz':'5','Tracking_1G.dll_bw_narrow_hz':'0.5'})
    values.update({f'Channel{i}.satellite':str(p) for i,p in enumerate(slots)});config['GNSS-SDR']=values;return config


def generated_slots(log):
    if 'GLONASS G1 with IF ' not in log:raise ValueError('Native GLONASS G1 signal is not enabled')
    section=log.split('GLONASS G1 with IF ',1)[1].split('Total channels:',1)[0]
    return [int(v)for v in re.findall(r'\|\s*(\d{2})\s*\|\s*[+-]?\d+\s*',section)]


def navigation_source(folder,start,offset,duration):
    folder=Path(folder);models=(folder/'source-models.rnx').read_text()
    if not -60<=(start-production(models)).total_seconds()<=10800:
        raise ValueError('GLONASS model publication is stale')
    errors=[]
    for name in ('source-broadcast.rnx','source-stream.rnx','source-models.rnx'):
        try:
            path=folder/name
            if name=='source-stream.rnx' and not path.exists():
                url=f'https://igs.bkg.bund.de/root_ftp/IGS/BRDC/{start.year}/{start.timetuple().tm_yday:03d}/BRDC00WRD_S_{start.year}{start.timetuple().tm_yday:03d}0000_01D_MN.rnx.gz'
                text,info=cached_fetch(url,1800,force=True)
                path.write_text(text)
                (folder/'source-stream-metadata.json').write_text(json.dumps(info,indent=2))
            candidate=path.read_text()
            if not -60<=(start-production(candidate)).total_seconds()<=1800:
                raise ValueError('GLONASS orbit publication is stale')
            nav,timing=convert(candidate,models,start,offset,duration)
            timing['selectedSourceFile']=name
            return nav,timing,candidate,models
        except (ValueError,OSError) as error:errors.append({'source':name,'error':str(error)})
    raise ValueError('No complete current GLONASS source: '+json.dumps(errors))


def prepare(settings,folder,execute,emit,planned=False):
    folder=Path(folder)
    sources=folder.with_name(folder.name+'-sources')
    emit('progress',message='正在获取最新格洛纳斯轨道与模型，检查完整时段')
    fetch_sources(sources)
    return run(settings,folder,sources,execute,emit,planned=planned)


def run(settings,folder,source_folder,execute,emit,planned=False):
    began=time.monotonic()
    source_folder=Path(source_folder);leaps=(source_folder/'source-iers.dat').read_text()
    duration=int(settings['duration']);lead=bds_lead_seconds(duration,previous_rate(STATE) if STATE.exists() else 1.2) if planned else 0
    checked=datetime.now(UTC);start=(checked+timedelta(seconds=lead)).replace(microsecond=0);offset=gps_utc_offset(leaps,start+timedelta(seconds=duration))
    if gps_utc_offset(leaps,start)!=offset:raise ValueError('Scenario crosses leap transition')
    nav,timing,candidate,models=navigation_source(source_folder,start,offset,duration)
    folder.parent.mkdir(parents=True,exist_ok=True)
    if shutil.disk_usage(folder.parent).free<duration*RATE*2+500000000:raise ValueError('Insufficient storage for GLONASS prototype')
    folder.mkdir(exist_ok=False)
    timing.update(mode='current-broadcast',signal='GLO-G1',startUTC=start.isoformat(),validatedAtUTC=checked.isoformat(),broadcastProducedAtUTC=production(candidate).isoformat(),modelProducedAtUTC=production(models).isoformat(),preparationLeadSeconds=lead,sourceFolder=str(source_folder.resolve()))
    (folder/'navigation.json').write_text(json.dumps(timing,indent=2));(folder/'broadcast-glonass.rnx').write_text(nav);(folder/'settings.json').write_text(json.dumps(settings,indent=2));(folder/'source-iers.dat').write_text(leaps)
    iq=folder/'signal.bin';config,ideal,emitted,preview=configuration(settings,folder/'broadcast-glonass.rnx',iq,start)
    (folder/'scenario.json').write_text(json.dumps(config,indent=2))
    for name,points in [('ideal.csv',ideal),('emitted.csv',emitted)]:np.savetxt(folder/name,points,delimiter=',',fmt=['%.1f','%.6f','%.6f','%.6f'])
    execute([str(ROOT/'.venv/bin/python'),str(ROOT/'build_bds_generator.py')],folder,'build.log',240)
    generator=json.loads((ROOT/'output/bds-build-current/provenance.json').read_text())
    emit('preview',preview=preview)
    execute([str(ROOT/'output/bds-build-current/IFdataGen'),'-c','scenario.json','-u','emitted.csv','-vo','-st'],folder,'visibility.log',30)
    slots=generated_slots((folder/'visibility.log').read_text())
    with (folder/'receiver.conf').open('w')as stream:receiver_config(iq,slots).write(stream)
    if iq.exists():raise ValueError('Visibility preflight unexpectedly wrote I/Q')
    environment=signal_environment(settings)
    execute(['/usr/bin/env','OMP_NUM_THREADS=4','OMP_WAIT_POLICY=PASSIVE','OMP_DYNAMIC=FALSE',str(ROOT/'output/bds-build-current/IFdataGen'),'-c','scenario.json','-u','emitted.csv','-mt','-Q',str(environment['profileIndex']),'-S',str(environment['seed'])],folder,'generator.log',duration*8)
    if iq.stat().st_size!=duration*RATE*2:raise ValueError('GLONASS sample length mismatch')
    if generated_slots((folder/'generator.log').read_text())!=slots:raise ValueError('Generated GLONASS channels differ from preflight')
    execute([str(RECEIVER),'--config_file='+str(folder/'receiver.conf'),'--log_dir=.'],folder,'receiver.log',duration*8)
    result=summarize((folder/'fix.nmea').read_text()if(folder/'fix.nmea').exists()else'',ideal,emitted,duration,start)
    with iq.open('rb')as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
    elapsed=time.monotonic()-began;record_duration(duration,elapsed,STATE);timing['preparationMillis']=round(elapsed*1000)
    (folder/'navigation.json').write_text(json.dumps(timing,indent=2))
    if planned and result['passed'] and (start-datetime.now(UTC)).total_seconds()<3:
        iq.unlink();raise ValueError('GLONASS preparation missed planned start; timing learned for next attempt')
    result.update(signal='GLO-G1',signalEnvironment=environment,mode='current-broadcast',navigation=timing,iqSHA256=digest,generator=generator,hardwareOutputAllowed=bool(planned and result['passed']),softwareReceiverVerified=result['passed'],rfEnabled=False,
                  sampleRate=RATE,frequencyHz=1602000000,durationSeconds=duration,
                  preparedFilesSHA256={name:hashlib.sha256((folder/name).read_bytes()).hexdigest() for name in ('scenario.json','broadcast-glonass.rnx','emitted.csv')},
                  limitations=['Native GNav omits RINEX3.05 group-delay/accuracy extensions.','Single-frequency channel uses GPS Klobuchar; receiver ionosphere correction disabled.','PZ-90/WGS-84 frame equivalence not independently calibrated.'])
    origin=np.asarray(ecef((preview['wgs84Latitude'],preview['wgs84Longitude'],preview['ellipsoidHeight'])));points=(emitted[:,1:]-origin)@enu_basis(preview['wgs84Latitude'],preview['wgs84Longitude']).T
    scenario={'id':folder.name,'phase':'prepared','startUTC':start.isoformat(),'durationSeconds':duration,'targetLatitude':preview['wgs84Latitude'],'targetLongitude':preview['wgs84Longitude'],'constellationId':3,'svids':slots,
              'points':[[float(emitted[i,0]),float(points[i,0]),float(points[i,1])]for i in range(0,len(emitted),10)]}
    (folder/'phone-scenario.json').write_text(json.dumps(scenario))
    (folder/'result.json').write_text(json.dumps(result,indent=2));emit('result',result=result);return result
