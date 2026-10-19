package local.hackrf.controller;

import java.util.Arrays;
public class PersonMotionTest {
    static void check(PhoneMotion m) throws Exception {
        PhoneMotion.Trace t=m.trace(()->{});double lastE=0,lastN=0;
        for(int i=1;i<t.ideal.length;i++){
            double[] p=t.ideal[i],previous=t.ideal[i-1];double e=(p[1]-previous[1])*10,n=(p[2]-previous[2])*10;
            assert Math.hypot(e,n)<=(m.mode.equals("office")?.71:Math.min(2.5,m.speed+m.speedVariation))+1e-7 : "Speed jump "+m.mode;
            if(i>1)assert Math.hypot(e-lastE,n-lastN)*10<=1.80001 : "Acceleration jump "+m.mode+" "+Math.hypot(e-lastE,n-lastN)*10;
            lastE=e;lastN=n;
            if(!m.mode.equals("roadside"))assert Math.hypot(p[1],p[2])<=(m.mode.equals("office")?4:m.radius)+1e-8;
            if(m.mode.equals("roadside") && !m.randomHeading)assert e*Math.sin(Math.toRadians(m.heading))+n*Math.cos(Math.toRadians(m.heading))>=-1e-7 : "Roadside reversed";
        }
    }
    public static void main(String[] args) throws Exception {
        for(String mode:new String[]{"stroll","office","roadside","wander"}){
            PhoneMotion m=PhoneMotion.profile(mode);m.drift=0;m.verticalDrift=0;
            PhoneMotion.Trace sample=m.trace(()->{});
            assert mode.equals("office") || (sample.stops>=1 && sample.starts>=1 && sample.stoppedSeconds>=1) : "No stop/start behavior "+mode;
            assert Arrays.deepEquals(sample.emitted,m.points(()->{}));
            m.seed++;assert !Arrays.deepEquals(sample.emitted,m.points(()->{}));
            for(int seed=0;seed<12;seed++){m.seed=seed;m.duration=600;check(m);}
            m.speed=0;if(!mode.equals("office"))for(double[] p:m.trace(()->{}).ideal)assert p[1]==0 && p[2]==0;
            m.speed=.5;m.drift=2;PhoneMotion.Trace drift=m.trace(()->{});boolean driftWhileStopped=false;
            for(int i=1;i<drift.ideal.length;i++)if(drift.ideal[i][1]==drift.ideal[i-1][1] && drift.ideal[i][2]==drift.ideal[i-1][2] && Math.hypot(drift.emitted[i][1]-drift.emitted[i-1][1],drift.emitted[i][2]-drift.emitted[i-1][2])>1e-6)driftWhileStopped=true;
            assert driftWhileStopped : "GPS drift disappeared during stops";
            m.pauseMinimum=20;m.pauseMaximum=10;try{m.validate();throw new AssertionError("Reversed pause range accepted");}catch(IllegalArgumentException expected){}
        }
        for(String mode:new String[]{"office","roadside","circle"})for(int seed=0;seed<12;seed++){
            PhoneMotion m=PhoneMotion.profile(mode);m.seed=seed;m.duration=600;m.randomHeading=false;m.heading=37;
            PhoneMotion.Trace trace=m.trace(()->{});double[] offset=m.startupOffset();
            for(double[] p:trace.emitted){double e=p[1]-offset[0],n=p[2]-offset[1];if(mode.equals("office"))assert Math.hypot(e,n)<=20+1e-8;else if(mode.equals("roadside"))assert Math.abs(e*Math.cos(Math.toRadians(37))-n*Math.sin(Math.toRadians(37)))<=5+1e-8;}
            int[] samples=m.observationIndices(trace.emitted.length);assert samples[0]==0 && samples[samples.length-1]==trace.emitted.length-1;
            for(int i=1;i<samples.length-1;i++)assert samples[i]-samples[i-1]>=10 && samples[i]-samples[i-1]<=50;
        }
        for(int seed=0;seed<30;seed++){
            PhoneMotion m=PhoneMotion.profile("office");m.seed=seed;m.duration=600;PhoneMotion.Trace trace=m.trace(()->{});double lastE=0,lastN=0;int seated=0;
            for(int i=1;i<trace.emitted.length;i++){
                double[] p=trace.emitted[i],previous=trace.emitted[i-1];double e=(p[1]-previous[1])*10,n=(p[2]-previous[2])*10;
                assert Math.hypot(e,n)<2 : "Office position speed jump";
                if(i>1)assert Math.hypot(e-lastE,n-lastN)*10<1 : "Office direction jump";
                lastE=e;lastN=n;
                assert Math.hypot(trace.ideal[i][1],trace.ideal[i][2])<=4+1e-8;
                if(Math.hypot(trace.ideal[i][1]-trace.ideal[i-1][1],trace.ideal[i][2]-trace.ideal[i-1][2])<.0001)seated++;
            }
            assert seated>trace.ideal.length*.6;
            check(m);
        }
        PhoneMotion office=PhoneMotion.profile("office");office.duration=600;assert office.trace(()->{}).stoppedSeconds>300;
        PhoneMotion fast=PhoneMotion.profile("office");fast.radius=2;fast.speed=2.5;fast.reactionSeconds=.2;fast.duration=600;check(fast);
        PhoneMotion hold=PhoneMotion.profile("wander");hold.duration=600;hold.walkMinimum=hold.walkMaximum=4;hold.pauseMinimum=hold.pauseMaximum=5;hold.reactionSeconds=5;
        double[][] points=hold.trace(()->{}).ideal;int zeros=0,fullHolds=0;
        for(int i=1;i<points.length;i++){boolean stopped=points[i][1]==points[i-1][1] && points[i][2]==points[i-1][2];if(stopped)zeros++;else if(zeros>0){assert zeros>=49 : "Pause counted deceleration as dwell";fullHolds++;zeros=0;}}
        assert fullHolds>0;
        System.out.println("PASS: four person profiles, 60/600s, 12 seeds, bounded speed/acceleration, stop/restart, office dwell, roadside forward, stationary drift and determinism");
    }
}
