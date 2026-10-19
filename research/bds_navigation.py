"""Current BDS D1/D2 RINEX3 input checks; preserve all orbit reference epochs."""
from datetime import datetime, timedelta, timezone
import json
import math
import re
from live_nav import cached_fetch, gps_utc_offset, header_line, navigation_sources, production, publication, fetch, LEAPS_URL

UTC=timezone.utc
BDT_EPOCH=datetime(2006,1,1,tzinfo=UTC)


def convert(broadcast,models,start_utc,offset,duration):
    if type(duration) is not int or not 60<=duration<=600:raise ValueError('BDS duration must be 60–600 seconds')
    rows=broadcast.splitlines()
    if not rows or not 3<=float(rows[0][:9])<4:raise ValueError('BDS requires RINEX3')
    end=next((i for i,r in enumerate(rows) if r[60:].strip()=='END OF HEADER'),None)
    if end is None:raise ValueError('Missing navigation header')
    model_rows=[];alpha=set();beta=set();delta=None
    for row in models.splitlines():
        label=row[60:].strip()
        if label=='END OF HEADER':break
        tag=row[:4]
        if label=='IONOSPHERIC CORR' and tag in ('BDSA','BDSB'):
            values=[float(row[5+j*12:17+j*12].replace('D','E')) for j in range(4)]
            if not all(math.isfinite(v) for v in values):raise ValueError('Invalid BDS ionosphere coefficients')
            (alpha if tag=='BDSA' else beta).add(row[53:60].strip())
            model_rows.append(row)
        elif label=='TIME SYSTEM CORR' and tag=='BDUT':
            values=[float(row[a:b].replace('D','E')) for a,b in ((5,22),(22,38))]
            tow,week=int(row[38:45]),int(row[45:50])
            if not all(math.isfinite(v) for v in values) or not 0<=tow<604800 or week<0:raise ValueError('Invalid BDUT')
            if abs(((start_utc+timedelta(seconds=offset-14))-(BDT_EPOCH+timedelta(weeks=week,seconds=tow))).total_seconds())>14*86400:raise ValueError('Outdated BDUT reference epoch')
            delta=row;model_rows.append(row)
        elif label=='LEAP SECONDS':
            if int(row[:6])!=offset:raise ValueError('BDS navigation and IERS leap offset disagree')
    if not alpha or alpha!=beta or delta is None:raise ValueError('Incomplete BDSA/BDSB/BDUT models')
    start_bdt=start_utc+timedelta(seconds=offset-14)
    finish=start_bdt+timedelta(seconds=duration)
    chosen={}
    for i,row in enumerate(rows[end+1:],end+1):
        if not re.match(r'^C\d{2} \d{4} ',row):continue
        block=rows[i:i+8]
        if len(block)!=8 or any(not r.startswith('    ') for r in block[1:]):raise ValueError('Truncated BDS record')
        prn=int(row[1:3]);toc=datetime(*map(int,row[4:23].split()),tzinfo=UTC)
        clock=[float(row[23+j*19:42+j*19].replace('D','E')) for j in range(3)]
        values=[[float(r[4+j*19:23+j*19].replace('D','E')) if r[4+j*19:23+j*19].strip() else None for j in range(4)] for r in block[1:]]
        if not 1<=prn<=63 or any(values[a][b] is None for a,indices in enumerate(((0,1,2,3),)*4+((0,2),(0,1,2,3),(0,1))) for b in indices) or not all(math.isfinite(v) for v in clock+[v for a in values for v in a if v is not None]):raise ValueError('Invalid BDS orbit fields')
        aode,aodc=values[0][0],values[6][1];week,tow=values[4][2],values[2][0]
        if not week.is_integer() or week<0 or not 0<=tow<604800 or not aode.is_integer() or not 0<=aode<=31 or not aodc.is_integer() or not 0<=aodc<=31:raise ValueError('Invalid BDS week/TOE/AODE/AODC')
        if not 0<=values[1][1]<1 or not 4000<=values[1][3]<=7000:raise ValueError('Invalid BDS eccentricity or semi-major axis')
        toe=BDT_EPOCH+timedelta(weeks=week,seconds=tow)
        # Hourly updates do not expire TOC; bound both references to the existing two-hour orbit window.
        if values[5][1]!=0 or any(abs((t-toc).total_seconds())>7200 or abs((t-toe).total_seconds())>7200 for t in (start_bdt,finish)):continue
        if prn not in chosen or toc>chosen[prn][0]:chosen[prn]=(toc,block)
    if len(chosen)<4:raise ValueError('Fewer than four healthy BDS records cover the complete scenario')
    text=header_line('     3.05           N: GNSS NAV DATA    C: BDS','RINEX VERSION / TYPE')
    text+='\n'.join(model_rows)+'\n'+header_line(f'{offset:6d}','LEAP SECONDS')+header_line('','END OF HEADER')
    text+=''.join('\n'.join(chosen[p][1])+'\n' for p in sorted(chosen))
    return text,{'healthyCoveredPRNs':sorted(chosen),'startBDT':start_bdt.isoformat().removesuffix('+00:00'),
                 'gpsMinusUTCSeconds':offset,'bdtMinusUTCSeconds':offset-14,
                 'coverageCheckedThroughUTC':(start_utc+timedelta(seconds=duration)).isoformat()}


