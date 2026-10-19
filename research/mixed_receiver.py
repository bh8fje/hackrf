"""Offline two-band receiver and conservative mixed-solution evidence checks."""
import configparser
from pathlib import Path
import re
from crosscheck import gga

ROOT=Path(__file__).resolve().parent
from mixed_lab import RATE, FREQUENCY_HZ, CARRIERS_HZ
INTERNAL_RATE=RATE//3


def receiver_config(iq, gps_prns, bds_prns):
    for prns,limit in ((gps_prns,32),(bds_prns,63)):
        if len(prns)<4 or len(set(prns))!=len(prns) or any(type(p) is not int or not 1<=p<=limit for p in prns):
            raise ValueError('Need at least four distinct generator-confirmed PRNs per constellation')
    config=configparser.ConfigParser();config.optionxform=str;config.read(ROOT/'gnss-sdr.conf')
    values={k:v for k,v in config['GNSS-SDR'].items() if not re.match(r'(SignalSource|SignalConditioner|DataTypeAdapter|InputFilter|Resampler|Channel\d+|Channels_|Channel\.)',k)}
    values.update({'GNSS-SDR.internal_fs_sps':str(INTERNAL_RATE),'GNSS-SDR.num_sources':'2','Channels_1C.count':str(len(gps_prns)), 'Channels_B1.count':str(len(bds_prns)), 'Channels.in_acquisition':str(len(gps_prns)+len(bds_prns)), 'PVT.iono_model':'Broadcast','PVT.trop_model':'Saastamoinen','PVT.rinex_output_enabled':'true','PVT.rinex_version':'3','PVT.rtk_trace_level':'4','PVT.threshold_reject_gdop':'6'})
    for rf,offset in enumerate(CARRIERS_HZ[s]-FREQUENCY_HZ for s in ('GPS','BDS')):
        values.update({f'SignalSource{rf}.implementation':'File_Signal_Source',f'SignalSource{rf}.filename':str(iq),f'SignalSource{rf}.item_type':'ibyte',f'SignalSource{rf}.sampling_frequency':str(RATE),f'SignalSource{rf}.samples':'0',f'SignalSource{rf}.repeat':'false',f'SignalSource{rf}.enable_throttle_control':'false',f'SignalConditioner{rf}.implementation':'Signal_Conditioner',f'DataTypeAdapter{rf}.implementation':'Ibyte_To_Complex',f'InputFilter{rf}.implementation':'Freq_Xlating_Fir_Filter',f'InputFilter{rf}.input_item_type':'gr_complex',f'InputFilter{rf}.output_item_type':'gr_complex',f'InputFilter{rf}.taps_item_type':'float',f'InputFilter{rf}.filter_type':'lowpass',f'InputFilter{rf}.IF':str(offset),f'InputFilter{rf}.sampling_frequency':str(RATE),f'InputFilter{rf}.decimation_factor':'3',f'InputFilter{rf}.bw':'2200000',f'InputFilter{rf}.tw':'300000',f'Resampler{rf}.implementation':'Pass_Through'})
    for key,value in list(values.items()):
        if key.startswith(('Acquisition_1C.','Tracking_1C.','TelemetryDecoder_1C.')):values[key.replace('_1C.','_B1.')]=value.replace('GPS_L1_CA','BEIDOU_B1I')
    values.update({'Acquisition_1C.doppler_max':'10000','Acquisition_B1.doppler_max':'10000','Acquisition_B1.coherent_integration_time_ms':'2','Tracking_B1.extend_correlation_symbols':'20'})
    for index,prn in enumerate(list(gps_prns)+list(bds_prns)):
        rf=int(index>=len(gps_prns));values.update({f'Channel{index}.satellite':str(prn),f'Channel{index}.RF_channel_ID':str(rf),f'Channel{index}.signal':'B1' if rf else '1C'})
    config['GNSS-SDR']=values
    return config


