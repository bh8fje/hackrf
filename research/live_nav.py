"""Fetch recent official broadcast navigation without relabelling orbit epochs."""
from datetime import datetime, timedelta, timezone
import gzip
import hashlib
import io
import json
import math
import re
import urllib.request
import urllib.error
from urllib.parse import urlsplit
from pathlib import Path
import os
import subprocess
import tempfile
import time
from email.utils import parsedate_to_datetime
from navigation_download import download as chunk_download

UTC = timezone.utc
GPS_EPOCH = datetime(1980, 1, 6, tzinfo=UTC)
LEAPS_URL = 'https://hpiers.obspm.fr/iers/bul/bulc/Leap_Second.dat'
download_status=None


def fetch(url):
    began=time.monotonic()
    if url.startswith('https://data.gnss.ga.gov.au/api/rinexFiles/'):
        expected=urlsplit(url).path.rsplit('/',1)[1]
        if not re.fullmatch(r'[A-Z0-9]{9}_R_\d{11}_01H_MN\.rnx\.gz',expected):raise ValueError('Invalid GA hourly navigation request')
        for attempt in range(2):
            try:
                with urllib.request.urlopen(url,timeout=15) as response:raw_index=response.read(65537)
                break
            except urllib.error.HTTPError:
                raise
            except OSError:
                if attempt:raise
        if len(raw_index)>65536:raise ValueError('GA navigation index exceeds size limit')
        entries=json.loads(raw_index)
        if not isinstance(entries,list) or len(entries)!=1:raise ValueError('No unique current GA navigation file')
        entry=entries[0];target=entry.get('fileLocation','');parts=urlsplit(target)
        if (entry.get('metadataStatus')!='valid' or entry.get('fileType')!='nav' or entry.get('filePeriod')!='01H' or str(entry.get('rinexVersion'))!='3'
                or parts.scheme!='https' or parts.hostname!='geodesy-archive-permanent-storage-prod.s3.ap-southeast-2.amazonaws.com'
                or parts.username or parts.port not in (None,443) or parts.path.rsplit('/',1)[-1]!=expected):raise ValueError('Untrusted GA navigation index entry')
        published=datetime.fromisoformat(entry['createdAt'].replace('Z','+00:00'))
        if published.utcoffset()!=timedelta(0) or published>datetime.now(UTC)+timedelta(seconds=60):raise ValueError('Invalid GA publication time')
        epoch=datetime.strptime(expected[12:23],'%Y%j%H%M')
        if epoch.year<1980 or epoch.strftime('%Y%j%H%M')!=expected[12:23]:raise ValueError('Invalid GA hourly file date')
        public=f'https://ga-gnss-data-rinex-v1.s3.dualstack.ap-southeast-2.amazonaws.com/public/hourly/{epoch:%Y/%j/%H}/{expected}'
        try:text,source=fetch(public);resolved=public
        except OSError:text,source=fetch(target);resolved=parts._replace(query='',fragment='').geturl()
        source.update(url=url,provider='Geoscience Australia',publishedAtUTC=published.isoformat(),
                      resolvedURL=resolved,indexSHA256=hashlib.sha256(raw_index).hexdigest(),indexBytes=len(raw_index))
        source.update(downloadBytes=source['downloadBytes']+len(raw_index),downloadMillis=round((time.monotonic()-began)*1000))
        return text,source
    chunked=chunk_download(url,CACHE/'parts',download_status) if url.startswith('https://') else None
    transport={}
    if chunked is not None:raw,modified,transport=chunked
    for attempt in range(0 if chunked is not None else 2):
        try:
            with urllib.request.urlopen(url, timeout=15) as response:
                raw = response.read(10_000_001)
                modified = response.headers.get('Last-Modified')
                length=response.headers.get('Content-Length')
                if length is not None and len(raw)!=int(length) and len(raw)<=10_000_000:raise urllib.error.URLError('Incomplete navigation download')
            break
        except urllib.error.HTTPError:
            raise
        except OSError:
            if attempt:raise
    if len(raw) > 10_000_000:
        raise ValueError('Navigation download exceeds size limit')
    if raw[:2] == b'\x1f\x8b':
        with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
            data = stream.read(20_000_001)
    elif raw[:2]==b'\x1f\x9d':
        with tempfile.TemporaryFile() as compressed:
            compressed.write(raw);compressed.seek(0)
            with subprocess.Popen(['uncompress','-c'],stdin=compressed,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL) as decoder:
                data=decoder.stdout.read(20_000_001)
                if len(data)>20_000_000:decoder.kill()
                code=decoder.wait(timeout=15)
            if code and len(data)<=20_000_000:raise ValueError('Invalid Unix-compress navigation file')
    else:
        data = raw
    if len(data) > 20_000_000:
        raise ValueError('Expanded navigation exceeds size limit')
    return data.decode('ascii'), {'url': url, 'lastModified': modified,
                                 'downloadSHA256': hashlib.sha256(raw).hexdigest(),
                                 'downloadBytes':len(raw),'expandedBytes':len(data),'downloadMillis':round((time.monotonic()-began)*1000),**transport}


