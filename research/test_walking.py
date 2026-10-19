import unittest
import numpy as np
import eviltransform
from walking import DEFAULTS, plan, validate


class WalkingTest(unittest.TestCase):
    def test_coordinate_conversion_and_bounds(self):
        settings = dict(DEFAULTS, latitude=39.9, longitude=116.4, coordinateSystem='GCJ-02', duration=90, height=500)
        ideal, emitted, preview = plan(settings)
        lat, lon = eviltransform.wgs2gcj(preview['wgs84Latitude'], preview['wgs84Longitude'])
        self.assertAlmostEqual(lat, settings['latitude'], delta=1e-6)
        self.assertAlmostEqual(lon, settings['longitude'], delta=1e-6)
        self.assertLessEqual(preview['idealHeightMax'] - 500, 0.08)
        self.assertGreaterEqual(preview['idealHeightMin'] - 500, -0.08)
        self.assertAlmostEqual(preview['maxHorizontalDrift'], 2.5)
        self.assertEqual(len(ideal), 902)
        self.assertFalse(preview['rfEnabled'])
        np.testing.assert_array_equal(emitted, plan(settings)[1])

    def test_zero_drift_and_stationary(self):
        settings = dict(DEFAULTS, route='stationary', heightVariation=0,
                        horizontalDrift=0, verticalDrift=0, coordinateSystem='WGS-84')
        ideal, emitted, preview = plan(settings)
        from walking import enu_basis, startup_offset
        delta=(emitted[:,1:]-ideal[:,1:]) @ enu_basis(settings['latitude'],settings['longitude']).T
        expected=np.r_[startup_offset(settings['seed']),0]
        self.assertLessEqual(np.linalg.norm(delta[:,:2]-expected[:2],axis=1).max(),20)
        self.assertGreater(np.std(delta[:,0]),.1)
        self.assertGreaterEqual(np.linalg.norm(expected),30)
        self.assertLessEqual(np.linalg.norm(expected),80)
        self.assertLessEqual(np.linalg.norm((ideal[:, 1:]-ideal[0,1:]) @ enu_basis(settings['latitude'],settings['longitude']).T,axis=1).max(),4+1e-8)
        self.assertEqual(preview['wgs84Longitude'], settings['longitude'])

    def test_first_point_is_offset_for_every_route(self):
        from walking import startup_offset, enu_basis
        from crosscheck import ecef
        for route in ('stationary','park','circle','line'):
            settings=dict(DEFAULTS,route=route)
            ideal,emitted,_=plan(settings)
            first=(emitted[0,1:]-np.array(ecef((settings['latitude'],settings['longitude'],settings['height'])))) @ enu_basis(settings['latitude'],settings['longitude']).T
            np.testing.assert_allclose(first[:2],startup_offset(settings['seed']),atol=1e-8)

    def test_realistic_routes_and_sampling(self):
        from walking import enu_basis, startup_offset
        for seed in range(12):
            for route in ('stationary','line','circle'):
                settings=dict(DEFAULTS,route=route,seed=seed,duration=600,heading=37,randomHeading=False)
                ideal,emitted,preview=plan(settings)
                basis=enu_basis(settings['latitude'],settings['longitude'])
                path=(emitted[:,1:]-ideal[0,1:]) @ basis.T
                relative=path[:,:2]-startup_offset(seed)
                if route=='stationary':
                    self.assertLessEqual(np.linalg.norm(relative,axis=1).max(),20+1e-8)
                    human=(ideal[:,1:]-ideal[0,1:]) @ basis.T
                    self.assertLessEqual(np.linalg.norm(human[:,:2],axis=1).max(),4+1e-8)
                    self.assertGreater(np.linalg.norm(human[:,:2],axis=1).max(),.5)
                elif route=='line':
                    lateral=relative @ np.array([np.cos(np.radians(37)),-np.sin(np.radians(37))])
                    self.assertLessEqual(np.abs(lateral).max(),5+1e-8)
                    self.assertGreater(np.std(np.diff(lateral)),.001)
                else:
                    gaps=np.diff(preview['sampleTimes'])
                    self.assertGreaterEqual(gaps[:-1].min(),1-1e-8)
                    self.assertLessEqual(gaps.max(),5+1e-8)
                    self.assertGreater(np.std(gaps),.1)
                    self.assertLess(np.linalg.norm(np.diff(path[:,:2],axis=0),axis=1).max(),.5)
                np.testing.assert_array_equal(emitted,plan(settings)[1])

    def test_office_inertia_seated_and_small_walks(self):
        from walking import stationary_drift, office_motion
        for seed in range(30):
            drift=stationary_drift(seed,6002)
            human=office_motion(seed,6002)
            radii=np.linalg.norm(drift,axis=1)
            self.assertLess(np.percentile(radii,95),12)
            self.assertLess(radii.max(),20)
            velocity=np.diff(drift,axis=0)*10
            acceleration=np.diff(velocity,axis=0)*10
            self.assertLess(np.linalg.norm(velocity,axis=1).max(),1.5)
            self.assertLess(np.linalg.norm(acceleration,axis=1).max(),1)
            self.assertLessEqual(np.linalg.norm(human,axis=1).max(),4)
            speeds=np.linalg.norm(np.diff(human,axis=0)*10,axis=1)
            self.assertLess(speeds.max(),.71)
            self.assertGreater(np.count_nonzero(speeds<.001)/len(speeds),.6)
            self.assertGreater(speeds.max(),.3)
            self.assertLess(np.linalg.norm(np.diff(np.diff(human,axis=0)*10,axis=0)*10,axis=1).max(),.8)
            self.assertTrue(np.any(np.all(human[600:]==0,axis=1)))
            np.testing.assert_array_equal(drift,stationary_drift(seed,6002))
            np.testing.assert_array_equal(human,office_motion(seed,6002))
        a=dict(DEFAULTS,route='stationary',duration=600)
        b=dict(a,speed=2,speedVariation=1,radius=40,horizontalDrift=10,verticalDrift=3)
        np.testing.assert_array_equal(plan(a)[1],plan(b)[1])

    def test_invalid_parameters(self):
        for key, value in (('duration', 601), ('duration', 90.5), ('latitude', float('nan')),
                           ('speed', -1), ('seed', True), ('route', 'unknown')):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                validate(dict(DEFAULTS, **{key: value}))
        with self.assertRaises(ValueError):
            plan(dict(DEFAULTS, latitude=-33, longitude=151, coordinateSystem='GCJ-02'))
        with self.assertRaises(ValueError):
            validate({'latitude': 30})

    def test_line_heading_and_seed(self):
        settings = dict(DEFAULTS, route='line', heading=90, randomHeading=False, speed=1.2, speedVariation=0, horizontalDrift=0,
                        verticalDrift=0, heightVariation=0)
        _, emitted, preview = plan(settings)
        self.assertGreater(preview['idealPath'][-1][0], 60)
        self.assertGreater(np.std(np.array(preview['idealPath'])[:,1]), .1)
        self.assertLessEqual(np.abs(np.array(preview['idealPath'])[:,1]).max(),5)
        self.assertFalse(np.array_equal(plan(DEFAULTS.copy())[1], plan(dict(DEFAULTS, seed=43))[1]))

    def test_random_park_is_bounded_and_speed_limited(self):
        from walking import enu_basis
        for radius in (10, 50):
            settings = dict(DEFAULTS, route='park', radius=radius, duration=180, horizontalDrift=0, verticalDrift=0, randomHeading=False)
            ideal, _, _ = plan(settings)
            enu = (ideal[:, 1:] - ideal[0, 1:]) @ enu_basis(settings['latitude'], settings['longitude']).T
            self.assertLessEqual(np.linalg.norm(enu[:, :2], axis=1).max(), radius + 1e-8)
            self.assertLessEqual(np.linalg.norm(np.diff(enu[:, :2], axis=0), axis=1).max(), (settings['speed'] + settings['speedVariation'])*.1 + 1e-8)
            velocity = np.diff(enu[:, :2], axis=0) / .1
            self.assertLessEqual(np.linalg.norm(np.diff(velocity, axis=0), axis=1).max() / .1, 2 * (settings['speed'] + settings['speedVariation']) / settings['turnSmoothing'] + 1e-6)
            self.assertGreater(np.linalg.norm(enu[:, :2], axis=1).max(), 2.0)
            self.assertGreater(enu[1, 1], 0)  # Initial northward heading.
            np.testing.assert_array_equal(ideal, plan(settings)[0])
        still = dict(DEFAULTS, route='park', speed=0)
        ideal, _, _ = plan(still)
        np.testing.assert_allclose(ideal[:, 1:], np.tile(ideal[0, 1:], (len(ideal), 1)), atol=.2)

    def test_random_walking_height_limits(self):
        settings = dict(DEFAULTS, route='park', radius=50, randomHeading=False, horizontalDrift=0, height=400, heightVariation=10, verticalDrift=0)
        ideal, _, preview = plan(settings)
        from walking import enu_basis
        from crosscheck import ecef
        enu = (ideal[:, 1:] - np.array(ecef((settings['latitude'], settings['longitude'], settings['height'])))) @ enu_basis(settings['latitude'], settings['longitude']).T
        self.assertLessEqual(np.linalg.norm(enu[:, :2], axis=1).max(), 50+1e-8)
        self.assertGreater(enu[10, 1], enu[5, 1])
        self.assertGreaterEqual(preview['idealHeightMin'], 390)
        self.assertLessEqual(preview['idealHeightMax'], 410)
        for changes in ({'radius': 9}, {'height': -500}, {'height': 10000}):
            with self.assertRaises(ValueError):
                plan(dict(settings, **changes))

    def test_random_speed_range_and_smoothing(self):
        from walking import random_series
        t = np.arange(601) / 10
        fast = random_series(np.random.default_rng(42), t, .3, .7, .1, initial=.5)
        slow = random_series(np.random.default_rng(42), t, .3, .7, 5, initial=.5)
        self.assertGreaterEqual(slow.min(), .3)
        self.assertLessEqual(slow.max(), .7)
        self.assertGreater(np.std(slow), .001)
        self.assertLess(abs(np.diff(slow)).max(), abs(np.diff(fast)).max())
        fixed = random_series(np.random.default_rng(42), t, .5, .5, 2)
        np.testing.assert_array_equal(fixed, np.full(len(t), .5))

    def test_random_ranges_and_legacy_settings(self):
        for changes in ({'headingMinimum': 300, 'headingMaximum': 20},
                        {'headingHoldMinimum': 5, 'headingHoldMaximum': 2},
                        {'speedSmoothing': 0}, {'randomHeading': 1},
                        {'heightSmoothing': float('nan')}, {'driftSmoothing': -1}):
            with self.assertRaises(ValueError):
                plan(dict(DEFAULTS, **changes))
        legacy = {k: v for k, v in DEFAULTS.items() if k not in
                  ('speedVariation', 'speedSmoothing', 'randomHeading', 'headingMinimum',
                   'headingMaximum', 'headingHoldMinimum', 'headingHoldMaximum',
                   'turnSmoothing', 'heightSmoothing', 'driftSmoothing')}
        plan(legacy)
        for seed in (42, 43):
            s = dict(DEFAULTS, route='line', seed=seed, headingMinimum=90, headingMaximum=90)
            self.assertLessEqual(np.abs(np.array(plan(s)[2]['idealPath'])[:,1]).max(),5)
        a = dict(DEFAULTS, route='park', headingHoldMinimum=1, headingHoldMaximum=1)
        b = dict(a, headingHoldMinimum=20, headingHoldMaximum=20)
        self.assertFalse(np.array_equal(plan(a)[0], plan(b)[0]))

    def test_requested_target_and_duration_endpoints(self):
        self.assertAlmostEqual(DEFAULTS['latitude'], 30 + 39/60 + 29/3600)
        self.assertAlmostEqual(DEFAULTS['longitude'], 104 + 6/60 + 13/3600)
        self.assertEqual(DEFAULTS['coordinateSystem'], 'WGS-84')
        for duration in (60, 180, 600):
            ideal, _, preview = plan(dict(DEFAULTS, duration=duration))
            self.assertEqual(len(ideal), duration*10+2)
            self.assertEqual(preview['estimatedFileMB'], int(duration*5.2))


if __name__ == '__main__':
    unittest.main()
