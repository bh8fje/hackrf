"""Offline GPS L1 C/A + BDS B1I configuration; hardware acceptance is separate."""
from datetime import timedelta
import numpy as np
from bds_lab import configuration as bds_configuration

BASEBAND_RATES = {'GPS': 2_600_000, 'BDS': 5_000_000}
RATE = 18_000_000
FREQUENCY_HZ = 1_567_747_500
CARRIERS_HZ = {'GPS': 1_575_420_000, 'BDS': 1_561_098_000}
MAIN_LOBE_HALF_WIDTH_HZ = {'GPS': 1_023_000, 'BDS': 2_046_000}


def configuration(settings, navigation, output, start):
    """Use one native generator, one UTC epoch and one sampled motion file."""
    if start.tzinfo is None or start.utcoffset() != timedelta(0) or start.microsecond:
        raise ValueError('Mixed signal requires a whole-second UTC epoch')
    config, ideal, emitted, preview = bds_configuration(settings, navigation, output, start)
    config['description'] = 'GPS L1 C/A primary + BDS B1I auxiliary, common UTC and sampled trajectory'
    config['output'].update(sampleFreq=RATE / 1e6, centerFreq=FREQUENCY_HZ / 1e6)
    config['output']['systemSelect'] = [
        {'system': 'GPS', 'signal': 'L1CA', 'enable': True},
        {'system': 'BDS', 'signal': 'B1I', 'enable': True},
        {'system': 'Galileo', 'enable': False},
        {'system': 'GLONASS', 'enable': False}]
    # SignalSim processes object members in order: system must precede powerValue.
    config['power']['signalPower'] = [
        {'system': 'GPS', 'powerValue': {'unit': 'dBHz', 'value': 47, 'time': 0}},
        {'system': 'BDS', 'powerValue': {'unit': 'dBHz', 'value': 44, 'time': 0}}]
    preview.update(estimatedFileMB=int(settings['duration'] * RATE * 2 / 1e6),
                   sampleRate=RATE, frequencyHz=FREQUENCY_HZ,
                   gpsOverBdsDb=3,
                   receiverPriorityGuaranteed=False,
                   hardwareOutputAllowed=False)
    return config, ideal, emitted, preview


def inspect_iq8(raw):
    """Bounded sample diagnostics; neither spectrum nor clipping proves GNSS decoding."""
    if not raw or len(raw) % 2:
        raise ValueError('Need complete nonempty interleaved IQ8 samples')
    values = np.frombuffer(raw, dtype=np.int8).astype(np.float64)
    complex_samples = values[::2] + 1j * values[1::2]
    return {'samples': len(complex_samples),
            'rms': float(np.sqrt(np.mean(abs(complex_samples) ** 2))),
            'clippedComponents': int(np.count_nonzero((values == -128) | (values == 127))),
            'dcI': float(np.mean(values[::2])), 'dcQ': float(np.mean(values[1::2]))}


