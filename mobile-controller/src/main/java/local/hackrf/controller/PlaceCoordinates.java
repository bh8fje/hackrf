package local.hackrf.controller;

/** Explicit longitude-first input; never guesses or swaps the coordinate order. */
final class PlaceCoordinates {
    static double[] parse(String text) {
        String value=text.trim().replace('，',',').replace('；',';');
        if(!value.matches("[+\\-0-9.eE,;\\s]+"))return null;
        String[] parts=value.split("[,;\\s]+",-1);
        if(parts.length!=2)throw new IllegalArgumentException("经纬度格式：经度,纬度，例如 104.0668,30.5728");
        double lon,lat;
        try{lon=Double.parseDouble(parts[0]);lat=Double.parseDouble(parts[1]);}
        catch(NumberFormatException error){throw new IllegalArgumentException("请填写有效的经度和纬度");}
        if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>85||Math.abs(lon)>180)throw new IllegalArgumentException("经度须在−180至180，纬度须在−85至85之间");
        return new double[]{lat,lon};
    }
}
