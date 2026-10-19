"""Bounded walking trajectory and coherent position-drift model, offline only."""
import math
import json
from pathlib import Path
import numpy as np
import eviltransform
from crosscheck import ecef

DEFAULTS = {'longitude': -77.03637, 'latitude': 38.89761, 'coordinateSystem': 'WGS-84',
            'height': 18.0, 'duration': 60, 'speed': 0.5, 'radius': 12.0,
            'route': 'circle', 'heading': 0.0, 'heightVariation': 0.08,
            'horizontalDrift': 2.5, 'verticalDrift': 0.5, 'seed': 42,
            'speedVariation': 0.2, 'speedSmoothing': 2.0, 'randomHeading': True,
            'headingMinimum': 0.0, 'headingMaximum': 360.0,
            'headingHoldMinimum': 3.0, 'headingHoldMaximum': 8.0,
            'turnSmoothing': 2.0, 'heightSmoothing': 2.0, 'driftSmoothing': 10.0}
LIMITS = {'longitude': (-180, 180), 'latitude': (-85, 85), 'height': (-500, 10000),
          'duration': (60, 600), 'speed': (0, 2.5), 'radius': (2, 50), 'heading': (0, 360),
          'heightVariation': (0, 5000), 'horizontalDrift': (0, 10), 'verticalDrift': (0, 3),
          'seed': (0, 2**31 - 1), 'speedVariation': (0, 2.5),
          'speedSmoothing': (0.1, 60), 'headingMinimum': (0, 360), 'headingMaximum': (0, 360),
          'headingHoldMinimum': (0.1, 600), 'headingHoldMaximum': (0.1, 600),
          'turnSmoothing': (0.1, 60), 'heightSmoothing': (0.1, 60), 'driftSmoothing': (0.1, 600)}


def signal_environment(settings):
    policy=json.loads((Path(__file__).resolve().parent.parent/'shared/signal-environments.json').read_text())
    profile=policy['routes'][settings['route']]
    index=next(i for i,p in enumerate(policy['profiles']) if p['id']==profile)
    return {'profile':profile,'profileIndex':index,'label':policy['profiles'][index]['label'],
            'motionRoute':settings['route'],'seed':int(settings['seed']),'absoluteCN0Calibrated':False}


def validate(settings):
    legacy = set(DEFAULTS) - {'speedVariation', 'speedSmoothing', 'randomHeading', 'headingMinimum',
                            'headingMaximum', 'headingHoldMinimum', 'headingHoldMaximum',
                            'turnSmoothing', 'heightSmoothing', 'driftSmoothing'}
    if not legacy <= set(settings) or set(settings) - set(DEFAULTS):
        raise ValueError('参数字段不完整或包含未知字段')
    settings = DEFAULTS | settings
    for key, (low, high) in LIMITS.items():
        value = settings[key]
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not low <= value <= high:
            raise ValueError(f'{key} 必须介于 {low} 与 {high} 之间')
    if any(int(settings[key]) != settings[key] for key in ('duration', 'seed')):
        raise ValueError('时长和随机种子必须为整数')
    if settings['coordinateSystem'] not in ('GCJ-02', 'WGS-84') or settings['route'] not in ('circle', 'line', 'stationary', 'park'):
        raise ValueError('不支持的坐标系或路线')
    if settings['height'] - settings['heightVariation'] < -500 or settings['height'] + settings['heightVariation'] > 10000:
        raise ValueError('高度上下限必须介于 -500 与 10000 米')
    if settings['route'] == 'park' and not 10 <= settings['radius'] <= 50:
        raise ValueError('园区步行的范围半径必须为 10–50 米')
    if not isinstance(settings['randomHeading'], bool):
        raise ValueError('randomHeading 必须为布尔值')
    if settings['headingMinimum'] > settings['headingMaximum'] or settings['headingHoldMinimum'] > settings['headingHoldMaximum']:
        raise ValueError('随机范围下限不能大于上限')


def random_series(rng, t, low, high, smoothing, initial=None):
    targets = rng.uniform(low, high, int(np.floor(t[-1])) + 1)
    values = np.empty(len(t))
    values[0] = targets[0] if initial is None else np.clip(initial, low, high)
    for i in range(1, len(t)):
        values[i] = values[i-1] + (-np.expm1(-(t[i]-t[i-1])/smoothing)) * (targets[int(t[i])] - values[i-1])
    return values


def wandering_series(rng, t, amplitude):
    times, targets = [0.0], [0.0]
    while times[-1] < t[-1]:
        times.append(times[-1] + rng.uniform(8, 20))
        targets.append(rng.uniform(-amplitude, amplitude))
    segment = np.searchsorted(times, t, side='right') - 1
    segment = np.minimum(segment, len(times)-2)
    times, targets = np.array(times), np.array(targets)
    fraction = (t-times[segment])/(times[segment+1]-times[segment])
    blend = fraction*fraction*(3-2*fraction)
    return targets[segment] + blend*(targets[segment+1]-targets[segment])