def run(settings, folder, execute, emit):
    """Generate an offline artifact; decoding and physical output are deliberately gated."""
    from datetime import datetime
    import hashlib
    import json
    from pathlib import Path
    import shutil
    from bds_lab import ROOT
    from mixed_navigation import prepare
    from walking import signal_environment, validate

    validate(settings)
    duration = int(settings['duration'])
    folder = Path(folder).resolve()
    folder.parent.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(folder.parent).free < duration * sum(BASEBAND_RATES.values()) * 2 + 500_000_000:
        raise ValueError('Insufficient storage for mixed IQ8 experiment')
    folder.mkdir(exist_ok=False)
    metadata = {'signal': 'GPS-L1CA+BDS-B1I', 'mode': 'current-broadcast',
                'status': 'preparing', 'passed': False, 'softwareReceiverVerified': False,
                'hardwareOutputAllowed': False, 'rfEnabled': False,
                'sampleRate': RATE, 'frequencyHz': FREQUENCY_HZ,
                'durationSeconds': duration, 'settings': settings,
                'configuredGpsOverBdsDb': 3, 'relativeRfPowerVerified': False,
                'storageFormat': 'separate-baseband-iq8', 'basebandRates': BASEBAND_RATES,
                'estimatedFileBytes': duration * sum(BASEBAND_RATES.values()) * 2,
                'receiverPriorityGuaranteed': False}
    manifest = folder / 'experiment.json'
    manifest.write_text(json.dumps(metadata, ensure_ascii=False, indent=2)+'\n')
    try:
        emit('progress', fraction=.02, message='正在准备GPS与北斗共同时标的当前星历；本轮仅离线生成')
        navigation, timing = prepare(folder, duration)
        start = datetime.fromisoformat(timing['startUTC'])
        emit('progress', fraction=.1, message='正在构建GPS与北斗共用原生生成器')
        execute([str(ROOT/'.venv/bin/python'), str(ROOT/'build_bds_generator.py')], folder, 'build.log', 240)
        iq = folder / 'wideband-not-generated.bin'
        config, ideal, emitted, preview = configuration(settings, navigation, iq, start)
        for name, points in (('ideal.csv', ideal), ('emitted.csv', emitted)):
            np.savetxt(folder/name, points, delimiter=',', fmt=['%.1f', '%.6f', '%.6f', '%.6f'])
        preview['estimatedFileMB'] = duration * sum(BASEBAND_RATES.values()) * 2 / 1e6
        preview['storageFormat'] = 'separate-baseband-iq8'
        (folder/'preview.json').write_text(json.dumps(preview, ensure_ascii=False, indent=2)+'\n')
        environment = signal_environment(settings)
        metadata.update(navigation=timing, startUTC=start.isoformat(), signalEnvironment=environment,
                        sourceSHA256=hashlib.sha256(navigation.read_bytes()).hexdigest())
        manifest.write_text(json.dumps(metadata, ensure_ascii=False, indent=2)+'\n')
        emit('preview', preview=preview)
        import copy
        artifacts = {}
        for system, rate in BASEBAND_RATES.items():
            name = system.lower()
            iq = folder / (name+'.bin')
            component = copy.deepcopy(config)
            component['output'].update(sampleFreq=rate/1e6, centerFreq=CARRIERS_HZ[system]/1e6, name=str(iq))
            for entry in component['output']['systemSelect']:
                entry['enable'] = entry['system'] == system
            scenario = folder / ('scenario-'+name+'.json')
            scenario.write_text(json.dumps(component, indent=2)+'\n')
            emit('progress', fraction=.2 if system == 'GPS' else .55,
                 message='正在生成'+system+'紧凑基带样本；共用UTC与轨迹，尚未通过联合接收验证')
            execute(['/usr/bin/env', 'OMP_NUM_THREADS=4', 'OMP_WAIT_POLICY=PASSIVE', 'OMP_DYNAMIC=FALSE',
                     str(ROOT/'output/bds-build-current/IFdataGen'), '-c', str(scenario),
                     '-u', str(folder/'emitted.csv'), '-mt', '-Q', str(environment['profileIndex']),
                     '-S', str(environment['seed'])], folder, 'generator-'+name+'.log', max(240, duration*20))
            if iq.stat().st_size != duration * rate * 2:
                raise ValueError(system+' generated file length mismatch')
            with iq.open('rb') as stream:
                diagnostic = inspect_iq8(stream.read(max(2, rate//10*2)))
            if diagnostic['rms'] == 0:
                raise ValueError(system+' generated sample is silent')
            with iq.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            artifacts[system] = {'filename': iq.name, 'sampleRate': rate, 'frequencyHz': CARRIERS_HZ[system],
                                 'bytes': iq.stat().st_size, 'iqSHA256': digest, 'sampleDiagnostics': diagnostic}
        metadata.update(status='generated-only', components=artifacts,
                        folder=str(folder), limitations=['Independent joint GNSS decoding is required before hardware output.',
                        'Separate AGC and noise realizations do not certify a 3 dB relative RF power difference.',
                        'The final mixer must normalize components and validate spectral images before output.',
                        'Real-time mixed output still requires 18 Msps and 36 MB/s USB throughput.'])
        manifest.write_text(json.dumps(metadata, ensure_ascii=False, indent=2)+'\n')
        return metadata
    except Exception as error:
        metadata.update(status='failed', error=str(error))
        manifest.write_text(json.dumps(metadata, ensure_ascii=False, indent=2)+'\n')
        raise