def prepare(folder,duration,lead=0):
    from pathlib import Path
    now=datetime.now(UTC);failures=[]
    try:
        leaps=(Path(__file__).resolve().parent.parent/'mobile-controller/data/Leap_Second.dat').read_text()
        offset=gps_utc_offset(leaps,now+timedelta(seconds=lead+duration))
    except (OSError,ValueError):
        leaps,_=fetch(LEAPS_URL)
        offset=gps_utc_offset(leaps,now+timedelta(seconds=lead+duration))
    sources=navigation_sources(now,'bds')
    models=None;candidates=[]
    for url in sources:
        try:
            # Refresh orbits; a validated current model cache avoids downloading unchanged UTC data again.
            text,info=cached_fetch(url,10800,force=not bool(candidates))
            if any(r.startswith('BDSB') for r in text.splitlines()) and any(r.startswith('BDUT') for r in text.splitlines()) and (models is None or publication(text,info)>publication(*models)):models=(text,info)
            if -60<=(datetime.now(UTC)-publication(text,info)).total_seconds()<=1800:candidates.append((text,info))
            if models:
                covered=False
                for candidate in candidates:
                    try:convert(candidate[0],models[0],datetime.now(UTC)+timedelta(seconds=lead),offset,duration);covered=True;break
                    except ValueError:pass
                if covered:break
        except Exception as e:failures.append({'url':url,'error':str(e)})
    if not candidates or not models:raise ValueError('No fresh BDS orbit/models: '+json.dumps(failures))
    checked=datetime.now(UTC);start=(checked+timedelta(seconds=lead)).replace(microsecond=0)
    if not -60<=(checked-publication(*models)).total_seconds()<=10800:raise ValueError('BDS models expired during preparation')
    if gps_utc_offset(leaps,start)!=offset or gps_utc_offset(leaps,start+timedelta(seconds=duration))!=offset:raise ValueError('BDS scenario crosses leap transition')
    broadcast=None
    for candidate in candidates:
        try:
            if not -60<=(checked-publication(*candidate)).total_seconds()<=1800:raise ValueError('BDS orbit expired during preparation')
            text,coverage=convert(candidate[0],models[0],start,offset,duration)
            broadcast=candidate;break
        except ValueError as e:failures.append({'url':candidate[1]['url'],'error':str(e)})
    if broadcast is None:raise ValueError('No current BDS source covers the complete scenario: '+json.dumps(failures))
    (folder/'source-broadcast.rnx').write_text(broadcast[0]);(folder/'source-models.rnx').write_text(models[0]);(folder/'source-iers.dat').write_text(leaps)
    nav=folder/'broadcast-bds.rnx';nav.write_text(text)
    metadata={'mode':'current-broadcast','signal':'BDS-B1I','startUTC':start.isoformat(),
              'validatedAtUTC':checked.isoformat(),'broadcastProducedAtUTC':publication(*broadcast).isoformat(),
              'modelProducedAtUTC':publication(*models).isoformat(),'rejectedSources':failures,'sources':[broadcast[1],models[1]],**coverage}
    (folder/'navigation.json').write_text(json.dumps(metadata,indent=2))
    return nav,metadata