def stationary_drift(seed, count):
    # Correlated position error with inertia and mean reversion, not human movement.
    rng = np.random.default_rng(int(seed) ^ 0x517cc1b7)
    position, velocity, fast, slow = (np.zeros(2) for _ in range(4))
    path = np.zeros((count, 2))
    fast_decay, slow_decay = math.exp(-.1/1.8), math.exp(-.1/15)
    for i in range(1, count):
        fast = fast_decay*fast + .12*math.sqrt(1-fast_decay**2)*rng.standard_normal(2)
        slow = slow_decay*slow + .045*math.sqrt(1-slow_decay**2)*rng.standard_normal(2)
        velocity += .1*(fast+slow-.5*velocity-.018*position)
        position += .1*velocity
        radius = float(np.linalg.norm(position))
        scale = 19.99*math.tanh(radius/19.99)/radius if radius else 1
        path[i] = position*scale
    return path


def office_motion(seed, count):
    rng = np.random.default_rng(int(seed) ^ 0x29a73f15)
    path = np.zeros((count, 2))
    phase, start, end = 0, 0.0, rng.uniform(20, 55)
    position, origin, target = (np.zeros(2) for _ in range(3))
    for i in range(1, count):
        t = i/10
        if t >= end:
            phase = (phase+1) % 4
            start, origin = t, position.copy()
            if phase == 1:
                angle, distance = rng.uniform(0, 2*math.pi), rng.uniform(1, 4)
                target = distance*np.array([math.sin(angle), math.cos(angle)])
            elif phase == 3:
                target = np.zeros(2)
            duration = (1.875*float(np.linalg.norm(target-origin))/rng.uniform(.35, .7)
                        if phase in (1, 3) else rng.uniform(5, 18) if phase == 2 else rng.uniform(30, 100))
            end = t + duration
        if phase in (1, 3):
            u = min(1, (t-start)/(end-start))
            blend = u**3*(10-15*u+6*u*u)
            position = origin+(target-origin)*blend
        else:
            position = target.copy() if phase == 2 else np.zeros(2)
        path[i] = position
    return path


def enu_basis(lat, lon):
    latitude, longitude = np.radians([lat, lon])
    return np.array([[-np.sin(longitude), np.cos(longitude), 0],
                     [-np.sin(latitude)*np.cos(longitude), -np.sin(latitude)*np.sin(longitude), np.cos(latitude)],
                     [np.cos(latitude)*np.cos(longitude), np.cos(latitude)*np.sin(longitude), np.sin(latitude)]])


