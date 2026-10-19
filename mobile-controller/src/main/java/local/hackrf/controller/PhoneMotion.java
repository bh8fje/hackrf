package local.hackrf.controller;

import java.io.*;
import java.util.Locale;
import java.util.Random;

final class PhoneMotion {
    interface Check { void run() throws IOException; }
    double latitude=30+39/60.0+29/3600.0, longitude=104+6/60.0+13/3600.0;
    double minimum=400, maximum=400, speed=.5, radius=12, heading=0, drift=2.5, verticalDrift=.5;
    double speedVariation=.2, speedSmoothing=2, headingMinimum=0, headingMaximum=360,
            headingHoldMinimum=3, headingHoldMaximum=8, turnSmoothing=2, heightSmoothing=2, driftSmoothing=10;
    double walkMinimum=12,walkMaximum=35,pauseMinimum=3,pauseMaximum=12,reactionSeconds=.8;
    boolean randomHeading=true; String coordinateSystem="WGS-84";
    int duration=60; long seed=42; String mode="circle";
    void validate() {
        for (double n : new double[]{latitude,longitude,minimum,maximum,speed,radius,heading,drift,verticalDrift,speedVariation,speedSmoothing,headingMinimum,headingMaximum,headingHoldMinimum,headingHoldMaximum,turnSmoothing,heightSmoothing,driftSmoothing,walkMinimum,walkMaximum,pauseMinimum,pauseMaximum,reactionSeconds}) if (!Double.isFinite(n)) throw new IllegalArgumentException("参数必须是有限数值");
        if (latitude < -85 || latitude > 85 || longitude < -180 || longitude > 180 || minimum < -500 || maximum > 10000 || minimum > maximum
                || (mode.equals("office") && radius>8) || speed < 0 || speed > 2.5 || radius < ((mode.equals("park") || mode.equals("stroll") || mode.equals("wander"))?10:2) || radius > 50 || heading < 0 || heading > 360 || drift < 0 || drift > 10 || verticalDrift < 0 || verticalDrift > 3
                || duration < 60 || duration > 600 || seed<0 || seed>2147483647L || !(mode.equals("park") || mode.equals("stationary") || mode.equals("circle") || mode.equals("line") || personMode())
                || speedVariation<0 || speedVariation>2.5 || speedSmoothing<.1 || speedSmoothing>60 || headingMinimum<0 || headingMaximum>360 || headingMinimum>headingMaximum
                || headingHoldMinimum<.1 || headingHoldMaximum>600 || headingHoldMinimum>headingHoldMaximum || turnSmoothing<.1 || turnSmoothing>60 || heightSmoothing<.1 || heightSmoothing>60 || driftSmoothing<.1 || driftSmoothing>600
                || walkMinimum<.5 || walkMaximum>600 || walkMinimum>walkMaximum || pauseMinimum<1 || pauseMaximum>600 || pauseMinimum>pauseMaximum || reactionSeconds<.2 || reactionSeconds>5
                || !(coordinateSystem.equals("WGS-84") || coordinateSystem.equals("GCJ-02"))) throw new IllegalArgumentException("请检查经纬度、高度、运动范围、随机区间或时长");
        wgs84();
    }
    boolean personMode(){return mode.equals("stroll") || mode.equals("office") || mode.equals("roadside") || mode.equals("wander");}
    static PhoneMotion profile(String mode){
        PhoneMotion m=new PhoneMotion();m.mode=mode;
        if(mode.equals("stroll")){m.speed=.85;m.speedVariation=.4;m.radius=30;}
        else if(mode.equals("office")){m.speed=0;m.speedVariation=0;m.radius=5;m.walkMinimum=4;m.walkMaximum=10;m.pauseMinimum=20;m.pauseMaximum=90;m.reactionSeconds=.6;}
        else if(mode.equals("roadside")){m.speed=1.2;m.speedVariation=.35;m.radius=30;m.walkMinimum=20;m.walkMaximum=55;m.pauseMinimum=5;m.pauseMaximum=25;m.randomHeading=false;m.reactionSeconds=.6;}
        else if(mode.equals("wander")){m.speed=.65;m.speedVariation=.55;m.radius=20;m.walkMinimum=3;m.walkMaximum=14;m.pauseMinimum=2;m.pauseMaximum=10;m.headingHoldMinimum=1.5;m.headingHoldMaximum=4;}
        else if(mode.equals("circle")){m.speed=1.25;m.speedVariation=.65;m.radius=20;}
        else throw new IllegalArgumentException("不支持的人物场景");
        return m;
    }
    static final class Trace {
        double[][] ideal,emitted;
        double distance,maximumSpeed,stoppedSeconds;int stops,starts;
        void summarize(){int stopped=0;boolean wasMoving=false;for(int i=1;i<ideal.length-1;i++){
            double step=Math.hypot(ideal[i][1]-ideal[i-1][1],ideal[i][2]-ideal[i-1][2]);double speed=step*10;
            distance+=step;maximumSpeed=Math.max(maximumSpeed,speed);
            if(speed<.02){stopped++;stoppedSeconds+=.1;if(stopped==10 && wasMoving){stops++;wasMoving=false;}}
            else {if(!wasMoving && (stopped>=10 || i==1))starts++;wasMoving=true;stopped=0;}
        }}
    }
    private static double uniform(Random rng,double low,double high) {return low+(high-low)*rng.nextDouble();}
    double[] startupOffset() {
        // Separate random stream preserves the existing motion and drift for a seed.
        long value=(1664525L*seed+1013904223L)&0xffffffffL;
        double distance=(3000+(value*5001L/4294967296L))/100.0;
        value=(1664525L*value+1013904223L)&0xffffffffL;
        double angle=Math.toRadians(135+90*value/4294967296.0);
        return new double[]{distance*Math.sin(angle),distance*Math.cos(angle)};
    }
    private double[] series(Random rng,int count,double low,double high,double smoothing,Double initial) {
        double[] targets=new double[duration+1], values=new double[count];
        for(int i=0;i<targets.length;i++) targets[i]=uniform(rng,low,high);
        values[0]=initial==null?targets[0]:Math.max(low,Math.min(high,initial)); double alpha=-Math.expm1(-.1/smoothing);
        for(int i=1;i<count;i++) values[i]=values[i-1]+alpha*(targets[i/10]-values[i-1]);
        return values;
    }
    private double[] wanderingSeries(Random rng,int count,double amplitude){
        double[] result=new double[count];double start=0,end=uniform(rng,8,20),from=0,to=uniform(rng,-amplitude,amplitude);
        for(int i=1;i<count;i++){double t=i/10.0;if(t>end){start=end;end+=uniform(rng,8,20);from=to;to=uniform(rng,-amplitude,amplitude);}double f=(t-start)/(end-start),blend=f*f*(3-2*f);result[i]=from+blend*(to-from);}
        return result;
    }
    private double[][] officeMotion(int count,Check check) throws IOException {
        Random rng=new Random(seed^0x29a73f15L);double[][] path=new double[count][2];
        int phase=0;double start=0,end=uniform(rng,20,55),e=0,n=0,fromE=0,fromN=0,toE=0,toN=0;
        for(int i=1;i<count;i++){
            check.run();double t=i/10.0;
            if(t>=end){
                phase=(phase+1)%4;start=t;fromE=e;fromN=n;
                if(phase==1){double angle=uniform(rng,0,2*Math.PI),distance=uniform(rng,1,4);toE=distance*Math.sin(angle);toN=distance*Math.cos(angle);}
                else if(phase==3){toE=0;toN=0;}
                double seconds=(phase==1 || phase==3)?1.875*Math.hypot(toE-fromE,toN-fromN)/uniform(rng,.35,.7):phase==2?uniform(rng,5,18):uniform(rng,30,100);
                end=t+seconds;
            }
            if(phase==1 || phase==3){double u=Math.min(1,(t-start)/(end-start)),blend=u*u*u*(10-15*u+6*u*u);e=fromE+(toE-fromE)*blend;n=fromN+(toN-fromN)*blend;}
            else {e=phase==2?toE:0;n=phase==2?toN:0;}
            path[i][0]=e;path[i][1]=n;
        }
        return path;
    }
    // shortcut: Java and NumPy use different seeded RNGs; models share constraints, not identical samples.
    double[][] points(Check check) throws IOException {return trace(check).emitted;}
    Trace trace(Check check) throws IOException {
        validate(); int count=duration*10+2;
        Random random=new Random(seed); double[] phase=new double[6];
        for(int i=0;i<6;i++) phase[i]=random.nextDouble()*2*Math.PI;
        double[] speeds=series(random,count,Math.max(0,speed-speedVariation),Math.min(2.5,speed+speedVariation),speedSmoothing,speed);
        if(speed==0) java.util.Arrays.fill(speeds,0);
        double initial=Math.toRadians(randomHeading?uniform(random,headingMinimum,headingMaximum):heading);
        double[] meander=wanderingSeries(random,count,speed==0?0:mode.equals("circle")?Math.min(2,radius*.15):5);
        double[][] values=new double[count][4];
        double east=0,north=0,distance=0,ve=speed*Math.sin(initial),vn=speed*Math.cos(initial);
        double de=.85*radius*Math.sin(initial),dn=.85*radius*Math.cos(initial),next=uniform(random,headingHoldMinimum,headingHoldMaximum),alpha=-Math.expm1(-.1/turnSmoothing);
        for(int i=0;i<count;i++) {
            check.run();double t=i/10.0;values[i][0]=t;
            if(i>0) distance+=speeds[i]*.1;
            if(mode.equals("circle")) {
                double r=radius+meander[i];
                east=r*Math.cos(distance/radius-initial);north=r*Math.sin(distance/radius-initial);
            }
            else if(mode.equals("line")) {
                double lateral=meander[i];
                east=distance*Math.sin(initial)+lateral*Math.cos(initial);north=distance*Math.cos(initial)-lateral*Math.sin(initial);
            }
            else if(mode.equals("park") && i>0) {
                double dx=de-east,dy=dn-north;
                if(t>=next || Math.hypot(dx,dy)<=Math.max(speeds[i],.5)) {
                    double angle=Math.toRadians(uniform(random,headingMinimum,headingMaximum));de=east+radius*Math.sin(angle);dn=north+radius*Math.cos(angle);
                    double scale=Math.min(1,.85*radius/Math.max(Math.hypot(de,dn),1e-30));de*=scale;dn*=scale;
                    if(Math.hypot(de-east,dn-north)<=.5) {de=0;dn=0;}
                    next=t+uniform(random,headingHoldMinimum,headingHoldMaximum);dx=de-east;dy=dn-north;
                }
                double scale=Math.min(speeds[i]/Math.max(Math.hypot(dx,dy),1e-30),1);
                ve+=alpha*(dx*scale-ve);vn+=alpha*(dy*scale-vn);east+=.1*ve;north+=.1*vn;
                scale=Math.min(1,radius/Math.max(Math.hypot(east,north),1e-30));east*=scale;north*=scale;
            }
            values[i][1]=east;values[i][2]=north;
        }
        if(personMode() && !mode.equals("office"))values=personPath(random,count,speeds,meander,initial,check);
        double[] up=series(random,count,-(maximum-minimum)/2,(maximum-minimum)/2,heightSmoothing,null);
        double[][] bias=new double[count][3];double peak=1e-30;
        double[] freq={1/50.0,1/23.0,1/11.0},weight={.6,.3,.1};
        for(int i=0;i<count;i++) {for(int j=0;j<3;j++) {
            double wave=2*Math.PI*values[i][0]*freq[j]*10/driftSmoothing;
            bias[i][0]+=Math.sin(wave+phase[j])*weight[j];bias[i][1]+=Math.cos(wave+phase[j+3])*weight[j];bias[i][2]+=Math.sin(wave+phase[j])*weight[j]*verticalDrift;
        }peak=Math.max(peak,Math.hypot(bias[i][0],bias[i][1]));}
        double east0=values[0][1],north0=values[0][2];
        double[] firstBias=bias[0].clone();peak=1e-30;double verticalPeak=1e-30;
        for(double[] b:bias){for(int axis=0;axis<3;axis++)b[axis]-=firstBias[axis];peak=Math.max(peak,Math.hypot(b[0],b[1]));verticalPeak=Math.max(verticalPeak,Math.abs(b[2]));}
        for(int i=0;i<count;i++){values[i][1]-=east0;values[i][2]-=north0;bias[i][2]*=verticalDrift/verticalPeak;}
        Trace trace=new Trace();trace.ideal=new double[count][4];
        for(int i=0;i<count;i++) {trace.ideal[i]=values[i].clone();trace.ideal[i][3]=up[i];values[i][1]+=bias[i][0]*drift/peak;values[i][2]+=bias[i][1]*drift/peak;values[i][3]=up[i]+bias[i][2];}
        if(mode.equals("office") || mode.equals("stationary")) {
            // Correlated position error with inertia and mean reversion, not human movement.
            double[][] human=officeMotion(count,check);
            Random noise=new Random(seed^0x517cc1b7L);
            double[] position=new double[2],velocity=new double[2],fast=new double[2],slow=new double[2];
            double fastDecay=Math.exp(-.1/1.8),slowDecay=Math.exp(-.1/15);
            for(int i=0;i<count;i++){
                check.run();
                if(i>0)for(int axis=0;axis<2;axis++){
                    fast[axis]=fastDecay*fast[axis]+.12*Math.sqrt(1-fastDecay*fastDecay)*noise.nextGaussian();
                    slow[axis]=slowDecay*slow[axis]+.045*Math.sqrt(1-slowDecay*slowDecay)*noise.nextGaussian();
                    velocity[axis]+=.1*(fast[axis]+slow[axis]-.5*velocity[axis]-.018*position[axis]);
                    position[axis]+=.1*velocity[axis];
                }
                double radius=Math.hypot(position[0],position[1]),scale=radius==0?1:19.99*Math.tanh(radius/19.99)/radius,t=i/10.0;
                double e=human[i][0]+position[0]*scale,n=human[i][1]+position[1]*scale,total=Math.hypot(e,n),bound=total==0?1:19.99*Math.tanh(total/19.99)/total;
                trace.ideal[i]=new double[]{t,human[i][0],human[i][1],0};values[i]=new double[]{t,e*bound,n*bound,0};
            }
        }else if(mode.equals("line") || mode.equals("roadside")) {
            for(double[] point:values){double lateral=point[1]*Math.cos(initial)-point[2]*Math.sin(initial),correction=Math.max(-5,Math.min(5,lateral))-lateral;point[1]+=correction*Math.cos(initial);point[2]-=correction*Math.sin(initial);}
        }
        double[] offset=startupOffset();
        for(double[] point:values){point[1]+=offset[0];point[2]+=offset[1];}
        trace.emitted=values;trace.summarize();return trace;
    }
    private double[][] personPath(Random rng,int count,double[] speeds,double[] meander,double initial,Check check) throws IOException {
        double[][] path=new double[count][4];double east=0,north=0,ve=0,vn=0;
        double bound=mode.equals("office")?Math.min(radius,8):radius;
        double de=.7*bound*Math.sin(initial),dn=.7*bound*Math.cos(initial);
        boolean paused=mode.equals("office"),holdStarted=paused;int journeys=0;double pauseLength=0;
        double deadline=paused?Math.min(8,pauseMinimum*.4):uniform(rng,walkMinimum,walkMaximum),turn=headingHoldMaximum;
        for(int i=1;i<count;i++){
            check.run();double t=i/10.0;boolean arrived=mode.equals("office") && !paused && Math.hypot(de-east,dn-north)<.25;
            if(t>=deadline || arrived){
                paused=!paused;
                if(paused){pauseLength=uniform(rng,pauseMinimum,pauseMaximum);deadline=Double.POSITIVE_INFINITY;holdStarted=false;}
                else {
                    deadline=t+uniform(rng,walkMinimum,walkMaximum);journeys++;
                    double angle=Math.toRadians(uniform(rng,headingMinimum,headingMaximum)),distance=bound*uniform(rng,.35,.75);
                    de=distance*Math.sin(angle);dn=distance*Math.cos(angle);
                    if(mode.equals("office") && journeys%2==0){de=0;dn=0;}
                    turn=t+uniform(rng,headingHoldMinimum,headingHoldMaximum);
                }
            }
            double desiredE=0,desiredN=0;
            if(!paused && speed>0){
                if(mode.equals("roadside")){
                    double lateral=east*Math.cos(initial)-north*Math.sin(initial);
                    double target=meander[i];
                    double sideways=Math.max(-.6,Math.min(.6,(target-lateral)*.7));
                    desiredE=speeds[i]*Math.sin(initial)+sideways*Math.cos(initial);desiredN=speeds[i]*Math.cos(initial)-sideways*Math.sin(initial);
                }else{
                    double dx=de-east,dy=dn-north;
                    if(!mode.equals("office") && (t>=turn || Math.hypot(dx,dy)<.5)){
                        double angle=Math.toRadians(uniform(rng,headingMinimum,headingMaximum));
                        de=east+bound*Math.sin(angle);dn=north+bound*Math.cos(angle);double scale=Math.min(1,.75*bound/Math.max(Math.hypot(de,dn),1e-30));de*=scale;dn*=scale;
                        if(Math.hypot(de-east,dn-north)<.5){de=0;dn=0;}dx=de-east;dy=dn-north;
                        turn=t+uniform(rng,headingHoldMinimum,headingHoldMaximum);
                    }
                    double scale=Math.min(speeds[i]/Math.max(Math.hypot(dx,dy),1e-30),1);desiredE=dx*scale;desiredN=dy*scale;
                }
            }
            double targetNorm=Math.hypot(desiredE,desiredN),limit=Math.min(2.5,speed+speedVariation);
            if(targetNorm>limit){desiredE*=limit/targetNorm;desiredN*=limit/targetNorm;}
            double response=-Math.expm1(-.1/reactionSeconds),ae=(desiredE-ve)*response,an=(desiredN-vn)*response;
            double cap=(paused?1.8:1.2)*.1,scale=Math.min(1,cap/Math.max(Math.hypot(ae,an),1e-30));ve+=ae*scale;vn+=an*scale;
            if((paused || speed==0) && Math.hypot(ve,vn)<.02){ve=0;vn=0;}
            if(paused && !holdStarted && ve==0 && vn==0){deadline=t+pauseLength;holdStarted=true;}
            double nextE=east+.1*ve,nextN=north+.1*vn;
            if(!mode.equals("roadside")){scale=Math.min(1,bound/Math.max(Math.hypot(nextE,nextN),1e-30));nextE*=scale;nextN*=scale;ve=(nextE-east)*10;vn=(nextN-north)*10;}
            east=nextE;north=nextN;path[i]=new double[]{t,east,north,0};
        }return path;
    }
    int[] observationIndices(int count){
        java.util.ArrayList<Integer> indices=new java.util.ArrayList<>();Random rng=new Random(seed+1);int index=0;
        indices.add(0);while(index<count-1){index=Math.min(count-1,index+10+rng.nextInt(41));indices.add(index);}
        int[] result=new int[indices.size()];for(int i=0;i<result.length;i++)result[i]=indices.get(i);return result;
    }
    double[] wgs84() {
        if(coordinateSystem.equals("WGS-84")) return new double[]{latitude,longitude};
        if(longitude<72.004 || longitude>137.8347 || latitude<.8293 || latitude>55.8271) throw new IllegalArgumentException("GCJ-02近似转换仅适用于中国大陆范围");
        double loLat=latitude-.01,hiLat=latitude+.01,loLon=longitude-.01,hiLon=longitude+.01,lat=latitude,lon=longitude;
        for(int i=0;i<30;i++) {lat=(loLat+hiLat)/2;lon=(loLon+hiLon)/2;double[] delta=gcjDelta(lat,lon);double a=lat+delta[0]-latitude,b=lon+delta[1]-longitude;
            if(Math.abs(a)<.000001 && Math.abs(b)<.000001) break;
            if(a>0) hiLat=lat;else loLat=lat;if(b>0) hiLon=lon;else loLon=lon;
        }return new double[]{lat,lon};
    }
    // GCJ conversion adapted from BSD-2-Clause eviltransform; license is bundled in assets.
    private static double[] gcjDelta(double lat,double lon) {
        double x=lon-105,y=lat-35,xy=x*y,root=Math.sqrt(Math.abs(x)),xp=x*Math.PI,yp=y*Math.PI;
        double shared=20*Math.sin(6*xp)+20*Math.sin(2*xp);
        double a=(shared+20*Math.sin(yp)+40*Math.sin(yp/3)+160*Math.sin(yp/12)+320*Math.sin(yp/30))*2/3;
        double b=(shared+20*Math.sin(xp)+40*Math.sin(xp/3)+150*Math.sin(xp/12)+300*Math.sin(xp/30))*2/3;
        a+=-100+2*x+3*y+.2*y*y+.1*xy+.2*root;b+=300+x+2*y+.1*x*x+.1*xy+.1*root;
        double magic=1-.00669342162296594323*Math.pow(Math.sin(Math.toRadians(lat)),2),sqrt=Math.sqrt(magic);
        return new double[]{a*180/(6378137*(1-.00669342162296594323)/(magic*sqrt)*Math.PI),b*180/(6378137/sqrt*Math.cos(Math.toRadians(lat))*Math.PI)};
    }
    double[] origin() {
        double[] wgs=wgs84(); double lat=Math.toRadians(wgs[0]), lon=Math.toRadians(wgs[1]), f=1/298.257223563, e2=f*(2-f);
        double n=6378137/Math.sqrt(1-e2*Math.sin(lat)*Math.sin(lat)), height=(minimum+maximum)/2;
        return new double[]{(n+height)*Math.cos(lat)*Math.cos(lon),(n+height)*Math.cos(lat)*Math.sin(lon),(n*(1-e2)+height)*Math.sin(lat)};
    }
    Trace write(File file, Check check) throws IOException {
        Trace trace=trace(check);writePoints(file,trace.emitted,check);return trace;
    }
    void writePoints(File file,double[][] points,Check check) throws IOException {
        double[] center=origin(); double[] wgs=wgs84(); double lat=Math.toRadians(wgs[0]), lon=Math.toRadians(wgs[1]);
        try (BufferedWriter output=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file),java.nio.charset.StandardCharsets.US_ASCII))) {
            for (double[] p : points) {
                check.run(); double e=p[1],n=p[2],u=p[3];
                double x=center[0]-Math.sin(lon)*e-Math.sin(lat)*Math.cos(lon)*n+Math.cos(lat)*Math.cos(lon)*u;
                double y=center[1]+Math.cos(lon)*e-Math.sin(lat)*Math.sin(lon)*n+Math.cos(lat)*Math.sin(lon)*u;
                double z=center[2]+Math.cos(lat)*n+Math.sin(lat)*u;
                output.write(String.format(Locale.US,"%.1f,%.6f,%.6f,%.6f\n",p[0],x,y,z));
            }
        }
    }
}
