"""Broadcast GPS ephemeris and static snapshot position, without truth-position input."""
import numpy as np

C = 299_792_458.0
OMEGA = 7.2921151467e-5
MU = 3.986005e14


def signed(value, width):
    return value - (1 << width) if value & (1 << (width - 1)) else value


def ephemeris(frames):
    if [f['subframe_id'] for f in frames] != [1, 2, 3]:
        raise ValueError('Need consecutive subframes 1, 2, 3')
    if any(f['valid_words'] != 10 or len(f['data_words_hex']) != 10 for f in frames):
        raise ValueError('Need ten parity-validated words per subframe')
    if any((frames[i+1]['next_tow_seconds'] - frames[i]['next_tow_seconds']) % 604800 != 6
           for i in (0, 1)):
        raise ValueError('Nonconsecutive subframe times')
    a, b, d = [[int(w, 16) for w in f['data_words_hex']] for f in frames]
    if any(not 0 <= w < 2**24 for w in a + b + d):
        raise ValueError('Word exceeds 24 data bits')
    iodc = ((a[2] & 3) << 8) | (a[7] >> 16)
    if b[2] >> 16 != d[9] >> 16 or iodc & 255 != b[2] >> 16:
        raise ValueError('Inconsistent ephemeris issue numbers')
    if (a[2] >> 2) & 63:
        raise ValueError('Satellite is unhealthy')
    def angle(hi, lo):
        return signed(((hi & 255) << 24) | lo, 32) * 2**-31 * np.pi
    result = {'week_mod_1024': a[2] >> 14, 'iode': b[2] >> 16,
              'toc': (a[7] & 65535) * 16, 'toe': (b[9] >> 8) * 16,
              'tgd': signed(a[6] & 255, 8) * 2**-31,
              'af0': signed(a[9] >> 2, 22) * 2**-31,
              'af1': signed(a[8] & 65535, 16) * 2**-43,
              'af2': signed(a[8] >> 16, 8) * 2**-55,
              'crs': signed(b[2] & 65535, 16) * 2**-5,
              'dn': signed(b[3] >> 8, 16) * 2**-43 * np.pi,
              'm0': angle(b[3], b[4]), 'cuc': signed(b[5] >> 8, 16) * 2**-29,
              'ecc': (((b[5] & 255) << 24) | b[6]) * 2**-33,
              'cus': signed(b[7] >> 8, 16) * 2**-29,
              'sqrt_a': (((b[7] & 255) << 24) | b[8]) * 2**-19,
              'cic': signed(d[2] >> 8, 16) * 2**-29, 'omega0': angle(d[2], d[3]),
              'cis': signed(d[4] >> 8, 16) * 2**-29, 'i0': angle(d[4], d[5]),
              'crc': signed(d[6] >> 8, 16) * 2**-5, 'argp': angle(d[6], d[7]),
              'omega_dot': signed(d[8], 24) * 2**-43 * np.pi,
              'idot': signed((d[9] >> 2) & 16383, 14) * 2**-43 * np.pi}
    if not 0 <= result['ecc'] < 1 or not 5000 < result['sqrt_a'] < 5300:
        raise ValueError('Invalid GPS orbit')
    return result


def week_delta(seconds):
    return (seconds + 302400) % 604800 - 302400


def satellite(e, tow):
    tk = week_delta(tow - e['toe'])
    if abs(tk) > 7200:
        raise ValueError('Ephemeris outside this baseline two-hour validity window')
    a = e['sqrt_a'] ** 2
    mean = e['m0'] + (np.sqrt(MU / a**3) + e['dn']) * tk
    anomaly = mean
    for _ in range(15):
        step = (anomaly - e['ecc'] * np.sin(anomaly) - mean) / (1 - e['ecc'] * np.cos(anomaly))
        anomaly -= step
        if abs(step) < 1e-13:
            break
    else:
        raise ValueError('Kepler equation did not converge')
    phi = np.arctan2(np.sqrt(1 - e['ecc']**2) * np.sin(anomaly),
                     np.cos(anomaly) - e['ecc']) + e['argp']
    sine, cosine = np.sin(2 * phi), np.cos(2 * phi)
    u = phi + e['cus'] * sine + e['cuc'] * cosine
    radius = a * (1 - e['ecc'] * np.cos(anomaly)) + e['crs'] * sine + e['crc'] * cosine
    inc = e['i0'] + e['idot'] * tk + e['cis'] * sine + e['cic'] * cosine
    node = e['omega0'] + (e['omega_dot'] - OMEGA) * tk - OMEGA * e['toe']
    x, y = radius * np.cos(u), radius * np.sin(u)
    xyz = np.array([x * np.cos(node) - y * np.cos(inc) * np.sin(node),
                    x * np.sin(node) + y * np.cos(inc) * np.cos(node), y * np.sin(inc)])
    dt = week_delta(tow - e['toc'])
    clock = e['af0'] + e['af1'] * dt + e['af2'] * dt**2
    clock += -4.442807633e-10 * e['ecc'] * e['sqrt_a'] * np.sin(anomaly) - e['tgd']
    return xyz, clock


def solve(satellites, pseudoranges):
    satellites, pseudoranges = np.asarray(satellites), np.asarray(pseudoranges)
    if (satellites.ndim != 2 or satellites.shape[1] != 3 or len(satellites) < 4
            or pseudoranges.shape != (len(satellites),)
            or not np.isfinite(satellites).all() or not np.isfinite(pseudoranges).all()):
        raise ValueError('Need at least four finite satellite positions and ranges')
    state = np.zeros(4)
    for _ in range(20):
        travel = np.linalg.norm(satellites - state[:3], axis=1) / C
        theta = OMEGA * travel
        rotated = satellites.copy()
        rotated[:, 0] = np.cos(theta) * satellites[:, 0] + np.sin(theta) * satellites[:, 1]
        rotated[:, 1] = -np.sin(theta) * satellites[:, 0] + np.cos(theta) * satellites[:, 1]
        delta = state[:3] - rotated
        distance = np.linalg.norm(delta, axis=1)
        if np.any(distance == 0):
            raise ValueError('Degenerate geometry')
        h = np.column_stack((delta / distance[:, None], np.ones(len(distance))))
        residual = pseudoranges - distance - state[3]
        correction, _, rank, _ = np.linalg.lstsq(h, residual, rcond=None)
        if rank < 4 or np.linalg.cond(h) > 1e8:
            raise ValueError('Insufficient satellite geometry')
        state += correction
        if np.linalg.norm(correction) < 1e-4:
            return state, residual
    raise ValueError('Position solution did not converge')


def geodetic(xyz):
    x, y, z = xyz
    p = np.hypot(x, y)
    lat = np.arctan2(z, p * (1 - 6.69437999014e-3))
    for _ in range(10):
        radius = 6378137 / np.sqrt(1 - 6.69437999014e-3 * np.sin(lat)**2)
        lat = np.arctan2(z + 6.69437999014e-3 * radius * np.sin(lat), p)
    height = p * np.cos(lat) + z * np.sin(lat) - radius * (1 - 6.69437999014e-3 * np.sin(lat)**2)
    return [float(np.degrees(lat)), float(np.degrees(np.arctan2(y, x))), float(height)]