def startup_offset(seed):
    # Separate random stream preserves the existing motion and drift for a seed.
    value = (1664525 * int(seed) + 1013904223) & 0xffffffff
    distance = (3000 + value * 5001 // 2**32) / 100
    value = (1664525 * value + 1013904223) & 0xffffffff
    angle = math.radians(135 + 90 * value / 2**32)
    return np.array([distance * math.sin(angle), distance * math.cos(angle)])


def plan(settings):
    validate(settings)
    s = DEFAULTS | settings
    lat, lon = s['latitude'], s['longitude']
    if s['coordinateSystem'] == 'GCJ-02':
        if eviltransform.outOfChina(lat, lon):
            raise ValueError('该 GCJ-02 离线近似转换只适用于中国大陆范围；其他位置请选 WGS-84')
        lat, lon = eviltransform.gcj2wgs_exact(lat, lon)
    t = np.arange(int(s['duration']) * 10 + 2) / 10
    rng = np.random.default_rng(int(s['seed']))
    phase = rng.uniform(0, 2 * np.pi, 6)
    low, high = max(0, s['speed']-s['speedVariation']), min(2.5, s['speed']+s['speedVariation'])
    speeds = random_series(rng, t, low, high, s['speedSmoothing'], initial=s['speed'])
    if s['speed'] == 0:
        speeds[:] = 0
    initial_heading = rng.uniform(s['headingMinimum'], s['headingMaximum']) if s['randomHeading'] else s['heading']
    distance = np.r_[0, np.cumsum(speeds[1:] * np.diff(t))]
    if s['route'] == 'circle':
        # Slow radius changes deform the loop; speed changes alter the spacing of fixes.
        radius = s['radius'] + wandering_series(rng, t, min(2, s['radius'] * .15) if s['speed'] else 0)
        angle = distance / s['radius'] - np.radians(initial_heading)
        east, north = radius * np.cos(angle), radius * np.sin(angle)
    elif s['route'] == 'line':
        heading = np.radians(initial_heading)
        lateral = wandering_series(rng, t, 5 if s['speed'] else 0)
        east = distance * np.sin(heading) + lateral * np.cos(heading)
        north = distance * np.cos(heading) - lateral * np.sin(heading)
    elif s['route'] == 'park':
        # Radius protection may shorten a heading hold near the boundary.
        nodes = np.zeros((len(t), 2))
        heading = np.radians(initial_heading)
        destination = 0.85 * s['radius'] * np.array([np.sin(heading), np.cos(heading)])
        velocity = s['speed'] * np.array([np.sin(heading), np.cos(heading)])
        next_turn = rng.uniform(s['headingHoldMinimum'], s['headingHoldMaximum'])
        for i in range(1, len(nodes)):
            delta = destination - nodes[i-1]
            if t[i] >= next_turn or np.linalg.norm(delta) <= max(speeds[i], 0.5):
                angle = np.radians(rng.uniform(s['headingMinimum'], s['headingMaximum']))
                destination = nodes[i-1] + s['radius'] * np.array([np.sin(angle), np.cos(angle)])
                destination *= min(1, 0.85 * s['radius'] / max(float(np.linalg.norm(destination)), 1e-30))
                if np.linalg.norm(destination - nodes[i-1]) <= 0.5:
                    destination = np.zeros(2)
                next_turn = t[i] + rng.uniform(s['headingHoldMinimum'], s['headingHoldMaximum'])
                delta = destination - nodes[i-1]
            desired = delta * min(speeds[i] / max(float(np.linalg.norm(delta)), 1e-30), 1.0)
            velocity += (-np.expm1(-0.1 / s['turnSmoothing'])) * (desired - velocity)
            candidate = nodes[i-1] + 0.1 * velocity
            nodes[i] = candidate * min(1, s['radius'] / max(float(np.linalg.norm(candidate)), 1e-30))
        east, north = nodes[:, 0], nodes[:, 1]
    else:
        east, north = np.zeros_like(t), np.zeros_like(t)
    up = random_series(rng, t, -s['heightVariation'], s['heightVariation'], s['heightSmoothing'])
    ideal_enu = np.column_stack((east-east[0], north-north[0], up))
    frequencies = np.array([1/50, 1/23, 1/11])
    waves = 2 * np.pi * t[:, None] * frequencies * 10 / s['driftSmoothing']
    weights = np.array([0.6, 0.3, 0.1])
    drift = np.column_stack((np.sin(waves + phase[:3]) @ weights,
                             np.cos(waves + phase[3:]) @ weights))
    drift -= drift[0]
    drift *= s['horizontalDrift'] / max(float(np.linalg.norm(drift, axis=1).max()), 1e-30)
    vertical = s['verticalDrift'] * (np.sin(waves + phase[:3]) @ weights)
    vertical -= vertical[0]
    vertical *= s['verticalDrift'] / max(float(np.abs(vertical).max()), 1e-30)
    emitted_enu = ideal_enu + np.column_stack((drift, vertical))
    offset = startup_offset(s['seed'])
    if s['route'] == 'stationary':
        ideal_enu[:] = 0
        ideal_enu[:, :2] = office_motion(s['seed'], len(t))
        combined = ideal_enu[:, :2] + stationary_drift(s['seed'], len(t))
        radius = np.linalg.norm(combined, axis=1)
        scale = np.divide(19.99*np.tanh(radius/19.99), radius, out=np.ones_like(radius), where=radius>0)
        emitted_enu[:, :2] = combined*scale[:, None]
        up[:] = 0
        emitted_enu[:, 2] = 0
    elif s['route'] == 'line':
        # Bound the final lateral error, including drift, relative to the shifted start.
        lateral = emitted_enu[:, 0]*np.cos(heading)-emitted_enu[:, 1]*np.sin(heading)
        correction = np.clip(lateral, -5, 5)-lateral
        emitted_enu[:, 0] += correction*np.cos(heading)
        emitted_enu[:, 1] -= correction*np.sin(heading)
    emitted_enu[:, :2] += offset
    basis = enu_basis(lat, lon)
    origin = np.array(ecef((lat, lon, s['height'])))
    ideal = np.column_stack((t, origin + ideal_enu @ basis))
    emitted = np.column_stack((t, origin + emitted_enu @ basis))
    step = max(1, int(math.ceil(len(t) / 240)))
    sample_indices = [0]
    sample_rng = np.random.default_rng(int(s['seed']) + 1)
    while sample_indices[-1] < len(t)-1:
        sample_indices.append(min(len(t)-1, sample_indices[-1]+int(sample_rng.integers(10, 51))))
    preview = {'sampledPath': np.round(emitted_enu[sample_indices if s['route']=='circle' else np.arange(0,len(t),step), :2], 2).tolist(),
               'sampleTimes': t[sample_indices].tolist(), 'wgs84Latitude' : lat, 'wgs84Longitude': lon,
               'coordinateConversion': '离线近似逆转换，非测绘校准' if s['coordinateSystem'] == 'GCJ-02' else '直接使用 WGS-84',
               'idealPath': ideal_enu[::step, :2].tolist(), 'emittedPath': emitted_enu[::step, :2].tolist(),
               'idealHeightMin': float(s['height'] + up.min()), 'idealHeightMax': float(s['height'] + up.max()),
               'emittedHeightMin': float(s['height'] + emitted_enu[:, 2].min()),
               'emittedHeightMax': float(s['height'] + emitted_enu[:, 2].max()),
               'maxHorizontalDrift': float(np.linalg.norm(emitted_enu[:, :2]-ideal_enu[:, :2]-offset, axis=1).max()),
               'startupOffsetEast': float(offset[0]), 'startupOffsetNorth': float(offset[1]),
               'estimatedFileMB': int(s['duration'] * 5.2), 'rfEnabled': False}
    return ideal, emitted, preview
