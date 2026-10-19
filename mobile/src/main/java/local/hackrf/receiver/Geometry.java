package local.hackrf.receiver;

final class Geometry {
    private static final double RADIUS = 6371008.8;

    static double distance(double lat, double lon, double targetLat, double targetLon) {
        double a = Math.pow(Math.sin(Math.toRadians(lat - targetLat) / 2), 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(targetLat))
                * Math.pow(Math.sin(Math.toRadians(lon - targetLon) / 2), 2);
        return 2 * RADIUS * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))));
    }

    static double[] point(double lat, double lon, double targetLat, double targetLon) {
        double delta = Math.IEEEremainder(lon - targetLon, 360);
        return new double[]{RADIUS * Math.toRadians(delta) * Math.cos(Math.toRadians(targetLat)),
                RADIUS * Math.toRadians(lat - targetLat)};
    }

    static String targetText(double value) {
        return Double.isFinite(value) ? String.format(java.util.Locale.US, "%.8f", value) : "";
    }

    static boolean validTarget(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    static int svidLimit(int constellation) {
        switch(constellation){case 1:return 32;case 3:return 24;case 5:return 63;case 6:return 36;default:throw new IllegalArgumentException("不支持的场景星座");}
    }
    static boolean usedWithSignal(boolean used,float cn0) {return used && Float.isFinite(cn0) && cn0>0;}
    static boolean matchesScenario(double errorM, double ageSeconds, int matchingSvids, boolean inWindow, boolean mock) {
        return inWindow && !mock && Double.isFinite(errorM) && errorM <= 30
                && ageSeconds >= -0.5 && ageSeconds <= 3 && matchingSvids >= 4;
    }
}
