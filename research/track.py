"""Offline, single-channel DLL/Costas tracking of the fixed clean fixture."""
import json
from pathlib import Path
import numpy as np
from acquire import RATE, N, ca_code, acquire


def track(raw, acquisition):
    if raw.ndim != 1 or raw.size < N * 40 or raw.size % (N * 2) or raw.dtype != np.int8:
        raise ValueError("Expected complete milliseconds of interleaved int8 I/Q")
    if not acquisition['detected']:
        raise ValueError("Tracking requires a detected acquisition")
    code = ca_code(acquisition['prn'])
    phase = -acquisition['code_phase_samples'] * 1023 / N
    frequency = float(acquisition['doppler_hz'])
    initial = raw[:N * 20]
    signal = initial[::2].astype(float) + 1j * initial[1::2]
    initial_time = np.arange(N * 10) / RATE
    chips = phase + 1_023_000 * (1 + frequency / 1_575_420_000) * initial_time
    replica = code[np.floor(chips).astype(int) % 1023]
    initial_prompts = (signal * replica * np.exp(-2j * np.pi * frequency * initial_time)).reshape(10, N).sum(axis=1)
    # Squaring adjacent prompt products removes BPSK data-bit sign changes.
    products = initial_prompts[1:] * initial_prompts[:-1].conj()
    frequency += np.angle(np.sum(products**2)) / (4 * np.pi * 0.001)
    # The squared discriminator aliases every 500 Hz; resolve neighboring branches by power.
    candidates = frequency + np.array([-500, 0, 500])
    powers = [np.sum(abs((signal * replica * np.exp(-2j * np.pi * f * initial_time))
                        .reshape(10, N).sum(axis=1))**2) for f in candidates]
    frequency = float(candidates[np.argmax(powers)])
    carrier = 0.0
    # shortcut: fixed loop gains for clean static fixtures; tune before RF/dynamic use.
    time = np.arange(N) / RATE
    prompts, frequencies, errors, phases = [], [], [], []
    for offset in range(0, raw.size, 2 * N):
        block = raw[offset:offset + 2 * N]
        phases.append(phase % 1023)
        signal = block[::2].astype(float) + 1j * block[1::2]
        baseband = signal * np.exp(-1j * (carrier + 2 * np.pi * frequency * time))
        chips = phase + 1_023_000 * (1 + frequency / 1_575_420_000) * time
        early, prompt, late = [np.sum(baseband * code[np.floor(chips + d).astype(int) % 1023])
                               for d in (0.5, 0, -0.5)]
        error = float(np.arctan2(prompt.imag * np.sign(prompt.real), abs(prompt.real)))
        denominator = abs(early) + abs(late)
        dll = (abs(early) - abs(late)) / max(denominator, 1e-30)
        phase = (phase + 1023 * (1 + frequency / 1_575_420_000) + 0.15 * dll) % 1023
        carrier = (carrier + 2 * np.pi * frequency * 0.001 + 0.5 * error) % (2 * np.pi)
        frequency += 4 * error
        prompts.append(prompt)
        frequencies.append(frequency)
        errors.append(error)
    return np.array(prompts), np.array(frequencies), np.array(errors), np.array(phases)


if __name__ == '__main__':
    folder = Path(__file__).resolve().parent / 'output'
    raw = np.memmap(folder / 'navigation.bin', dtype=np.int8, mode='r')
    first = raw[:N * 20]
    signal = first[::2].astype(float) + 1j * first[1::2]
    acquisition = acquire(signal, [24])[0]
    prompts, frequencies, errors, phases = track(raw, acquisition)
    np.savez(folder / 'tracking.npz', prompts=prompts, frequencies=frequencies, errors=errors, phases=phases)
    report = {'prn': 24, 'milliseconds': len(prompts),
              'final_doppler_hz': float(frequencies[-1]),
              'last_second_costas_error_rms_rad': float(np.sqrt(np.mean(errors[-1000:] ** 2))),
              'pvt_verified': False}
    (folder / 'tracking.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
