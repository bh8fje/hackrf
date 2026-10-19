"""Summarize exported phone observations against one recorded TX interval."""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import statistics
import numpy as np

from crosscheck import gga, ecef, NMEA_GGA_PREFIXES
from dynamic import elapsed_seconds
from walking import enu_basis


def read_rows(payload):
    complete, separator, tail = payload.rpartition(b'\n')
    if not separator:
        raise ValueError('No complete phone record lines')
    return [json.loads(line) for line in complete.decode().splitlines()], len(tail)


def track_metrics(observations, motion, latitude, longitude):
    if not observations:
        return {'samples': 0, 'horizontalPathObserved': False, 'position3DObserved': False}
    points = np.asarray(observations)
    if not np.isfinite(points).all() or np.any(np.diff(points[:, 0]) < 0):
        raise ValueError('Invalid or decreasing phone trajectory times')
    if points[0, 0] < motion[0, 0] or points[-1, 0] > motion[-1, 0]:
        raise ValueError('Phone trajectory outside simulated coverage')
    expected = np.column_stack([np.interp(points[:, 0], motion[:, 0], motion[:, i]) for i in (1, 2, 3)])
    errors = (points[:, 1:] - expected) @ enu_basis(latitude, longitude).T
    horizontal = np.linalg.norm(errors[:, :2], axis=1)
    epochs=len(np.unique(points[:,0]))
    return {'samples': len(points), 'uniqueEpochs': epochs, 'maximumAllowedErrorM': 30, 'firstElapsedSeconds': float(points[0, 0]),
            'lastElapsedSeconds': float(points[-1, 0]),
            'medianHorizontalErrorM': float(np.median(horizontal)),
            'maximumHorizontalErrorM': float(max(horizontal)),
            'horizontalErrorP95M': float(np.percentile(horizontal,95)),
            'verticalAbsErrorP95M': float(np.percentile(np.abs(errors[:,2]),95)),
            'error3DP95M': float(np.percentile(np.linalg.norm(errors,axis=1),95)),
            'medianSignedVerticalErrorM': float(np.median(errors[:, 2])),
            'minimumSignedVerticalErrorM': float(min(errors[:, 2])),
            'maximumSignedVerticalErrorM': float(max(errors[:, 2])),
            'median3DErrorM': float(np.median(np.linalg.norm(errors, axis=1))),
            'maximum3DErrorM': float(max(np.linalg.norm(errors, axis=1))),
            'measuredEndpointDistanceM': float(np.linalg.norm(points[-1, 1:] - points[0, 1:])),
            'horizontalPathObserved': bool(epochs >= 10 and points[-1, 0] - points[0, 0] >= 10 and max(horizontal) <= 30),
            'position3DObserved': bool(epochs >= 10 and points[-1, 0] - points[0, 0] >= 10 and max(np.linalg.norm(errors,axis=1)) <= 30)}


def scenario_matches(rows,start_ms,end_ms,scenario):
    return sum(1 for r in rows if r['type']=='scenario_match' and start_ms<=r['receivedAtMs']<end_ms
               and r.get('scenarioId')==scenario['id'] and r.get('constellationId')==scenario.get('constellationId',1)
               and r.get('matched') is True and r.get('consecutiveFixes',0)>=3 and r.get('matchingSvids',0)>=4
               and isinstance(r.get('horizontalErrorM'),(int,float)) and math.isfinite(r['horizontalErrorM']) and 0<=r['horizontalErrorM']<=30)


def joint_usage(satellites):
    epochs=[]
    for row in satellites:
        used={name:set() for name in ('GPS','北斗')}
        for item in row['satellites']:
            name=item.get('constellation');cn0=item.get('cn0DbHz');svid=item.get('svid')
            if (name in used and item.get('usedInFix') is True and type(svid) is int
                    and 1<=svid<=(32 if name=='GPS' else 63)
                    and type(cn0) in (int,float) and math.isfinite(cn0) and cn0>0):
                used[name].add(svid)
        if all(used.values()):
            epochs.append(dict(receivedAtMs=row['receivedAtMs'],gps=sorted(used['GPS']),bds=sorted(used['北斗'])))
    return dict(epochs=len(epochs),spanSeconds=(epochs[-1]['receivedAtMs']-epochs[0]['receivedAtMs'])/1000 if epochs else 0,
                examples=epochs[:3],evidence='GPS and BDS usedInFix with positive CN0 in the same GNSS status event')


