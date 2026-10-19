import unittest
import numpy as np
from crosscheck import ecef
from dynamic import compare, elapsed_seconds, trajectory


class DynamicTest(unittest.TestCase):
    def test_trajectory_geometry(self):
        motion = trajectory()
        origin = np.array(ecef((35.681298, 139.766247, 10)))
        np.testing.assert_allclose(motion[0, 1:], origin, atol=1e-8)
        np.testing.assert_allclose(motion[600, 1:], origin, atol=1e-8)
        self.assertAlmostEqual(np.linalg.norm(motion[300, 1:] - origin), 200, places=7)
        speed = np.linalg.norm(np.diff(motion[:, 1:], axis=0), axis=1) * 10
        np.testing.assert_allclose(speed, 10.47193, atol=1e-5)

    def test_gps_utc_midnight_alignment(self):
        self.assertEqual(elapsed_seconds('235942.00'), 0)
        self.assertAlmostEqual(elapsed_seconds('000018.10'), 36.1)
        self.assertEqual(elapsed_seconds('000042.00'), 60)
        for utc in ('240000', '126000', '125960', 'bad', '0000nan'):
            with self.assertRaises(ValueError):
                elapsed_seconds(utc)

    def test_user_30_metre_limit_keeps_full_error(self):
        motion=np.column_stack((np.arange(101)/10,np.zeros((101,3))))
        points=motion.copy();points[:,1]=30
        result=compare(points.tolist(),motion)
        self.assertTrue(result['passed']);self.assertEqual(result['maximum_error_3d_m'],30)
        points[-1,1]=30.01
        self.assertFalse(compare(points.tolist(),motion)['passed'])

    def test_alignment_error_is_detected(self):
        motion = trajectory()
        observations = motion[200:401].copy()
        result = compare(observations.tolist(), motion)
        self.assertTrue(result['passed'])
        self.assertEqual(result['maximum_error_3d_m'], 0)
        observations[:, 0] += 10
        self.assertFalse(compare(observations.tolist(), motion)['passed'])
        self.assertFalse(compare([], motion)['passed'])
        observations[1, 0] = observations[0, 0]
        with self.assertRaises(ValueError):
            compare(observations.tolist(), motion)


if __name__ == '__main__':
    unittest.main()
