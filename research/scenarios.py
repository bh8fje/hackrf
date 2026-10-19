"""Fixed offline multi-location and quantized-noise GNSS-SDR regression matrix."""
import configparser
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess

import numpy as np
from acquire import N, acquire
from crosscheck import ecef, gga

ROOT = Path(__file__).resolve().parent
SIZE = 61 * 2_600_000 * 2
CASES = [
    ('tokyo', (35.681298, 139.766247, 10), '00:00:00'),
    ('shanghai', (31.2304, 121.4737, 20), '00:00:00'),
    ('sydney', (-33.8688, 151.2093, 30), '12:00:00'),
    ('london', (51.5074, -0.1278, 25), '12:00:00'),
]


def add_noise(source, destination, sigma, seed):
    if not math.isfinite(sigma) or sigma <= 0:
        raise ValueError('Noise standard deviation must be positive and finite')
    if source.resolve() == destination.resolve():
        raise ValueError('Noise output must differ from source')
    if source.stat().st_size == 0 or source.stat().st_size % 2:
        raise ValueError('Expected nonempty interleaved I/Q')
    rng = np.random.default_rng(seed)
    clipped, total = 0, 0
    with source.open('rb') as src, destination.open('wb') as dst:
        while block := src.read(1_048_576):
            noisy = np.rint(np.frombuffer(block, dtype=np.int8).astype(float)
                            + rng.normal(0, sigma, len(block)))
            clipped += int(np.count_nonzero((noisy < -128) | (noisy > 127)))
            total += len(block)
            dst.write(np.clip(noisy, -128, 127).astype(np.int8).tobytes())
    if total == 0 or total % 2:
        raise ValueError('Expected nonempty interleaved I/Q')
    return clipped / total


def receiver_config(iq_path, prns):
    if not prns or len(set(prns)) != len(prns) or any(not 1 <= p <= 32 for p in prns):
        raise ValueError('Need unique GPS PRNs in 1..32')
    config = configparser.ConfigParser(interpolation=None)
    config.optionxform = str
    config.read(ROOT / 'gnss-sdr.conf')
    section = config['GNSS-SDR']
    for key in list(section):
        if re.fullmatch(r'Channel\d+\.satellite', key):
            del section[key]
    section['SignalSource.filename'] = str(iq_path.resolve())
    section['Channels_1C.count'] = section['Channels.in_acquisition'] = str(len(prns))
    for i, prn in enumerate(prns):
        section[f'Channel{i}.satellite'] = str(prn)
    return config


def receive(binary, folder, iq, prns):
    config = receiver_config(iq, prns)
    config_path = folder / 'receiver.conf'
    with config_path.open('w') as stream:
        config.write(stream)
    with (folder / 'receiver.log').open('w') as log:
        (folder / 'fix.nmea').unlink(missing_ok=True)
        subprocess.run([binary, f'--config_file={config_path}', '--log_dir=.'],
                       cwd=folder, stdout=log, stderr=log, check=True, timeout=180)
    nmea = folder / 'fix.nmea'
    return nmea.read_bytes() if nmea.exists() else b''


def run_receiver(binary, folder, iq, prns, truth, expect_fix=True):
    sentences = receive(binary, folder, iq, prns)
    fixes = [fix for line in sentences.decode().splitlines() if (fix := gga(line)) is not None]
    errors = [math.dist(ecef(fix), ecef(truth)) for fix in fixes]
    with iq.open('rb') as stream:
        source_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'iq_sha256': source_hash, 'selected_prns': prns,
            'valid_fixes': len(fixes), 'expected_fix': expect_fix,
            'median_error_3d_m': statistics.median(errors) if errors else None,
            'maximum_error_3d_m': max(errors) if errors else None,
            'passed': (len(fixes) >= 100 and max(errors) < 20) if expect_fix else not fixes}


def main():
    binary = os.environ.get('GNSS_SDR', 'gnss-sdr')
    version = subprocess.check_output([binary, '--version'], text=True).strip()
    output = ROOT / 'output/scenarios'
    output.mkdir(parents=True, exist_ok=True)
    summary = {'receiver_version': version, 'rinex_date': '2022-01-01',
               'prn_selection': 'own acquisition on clean input; not blind GNSS-SDR search',
               'acceptance': 'positive: >=100 fixes, all <20m; zero input: no fix',
               'complete': False, 'cases': []}
    report = output / 'summary.json'
    report.unlink(missing_ok=True)
    tokyo_iq, tokyo_prns = None, None
    for name, truth, start in CASES:
        folder = output / name
        folder.mkdir(exist_ok=True)
        iq = folder / 'signal.bin'
        with (folder / 'generator.log').open('w') as log:
            subprocess.run([str(ROOT / 'gps-sdr-sim/gps-sdr-sim'),
                            '-e', str(ROOT / 'gps-sdr-sim/brdc0010.22n'),
                            '-l', ','.join(map(str, truth)), '-t', f'2022/01/01,{start}',
                            '-d', '61.1', '-s', '2600000', '-b', '8', '-o', str(iq)],
                           stdout=log, stderr=log, check=True, timeout=60)
        if iq.stat().st_size != SIZE:
            raise ValueError(f'{name}: incorrect file length')
        with iq.open('rb') as stream:
            first = np.frombuffer(stream.read(N * 20), dtype=np.int8)
        signal = first[::2].astype(float) + 1j * first[1::2]
        prns = [a['prn'] for a in acquire(signal) if a['detected']]
        result = {'name': name, 'truth_lat_lon_height': truth, 'start_time': start,
                  **run_receiver(binary, folder, iq, prns, truth)}
        summary['cases'].append(result)
        report.write_text(json.dumps(summary, indent=2) + '\n')
        print(json.dumps(result), flush=True)
        if name == 'tokyo':
            tokyo_iq, tokyo_prns = iq, prns
    for sigma in (20, 50, 0):
        folder = output / (f'tokyo-noise-{sigma}' if sigma else 'zero-input')
        folder.mkdir(exist_ok=True)
        iq = folder / 'signal.bin'
        if sigma:
            clipping = add_noise(tokyo_iq, iq, sigma, seed=20261016)
        else:
            with iq.open('wb') as stream:
                stream.truncate(SIZE)
            clipping = 0
        result = {'name': folder.name, 'noise_sigma_per_component': sigma,
                  'seed': 20261016 if sigma else None, 'clipped_component_fraction': clipping,
                  **run_receiver(binary, folder, iq, tokyo_prns, CASES[0][1], bool(sigma))}
        summary['cases'].append(result)
        report.write_text(json.dumps(summary, indent=2) + '\n')
        print(json.dumps(result), flush=True)
    summary['complete'] = True
    summary['all_passed'] = all(case['passed'] for case in summary['cases'])
    report.write_text(json.dumps(summary, indent=2) + '\n')
    if not summary['all_passed']:
        raise SystemExit('Offline scenario matrix did not pass; see summary.json')


if __name__ == '__main__':
    main()
