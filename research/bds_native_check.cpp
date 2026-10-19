#include <cmath>
#include <new>
#include <iostream>
#include <stdexcept>
#include <limits>
#include <thread>
#include <vector>
#include <atomic>
#include <algorithm>
#include <array>
#include <cstdio>
#include "JsonInterpreter.h"
#include "NavData.h"
#include "Rinex.h"
#include "GnssTime.h"
#include "Trajectory.h"
#include "GNavBit.h"
#include "GalileoCboc.h"
#include "SatelliteParam.h"
#include "Coordinate.h"
#include "INavBit.h"
struct RecordedIono:CIonoDelay {
    double time=0;
    double GetDelay(double t,double,double,double,double) override {time=t;return 10;}
};
static void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
int main(int argc,char** argv){try{
    for(int system=0;system<4;system++)for(int enabled=0;enabled<2;enabled++)for(bool pretty:{false,true}){
        const char* systems[]={"GPS","BDS","Galileo","GLONASS"};const char* signals[]={"L1CA","B1I","E1","G1"};int bit=system==BdsSystem?SIGNAL_INDEX_B1I:0;
        std::array<std::string,3> fields={std::string("\"system\":\"")+systems[system]+"\"",std::string("\"signal\":\"")+signals[system]+"\"",std::string("\"enable\":")+(enabled?"true":"false")};
        std::array<int,3> order={0,1,2};do{
            // shortcut: pinned tokenizer has 256-byte line buffers; keep entries on separate lines until it handles split tokens.
            std::string json="{\"output\":{\"systemSelect\":[\n";for(auto s:systems)json+=std::string("{\"system\":\"")+s+"\",\"enable\":false},\n";
            json+=std::string("{\"system\":\"")+systems[system]+"\",\"signal\":\""+signals[system]+"\",\"enable\":true},\n{";
            for(int i=0;i<3;i++){if(i)json+=pretty?",\n":",";json+=fields[order[i]];}json+=pretty?"\n}]}}":"}]}}";
            FILE* input=tmpfile();require(input!=nullptr,"JSON test file");fwrite(json.data(),1,json.size(),input);rewind(input);JsonStream tree;int code=tree.ReadFile(input);fclose(input);require(code==0,"Compact and pretty JSON parsed");
            OUTPUT_PARAM output={};output.FreqSelect[system]=enabled?0:1<<bit;AssignParameters(tree.GetRootObject(),nullptr,nullptr,nullptr,nullptr,nullptr,&output,nullptr,nullptr);
            for(int s=0;s<4;s++)if(output.FreqSelect[s]!=(s==system&&enabled?1<<bit:0))throw std::runtime_error("System/signal/enable order: "+json+"; system="+std::to_string(s)+" bits="+std::to_string(output.FreqSelect[s]));
        }while(std::next_permutation(order.begin(),order.end()));
    }
    {
        INavBit nav;int expected[8][3][500];const int seconds[]={5,17,25};
        for(int channel=0;channel<8;channel++)for(int page=0;page<3;page++) {
            GNSS_TIME t={2440,(30*channel+seconds[page])*1000,0};nav.GetFrameData(t,channel+1,1,expected[channel][page]);
        }
        std::atomic<bool> matched{true};std::vector<std::thread> workers;
        for(int channel=0;channel<8;channel++)workers.emplace_back([&,channel]{
            for(int repeat=0;repeat<100;repeat++)for(int page=0;page<3;page++) {
                GNSS_TIME t={2440,(30*channel+seconds[page])*1000,0};int actual[500];nav.GetFrameData(t,channel+1,1,actual);
                for(int bit=0;bit<500;bit++)if(actual[bit]!=expected[channel][page][bit])matched=false;
            }
        });
        for(auto& worker:workers)worker.join();require(matched,"Concurrent Galileo WN/TOW frames match serial output");
    }
    const double a=std::sqrt(10./11),b=std::sqrt(1./11);
    const double dataExpected[]={a+b,a-b,a+b,a-b,a+b,a-b,-a+b,-a-b,-a+b,-a-b,-a+b,-a-b};
    const double pilotExpected[]={a-b,a+b,a-b,a+b,a-b,a+b,-a-b,-a+b,-a-b,-a+b,-a-b,-a+b};
    double power=0;
    for(int i=0;i<12;i++){
        auto data=GalileoCboc((i+.5)/6,complex_number(1,0),complex_number(0,0));
        auto pilot=GalileoCboc((i+.5)/6,complex_number(0,0),complex_number(1,0));
        require(std::abs(data.real-dataExpected[i])<1e-12 && std::abs(pilot.real-pilotExpected[i])<1e-12,"CBOC ICD subcarrier polarity");
        power+=data.real*data.real+pilot.real*pilot.real;
    }
    require(std::abs(power/24-1)<1e-12,"CBOC normalized component power");
    UTC_TIME calendar={2026,10,17,11,20,0};auto glot=UtcToGlonassTime(calendar);
    require(glot.LeapYear==8 && glot.Day==1021,"GLONASS current four-year interval and day");
    {
        GLONASS_EPHEMERIS eph={};eph.flag=1;eph.n=1;eph.tb=14*3600+20*60;eph.x=25000000;eph.y=5000000;eph.z=10000000;eph.gamma=2e-10;
        RecordedIono iono;CSatelliteParam p;KINEMATIC_INFO receiver={};receiver.x=6378137;LLA_POSITION lla={};
        for(int channel:{-7,0,6}) {
            eph.freq=channel;p.Initialize(GlonassSystem,(PGPS_EPHEMERIS)&eph,&iono,47,ElevationAdjustNone);
            p.CalculateParam(receiver,lla,UtcToGpsTime(calendar));
            require(std::abs(std::fmod(iono.time,86400)-(11*3600+20*60+18))<.2,"GLONASS Klobuchar receives GPS seconds, not UTC+3h");
            require(std::isfinite(p.RelativeSpeed) && std::abs(p.RelativeSpeed)<10000,"GLONASS typed clock drift");
            double withDrift=p.RelativeSpeed;eph.gamma=0;p.CalculateParam(receiver,lla,UtcToGpsTime(calendar));
            require(std::abs(withDrift-p.RelativeSpeed+LIGHT_SPEED*2e-10)<1e-8,"GLONASS Doppler uses gamma, not a GPS ephemeris field");eph.gamma=2e-10;
            for(int signal:{SIGNAL_INDEX_G1,SIGNAL_INDEX_G2}) {
                require(p.GroupDelay[signal]==0,"GLONASS group delays initialized");
                double frequency=signal==SIGNAL_INDEX_G1?1602e6+562500*channel:1246e6+437500*channel;
                double ratio=1575.42e6/frequency;
                require(std::abs((p.GetTravelTime(signal)-p.TravelTime)*LIGHT_SPEED-10*ratio*ratio)<1e-8,"GLONASS FDMA squared-frequency ionosphere delay");
            }
        }
    }
    GNavBit gnav;require(gnav.SetSystemTime(glot)==0,"GNAV calendar setup without almanac");
    require(gnav.SetTimeOffsets(std::numeric_limits<double>::quiet_NaN(),0)!=0 && gnav.SetTimeOffsets(0,.002)!=0,"GNAV time model rejects nonfinite and overflow");
    for(int sign:{-1,1})for(int frame=0;frame<5;frame++){
        require(gnav.SetTimeOffsets(sign*3./2147483648.,-sign*6./1073741824.)==0,"GNAV time offset setup");
        GNSS_TIME t={2440,frame*30000+8000,0};int symbols[200],bits[85];gnav.GetFrameData(t,1,0,symbols);
        bits[0]=symbols[0];for(int i=1;i<85;i++)bits[i]=symbols[i*2]^symbols[(i-1)*2];
        auto read=[&](int first,int count){unsigned long long value=0;for(int i=0;i<count;i++)value=(value<<1)|bits[first-1+i];return value;};
        require(read(2,4)==5 && read(6,11)==1021 && read(50,5)==8,"GNAV string5 calendar bits in every frame");
        require(read(17,1)==(sign<0) && read(18,31)==3 && read(55,1)==(sign>0) && read(56,21)==6,"GNAV signed magnitude and tauC/tauGPS bit scales");
    }
    for(int seconds:{60,90,180}){
        CTrajectory t;KINEMATIC_INFO p={0};p.x=6378137;t.SetInitPosVel(p);
        require(t.AppendTrajectory(TrajTypeConstSpeed,TrajDataTimeSpan,seconds,TrajDataUnknown,0)==0,"trajectory setup");
        t.ResetTrajectoryTime();int count=0;while(t.GetNextPosVelECEF(.001,p))count++;
        require(count==seconds*1000,"millisecond endpoint/sample count");
    }
    UTC_TIME utc={2026,10,17,6,27,25};auto bdt=UtcToBdsTime(utc);auto gps=UtcToGpsTime(utc);
    require(bdt.Week==1084 && bdt.MilliSeconds==541649000,"BDT current epoch");
    require((gps.Week-bdt.Week)*604800LL+(gps.MilliSeconds-bdt.MilliSeconds)/1000LL==1356LL*604800+14,"GPS/BDT difference");
    utc={2026,10,17,23,59,57};bdt=UtcToBdsTime(utc);require(bdt.Week==1085 && bdt.MilliSeconds==1000,"BDT week rollover");
    {
        alignas(CNavData) unsigned char storage[sizeof(CNavData)];
        memset(storage,0xff,sizeof(storage));auto nav=new(storage) CNavData;
        for(int hour=0;hour<24;hour++)require(nav->GetBdsIono(hour)->flag==0,"Absent BDS ionosphere slots remain invalid");
        nav->~CNavData();
    }
    for(bool beta:{false,true})for(bool timed:{false,true}){
        FILE* input=tmpfile();require(input!=nullptr,"BDS header fixture");
        fprintf(input,"%-53s%-7s%s\n",beta?"BDSB   .1290D+06  -.3113D+06   .1245D+07  -.7209D+06":"BDSA   .3166D-07   .1043D-06  -.1252D-05   .1967D-05",timed?"G 06":"","IONOSPHERIC CORR");
        rewind(input);IONO_PARAM iono={};auto type=LoadNavFileHeader(input,&iono);fclose(input);
        require(type==(beta?NavDataBdsIonoB:NavDataBdsIonoA),"BDS header type");
        require(iono.flag==(timed?(6<<8)+6:0),"Optional BDS time/SVID excludes header label");
        require(beta?std::abs(iono.b0-129000)<1e-8:std::abs(iono.a0-3.166e-8)<1e-18,"BDS header coefficient value");
    }
    if(argc>1){CNavData nav;nav.ReadNavFile(argv[1]);auto iono=nav.GetBdsIono(6);require((iono->flag&1)!=0,"BDS model pair decoded");require(std::isfinite(iono->b0)&&iono->b0>10000,"BDS beta coefficient decoded");
        PGPS_EPHEMERIS eph=nullptr;GNSS_TIME time={};
        for(int prn=1;prn<=63&&!eph;prn++)eph=nav.FindEphemeris(BdsSystem,time,prn,1);
        require(eph!=nullptr,"BDS orbit fixture available");KINEMATIC_INFO position={};
        for(double age:{-7200.,-3601.,3601.,7200.}) {
            require(GpsSatPosSpeedEph(BdsSystem,eph->toe+age,eph,&position,nullptr),"BDS two-hour propagation boundary");
            require(std::isfinite(position.x)&&std::isfinite(position.y)&&std::isfinite(position.z),"BDS finite propagated orbit");
        }
        for(double age:{-7201.,7201.})require(!GpsSatPosSpeedEph(BdsSystem,eph->toe+age,eph,&position,nullptr),"Expired BDS orbit rejected");
    }
    if(argc>2){CNavData nav;require(nav.GetGlonassUtcParam()->flag==0 && nav.GetGlonassGpsParam()->flag==0,"Absent GLONASS models remain invalid");nav.ReadNavFile(argv[2]);require((nav.GetGlonassUtcParam()->flag&1) && (nav.GetGlonassGpsParam()->flag&1),"GLUT/GLGP headers decoded");require(std::abs(nav.GetGlonassUtcParam()->A0+1.3969838619e-9)<1e-19 && std::abs(nav.GetGlonassGpsParam()->A0-5.5879354477e-9)<1e-19,"RINEX time offset signs retained");}
    if(argc>3){CNavData nav;nav.ReadNavFile(argv[3]);auto iono=(PIONO_NEQUICK)nav.GetGalileoIono();require(std::abs(iono->ai0-114.25)<1e-12 && std::abs(iono->ai1+.0625)<1e-12 && std::abs(iono->ai2+.0043335)<1e-12 && iono->flag==17,"Galileo RINEX3 NeQuick coefficients and five region flags stored");}
    std::cout<<"PASS: exact sample windows, BDT/GPS/UTC rollover, BDS models, GNAV calendar and signed time offsets, GLONASS delays and Doppler, Galileo CBOC subcarriers\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