def gps_utc_offset(table, instant):
    match = re.search(r'File expires on (\d+ \w+ \d{4})', table)
    if not match:
        raise ValueError('Leap-second table has no expiration')
    expiration = datetime.strptime(match[1], '%d %B %Y').replace(tzinfo=UTC)
    if instant >= expiration:
        raise ValueError('Leap-second table has expired')
    tai = None
    for line in table.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        fields = line.split()
        day, month, year, value = map(int, fields[1:5])
        if datetime(year, month, day, tzinfo=UTC) <= instant:
            tai = value
    if tai is None or not 19 <= tai < 100:
        raise ValueError('Unsupported GPS/UTC offset')
    return tai - 19


def header_line(body, label):
    return body.ljust(60) + label + '\n'


def model_headers(text):
    rows=[]
    for line in text.splitlines():
        label=line[60:].strip()
        if label=='IONOSPHERIC CORR' and line[:4] in ('GPSA','GPSB'):
            values=[float(line[5+j*12:17+j*12].replace('D','E')) for j in range(4)]
            rows.append(header_line('  '+''.join(f'{v:12.4E}' for v in values),'ION ALPHA' if line[:4]=='GPSA' else 'ION BETA'))
        elif label=='TIME SYSTEM CORR' and line[:4]=='GPUT':
            a,b=[float(line[x:y].replace('D','E')) for x,y in ((5,22),(22,38))]
            rows.append(header_line('   '+f'{a:19.11E}{b:19.11E}{int(line[38:45]):9d}{int(line[45:50]):9d}','DELTA-UTC: A0,A1,T,W'))
        else:rows.append(line+'\n')
    return ''.join(rows)


