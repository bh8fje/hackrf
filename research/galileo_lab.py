"""Current-data Galileo E1 generation and independent acceptance for bounded lab TX."""
from datetime import datetime,timedelta,timezone
from pathlib import Path
import configparser,hashlib,json,math,re,shutil,subprocess,time
import numpy as np
from bds_lab import configuration as bds_configuration,summarize,RECEIVER,ROOT
from live_nav import cached_fetch,production,publication,navigation_sources,gps_utc_offset,header_line
from preparation import bds_lead_seconds,previous_rate,record_duration
from crosscheck import ecef,gga
from walking import enu_basis,signal_environment

UTC=timezone.utc
GPS_EPOCH=datetime(1980,1,6,tzinfo=UTC)
RATE=16000000
STATE=ROOT/'output/preparation-galileo-cboc16-mac.json'


def convert(broadcast,models,start,offset,duration):
    if type(duration) is not int or not 90<=duration<=180:raise ValueError('Galileo duration must be 90-180 seconds')
    rows=broadcast.splitlines()
    if not rows or not 3<=float(rows[0][:9])<4:raise ValueError('Galileo requires RINEX3')
    end=next((i for i,r in enumerate(rows) if r[60:].strip()=='END OF HEADER'),None)
    if end is None:raise ValueError('Missing navigation header')
    headers=[];tags=set();leaps=None
    for row in models.splitlines():
        label=row[60:].strip();tag=row[:4].strip()
        if label=='END OF HEADER':break
        if label=='IONOSPHERIC CORR' and tag in ('GPSA','GPSB','GAL'):
            values=[float(row[5+j*12:17+j*12].replace('D','E')) for j in range(3 if tag=='GAL' else 4)]
            if not all(math.isfinite(v) for v in values):raise ValueError('Invalid ionosphere model')
            if tag=='GAL':
                flags=float(row[41:53].replace('D','E'))
                if not math.isfinite(flags) or not flags.is_integer() or not 0<=flags<32:raise ValueError('Invalid Galileo ionosphere region flags')
            tags.add(tag);headers.append(row)
        elif label=='TIME SYSTEM CORR' and tag in ('GAUT','GPUT'):
            a0=float(row[5:22].replace('D','E'));a1=float(row[22:38].replace('D','E'));tow=int(row[38:45]);week=int(row[45:50])
            if not all(math.isfinite(v) for v in (a0,a1)) or not 0<=tow<604800 or week<0:raise ValueError('Invalid UTC model')
            if abs((start+timedelta(seconds=offset)-(GPS_EPOCH+timedelta(weeks=week,seconds=tow))).total_seconds())>14*86400:raise ValueError('Outdated UTC reference')
            tags.add(tag);headers.append(row)
        elif label=='LEAP SECONDS':leaps=int(row[:6])
    if not {'GPSA','GPSB','GAL','GAUT'}<=tags or leaps!=offset:raise ValueError('Incomplete Galileo/GPS channel models or inconsistent leap seconds')
    begin=start+timedelta(seconds=offset);finish=begin+timedelta(seconds=duration);chosen={}
    for i,row in enumerate(rows[end+1:],end+1):
        if not re.match(r'^E\d{2} \d{4} ',row):continue
        block=rows[i:i+8]
        if len(block)!=8 or any(not r.startswith('    ') for r in block[1:]):raise ValueError('Truncated Galileo record')
        prn=int(row[1:3]);toc=datetime(*map(int,row[4:23].split()),tzinfo=UTC)
        clock=[float(row[23+j*19:42+j*19].replace('D','E')) for j in range(3)]
        values=[[float(r[4+j*19:23+j*19].replace('D','E')) if r[4+j*19:23+j*19].strip() else None for j in range(4)] for r in block[1:]]
        if not 1<=prn<=36:continue
        if any(values[a][b] is None for a,indices in enumerate(((0,1,2,3),)*4+((0,1,2),(0,1,2,3),())) for b in indices) or not all(math.isfinite(v) for v in clock+[v for r in values for v in r if v is not None]):raise ValueError('Invalid Galileo orbit fields')
        iod,flags,week,health=values[0][0],values[4][1],values[4][2],values[5][1]
        if not all(v.is_integer() for v in (iod,flags,week,health)) or not 0<=iod<1024 or not 0<=flags<1024 or week<1024 or not 0<=health<512:raise ValueError('Invalid Galileo identifiers')
        tow=values[2][0]
        if not 0<=tow<604800 or not 0<=values[1][1]<1 or not 5000<=values[1][3]<=6000:raise ValueError('Invalid Galileo orbit')
        toe=GPS_EPOCH+timedelta(weeks=week,seconds=tow)
        if not int(flags)&1 or health!=0 or any(abs((t-toc).total_seconds())>7200 or abs((t-toe).total_seconds())>7200 for t in (begin,finish)):continue
        if prn not in chosen or toc>chosen[prn][0]:chosen[prn]=(toc,block)
    if len(chosen)<4:raise ValueError('Fewer than four healthy Galileo I/NAV records cover the complete scenario')
    text=header_line('     3.05           N: GNSS NAV DATA    E: GAL','RINEX VERSION / TYPE')+'\n'.join(headers)+'\n'+header_line(f'{offset:6d}','LEAP SECONDS')+header_line('','END OF HEADER')
    text+=''.join('\n'.join(chosen[p][1])+'\n' for p in sorted(chosen))
    return text,{'healthyCoveredPRNs':sorted(chosen),'gpsMinusUTCSeconds':offset,'coverageCheckedThroughUTC':(start+timedelta(seconds=duration)).isoformat()}