def summarize(rows, start_ms, end_ms):
    if not rows or end_ms <= start_ms:
        raise ValueError('Missing observations or invalid transmission interval')
    clocks = [r['receivedElapsedRealtimeNanos'] for r in rows]
    if any(b < a for a, b in zip(clocks, clocks[1:])):
        raise ValueError('Phone monotonic clock moved backwards')
    result = {'eventCounts': dict(Counter(r['type'] for r in rows)), 'periods': {}}
    for label in ('before', 'during', 'after'):
        def belongs(row):
            t = row['receivedAtMs']
            return t < start_ms if label == 'before' else (start_ms <= t < end_ms if label == 'during' else t >= end_ms)
        selected = [r for r in rows if belongs(r)]
        locations = [r for r in selected if r['type'] == 'location']
        gps = [r for r in locations if r.get('provider') == 'gps']
        satellites = [r for r in selected if r['type'] == 'satellites']
        signals = [s for r in satellites for s in r['satellites']]
        distances = [r['distanceToTargetCenterM'] for r in gps]
        valid_gga, bad_gga = 0, 0
        for r in selected:
            if r['type'] != 'nmea' or not r['sentence'].startswith(NMEA_GGA_PREFIXES):
                continue
            try:
                valid_gga += gga(r['sentence']) is not None
            except (ValueError, IndexError):
                bad_gga += 1
        result['periods'][label] = {
            'jointGpsBdsUsage': joint_usage(satellites),
            'events': len(selected), 'gpsProviderFixes': len(gps),
            'networkFixes': sum(r.get('provider') == 'network' for r in locations),
            'validNmeaGga': valid_gga, 'malformedNmeaGga': bad_gga,
            'minimumDistanceToTargetCenterM': min(distances) if distances else None,
            'medianDistanceToTargetCenterM': statistics.median(distances) if distances else None,
            'gpsFixesWithin50M': sum(d <= 50 for d in distances),
            'lastGpsReceivedAtMs': gps[-1]['receivedAtMs'] if gps else None,
            'constellations': {
                name: {
                    'peakCn0DbHz': max((s['cn0DbHz'] for s in signals if s['constellation'] == name), default=None),
                    'maximumUsedSvids': max((len({s['svid'] for s in r['satellites']
                                                if s['constellation'] == name and s['usedInFix']})
                                             for r in satellites), default=0)}
                for name in ('GPS', '北斗', 'GAL', 'GLO')}}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('phone', type=Path)
    parser.add_argument('transmission', type=Path)
    args = parser.parse_args()
    payload = args.phone.read_bytes()
    rows, ignored_tail = read_rows(payload)
    tx = json.loads(args.transmission.read_text())
    start = datetime.fromisoformat(tx['startedAt']).timestamp() * 1000
    end = datetime.fromisoformat(tx['endedAt']).timestamp() * 1000
    if not (rows[0]['receivedAtMs'] < end and rows[-1]['receivedAtMs'] > start):
        raise SystemExit('No overlap between phone recording and transmission')
    report = summarize(rows, start, end)
    report.update({'phoneSHA256': hashlib.sha256(payload).hexdigest(),
                   'unterminatedTailBytesIgnored': ignored_tail,
                   'transmissionRecord': str(args.transmission), 'txStatus': tx['status'],
                   'session': rows[0], 'startedAt': tx['startedAt'], 'endedAt': tx['endedAt'],
                   'alignment': 'Mac and phone wall-clock timestamps; clock offset not calibrated',
                   'targetProximityObservedDuringTxWithin50M': report['periods']['during']['gpsFixesWithin50M'] > 0,
                   'receptionAtTargetVerified': False, 'reception3DAtTargetVerified': False})
    folder = args.transmission.parent
    if (folder / 'navigation.json').exists():
        navigation = json.loads((folder / 'navigation.json').read_text())
        session = report['session']
        scenario=json.loads((folder/'phone-scenario.json').read_text()) if (folder/'phone-scenario.json').exists() else None
        latitude=scenario['targetLatitude'] if scenario else session['targetLatitudeWgs84']
        longitude=scenario['targetLongitude'] if scenario else session['targetLongitudeWgs84']
        if not math.isfinite(latitude) or not math.isfinite(longitude) or not -90<=latitude<=90 or not -180<=longitude<=180:
            raise ValueError('Invalid trajectory reference coordinates')
        report['trajectoryReference']={'latitudeWgs84':latitude,'longitudeWgs84':longitude,'source':'syncedScenario' if scenario else 'recordingSession'}
        planned = datetime.fromisoformat(navigation['startUTC'])
        gps, nmea = [], []
        for row in rows:
            if not start <= row['receivedAtMs'] < end:
                continue
            if row['type'] == 'location' and row.get('provider') == 'gps' and 'ellipsoidHeightM' in row:
                t = row['locationTimeMs'] / 1000 - planned.timestamp()
                if 0 <= t <= tx['durationSeconds']:
                    gps.append((t, *ecef((row['latitude'], row['longitude'], row['ellipsoidHeightM']))))
            if row['type'] == 'nmea':
                fix = gga(row['sentence'])
                if fix is not None:
                    t = elapsed_seconds(row['sentence'].split(',')[1], planned.strftime('%H%M%S'))
                    if 0 <= t <= tx['durationSeconds']:
                        nmea.append((t, *ecef(fix)))
        # Android can deliver valid NMEA epochs out of order; retain every observation.
        for observations in (gps,nmea):observations.sort(key=lambda point:point[0])
        report['epochOrdering']='Sorted by navigation epoch; original arrival order retained in raw recording'
        motion = np.loadtxt(folder / 'emitted.csv', delimiter=',')
        report['trajectoryComparison'] = {
            name: track_metrics(points, motion, latitude, longitude)
            for name, points in (('gpsProvider', gps), ('nmeaGga', nmea))}
        report['matchingScenarioEvidenceCount']=scenario_matches(rows,start,end,scenario) if scenario else 0
        report['receptionAtTargetVerified'] = report['matchingScenarioEvidenceCount']>0 and all(r['horizontalPathObserved'] for r in report['trajectoryComparison'].values())
        report['reception3DAtTargetVerified'] = report['receptionAtTargetVerified'] and all(r['position3DObserved'] for r in report['trajectoryComparison'].values())
        # One TX window does not establish coverage of all configurable height behaviors.
        report['fullHeightAndPositionScenarioVerified'] = False
    path = args.phone.with_suffix('.report.json')
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))
