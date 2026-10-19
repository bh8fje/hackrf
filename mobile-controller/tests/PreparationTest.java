package local.hackrf.controller;
public class PreparationTest {
    public static void main(String[] args){
        assert Preparation.streamLeadSeconds(238)==3;
        assert Preparation.streamLeadSeconds(4832)==8;
        assert Preparation.streamLeadSeconds(Long.MAX_VALUE)==120;
        assert Preparation.nextStreamReadyMillis(8000,4832)==6400;
        long learned=Preparation.nextStreamReadyMillis(6400,4832);assert learned==5120;
        assert Preparation.nextStreamReadyMillis(learned,4832)==4832;
        assert !Preparation.streamLate(10000,11000)&&Preparation.streamLate(10000,11001);
        int builds=0;
        for(int attempt=0;attempt<2;attempt++){
            builds++;
            long ready=attempt==0?11214:10500;
            if(Preparation.streamLate(10000,ready)&&Preparation.retryStream(attempt,false,false,true))continue;
            break;
        }
        assert builds==2;
        assert !Preparation.retryStream(1,false,false,true);
        assert !Preparation.retryStream(0,true,false,true);
        assert !Preparation.retryStream(0,false,true,true);
        assert !Preparation.retryStream(0,false,false,false);
        assert Preparation.leadSeconds(90,Double.NaN)==16;
        assert Preparation.leadSeconds(600,.18)==75;
        assert Preparation.leadSeconds(60,.01)==6;
        assert Preparation.leadSeconds(600,3)==210;
        assert Preparation.bdsLeadSeconds(90,Double.NaN)==162;
        assert Preparation.bdsLeadSeconds(180,2)==255;
        assert Preparation.bdsLeadSeconds(180,20)==600;
        assert Preparation.bdsLeadSeconds(600,.1)==45;
        assert Preparation.bdsLeadSeconds(600,2)==600;
        try{Preparation.bdsLeadSeconds(601,2);throw new AssertionError("Duration overflow accepted");}catch(IllegalArgumentException expected){}
        assert Preparation.bdsLeadSeconds(90,.1)==11;
        assert Math.abs(Preparation.channelRate(1,9,15)-15.0/9)<1e-12;
        assert Preparation.channelRate(1,15,9)==1;
        assert Preparation.channelRate(1,0,15)==1;
        double rate=Preparation.nextRate(90,133000,4);
        int lead=Preparation.bdsLeadSeconds(90,rate);
        assert lead==160;
        assert Preparation.bdsLeadSeconds(90,Preparation.nextRate(90,100000,2.4))>=114;
        assert Preparation.nextRate(90,40000,rate)<rate;
        assert Preparation.leadSeconds(90,Preparation.nextRate(90,40000,rate))==45;
        assert Preparation.leadSeconds(60,1e308)==210;
        assert Preparation.bdsLeadSeconds(90,1e308)==600;
        assert Preparation.nextRate(90,0,.18)==.18;
        for(int i=0;i<SignalProfile.ROUTES.length;i++)assert SignalProfile.forRoute(SignalProfile.ROUTES[i])==SignalProfile.PROFILES[i];
        assert SignalProfile.forRoute("office")==3;assert SignalProfile.forRoute("stroll")==1;assert SignalProfile.forRoute("roadside")==2;assert SignalProfile.forRoute("wander")==0;
        for(int i=0;i<4;i++)assert SignalProfile.index(SignalProfile.IDS[i])==i;
        try{SignalProfile.index("indoor-deep");throw new AssertionError();}catch(IllegalArgumentException ok){}
        System.out.println("PASS adaptive 5-30s reserve, faster-device adaptation, legacy rate compatibility, overflow caps and motion profiles");
    }
}
