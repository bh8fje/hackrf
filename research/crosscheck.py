"""Validate checksum-protected GNSS-SDR GGA fixes against the static fixture."""
import json
import hashlib
import math
from pathlib import Path
import statistics


NMEA_GGA_PREFIXES=tuple(f"${system}GGA," for system in ("GP","GN","GA","GL","GB","BD"))
NMEA_RMC_PREFIXES=tuple(f"${system}RMC," for system in ("GP","GN","GA","GL","GB","BD"))

def gga(line):
    line = line.strip()
    if not line.startswith(NMEA_GGA_PREFIXES):
        return None
    body, checksum = line[1:].split('*')
    actual = 0
    for char in body:
        actual ^= ord(char)
    if len(checksum) != 2 or actual != int(checksum, 16):
        raise ValueError('GGA checksum mismatch')
    fields = body.split(',')
    if len(fields) < 15:
        raise ValueError('Truncated GGA')
    if int(fields[6]) == 0:
        return None
    def coordinate(value, hemisphere, positive, negative, limit):
        if hemisphere not in (positive, negative):
            raise ValueError('Invalid hemisphere')
        number = float(value)
        degrees = int(number // 100)
        minutes = number - degrees * 100
        result = degrees + minutes / 60
        if not math.isfinite(result) or not 0 <= minutes < 60 or not 0 <= result <= limit:
            raise ValueError('Invalid coordinate')
        return result if hemisphere == positive else -result
    lat = coordinate(fields[2], fields[3], 'N', 'S', 90)
    lon = coordinate(fields[4], fields[5], 'E', 'W', 180)
    if fields[10] != 'M' or fields[12] != 'M':
        raise ValueError('Expected metric altitude and geoid separation')
    height = float(fields[9]) + float(fields[11])
    if not math.isfinite(height):
        raise ValueError('Invalid height')
    return lat, lon, height


def ecef(llh):
    lat, lon = map(math.radians, llh[:2])
    radius = 6378137 / math.sqrt(1 - 6.69437999014e-3 * math.sin(lat)**2)
    return ((radius + llh[2]) * math.cos(lat) * math.cos(lon),
            (radius + llh[2]) * math.cos(lat) * math.sin(lon),
            (radius * (1 - 6.69437999014e-3) + llh[2]) * math.sin(lat))


if __name__ == '__main__':
    folder = Path(__file__).resolve().parent / 'output'
    report_path = folder / 'crosscheck.json'
    report_path.unlink(missing_ok=True)
    nmea = (folder / 'gnss-sdr/fix.nmea').read_bytes()
    fixes = [fix for line in nmea.decode().splitlines()
             if (fix := gga(line)) is not None]
    if not fixes:
        raise SystemExit('No valid GGA position fixes; cross-check not passed')
    truth = ecef((35.681298, 139.766247, 10))
    errors = [math.dist(ecef(fix), truth) for fix in fixes]
    own = json.loads((folder / 'position.json').read_text())
    with (folder / 'navigation-extended.bin').open('rb') as stream:
        prefix_hash = hashlib.sha256(stream.read(98_800_000)).hexdigest()
        stream.seek(0)
        source_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
    if prefix_hash != own['fixture_sha256']:
        raise ValueError('Extended file does not match the original 19-second fixture')
    differences = [math.dist(ecef(fix), own['ecef_m']) for fix in fixes]
    report = {'nmea_sha256': hashlib.sha256(nmea).hexdigest(),
              'receiver_version': (folder / 'gnss-sdr/version.txt').read_text().strip(),
              'source_sha256': source_hash, 'original_prefix_sha256': prefix_hash,
              'valid_gga_fixes': len(fixes), 'median_error_3d_m': statistics.median(errors),
              'maximum_error_3d_m': max(errors),
              'median_difference_from_own_solver_m': statistics.median(differences),
              'last_lat_lon_ellipsoid_height': fixes[-1],
              'crosscheck_passed': statistics.median(errors) < 20}
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    if not report['crosscheck_passed']:
        raise SystemExit('GNSS-SDR median error exceeds 20 m')
