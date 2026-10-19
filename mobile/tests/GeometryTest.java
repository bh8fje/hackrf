package local.hackrf.receiver;

public class GeometryTest {
    public static void main(String[] args) {
        assert Geometry.targetText(Double.NaN).isEmpty();
        assert Geometry.targetText(Double.POSITIVE_INFINITY).isEmpty();
        assert Geometry.targetText(0).equals("0.00000000");
        assert Geometry.targetText(-0.125).equals("-0.12500000");
        assert !Geometry.validTarget(Double.NaN, Double.NaN);
        assert Geometry.distance(30, 104, 30, 104) == 0;
        assert Math.abs(Geometry.distance(0, 0, 0, 1) - 111195.08) < 0.1;
        assert Math.abs(Geometry.distance(0, 179.999, 0, -179.999) - 222.39) < 0.1;
        double[] p = Geometry.point(30.0001, 104.0001, 30, 104);
        assert p[0] > 9 && p[0] < 10 && p[1] > 11 && p[1] < 12;
        assert Math.abs(Geometry.point(0, 179.999, 0, -179.999)[0]) < 223;
        assert !Geometry.validTarget(Double.NaN, 104);
        assert !Geometry.validTarget(91, 104);
        assert Geometry.validTarget(-33.86, 151.21);
        assert Geometry.svidLimit(1)==32 && Geometry.svidLimit(3)==24 && Geometry.svidLimit(5)==63 && Geometry.svidLimit(6)==36;
        assert Geometry.usedWithSignal(true,35);
        assert !Geometry.usedWithSignal(true,0) && !Geometry.usedWithSignal(false,35);
        assert !Geometry.usedWithSignal(true,Float.NaN) && !Geometry.usedWithSignal(true,Float.POSITIVE_INFINITY);
        try{Geometry.svidLimit(0);throw new AssertionError();}catch(IllegalArgumentException expected){}
        assert Geometry.matchesScenario(1, 0.2, 4, true, false);
        assert !Geometry.matchesScenario(1, 4, 12, true, false);
        assert Geometry.matchesScenario(30, 0, 12, true, false);
        assert !Geometry.matchesScenario(30.01, 0, 12, true, false);
        assert !Geometry.matchesScenario(1, 0, 3, true, false);
        assert !Geometry.matchesScenario(1, 0, 12, false, false);
        assert !Geometry.matchesScenario(1, 0, 12, true, true);
        System.out.println("PASS: target bounds, distance, projection and dateline");
    }
}