def generated_prns(log):
    result={}
    for system,heading in (('gps','GPS L1CA with IF '),('bds','BeiDou B1I with IF ')):
        if heading not in log:raise ValueError('Missing generator channel evidence: '+system)
        section=log.split(heading,1)[1].split('Total channels:',1)[0]
        section=section.split('with IF ',1)[0] if 'with IF ' in section else section
        result[system]=[int(value) for value in re.findall(r'\|\s*(\d{2})\s*\|\s*[+-]?\d+\s*',section)]
        if not result[system]:raise ValueError('No generator PRNs: '+system)
    return result


def mixed_solution_evidence(status, valid_fixes=0):
    """Require both systems marked used at one RTKLIB solution epoch, not just acquisition."""
    epochs={}
    for line in status.splitlines():
        if not line.startswith('$SAT,'):continue
        fields=line.split(',')
        if len(fields)<10:continue
        try:
            if int(fields[9])!=1:continue
            epoch=(int(fields[1]),float(fields[2]));sat=fields[3]
            if not re.fullmatch(r'[GC]\d{2}',sat):continue
        except ValueError:continue
        epochs.setdefault(epoch,set()).add(sat)
    joint=[{'week':week,'tow':tow,'usedSatellites':sorted(sats)} for (week,tow),sats in sorted(epochs.items()) if any(s.startswith('G') for s in sats) and any(s.startswith('C') for s in sats)]
    return {'passed':bool(valid_fixes>0 and joint),'validFixes':valid_fixes,'jointSolutionEpochs':len(joint),'jointSolutionExamples':joint[:5], 'reason':'Both GPS and BDS must be marked used in the same solution epoch; acquisition or GPS-only PVT is insufficient'}


def mixed_nmea_solution_evidence(text):
    """Local RTKLIB writes GGA then per-system GSA from the same pvt_ssat[].vs."""
    epochs=[];current=None;fixes=[];date=''
    def finish():
        if current and current['gps'] and current['bds']:epochs.append(current.copy())
    for line in text.splitlines():
        line=line.strip()
        try:
            body,checksum=line[1:].split('*')
            total=0
            for char in body:total^=ord(char)
            if not line.startswith('$') or total!=int(checksum,16):continue
        except (ValueError,IndexError):continue
        fields=body.split(',');kind=fields[0]
        if kind.endswith('RMC'):
            finish();current=None
            date=fields[9] if len(fields)>9 else ''
        elif kind.endswith('GGA'):
            finish();current=None
            try:position=gga(line)
            except (ValueError,IndexError):continue
            if position is None:continue
            fixes.append({'utcDate':date,'utcTime':fields[1],'latitude':position[0],'longitude':position[1],'height':position[2]})
            current={'utcDate':date,'utcTime':fields[1],'gps':[],'bds':[]}
        elif kind in ('GPGSA','BDGSA','GBGSA') and current and len(fields)>=18 and fields[2] in ('2','3'):
            try:prns=[int(p) for p in fields[3:15] if p]
            except ValueError:continue
            if kind=='GPGSA':current['gps']=sorted(set(p for p in prns if 1<=p<=32))
            else:current['bds']=sorted(set(p for p in prns if 1<=p<=63))
    finish()
    return {'passed':bool(epochs),'validFixes':len(fixes),'jointSolutionEpochs':len(epochs),
            'fixStatistics':{'first':fixes[0] if fixes else None,'last':fixes[-1] if fixes else None,
                             'latitudeRange':[min(f['latitude'] for f in fixes),max(f['latitude'] for f in fixes)] if fixes else [],
                             'longitudeRange':[min(f['longitude'] for f in fixes),max(f['longitude'] for f in fixes)] if fixes else [],
                             'heightRange':[min(f['height'] for f in fixes),max(f['height'] for f in fixes)] if fixes else []},
            'jointSolutionExamples':epochs[:5], 'evidence':'same-GGA-cycle GPS/BDS GSA used-satellite lists',
            'reason':'Local RTKLIB outnmea_gsa selects ssat[].vs; both used lists must follow the same valid GGA before the next RMC/GGA'}
