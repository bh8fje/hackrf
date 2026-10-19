"""Recover a static position from the offline I/Q fixture, then compare with truth."""
import hashlib
import json
from pathlib import Path
import numpy as np
from acquire import N, acquire
from track import track
from telemetry import synchronize
from position import C, ephemeris, satellite, solve, geodetic


def measurements(phases, frame, epoch_ms=15000):
    if not frame['start_ms'] < epoch_ms < len(phases) - 50:
        raise ValueError('Observation epoch outside tracked data')
    unwrapped = np.unwrap(phases * 2 * np.pi / 1023) * 1023 / (2 * np.pi)
    anchor = round(unwrapped[frame['start_ms']] / 1023) * 1023
    phase = float(np.mean(unwrapped[epoch_ms - 50:epoch_ms + 50])) - anchor
    # shortcut: nearest-ms bit boundary is unambiguous in this clean static fixture.
    return frame['next_tow_seconds'] - 6 + (epoch_ms - frame['start_ms']) / 1000 + phase / 1_023_000


def main():
    folder = Path(__file__).resolve().parent / 'output'
    path = folder / 'navigation.bin'
    raw = np.memmap(path, dtype=np.int8, mode='r')
    first = raw[:N * 20]
    acquisitions = acquire(first[::2].astype(float) + 1j * first[1::2])
    satellites, ranges, channels = [], [], []
    rejected = []
    reference = None
    for acquisition in acquisitions:
        if not acquisition['detected']:
            continue
        prn = acquisition['prn']
        prompts, frequencies, errors, phases = track(raw, acquisition)
        frames = synchronize(prompts)
        try:
            eph = ephemeris(frames)
            if float(np.sqrt(np.mean(errors[-1000:]**2))) > 0.15:
                raise ValueError('Excessive final carrier error')
            transmit_clock = measurements(phases, frames[0])
            frame_time = frames[0]['next_tow_seconds'] - 6
            if reference is None:
                reference = frame_time
            if frame_time != reference:
                raise ValueError('Mismatched frame epoch')
            transmit_gps = transmit_clock
            for _ in range(3):
                xyz, clock = satellite(eph, transmit_gps)
                transmit_gps = transmit_clock - clock
            xyz, clock = satellite(eph, transmit_gps)
            pseudorange = C * (reference + 15 - transmit_clock) + C * clock
            satellites.append(xyz)
            ranges.append(pseudorange)
            channels.append({'prn': prn, 'ephemeris': eph, 'subframes': frames,
                             'transmit_clock_tow': transmit_clock,
                             'clock_corrected_pseudorange_m': pseudorange})
            print(f'PRN {prn:02}: 30 words valid; measurement accepted', flush=True)
        except ValueError as error:
            rejected.append({'prn': prn, 'reason': str(error)})
            print(f'PRN {prn:02}: excluded: {error}', flush=True)
    state, residuals = solve(satellites, ranges)
    # Truth is used only after the position has been solved from measurements.
    lat, lon, height = np.radians(35.681298), np.radians(139.766247), 10.0
    radius = 6378137 / np.sqrt(1 - 6.69437999014e-3 * np.sin(lat)**2)
    truth = np.array([(radius + height) * np.cos(lat) * np.cos(lon),
                      (radius + height) * np.cos(lat) * np.sin(lon),
                      (radius * (1 - 6.69437999014e-3) + height) * np.sin(lat)])
    error = float(np.linalg.norm(state[:3] - truth))
    report = {'fixture_sha256': hashlib.sha256(raw).hexdigest(), 'channels': channels,
              'rejected_channels': rejected, 'ecef_m': state[:3].tolist(),
              'lat_lon_height': geodetic(state[:3]), 'receiver_clock_bias_m': float(state[3]),
              'residual_rms_m': float(np.sqrt(np.mean(residuals**2))),
              'position_error_3d_m': error, 'position_acceptance_limit_m': 20,
              'static_position_verified': error < 20,
              'velocity_verified': False, 'ionosphere_corrected': False,
              'limitations': 'clean static fixture; heuristic loops; no RF validation'}
    (folder / 'position.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k not in ('channels',)}, indent=2))
    if error >= 20:
        raise SystemExit('Static baseline failed 20 m acceptance limit')


if __name__ == '__main__':
    main()
