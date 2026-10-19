"""Generate and check a fixed, offline-only one-second GPS I/Q fixture."""
from array import array
import hashlib
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent
source = root / "gps-sdr-sim"
output = root / "output"
output.mkdir(exist_ok=True)
iq = output / "smoke.bin"
subprocess.run(["make", "-C", str(source), "CC=clang"], check=True)
# shortcut: upstream omits one 0.1-second block; remove compensation after its fix.
command = [str(source / "gps-sdr-sim"), "-e", str(source / "brdc0010.22n"),
           "-l", "35.681298,139.766247,10", "-t", "2022/01/01,00:00:00",
           "-d", "1.1", "-s", "2600000", "-b", "8", "-o", str(iq)]
with (output / "generator.log").open("w") as log:
    subprocess.run(command, stdout=log, stderr=log, check=True, timeout=60)
data = iq.read_bytes()
if len(data) != 5_200_000:
    raise ValueError(f"Expected 5200000 bytes, got {len(data)}")
samples = array("b", data)
report = {"sample_rate_hz": 2_600_000, "duration_seconds": 1,
          "format": "interleaved signed int8 I,Q", "bytes": len(data),
          "sha256": hashlib.sha256(data).hexdigest(), "channels": {}}
for offset, name in enumerate("IQ"):
    values = samples[offset::2]
    if not min(values) < 0 < max(values):
        raise ValueError(f"{name} is missing bipolar samples")
    report["channels"][name] = {
        "min": min(values), "max": max(values),
        "mean": sum(values) / len(values),
        "rms": (sum(v * v for v in values) / len(values)) ** 0.5,
    }
report["validation"] = "size and amplitude only; acquisition and PVT unverified"
(output / "smoke.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
