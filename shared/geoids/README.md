# EGM2008 vertical reference grid

`egm2008-5.pgm` is GeographicLib's 5-arc-minute sampled EGM2008 geoid grid, based on the NGA Earth Gravitational Model. The original PGM comments and metadata are preserved.

- Model documentation / original distribution: https://geographiclib.sourceforge.io/C++/doc/geoid.html
- Original archive: https://sourceforge.net/projects/geographiclib/files/geoids-distrib/egm2008-5.tar.bz2
- Download mirror used: https://github.com/nagix/sea-level-rise-3d-map/blob/main/egm2008-5.pgm
- Mirror Git blob: `01f6deb7f6c67a46a4773dab67350d97fe72edf1`
- SHA-256: `96d55e88db186ddae892b00c5f8cc42a37cdac8ddb69b6de00af8c75dffb34a3`
- Grid: 4320 × 2161, unsigned big-endian 16-bit samples, north-to-south from 90° N and eastward from 0° longitude; offset −108 m, scale 0.003 m.

The macOS Python trajectory path interprets its height input as EGM2008 orthometric height H and uses h = H + N to obtain WGS84 ellipsoidal height. It evaluates N at the WGS84 route origin after horizontal coordinate conversion and uses that correction for the local walking scenario. It does not claim a national vertical-datum transformation or remove terrain/building-model uncertainty.

Bilinear interpolation is used. GeographicLib reports a maximum interpolation/quantization error of 0.478 m and RMS 0.012 m relative to the underlying EGM2008 model for this grid; these are not real-world terrain or receiver-accuracy guarantees. Independent interpolation checks used PyGeodesy GeoidKarney(kind=2), which is not a runtime dependency.

The desktop GPS, BDS and combined generators share the converted ECEF trajectory. The Android native trajectory implementation is separate and is not changed by this macOS conversion.