def prepare(folder,duration,lead=0):
    now=datetime.now(UTC);leaps=(ROOT.parent/'mobile-controller/data/Leap_Second.dat').read_text();offset=gps_utc_offset(leaps,now+timedelta(seconds=duration));candidates=[];models=[];errors=[]
    for url in navigation_sources(now):
        try:
            text,info=cached_fetch(url,10800,force=True);age=(datetime.now(UTC)-publication(text,info)).total_seconds()
            if -60<=age<=1800:candidates.append((text,info))
            if -60<=age<=10800 and all(tag in text for tag in ('GPSA','GPSB','GAUT','GAL ')):models.append((text,info))
        except Exception as e:errors.append({'url':url,'error':str(e)})
        checked=datetime.now(UTC);start=(checked+timedelta(seconds=lead)).replace(microsecond=0)
        for broadcast,source in candidates:
            for model,model_source in sorted(models,key=lambda x:publication(*x),reverse=True):
                try:
                    if not -60<=(checked-publication(broadcast,source)).total_seconds()<=1800 or not -60<=(checked-publication(model,model_source)).total_seconds()<=10800:raise ValueError('Navigation expired during download')
                    text,coverage=convert(broadcast,model,start,offset,duration)
                    if gps_utc_offset(leaps,start)!=offset or gps_utc_offset(leaps,start+timedelta(seconds=duration))!=offset:raise ValueError('Scenario crosses leap transition')
                    for name,value in [('broadcast-galileo.rnx',text),('source-broadcast.rnx',broadcast),('source-models.rnx',model),('source-iers.dat',leaps)]:(folder/name).write_text(value)
                    timing={'mode':'current-broadcast','signal':'GAL-E1','startUTC':start.isoformat(),'validatedAtUTC':checked.isoformat(),'broadcastProducedAtUTC':publication(broadcast,source).isoformat(),'modelProducedAtUTC':publication(model,model_source).isoformat(),'sources':[source,model_source],**coverage}
                    (folder/'navigation.json').write_text(json.dumps(timing,indent=2));return folder/'broadcast-galileo.rnx',timing
                except ValueError as e:errors.append({'url':source['url'],'error':str(e)})
    raise ValueError('No complete current Galileo source: '+json.dumps(errors))


def configuration(settings,navigation,iq,start):
    config,ideal,emitted,preview=bds_configuration(settings,navigation,iq,start)
    config['description']='Galileo E1 current-data sampled trajectory'
    config['output']['centerFreq']=1575.42
    config['output']['sampleFreq']=RATE/1e6
    preview['estimatedFileMB']=int(settings['duration']*RATE*2/1e6)
    for entry in config['output']['systemSelect']:
        entry['enable']=entry['system']=='Galileo'
        if entry['system']=='Galileo':entry['signal']='E1'
    return config,ideal,emitted,preview


def generated_prns(log):
    section=log.split('Galileo E1 with IF ',1)[1].split('Total channels:',1)[0]
    return [int(v) for v in re.findall(r'\|\s*(\d{2})\s*\|\s*[+-]?\d+\s*',section)]


