"""Height datum, sign and shared GPS/BDS trajectory checks; no RF."""
import unittest
from unittest.mock import patch
import numpy as np
from geoid import undulation,grid
from walking import DEFAULTS,plan
from crosscheck import ecef

class GeoidTest(unittest.TestCase):
    def test_independent_bilinear_reference(self):
        # Independently checked with PyGeodesy GeoidKarney(kind=2), not required at runtime.
        for lat,lon,expected in [(0,0,17.226),(38.89761,-77.03637,-32.95271636940474),
                                 (51.503368,-.127721,46.08435170892787),(90,0,14.898),(-90,0,-30.15)]:
            self.assertAlmostEqual(undulation(lat,lon),expected,places=8)
        self.assertAlmostEqual(undulation(0,-180),undulation(0,180),places=10)
        for lat,lon in [(91,0),(0,181),(float('nan'),0)]:
            with self.assertRaises(ValueError):undulation(lat,lon)

    def test_conversion_once_and_shared_trajectory(self):
        from bds_lab import configuration as bds_configuration,geodetic
        from mixed_lab import configuration as mixed_configuration
        from datetime import datetime,timezone
        from pathlib import Path
        settings=dict(DEFAULTS,latitude=38.89761,longitude=-77.03637,height=100,route='stationary',heightVariation=0,verticalDrift=0,horizontalDrift=0)
        ideal,emitted,preview=plan(settings)
        h=100+undulation(settings['latitude'],settings['longitude'])
        self.assertAlmostEqual(preview['ellipsoidHeight'],h)
        self.assertEqual(settings['height'],100)
        self.assertEqual(preview['inputHeightMSL'],100)
        np.testing.assert_allclose(geodetic(emitted[0,1:])[2],h,atol=.01)
        start=datetime(2026,10,20,tzinfo=timezone.utc)
        for configure in [bds_configuration,mixed_configuration]:
            config,_,both,p=configure(settings,Path('unused.rnx'),Path('unused.bin'),start)
            np.testing.assert_array_equal(emitted,both)
            self.assertAlmostEqual(p['ellipsoidHeight'],h)
            position=config['trajectory']['initPosition']
            self.assertAlmostEqual(position['altitude'],geodetic(emitted[0,1:])[2],places=6)

    def test_missing_grid_never_falls_back_to_sea_height(self):
        grid.cache_clear()
        try:
            with patch('geoid.MODEL') as path:
                path.read_bytes.side_effect=FileNotFoundError()
                with self.assertRaisesRegex(ValueError,'模型缺失'):undulation(0,0)
        finally:grid.cache_clear()

if __name__=='__main__':unittest.main()
