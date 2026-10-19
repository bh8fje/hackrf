"""Offline GPS L1 C/A PCPS acquisition; no device access or navigation solution."""
import json
from pathlib import Path

import numpy as np

RATE = 2_600_000
N = RATE // 1000
# IS-GPS-200, Table 3-Ia: G2 phase selector stages for PRNs 1 through 32.
TAPS = [(2,6),(3,7),(4,8),(5,9),(1,9),(2,10),(1,8),(2,9),(3,10),
        (2,3),(3,4),(5,6),(6,7),(7,8),(8,9),(9,10),(1,4),(2,5),
        (3,6),(4,7),(5,8),(6,9),(1,3),(4,6),(5,7),(6,8),(7,9),
        (8,10),(1,6),(2,7),(3,8),(4,9)]


def ca_code(prn):
    if not 1 <= prn <= 32:
        raise ValueError("PRN must be in 1..32")
    a, b = TAPS[prn - 1]
    g1, g2 = [1] * 10, [1] * 10
    chips = []
    for _ in range(1023):
        chips.append(1 - 2 * (g1[9] ^ g2[a - 1] ^ g2[b - 1]))
        f1 = g1[2] ^ g1[9]
        f2 = g2[1] ^ g2[2] ^ g2[5] ^ g2[7] ^ g2[8] ^ g2[9]
        g1, g2 = [f1] + g1[:-1], [f2] + g2[:-1]
    return np.array(chips)


def acquire(signal, prns=range(1, 33)):
    if len(signal) != N * 10 or not np.isfinite(signal).all():
        raise ValueError("Expected exactly 10 ms of finite complex samples")
    blocks = signal.reshape(10, N)
    time = np.arange(N) / RATE
    chip_indices = np.arange(N) * 1023 // N
    frequencies = np.arange(-5000, 5001, 500)
    spectra = [np.fft.fft(blocks * np.exp(-2j * np.pi * f * time), axis=1)
               for f in frequencies]
    results = []
    for prn in prns:
        local = np.conj(np.fft.fft(ca_code(prn)[chip_indices]))
        grid = np.array([np.sum(abs(np.fft.ifft(s * local, axis=1)) ** 2, axis=0)
                         for s in spectra])
        row, phase = np.unravel_index(np.argmax(grid), grid.shape)
        power = grid[row]
        distance = abs(np.arange(N) - phase)
        outside = np.minimum(distance, N - distance) > int(np.ceil(N / 1023))
        peak = float(power[phase])
        ratio = peak / max(float(power[outside].max()), 1e-30)
        background = peak / max(float(power.mean()), 1e-30)
        # shortcut: heuristic threshold for this clean fixture, not a calibrated Pfa.
        results.append({"prn": prn, "doppler_hz": int(frequencies[row]),
                        "code_phase_samples": int(phase), "peak_ratio": ratio,
                        "peak_to_mean": background,
                        "detected": bool(ratio > 2.5 and background > 20)})
    return results


if __name__ == "__main__":
    folder = Path(__file__).resolve().parent / "output"
    with (folder / "smoke.bin").open("rb") as stream:
        raw = np.frombuffer(stream.read(N * 10 * 2), dtype=np.int8)
    if raw.size != N * 10 * 2:
        raise ValueError("I/Q file is shorter than 10 ms")
    signal = raw[::2].astype(float) + 1j * raw[1::2]
    report = {"method": "10 x 1 ms noncoherent PCPS, +/-5 kHz, 500 Hz bins",
              "results": acquire(signal), "pvt_verified": False}
    (folder / "acquisition.json").write_text(json.dumps(report, indent=2) + "\n")
    for item in report["results"]:
        if item["detected"]:
            print(f"PRN {item['prn']:02}: {item['doppler_hz']:+5} Hz, "
                  f"peak ratio {item['peak_ratio']:.1f}")