def convert(broadcast, models, start_gps, offset, duration=0):
    if type(duration) is not int or not 0 <= duration <= 600:
        raise ValueError("Invalid coverage duration")
    models = model_headers(models)
    end_gps = start_gps + timedelta(seconds=duration)
    lines = broadcast.splitlines()
    if not lines or not 3 <= float(lines[0][:9]) < 4:
        raise ValueError('Expected RINEX 3 navigation data')
    end = next(i for i, line in enumerate(lines) if line[60:].startswith('END OF HEADER'))
    headers = {line[60:].strip(): line for line in models.splitlines()
               if len(line) >= 60}
    if not models.splitlines() or 'RINEX VERSION / TYPE' not in models.splitlines()[0]:
        raise ValueError('Model source returned no RINEX navigation file')
    labels = ('ION ALPHA', 'ION BETA', 'DELTA-UTC: A0,A1,T,W')
    if any(label not in headers for label in labels):
        raise ValueError('Current ionosphere/UTC model header is incomplete')
    numbers = []
    for label in labels[:2]:
        numbers.extend(float(headers[label][2+j*12:14+j*12].replace('D', 'E')) for j in range(4))
    delta = headers[labels[2]]
    numbers.extend(float(delta[a:b].replace('D', 'E')) for a, b in ((3, 22), (22, 41)))
    tot, week = int(delta[41:50]), int(delta[50:59])
    if (not all(math.isfinite(v) for v in numbers) or not 0 <= tot < 604800 or tot % 4096
            or week < 0 or abs((start_gps - (GPS_EPOCH + timedelta(weeks=week, seconds=tot))).total_seconds()) > 1209600):
        raise ValueError('Invalid or outdated broadcast UTC model')
    if 'LEAP SECONDS' in headers and int(headers['LEAP SECONDS'][:6]) != offset:
        raise ValueError('Broadcast and IERS leap-second offsets disagree')
    records = []
    for i in range(end + 1, len(lines)):
        line = lines[i]
        if not re.match(r'^G\d{2} \d{4} ', line):
            continue
        block = lines[i:i+8]
        if len(block) != 8 or any(not row.startswith('    ') for row in block[1:]):
            raise ValueError('Truncated GPS LNAV record')
        prn = int(line[1:3])
        epoch = datetime(*map(int, line[4:23].split()), tzinfo=UTC)
        clock = [float(line[23+j*19:42+j*19].replace('D', 'E')) for j in range(3)]
        values = [[float(row[4+j*19:4+(j+1)*19].replace('D', 'E'))
                   for j in range(4) if row[4+j*19:4+(j+1)*19].strip()] for row in block[1:]]
        if not 1 <= prn <= 32 or any(not math.isfinite(v) for v in clock) or any(not math.isfinite(v) for row in values for v in row):
            raise ValueError('Invalid GPS navigation values')
        if any(len(row) != 4 for row in values[:6]) or len(values[6]) < 1:
            raise ValueError('Incomplete GPS orbit parameters')
        toe = GPS_EPOCH + timedelta(weeks=int(values[4][2]), seconds=values[2][0])
        healthy = values[5][1] == 0
        if duration:
            iode, iodc = values[0][0], values[5][3]
            if not (iode.is_integer() and iodc.is_integer() and 0 <= iode <= 255 and 0 <= iodc <= 1023 and int(iodc) & 255 == int(iode)):
                continue
            fit = values[6][1] if len(values[6]) > 1 else 4
            half = min(fit if fit > 0 else 4, 4) * 1800
            # The generator selects TOC at startup; full-run validity follows the TOE fit interval.
            if not healthy or abs((start_gps-epoch).total_seconds()) >= 3600 or any(abs((t-toe).total_seconds()) > half for t in (start_gps,end_gps)):
                continue
        prefix = f'{prn:2d} {epoch.year % 100:02d} {epoch.month:2d} {epoch.day:2d} {epoch.hour:2d} {epoch.minute:2d} {epoch.second:4.1f}'
        output = prefix + line[23:80] + '\n' + ''.join('   ' + row[4:] + '\n' for row in block[1:])
        records.append((epoch, prn, toe, healthy, output))
    # Preserve actual TOC/TOE values, retaining nearby records in chronological order.
    records = sorted(r for r in records if abs((start_gps - r[0]).total_seconds()) <= 10800)
    if duration and records:
        newest = records[-1][0]
        latest = {r[1]:r for r in records if (newest-r[0]).total_seconds() <= 3600}
        records = sorted(latest.values(), key=lambda r:(r[0],r[1]))
    fresh = {r[1] for r in records if r[3] and abs((start_gps - r[0]).total_seconds()) < 3600
             and abs((start_gps - r[2]).total_seconds()) <= 7200}
    if len(fresh) < 4:
        raise ValueError('Fewer than four healthy recent GPS ephemerides')
    if not duration and (start_gps < records[0][0] or (start_gps - records[-1][0]).total_seconds() > 3600):
        raise ValueError('Current start time is outside the generator navigation coverage')
    text = header_line('     2.11           N: GPS NAV DATA', 'RINEX VERSION / TYPE')
    text += ''.join(headers[label].ljust(80) + '\n' for label in labels)
    text += header_line(f'{offset:6d}', 'LEAP SECONDS')
    text += header_line('', 'END OF HEADER')
    text += ''.join(r[4] for r in records)
    return text, {'healthyRecentPRNs': sorted(fresh), 'gpsRecordCount': len(records),
                  'minimumTocGPS': records[0][0].isoformat(), 'maximumTocGPS': records[-1][0].isoformat()}


def production(text):
    rows = text.splitlines()
    row = rows[1] if len(rows)>1 else ''
    for pattern,fmt in ((r'(\d{8}) (\d{6}) UTC','%Y%m%d%H%M%S'),
                        (r'(\d{8}) (\d{2}:\d{2}:\d{2})UTC','%Y%m%d%H:%M:%S'),
                        (r'(\d{2}-[A-Za-z]{3}-\d{2}) (\d{2}:\d{2})','%d-%b-%y%H:%M')):
        match=re.search(pattern,row)
        if match:return datetime.strptime(''.join(match.groups()),fmt).replace(tzinfo=UTC)
    raise ValueError('Navigation production timestamp is missing')


def publication(text,source):
    if source.get('provider')=='Geoscience Australia' and source.get('url','').startswith('https://data.gnss.ga.gov.au/api/rinexFiles/'):
        instant=datetime.fromisoformat(source['publishedAtUTC'])
        if instant.utcoffset()!=timedelta(0):raise ValueError('Invalid navigation publication zone')
        return instant
    return production(text)


