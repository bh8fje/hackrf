"""Read-only RINEX 3 navigation inventory; no orbit propagation or RF output."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re

SYSTEMS = {'G': ('GPS', 'GPS'), 'C': ('BDS', 'BDT'),
           'R': ('GLONASS', 'UTC'), 'E': ('Galileo', 'GST')}
START = re.compile(r'^([GRECJSI]\d{2}) (\d{4}) +(\d{1,2}) +(\d{1,2}) +(\d{1,2}) +(\d{1,2}) +(\d{1,2})(?=[ +\-])')


def inventory(text, selected=None):
    selected = list(SYSTEMS) if selected is None else list(selected)
    if not selected or len(set(selected)) != len(selected) or any(s not in SYSTEMS for s in selected):
        raise ValueError('Choose distinct systems from G,C,R,E')
    lines = text.splitlines()
    if not lines or 'RINEX VERSION / TYPE' not in lines[0] or not 3 <= float(lines[0][:9]) < 4 or lines[0][20:21] != 'N':
        raise ValueError('Expected RINEX 3 navigation file')
    end = next((i for i, line in enumerate(lines) if line[60:].strip() == 'END OF HEADER'), None)
    if end is None:
        raise ValueError('Missing navigation header end')
    groups = {s: {} for s in selected}
    current = None
    count = 0

    def finish():
        if current is None:
            return
        satellite, epoch = current
        allowed = (4, 5) if satellite[0] == 'R' else (4,) if satellite[0] == 'S' else (8,)
        if count not in allowed:
            raise ValueError(f'Truncated or unsupported record for {satellite}: {count} lines')
        if satellite[0] in groups:
            groups[satellite[0]].setdefault(satellite, []).append(epoch)

    for line in lines[end + 1:]:
        if not line.strip():
            continue
        match = START.match(line)
        if match:
            finish()
            satellite = match[1]
            epoch = datetime(*map(int, match.groups()[1:])).isoformat()
            current, count = (satellite, epoch), 1
        elif current is not None and line.startswith('    '):
            count += 1
        else:
            raise ValueError('Malformed navigation record')
    finish()
    result = {}
    for system, satellites in groups.items():
        epochs = [epoch for values in satellites.values() for epoch in values]
        result[SYSTEMS[system][0]] = {
            'timeScale': SYSTEMS[system][1], 'satelliteCount': len(satellites),
            'recordCount': len(epochs), 'firstEpochNative': min(epochs) if epochs else None,
            'lastEpochNative': max(epochs) if epochs else None,
            'satellites': sorted(satellites)}
    return {'schema': 1, 'analysis': 'navigation-inventory-only', 'systems': result,
            'limitations': ['Epochs remain in each system time scale; not normalized to UTC.',
                            'Record presence does not prove freshness, health, visibility or receiver reception.',
                            'No waveform generation or transmitter control.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('navigation', type=Path)
    parser.add_argument('--systems', nargs='+', choices=SYSTEMS, default=list(SYSTEMS))
    args = parser.parse_args()
    with args.navigation.open('rb') as stream:
        raw = stream.read(20_000_001)
    if len(raw) > 20_000_000:
        parser.error('Navigation file exceeds 20 MB')
    report = inventory(raw.decode('ascii'), args.systems)
    report.update(source=str(args.navigation.resolve()), sha256=hashlib.sha256(raw).hexdigest())
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
