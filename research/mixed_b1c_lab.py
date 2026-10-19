"""Unverified bandwidth-limited B1C research configuration, separate from proven B1I."""
from mixed_lab import configuration as mixed_configuration

RATE = 8_000_000
FREQUENCY_HZ = 1_575_420_000
LIMITATIONS = [
    'SignalSim currently generates B1C BOC(1,1) components and omits the quadrature BOC(6,1) pilot component.',
    'This is not a complete ICD B1C QMBOC waveform or a certified receiver-front-end equivalent.',
    '8 Msps preserves the BOC(1,1) main lobes in principle; anti-alias filtering and independent decoding remain unverified.',
    'GPS 47 and BDS 44 dBHz are configured weights, not calibrated receiver C/N0 or guaranteed GPS priority.',
    'Existing B1I reception acceptance does not validate B1C or joint GPS+B1C positioning.'
]


def configuration(settings, navigation, output, start):
    config, ideal, emitted, preview = mixed_configuration(settings, navigation, output, start)
    config['description'] = 'Unverified GPS L1CA + BDS B1C BOC11-only offline research profile'
    config['output'].update(sampleFreq=RATE/1e6, centerFreq=FREQUENCY_HZ/1e6)
    for entry in config['output']['systemSelect']:
        if entry['system'] == 'BDS':
            entry['signal'] = 'B1C'
    audit = {'profile': 'gps-b1c-boc11-limited-research', 'signal': 'GPS-L1CA+BDS-B1C',
             'sampleRate': RATE, 'frequencyHz': FREQUENCY_HZ,
             'estimatedFileBytes': int(settings['duration'])*RATE*2,
             'fullQmboc': False, 'boc6PilotComponentPresent': False,
             'receiverVerified': False, 'hardwareOutputAllowed': False,
             'antiAliasFilteringVerified': False, 'passed': False,
             'requiredReceiverQmboc': False, 'limitations': LIMITATIONS.copy()}
    preview.update(estimatedFileMB=audit['estimatedFileBytes']/1e6, sampleRate=RATE,
                   frequencyHz=FREQUENCY_HZ, fullQmboc=False,
                   receiverVerified=False, hardwareOutputAllowed=False)
    return config, ideal, emitted, preview, audit


def prepare(settings, folder):
    """Write current common navigation and offline configuration only; generate no I/Q."""
    from datetime import datetime
    import json
    from pathlib import Path
    import numpy as np
    from mixed_navigation import prepare as prepare_navigation
    from walking import validate
    validate(settings)
    folder = Path(folder).resolve()
    folder.mkdir(parents=True, exist_ok=False)
    navigation, timing = prepare_navigation(folder, int(settings['duration']))
    config, ideal, emitted, preview, audit = configuration(
        settings, navigation, folder/'signal.bin', datetime.fromisoformat(timing['startUTC']))
    for name, points in (('ideal.csv', ideal), ('emitted.csv', emitted)):
        np.savetxt(folder/name, points, delimiter=',', fmt=['%.1f', '%.6f', '%.6f', '%.6f'])
    audit.update(navigation=timing, status='configuration-only', rfEnabled=False)
    for name, value in (('scenario.json', config), ('preview.json', preview), ('experiment.json', audit)):
        (folder/name).write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n')
    return audit