def broadcast3(text):
    rows=text.splitlines()
    version=float(rows[0][:9])
    if 3<=version<4:return text
    if not 2<=version<3 or rows[0][20:21]!='N':raise ValueError('Expected GPS RINEX2 or RINEX3')
    end=next(i for i,row in enumerate(rows) if row[60:].startswith('END OF HEADER'))
    output=[header_line('     3.05           N: GNSS NAV DATA    G: GPS','RINEX VERSION / TYPE')]+[r+'\n' for r in rows[1:end+1]]
    i=end+1
    while i<len(rows):
        if not rows[i].strip():i+=1;continue
        if i+7>=len(rows):raise ValueError('Truncated RINEX2 orbit')
        fields=rows[i][:22].split()
        if len(fields)!=7:raise ValueError('Invalid RINEX2 epoch')
        prn,year,month,day,hour,minute=map(int,fields[:6]);second=float(fields[6])
        if not second.is_integer():raise ValueError('Fractional reference seconds unsupported')
        year+=2000 if year<80 else 1900
        output.append(f'G{prn:02d} {year:04d} {month:02d} {day:02d} {hour:02d} {minute:02d} {int(second):02d}'+rows[i][22:]+'\n')
        for row in rows[i+1:i+8]:
            if not row.startswith('   '):raise ValueError('Incomplete RINEX2 orbit')
            output.append(' '+row+'\n')
        i+=8
    return ''.join(output)


CACHE = Path(__file__).resolve().parent/'output/navigation-cache'


def cached_fetch(url, maximum_age, force=False, cache_only=False):
    key=hashlib.sha256(url.encode('ascii')).hexdigest()
    file=CACHE/(key+'.json')
    if not force:
        try:
            if file.stat().st_size>40_000_000:raise ValueError('Cache exceeds limit')
            stored=json.loads(file.read_text())
            text=stored['text'];now=datetime.now(UTC)
            age=(now-publication(text,stored)).total_seconds()
            elapsed=(now-datetime.fromisoformat(stored['fetchedUTC'])).total_seconds()
            if (stored['url']==url and -60<=age<=maximum_age and 0<=elapsed<=900
                    and hashlib.sha256(text.encode('ascii')).hexdigest()==stored['textSHA256']):
                return text,{k:v for k,v in stored.items() if k not in ('text','textSHA256')}|{'cached':True,'transferredBytes':0,'resumedBytes':stored.get('downloadBytes',0)}
        except (OSError,ValueError,KeyError,IndexError,TypeError):pass
    if cache_only:return None
    text,source=fetch(url)
    if not -60<=(datetime.now(UTC)-publication(text,source)).total_seconds()<=maximum_age:
        raise ValueError('Navigation publication is stale')
    source=source|{'cached':False,'fetchedUTC':datetime.now(UTC).isoformat()}
    CACHE.mkdir(parents=True,exist_ok=True)
    temporary=file.with_suffix('.pending')
    temporary.write_text(json.dumps(source|{'text':text,'textSHA256':hashlib.sha256(text.encode('ascii')).hexdigest()}))
    os.chmod(temporary,0o600);temporary.replace(file)
    return text,source


def has_models(text):
    headers={line[60:].strip() for line in model_headers(text).splitlines() if len(line)>=60}
    return {'ION ALPHA','ION BETA','DELTA-UTC: A0,A1,T,W'}<=headers


def navigation_sources(now,signal=None):
    prior=now-timedelta(days=1)
    hour=now-timedelta(hours=1)
    older_hour=now-timedelta(hours=2)
    values={'year':now.year,'doy':now.strftime('%j'),'yy':now.strftime('%y'),'previousYear':prior.year,'previousDoy':prior.strftime('%j'),
            'previousHourYear':hour.year,'previousHourDoy':hour.strftime('%j'),'previousHour':hour.strftime('%H'),
            'olderHourYear':older_hour.year,'olderHourDoy':older_hour.strftime('%j'),'olderHour':older_hour.strftime('%H')}
    policy=json.loads((Path(__file__).resolve().parent.parent/'shared/navigation-sources.json').read_text())
    templates=policy.get(signal,[])+policy['broadcast'] if signal in ('gps','bds') else policy['broadcast']
    return list(dict.fromkeys(template.format(**values) for template in templates))


