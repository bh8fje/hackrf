"""Time-aligned offline circular-motion regression using GNSS-SDR GGA fixes."""
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess

import numpy as np
from acquire import N, acquire
from crosscheck import ecef, gga
from scenarios import SIZE, receive

ROOT = Path(__file__).resolve().parent


def trajectory():
    t = np.arange(612) / 10
    angle = 2 * np.pi * t / 60
    east = 100 * np.sin(angle)
    north = 100 * (1 - np.cos(angle))
    lat, lon = np.radians([35.681298, 139.766247])
    east_axis = np.array([-np.sin(lon), np.cos(lon), 0])
    north_axis = np.array([-np.sin(lat) * np.cos(lon), -np.sin(lat) * np.sin(lon), np.cos(lat)])
    xyz = np.array(ecef((35.681298, 139.766247, 10))) + east[:, None] * east_axis + north[:, None] * north_axis
    return np.column_stack((t, xyz))


def elapsed_seconds(utc, start_utc='235942'):
    def seconds(value):
        if len(value) < 6 or not value[:6].isdigit():
            raise ValueError('Invalid GGA UTC time')
        hour, minute, second = int(value[:2]), int(value[2:4]), float(value[4:])
        if hour >= 24 or minute >= 60 or not math.isfinite(second) or not 0 <= second < 60:
            raise ValueError('Invalid GGA UTC time')
        return hour * 3600 + minute * 60 + second
    # Default preserves the 2022 fixture; current scenarios pass their actual UTC start.
    return (seconds(utc) - seconds(start_utc)) % 86400


def compare(observations, motion):
    if not observations:
        return {'valid_fixes': 0, 'passed': False}
    points = np.asarray(observations)
    if (points.ndim != 2 or points.shape[1] != 4 or not np.isfinite(points).all()
            or np.any(np.diff(points[:, 0]) <= 0)
            or points[0, 0] < motion[0, 0] or points[-1, 0] > motion[-1, 0]):
        raise ValueError('Observations must have increasing times within the trajectory')
    expected = np.column_stack([np.interp(points[:, 0], motion[:, 0], motion[:, i]) for i in (1, 2, 3)])
    errors = np.linalg.norm(points[:, 1:] - expected, axis=1)
    return {'valid_fixes': len(points), 'maximum_allowed_3d_error_m': 30, 'first_elapsed_seconds': float(points[0, 0]),
            'last_elapsed_seconds': float(points[-1, 0]),
            'median_error_3d_m': float(np.median(errors)), 'maximum_error_3d_m': float(max(errors)),
            'measured_endpoint_distance_m': float(np.linalg.norm(points[-1, 1:] - points[0, 1:])),
            'passed': bool(len(points) >= 100 and max(errors) <= 30)}


def main():
    binary = os.environ.get('GNSS_SDR', 'gnss-sdr')
    folder = ROOT / 'output/dynamic'
    folder.mkdir(parents=True, exist_ok=True)
    report_path = folder / 'summary.json'
    report_path.unlink(missing_ok=True)
    motion = trajectory()
    csv = folder / 'trajectory.csv'
    np.savetxt(csv, motion, delimiter=',', fmt=['%.1f', '%.6f', '%.6f', '%.6f'])
    iq = folder / 'signal.bin'
    with (folder / 'generator.log').open('w') as log:
        subprocess.run([str(ROOT / 'gps-sdr-sim/gps-sdr-sim'),
                        '-e', str(ROOT / 'gps-sdr-sim/brdc0010.22n'), '-u', str(csv),
                        '-t', '2022/01/01,00:00:00', '-d', '61.1', '-s', '2600000',
                        '-b', '8', '-o', str(iq)], stdout=log, stderr=log, check=True, timeout=60)
    if iq.stat().st_size != SIZE:
        raise ValueError('Incorrect dynamic I/Q file length')
    with iq.open('rb') as stream:
        first = np.frombuffer(stream.read(N * 20), dtype=np.int8)
    prns = [a['prn'] for a in acquire(first[::2].astype(float) + 1j * first[1::2]) if a['detected']]
    nmea = receive(binary, folder, iq, prns)
    observations = []
    for line in nmea.decode().splitlines():
        fix = gga(line)
        if fix is not None:
            utc = line.split(',')[1]
            observations.append((elapsed_seconds(utc), *ecef(fix)))
    result = compare(observations, motion)
    with iq.open('rb') as stream:
        source_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
    result.update({'receiver_version': subprocess.check_output([binary, '--version'], text=True).strip(),
                   'iq_sha256': source_hash, 'trajectory_sha256': hashlib.sha256(csv.read_bytes()).hexdigest(),
                   'motion': '100 m radius, 60 s circular period, 10 Hz ECEF trajectory',
                   'nominal_speed_m_s': 2 * math.pi * 100 / 60,
                   'generator_start_gps': '2022-01-01 00:00:00', 'gps_minus_utc_seconds': 18,
                   'selected_prns': prns, 'velocity_verified': False})
    report_path.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    if not result['passed']:
        raise SystemExit('Dynamic path failed acceptance')


if __name__ == '__main__':
    main()