def receiver_config(iq,prns):
    if len(set(prns))!=len(prns) or len(prns)<4 or any(type(p) is not int or not 1<=p<=36 for p in prns):raise ValueError('Invalid Galileo PRNs')
    config=configparser.ConfigParser();config.optionxform=str;config.read(ROOT/'gnss-sdr.conf')
    values={k.replace('_1C','_1B'):v for k,v in config['GNSS-SDR'].items() if not re.fullmatch(r'Channel\d+\.satellite',k)}
    # Lower-rate sinBOC acquisition; full-rate pilot tracking and transmitted CBOC stay unchanged.
    values.update({'GNSS-SDR.internal_fs_sps':str(RATE),'GNSS-SDR.use_acquisition_resampler':'true','SignalSource.sampling_frequency':str(RATE),'SignalSource.filename':str(iq),'Channels_1B.count':str(len(prns)),'Channel.signal':'1B','Channels.in_acquisition':str(min(8,len(prns))),
                   'Acquisition_1B.implementation':'Galileo_E1_PCPS_Ambiguous_Acquisition','Acquisition_1B.doppler_max':'10000','Acquisition_1B.cboc':'false','Acquisition_1B.acquire_pilot':'true','Acquisition_1B.max_dwells':'5','Acquisition_1B.full_grid_search':'true',
                   'Acquisition_1B.doppler_step':'125','Acquisition_1B.make_two_steps':'true','Acquisition_1B.second_nbins':'8','Acquisition_1B.second_doppler_step':'25',
                   'Tracking_1B.implementation':'Galileo_E1_DLL_PLL_VEML_Tracking','Tracking_1B.track_pilot':'true',
                   'Tracking_1B.pll_bw_hz':'15.0','Tracking_1B.dll_bw_hz':'0.75','Tracking_1B.pll_bw_narrow_hz':'5.0','Tracking_1B.dll_bw_narrow_hz':'0.2',
                   'Tracking_1B.extend_correlation_symbols':'10','Tracking_1B.enable_fll_pull_in':'true','Tracking_1B.early_late_space_chips':'0.25','Tracking_1B.early_late_space_narrow_chips':'0.15',
                   'Tracking_1B.very_early_late_space_chips':'0.5','Tracking_1B.very_early_late_space_narrow_chips':'0.5',
                   'TelemetryDecoder_1B.implementation':'Galileo_E1B_Telemetry_Decoder','PVT.iono_model':'Broadcast','PVT.trop_model':'Saastamoinen'})
    values.update({f'Channel{i}.satellite':str(p) for i,p in enumerate(prns)});config['GNSS-SDR']=values;return config


def decode(iq,folder,prns,ideal,emitted,duration,start,execute,emit=None):
    attempts=[]
    selected=None
    for milliseconds,name in ((40,'.'),(20,'tracking-20ms')):
        if milliseconds==20 and emit:emit('progress',fraction=.7,message='独立解码定位点不足，正在复用当前样本补试一次20 ms跟踪；不会重新下载或生成')
        target=folder/name
        target.mkdir(exist_ok=True)
        config=receiver_config(iq,prns)
        if milliseconds==20:config['GNSS-SDR']['Tracking_1B.extend_correlation_symbols']='5'
        with (target/'receiver.conf').open('w') as stream:config.write(stream)
        began=time.monotonic()
        execute([str(RECEIVER),'--config_file='+str(target/'receiver.conf'),'--log_dir='+str(target)],target,'receiver.log',int(duration)*8)
        payload=(target/'fix.nmea').read_bytes() if (target/'fix.nmea').exists() else b''
        nmea=payload.decode('utf-8')
        result=summarize(nmea,ideal,emitted,duration,start)
        (target/f'decode-{milliseconds}ms.json').write_text(json.dumps(result,indent=2)+'\n')
        attempts.append(dict(result,trackingIntegrationMilliseconds=milliseconds,directory=name,processingMillis=round((time.monotonic()-began)*1000),nmeaSHA256=hashlib.sha256(payload).hexdigest()))
        if selected is None or result['passed']:selected=(result,nmea,name)
        # Retry only insufficient observations; never retry an accuracy/date failure with enough fixes.
        if result['passed'] or result['validFixes']>=100:break
    result,nmea,name=selected
    result=dict(result,independentReceiverDirectory=name,independentReceiverAttempts=attempts)
    (folder/'decode-attempts.json').write_text(json.dumps(result,indent=2)+'\n')
    return result,nmea