def prepare(folder, lead_seconds=90, duration=60, domestic_only=False, force=False):
    now=datetime.now(UTC);day=now.strftime('%j')
    leap_file=Path(__file__).resolve().parent.parent/'mobile-controller/data/Leap_Second.dat'
    try:
        leaps=leap_file.read_text();gps_utc_offset(leaps,now+timedelta(seconds=810))
        leap_source={'url':LEAPS_URL,'cached':True,'downloadSHA256':hashlib.sha256(leaps.encode()).hexdigest()}
    except (OSError,ValueError):
        leaps,leap_source=fetch(LEAPS_URL)
    local=None;broadcast=None;failures=[]
    if broadcast is None:
        for url in navigation_sources(now,'gps'):
            try:
                candidate=cached_fetch(url,10800,force=force)
                if has_models(candidate[0]) and (local is None or publication(*candidate)>publication(*local)):local=candidate
                age=(datetime.now(UTC)-publication(*candidate)).total_seconds()
                if age>1800 and candidate[1].get('cached'):
                    candidate=cached_fetch(url,10800,force=True);age=(datetime.now(UTC)-publication(*candidate)).total_seconds()
                    if has_models(candidate[0]):local=candidate
                if not -60<=age<=1800:raise ValueError(f'publication age {age:.0f}s exceeds 1800s')
                model=candidate if has_models(candidate[0]) else local
                if model is None:
                    for model_url in navigation_sources(now,'gps'):
                        try:
                            possible=cached_fetch(model_url,10800,force=force)
                            if has_models(possible[0]) and -60 <= (datetime.now(UTC)-publication(*possible)).total_seconds() <= 10800:
                                model=possible; local=model; break
                        except (OSError,ValueError):continue
                if model is None:raise ValueError('No complete ionosphere/UTC model')
                offset=gps_utc_offset(leaps,now)
                convert(broadcast3(candidate[0]),model[0],datetime.now(UTC)+timedelta(seconds=lead_seconds+offset),offset,duration)
                broadcast=candidate;break
            except Exception as error:failures.append({'url':url,'error':str(error)})
    (folder/'navigation-source-audit.json').write_text(json.dumps(failures,ensure_ascii=False,indent=2))
    if broadcast is None:
        raise ValueError('No navigation source has fresh complete coverage: '+json.dumps(failures,ensure_ascii=False))
    models=local if local is not None and has_models(local[0]) else None
    if models is None:raise ValueError('No complete, recent UTC/ionosphere model available')
    fetched=datetime.now(UTC);produced=publication(*broadcast);age=(fetched-produced).total_seconds()
    if not -60<=age<=1800 or not -60<=(fetched-publication(*models)).total_seconds()<=10800:raise ValueError('Navigation source expired during download')
    start=(fetched+timedelta(seconds=lead_seconds)).replace(microsecond=0)
    offset=gps_utc_offset(leaps,start)
    if gps_utc_offset(leaps,start+timedelta(seconds=duration))!=offset:raise ValueError('Scenario crosses leap-second transition')
    gps=start+timedelta(seconds=offset)
    try:navigation,coverage=convert(broadcast3(broadcast[0]),models[0],gps,offset,duration)
    except ValueError:
        if not force and (broadcast[1].get('cached') or models[1].get('cached')):
            return prepare(folder,lead_seconds,duration,domestic_only,force=True)
        raise
    for name,text in (('source-broadcast.rnx',broadcast[0]),('source-models.rnx',models[0]),('source-iers.dat',leaps)):(folder/name).write_text(text)
    nav=folder/'broadcast-gps.rnx';nav.write_text(navigation)
    metadata={'mode':'current-broadcast','startUTC':start.isoformat(),'startGPS':gps.strftime('%Y/%m/%d,%H:%M:%S'),
              'gpsMinusUTCSeconds':offset,'downloadedAtUTC':broadcast[1]['fetchedUTC'],'validatedAtUTC':fetched.isoformat(),
              'broadcastProducedAtUTC':produced.isoformat(),'modelProducedAtUTC':publication(*models).isoformat(),
              'coverageCheckedThroughUTC':(start+timedelta(seconds=duration)).isoformat(),'preparationLeadSeconds':lead_seconds,
              'cacheRefreshSeconds':900,'broadcastAgeSecondsAtDownload':age,'sources':[broadcast[1],models[1],leap_source],
              'navigationSHA256':hashlib.sha256(navigation.encode()).hexdigest(),**coverage}
    (folder/'navigation.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
    return nav,metadata


def launch_delay(metadata, now=None):
    if metadata.get('mode') != 'current-broadcast':
        raise ValueError('历史场景不能用于当前手机测试，请重新生成最新星历场景')
    now = now or datetime.now(UTC)
    start = datetime.fromisoformat(metadata['startUTC'])
    delay = (start - now).total_seconds()
    if not -1 <= delay <= 240:
        raise ValueError('场景发送时刻已过期或不合理，请重新生成并及时发送')
    produced = datetime.fromisoformat(metadata['broadcastProducedAtUTC'])
    if not -60 <= (now - produced).total_seconds() <= 2100:
        raise ValueError('广播星历来源已过期，请重新获取')
    return max(0, delay)
