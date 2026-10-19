"""GPS LNAV word parity and subframe synchronization, offline only."""
import json
from pathlib import Path
import numpy as np

# IS-GPS-200 parity equations: data bits d1..d24 and previous D29/D30.
PARITY = [
    (0, (1,2,3,5,6,10,11,12,13,14,17,18,20,23)),
    (1, (2,3,4,6,7,11,12,13,14,15,18,19,21,24)),
    (0, (1,3,4,5,7,8,12,13,14,15,16,19,20,22)),
    (1, (2,4,5,6,8,9,13,14,15,16,17,20,21,23)),
    (1, (1,3,5,6,7,9,10,14,15,16,17,18,21,22,24)),
    (0, (3,5,6,8,9,10,11,13,15,19,22,23,24)),
]


def decode_word(bits, previous):
    if len(bits) != 30 or len(previous) != 2 or any(x not in (0, 1) for x in (*bits, *previous)):
        raise ValueError('Expected 30 binary bits and two previous parity bits')
    data = [int(x) ^ previous[1] for x in bits[:24]]
    parity = [(previous[p] + sum(data[i - 1] for i in indices)) % 2
              for p, indices in PARITY]
    if parity != list(bits[24:]):
        raise ValueError('LNAV parity mismatch')
    return data


def integer(bits):
    return int(''.join(str(int(x)) for x in bits), 2)


def synchronize(prompts):
    if prompts.ndim != 1 or len(prompts) < 6020 or not np.isfinite(prompts).all():
        raise ValueError('Need at least 6020 finite 1-ms prompts')
    found = {}
    for offset in range(20):
        count = (len(prompts) - offset) // 20
        symbols = prompts.real[offset:offset + count * 20].reshape(-1, 20).sum(axis=1)
        for polarity in (1, -1):
            bits = (symbols * polarity < 0).astype(int).tolist()
            for start in range(len(bits) - 299):
                # First data word may be inverted by the preceding D30.
                if integer(bits[start:start + 8]) not in (0x8b, 0x74):
                    continue
                for seed in ((0,0), (0,1), (1,0), (1,1)):
                    previous = seed
                    words = []
                    try:
                        for word_index in range(10):
                            pos = start + 30 * word_index
                            word = bits[pos:pos + 30]
                            words.append(decode_word(word, previous))
                            previous = word[-2:]
                    except ValueError:
                        continue
                    subframe = integer(words[1][19:22])
                    tow = integer(words[1][:17]) * 6
                    if integer(words[0][:8]) != 0x8b or not 1 <= subframe <= 5 or tow >= 604800:
                        continue
                    record = {'subframe_id': subframe, 'next_tow_seconds': tow,
                              'start_ms': start * 20 + offset, 'valid_words': 10,
                              'bit_offset_ms': offset, 'polarity': polarity,
                              'data_words_hex': [f'{integer(w):06x}' for w in words]}
                    key = (tow, subframe)
                    # Prefer the alignment with strongest coherent symbol sums.
                    strength = float(np.sum(abs(symbols[start:start + 300])))
                    if key not in found or strength > found[key][0]:
                        found[key] = (strength, record)
    return sorted((value[1] for value in found.values()), key=lambda r: r['start_ms'])


if __name__ == '__main__':
    folder = Path(__file__).resolve().parent / 'output'
    with np.load(folder / 'tracking.npz') as data:
        frames = synchronize(data['prompts'])
    report = {'prn': 24, 'subframes': frames, 'pvt_verified': False}
    (folder / 'telemetry.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    if not frames:
        raise SystemExit('No parity-valid subframes found')