def run(settings,folder,execute,emit,planned=False):
    environment=signal_environment(settings)
    state=STATE.with_name(STATE.stem+'-'+environment['profile']+STATE.suffix)
    folder.parent.mkdir(parents=True,exist_ok=True)
    if shutil.disk_usage(folder.parent).free<settings['duration']*RATE*2+500000000:raise ValueError('Insufficient storage for Galileo experiment')
    folder.mkdir(parents=True,exist_ok=False);(folder/'settings.json').write_text(json.dumps(settings,indent=2))
    emit('progress',fraction=.03,message='正在获取最新 Galileo 轨道、NeQuick 和 UTC 模型；校验完整时段')
    execute([str(ROOT/'.venv/bin/python'),str(ROOT/'build_bds_generator.py')],folder,'build.log',240)
    previous=state if state.exists() else STATE if STATE.exists() else ROOT/'output/preparation-galileo-cboc-mac.json'
    nav,timing=prepare(folder,int(settings['duration']),bds_lead_seconds(int(settings['duration']),previous_rate(previous) if previous.exists() else 2.4) if planned else 0);start=datetime.fromisoformat(timing['startUTC']);iq=folder/'signal.bin';began=time.monotonic()
    config,ideal,emitted,preview=configuration(settings,nav,iq,start)
    generator=json.loads((ROOT/'output/bds-build-current/provenance.json').read_text())
    for name,points in [('ideal.csv',ideal),('emitted.csv',emitted)]:np.savetxt(folder/name,points,delimiter=',',fmt=['%.1f','%.6f','%.6f','%.6f'])
    (folder/'scenario.json').write_text(json.dumps(config,indent=2));emit('preview',preview=preview)
    emit('progress',fraction=.15,message='正在生成 Galileo E1 CBOC 轨迹信号')
    execute(['/usr/bin/env','OMP_NUM_THREADS=4','OMP_WAIT_POLICY=PASSIVE','OMP_DYNAMIC=FALSE',str(ROOT/'output/bds-build-current/IFdataGen'),'-c','scenario.json','-u','emitted.csv','-mt','-Q',str(environment['profileIndex']),'-S',str(environment['seed'])],folder,'generator.log',int(settings['duration'])*8)
    if iq.stat().st_size!=settings['duration']*RATE*2:raise ValueError('Galileo sample length mismatch')
    prns=generated_prns((folder/'generator.log').read_text());print('Galileo channels:',prns,flush=True)
    emit('progress',fraction=.7,message='正在由独立接收机解码 Galileo E1')
    result,nmea=decode(iq,folder,prns,ideal,emitted,settings['duration'],start,execute,emit)
    with iq.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
    elapsed=time.monotonic()-began;record_duration(int(settings['duration']),elapsed,state);timing.update(preparationMillis=round(elapsed*1000),preparationProfile=environment['profile'])
    (folder/'navigation.json').write_text(json.dumps(timing,indent=2))
    if planned and result['passed'] and (start-datetime.now(UTC)).total_seconds()<3:raise ValueError('Galileo preparation missed planned start; regenerate')
    result.update(signal='GAL-E1',signalEnvironment=environment,mode='current-broadcast',navigation=timing,generator=generator,iqSHA256=digest,hardwareOutputAllowed=bool(planned and result['passed']),softwareReceiverVerified=result['passed'],rfEnabled=False,
                  sampleRate=RATE,frequencyHz=1575420000,durationSeconds=int(settings['duration']),
                  preparedFilesSHA256={name:hashlib.sha256((folder/name).read_bytes()).hexdigest() for name in ('scenario.json','broadcast-galileo.rnx','emitted.csv')},
                  waveform='CBOC(6,1,1/11)',limitations=['16 Msps limits represented bandwidth below the Galileo E1 24.552 MHz receiver reference bandwidth.','Channel ionosphere uses the current GPS Klobuchar model; not a calibrated Galileo NeQuick propagation model.'])
    origin=np.array(ecef((preview['wgs84Latitude'],preview['wgs84Longitude'],preview['ellipsoidHeight'])))
    fixes=[ecef(fix) for line in nmea.splitlines() if (fix:=gga(line)) is not None]
    decoded=(np.asarray(fixes)-origin)@enu_basis(preview['wgs84Latitude'],preview['wgs84Longitude']).T if fixes else np.empty((0,3))
    result.update(folder=str(folder),startUTC=timing['startUTC'] if planned else None,sceneSatellites=[f'E{p:02d}'for p in prns],
                  decodedPath=decoded[::max(1,len(decoded)//240),:2].tolist())
    points=(emitted[:,1:]-origin)@enu_basis(preview['wgs84Latitude'],preview['wgs84Longitude']).T
    scenario={'id':folder.name,'phase':'prepared','startUTC':timing['startUTC'],'durationSeconds':int(settings['duration']),
              'targetLatitude':preview['wgs84Latitude'],'targetLongitude':preview['wgs84Longitude'],'constellationId':6,'svids':prns,
              'points':[[float(emitted[i,0]),float(points[i,0]),float(points[i,1])] for i in range(0,len(emitted),10)]}
    (folder/'phone-scenario.json').write_text(json.dumps(scenario))
    (folder/'result.json').write_text(json.dumps(result,indent=2));emit('result',result=result);return result
