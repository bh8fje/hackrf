package local.hackrf.controller;

import java.util.Arrays;
public class PhoneMotionTest {
    static double[][] relativePoints(PhoneMotion m) throws Exception {
        double[][] points=m.points(()->{});double[] offset=m.startupOffset();
        assert Math.hypot(offset[0],offset[1])>=30 && Math.hypot(offset[0],offset[1])<=80+1e-10;
        for(double[] p:points){p[1]-=offset[0];p[2]-=offset[1];}
        return points;
    }
    public static void main(String[] args) throws Exception {
        PhoneMotion offsets=new PhoneMotion();double minimum=Double.POSITIVE_INFINITY,maximum=0;
        java.util.Random seeds=new java.util.Random(20261018);
        for(int i=0;i<10000;i++){
            offsets.seed=seeds.nextInt(Integer.MAX_VALUE);double[] first=offsets.startupOffset();
            assert first[1]<=0 && Math.abs(first[0])<=-first[1]+1e-8;
            double distance=Math.hypot(first[0],first[1]);assert distance>=30-1e-10 && distance<=80+1e-10;
            assert Math.abs(distance*100-Math.rint(distance*100))<1e-8;
            assert Arrays.equals(first,offsets.startupOffset());
            minimum=Math.min(minimum,distance);maximum=Math.max(maximum,distance);
        }
        assert minimum<30.1 && maximum>79.9;
        for(String route:new String[]{"stroll","office","roadside","wander","circle"}){PhoneMotion motion=route.equals("circle")?new PhoneMotion():PhoneMotion.profile(route);motion.mode=route;double[] origin=motion.points(()->{})[0],offset=motion.startupOffset();assert Math.abs(origin[1]-offset[0])<1e-8 && Math.abs(origin[2]-offset[1])<1e-8;}
        PhoneMotion m = new PhoneMotion(); m.drift=0; m.verticalDrift=0; m.minimum=m.maximum=0; m.latitude=m.longitude=0;m.mode="stationary";m.randomHeading=false;m.speedVariation=0;
        assert Arrays.equals(m.origin(),new double[]{6378137,0,0});
        double[][] still=relativePoints(m); for (double[] p : still) assert Math.hypot(p[1],p[2])<=20 && p[3]==0;
        java.io.File csv=java.io.File.createTempFile("ecef-motion",".csv");
        try{m.writePoints(csv,new double[][]{{0,1,2,3}},()->{});
            assert java.nio.file.Files.readString(csv.toPath()).equals("0.0,6378140.000000,1.000000,2.000000\n");
        }finally{csv.delete();}
        m.mode="park";m.duration=600;m.heading=90;
        for (double radius : new double[]{10,50}) {
            m.radius=radius; double[][] p=relativePoints(m), repeat=relativePoints(m);
            assert p.length==6002 && p[1][1]>0;
            for (int i=0;i<p.length;i++) {
                assert Arrays.equals(p[i],repeat[i]); assert Math.hypot(p[i][1],p[i][2])<=radius+1e-8;
                if (i>0) assert Math.hypot(p[i][1]-p[i-1][1],p[i][2]-p[i-1][2])<=m.speed*.1+1e-8;
            }
        }
        m.mode="stationary";m.drift=2;m.minimum=399;m.maximum=401;
        for (double[] p : relativePoints(m)) { assert Math.hypot(p[1],p[2])<=20+1e-8; assert Math.abs(p[3])<=1+1e-8; }
        try { m.points(()->{throw new java.io.IOException("cancelled");}); throw new AssertionError("Cancellation ignored"); } catch (java.io.IOException expected) { }
        m.drift=m.verticalDrift=0;m.minimum=m.maximum=0;m.speedVariation=.2;m.randomHeading=true;m.speed=.5;m.mode="park";
        double[][] smooth=relativePoints(m);for(int i=1;i<smooth.length;i++)assert Math.hypot(smooth[i][1]-smooth[i-1][1],smooth[i][2]-smooth[i-1][2])<=.07000001;
        m.mode="line";m.randomHeading=false;m.heading=90;m.speedVariation=0;
        double[][] line=relativePoints(m);assert Math.abs(line[6000][1]-300)<1e-7 && Math.abs(line[6000][2])<=5;
        m.mode="circle";double centerE=-m.radius*Math.cos(Math.toRadians(m.heading)),centerN=m.radius*Math.sin(Math.toRadians(m.heading));for(double[] p:relativePoints(m))assert Math.abs(Math.hypot(p[1]-centerE,p[2]-centerN)-m.radius)<=2+1e-8;
        m.speed=0;m.mode="park";for(double[] p:relativePoints(m))assert Math.abs(p[1])<1e-8 && Math.abs(p[2])<1e-8;
        m.coordinateSystem="GCJ-02";m.latitude=39.9;m.longitude=116.4;
        double[] wgs=m.wgs84();assert Math.abs(wgs[0]-39.89859741210937)<1e-6 && Math.abs(wgs[1]-116.3937585449219)<1e-6;
        m.headingMinimum=200;m.headingMaximum=100;try{m.validate();throw new AssertionError("Reversed heading accepted");}catch(IllegalArgumentException expected){}m.headingMinimum=0;m.headingMaximum=360;
        m.latitude=Double.NaN; try { m.validate(); throw new AssertionError("NaN accepted"); } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: phone WGS84 origin, static/600-second bounded walking, speed, drift, height and cancellation");
    }
}
