package local.hackrf.controller;

final class Preparation {
    static int streamLeadSeconds(long readyMillis){
        if(readyMillis<0)throw new IllegalArgumentException("实时准备计时无效");
        return (int)Math.max(3,Math.min(120,Math.ceil(readyMillis/1000.0*1.2)+2));
    }
    static long nextStreamReadyMillis(long previous,long observed){
        if(previous<0||observed<0)throw new IllegalArgumentException("实时准备计时无效");
        return Math.max(observed,(long)(previous*.8));
    }
    static boolean streamLate(long startMillis,long nowMillis){return nowMillis-startMillis>1000;}
    static boolean retryStream(int attempt,boolean cancelled,boolean radioAttempted,boolean usbPresent){return attempt==0&&!cancelled&&!radioAttempted&&usbPresent;}

    static int leadSeconds(int duration,double secondsPerSceneSecond) {
        if(duration<60 || duration>600)throw new IllegalArgumentException("时长无效");
        double rate=Double.isFinite(secondsPerSceneSecond)&&secondsPerSceneSecond>0?secondsPerSceneSecond:.18;
        // Stored rates include the legacy 1.6 margin; retain a bounded 10% timing reserve.
        double seconds=duration*rate/1.6;
        return (int)Math.ceil(Math.min(210,seconds+Math.max(5,Math.min(30,seconds*.1))));
    }
    static int bdsLeadSeconds(int duration,double secondsPerSceneSecond) {
        if(duration<90 || duration>600)throw new IllegalArgumentException("北斗时长无效");
        double rate=Double.isFinite(secondsPerSceneSecond)&&secondsPerSceneSecond>0?secondsPerSceneSecond:2.4;
        double seconds=duration*rate/1.6;
        return (int)Math.ceil(Math.min(600,seconds+Math.max(5,Math.min(30,seconds*.2))));
    }
    static double channelRate(double rate,int previousChannels,int channels){
        if(channels<1||channels>63||previousChannels<0||previousChannels>63)throw new IllegalArgumentException("卫星通道数无效");
        if(!Double.isFinite(rate)||rate<=0)rate=2.4;
        return previousChannels==0?rate:rate*Math.max(1,channels/(double)previousChannels);
    }
    static double nextRate(int duration,long elapsedMillis,double previous) {
        if(duration<60||duration>600||elapsedMillis<0)throw new IllegalArgumentException("准备计时无效");
        return elapsedMillis>0?elapsedMillis/1000.0/duration*1.6:(Double.isFinite(previous)&&previous>0?previous:.18);
    }
}
