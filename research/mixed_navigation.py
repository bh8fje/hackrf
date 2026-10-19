"""Validate both constellations at one UTC epoch before constructing mixed RINEX3."""
from datetime import datetime, timedelta, timezone
from pathlib import Path
import hashlib
import json
import live_nav
import bds_navigation


def combine(gps_broadcast, gps_models, bds_broadcast, bds_models, start, offset, duration):
    gps, gps_coverage = live_nav.convert(live_nav.broadcast3(gps_broadcast), gps_models,
                                       start + timedelta(seconds=offset), offset, duration)
    bds, bds_coverage = bds_navigation.convert(bds_broadcast, bds_models, start, offset, duration)
    gps3 = live_nav.broadcast3(gps)
    rows = gps.splitlines()
    models = []
    for tag, label in [('GPSA', 'ION ALPHA'), ('GPSB', 'ION BETA')]:
        row = next(r for r in rows if r[60:].strip() == label)
        values = [float(row[2+i*12:14+i*12].replace('D', 'E')) for i in range(4)]
        models.append(live_nav.header_line(tag+' '+''.join(f'{v:12.4E}' for v in values), 'IONOSPHERIC CORR'))
    row = next(r for r in rows if r[60:].strip() == 'DELTA-UTC: A0,A1,T,W')
    a, b = [float(row[x:y].replace('D', 'E')) for x, y in ((3, 22), (22, 41))]
    models.append(live_nav.header_line('GPUT '+f'{a:17.10E}{b:16.9E}{int(row[41:50]):7d}{int(row[50:59]):5d}', 'TIME SYSTEM CORR'))
    bds_rows = bds.splitlines()
    end = next(i for i, r in enumerate(bds_rows) if r[60:].strip() == 'END OF HEADER')
    models.extend(r+'\n' for r in bds_rows[1:end] if r[60:].strip() in ('IONOSPHERIC CORR', 'TIME SYSTEM CORR'))
    text = live_nav.header_line('     3.05           N: GNSS NAV DATA    M: MIXED', 'RINEX VERSION / TYPE')
    text += ''.join(models)+live_nav.header_line(f'{offset:6d}', 'LEAP SECONDS')+live_nav.header_line('', 'END OF HEADER')
    gps_rows = gps3.splitlines()
    gps_end = next(i for i, r in enumerate(gps_rows) if r[60:].strip() == 'END OF HEADER')
    text += '\n'.join(gps_rows[gps_end+1:])+'\n'+'\n'.join(bds_rows[end+1:])+'\n'
    # Recheck the exact file consumed by the native generator, including model serialization.
    live_nav.convert(text, text, start+timedelta(seconds=offset), offset, duration)
    bds_navigation.convert(text, text, start, offset, duration)
    return text, {'startUTC': start.isoformat(), 'gpsMinusUTCSeconds': offset,
                  'bdtMinusUTCSeconds': offset-14,
                  'coverageCheckedThroughUTC': (start+timedelta(seconds=duration)).isoformat(),
                  'gps': gps_coverage, 'bds': bds_coverage}


def prepare(folder, duration=90, lead_seconds=0):
    if type(lead_seconds) is not int or not 0 <= lead_seconds <= 30:
        raise ValueError('Invalid mixed preparation lead')
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    gps_folder, bds_folder = folder/'gps-navigation', folder/'bds-navigation'
    gps_folder.mkdir(exist_ok=True); bds_folder.mkdir(exist_ok=True)
    _, gps = live_nav.prepare(gps_folder, lead_seconds=0, duration=duration)
    _, bds = bds_navigation.prepare(bds_folder, duration, lead=0)
    start = max(datetime.fromisoformat(gps['startUTC']), datetime.fromisoformat(bds['startUTC']))
    if lead_seconds:
        start = max(start, datetime.now(timezone.utc).replace(microsecond=0)+timedelta(seconds=lead_seconds+1))
    leaps = (gps_folder/'source-iers.dat').read_text()
    offset = live_nav.gps_utc_offset(leaps, start)
    if live_nav.gps_utc_offset((bds_folder/'source-iers.dat').read_text(), start+timedelta(seconds=duration)) != offset:
        raise ValueError('Constellations disagree about leap seconds or cross a leap transition')
    text, coverage = combine(*( (gps_folder/name).read_text() for name in ('source-broadcast.rnx','source-models.rnx')),
                            *( (bds_folder/name).read_text() for name in ('source-broadcast.rnx','source-models.rnx')),
                            start, offset, duration)
    nav = folder/'broadcast-mixed.rnx'; nav.write_text(text)
    metadata = dict(mode='current-broadcast', **coverage, sources={'gps': gps, 'bds': bds},
                    navigationSHA256=hashlib.sha256(text.encode()).hexdigest(), hardwareOutputAllowed=False)
    (folder/'navigation.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2)+'\n')
    return nav, metadata
