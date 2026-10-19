"""Build pinned SignalSim with recorded BDS model fixes, leaving upstream untouched."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT=Path(__file__).resolve().parent
SOURCE=ROOT/'SignalSim'
REVISION='baba9deb68ed193efb9fd9a362b431ae8c491fa4'
NDK=Path(__import__('os').environ.get('ANDROID_NDK_HOME', str(Path.home()/'Library/Android/sdk/ndk/29.0.14206865')))


def patched_files():
    actual=subprocess.check_output(['git','-C',str(SOURCE),'rev-parse','HEAD'],text=True).strip()
    if actual!=REVISION or subprocess.check_output(['git','-C',str(SOURCE),'diff','HEAD','--'],text=True):
        raise ValueError('SignalSim source differs from pinned revision')
    files={str(p.relative_to(SOURCE)):p.read_text() for directory in ('inc','src')
           for p in (SOURCE/directory).iterdir() if p.suffix in ('.h','.cpp','.dat')}
    files['IFdataGen.cpp']=(SOURCE/'IFdataGen/IFdataGen.cpp').read_text()
    def replace(file,old,new,count=1):
        if files[file].count(old)!=count:raise ValueError('Upstream BDS patch anchor changed: '+file)
        files[file]=files[file].replace(old,new)
    # Match navigation preflight and ephemeris selection; hourly refresh is not an orbit discontinuity.
    replace('src/Coordinate.cpp','\telse if ((fabs(delta_t) > 3600.0) && system == BdsSystem)\n\t\treturn false;\n','')
    interpreter=files['src/JsonInterpreter.cpp']
    begin=interpreter.index('\nBOOL ProcessSystemSelect(JsonObject *Object, OUTPUT_PARAM &OutputParam)\n{')
    end=interpreter.index('\nBOOL SetPowerControl(',begin)
    files['src/JsonInterpreter.cpp']=interpreter[:begin]+'''
BOOL ProcessSystemSelect(JsonObject *Object, OUTPUT_PARAM &OutputParam)
{
    int system=GpsSystem,signal=-1;
    JsonObject *enabled=nullptr;
    for(;Object;Object=Object->GetNextObject()) {
        switch(SearchDictionary(Object->Key,PARAMETER(KeyDictionaryListOutput))) {
        case 8:
            if(Object->Type==JsonObject::ValueTypeString)
                system=SearchDictionary(Object->String,PARAMETER(DictionaryListSystem))-1;
            break;
        case 10:
            if(Object->Type==JsonObject::ValueTypeString)
                signal=SearchDictionary(Object->String,PARAMETER(DictionaryListSignal));
            break;
        case 11:enabled=Object;break;
        }
    }
    // JSON object member order cannot select a different carrier.
    if(!enabled || system<0 || (signal>=0 && signal/8!=system))return TRUE;
    signal=signal<0?0:signal%8;
    if(enabled->Type==JsonObject::ValueTypeTrue)OutputParam.FreqSelect[system]|=1<<signal;
    else if(enabled->Type==JsonObject::ValueTypeFalse)OutputParam.FreqSelect[system]&=~(1<<signal);
    return TRUE;
}
''' +interpreter[end:]
    replace('src/JsonParser.cpp',"while (*p != '\\0' && *p != ',' && !(IsWhiteSpace(*p)))", "while (*p != '\\0' && *p != ',' && *p != '}' && *p != ']' && !(IsWhiteSpace(*p)))")
    name='src/Rinex.cpp'
    replace(name,'''\t\t\tsscanf(str + 4, "%lf %lf %lf", &(Iono->a0), &(Iono->a1), &(Iono->a2));
\t\t\treturn NavDataGalileoIono;''', '''            auto gal = (PIONO_NEQUICK)NavData;
            double flags;
            if(sscanf(str+4,"%lf %lf %lf %lf",&gal->ai0,&gal->ai1,&gal->ai2,&flags)!=4 ||
               !isfinite(gal->ai0) || !isfinite(gal->ai1) || !isfinite(gal->ai2) ||
               !isfinite(flags) || flags<0 || flags>31 || floor(flags)!=flags) return NavDataUnknown;
            gal->flag=(unsigned long)flags;
\t\t\treturn NavDataGalileoIono;''')
    replace('src/NavData.cpp','\tcase NavDataIonGalileo:', '\tcase NavDataGalileoIono:\n\tcase NavDataIonGalileo:')
    replace('src/NavData.cpp','\tmemset(GalileoAlmanac, 0, sizeof(GalileoAlmanac));','\tmemset(GalileoAlmanac, 0, sizeof(GalileoAlmanac));\n\tmemset(&GalileoIono, 0, sizeof(GalileoIono));')
    replace('src/INavBit.cpp','unsigned int *Data;\t// 128bit Data','unsigned int Data[4];\t// per-channel copy keeps WN/TOW writes out of shared templates')
    replace('src/INavBit.cpp','Data = GetWordData(svid, Word, subframe, TOW);','memcpy(Data,GetWordData(svid, Word, subframe, TOW),sizeof(Data));')
    replace('IFdataGen.cpp','#include "SignalSim.h"','#include "SignalSim.h"\n#include "FastMath.h"')
    replace('IFdataGen.cpp','\tauto start_time = std::chrono::high_resolution_clock::now();','\tFastMath::InitializeLUT(); // initialize shared tables before parallel workers\n\tauto start_time = std::chrono::high_resolution_clock::now();')
    marker='\t\t\treturn NavDataBdsIonoB;'
    before,after=files[name].split(marker)
    index=before.rindex('else if (strstr(str, "BDSA") == str)')
    tail=before[index:].replace('"BDSA"','"BDSB"')
    for i in range(4):tail=tail.replace(f'Iono->a{i}',f'Iono->b{i}')
    files[name]=before[:index]+tail+marker+after
    replace(name,'char str[256], TimeMark;','char str[256], TimeMark = \'A\';')
    replace(name,'\tint Svid;','\tint Svid = 0;')
    replace(name,"Iono->flag = (Svid << 8) + TimeMark - 'A';","Iono->flag = (Svid << 8) + ((TimeMark >= 'A' && TimeMark <= 'X') ? TimeMark - 'A' : 0);",2)
    replace('src/NavData.cpp','\tmemset(BdsAlmanac, 0, sizeof(BdsAlmanac));',
            '\tmemset(BdsAlmanac, 0, sizeof(BdsAlmanac));\n\tmemset(BdsIono, 0, sizeof(BdsIono));')
    for coefficient in ('a0','b0'):
        scan='sscanf(str + 4, "%lf %lf %lf %lf %c %d", &(Iono->'+coefficient+')'
        replace(name,scan,'str[60] = 0; '+scan)
    replace('inc/NavData.h','PIONO_PARAM GetBdsIono() { return &BdsIono[0]; }',
            'PIONO_PARAM GetBdsIono(int hour=23) { for(int i=hour;i>=0;i--) if(BdsIono[i].flag & 1) return &BdsIono[i]; return &BdsIono[0]; }')
    replace('IFdataGen.cpp','CIonoKlobuchar8 IonoModel(NavData.GetGpsIono());',
            'CIonoKlobuchar8 IonoModel(NavData.GetGpsIono());\n\tCIonoKlobuchar8 BdsIonoModel(NavData.GetBdsIono());')
    files['IFdataGen.cpp']=files['IFdataGen.cpp'].replace('NavData.GetBdsIono()', 'NavData.GetBdsIono(BdsTime.MilliSeconds / 3600000)')
    replace('IFdataGen.cpp','Initialize(BdsSystem, BdsEph[i], &IonoModel,',
            'Initialize(BdsSystem, BdsEph[i], &BdsIonoModel,')
    replace('src/Trajectory.cpp','RelativeTime > m_pCurrentTrajectory->m_TimeSpan','RelativeTime - m_pCurrentTrajectory->m_TimeSpan > 1e-8')
    replace('src/SatelliteParam.cpp','\tTimeTag = -1;', '\tTimeTag = -1;\n\tfor(auto& delay:GroupDelay) delay=0;')
    replace('src/SatelliteParam.cpp','\tdouble Distance, SatelliteTime, WeightPrev;', '\tdouble Distance, SatelliteTime, WeightPrev;\n\tdouble gpsSeconds=(time.MilliSeconds+time.SubMilliSeconds)/1000.0;')
    replace('src/SatelliteParam.cpp','IonoDelayModel->GetDelay(SatelliteTime,', 'IonoDelayModel->GetDelay(system==GlonassSystem ? gpsSeconds-TravelTime : SatelliteTime,')
    replace('src/SatelliteParam.cpp','LIGHT_SPEED * EphCur->af1;', 'LIGHT_SPEED * (system==GlonassSystem ? GloEphCur->gamma : EphCur->af1);')
    replace('src/SatelliteParam.cpp','double CSatelliteParam::GetIonoDelayFactor(int SignalIndex)\n{', '''double CSatelliteParam::GetIonoDelayFactor(int SignalIndex)
{
    if(system==GlonassSystem) {
        double ratio=FREQ_GPS_L1*GetWaveLength(SignalIndex)/LIGHT_SPEED;
        return ratio*ratio;
    }''')
    replace('src/SatIfSignal.cpp','\tCurIntPhase = (unsigned int)std::floor(CurPhase * 4294967296.);',
            '\tCurPhase -= std::floor(CurPhase); // wrap FDMA half-cycle before unsigned conversion\n\tCurIntPhase = (unsigned int)std::floor(CurPhase * 4294967296.);')
    replace('inc/Rinex.h','NavDataGalileoGps, NavDataLeapSecond,','NavDataGalileoGps, NavDataLeapSecond, NavDataGlonassUtc, NavDataGlonassGps,')
    replace('src/Rinex.cpp','\t\telse if (strstr(str, "GAUT") == str)', '''        else if (strstr(str, "GLUT") == str || strstr(str, "GLGP") == str)
        {
            bool utc = str[2] == 'U';
            ReadUtcParam(str, UtcParam);
            return utc ? NavDataGlonassUtc : NavDataGlonassGps;
        }
\t\telse if (strstr(str, "GAUT") == str)''')
    replace('inc/NavData.h','\tUTC_PARAM GalileoUtcParam;', '\tUTC_PARAM GalileoUtcParam;\n\tUTC_PARAM GlonassUtcParam, GlonassGpsParam;')
    replace('inc/NavData.h','\tPUTC_PARAM GetGalileoUtcParam()', '\tPUTC_PARAM GetGlonassUtcParam() { return &GlonassUtcParam; }\n\tPUTC_PARAM GetGlonassGpsParam() { return &GlonassGpsParam; }\n\tPUTC_PARAM GetGalileoUtcParam()')
    replace('src/NavData.cpp','\tmemset(&GpsUtcParam, 0, sizeof(UTC_PARAM));', '\tmemset(&GpsUtcParam, 0, sizeof(UTC_PARAM));\n\tmemset(&GlonassUtcParam, 0, sizeof(UTC_PARAM));\n\tmemset(&GlonassGpsParam, 0, sizeof(UTC_PARAM));')
    replace('src/NavData.cpp','\tcase NavDataGpsUtc:', '''    case NavDataGlonassUtc:
        GlonassUtcParam = *UtcParam; GlonassUtcParam.flag = 1;
        break;
    case NavDataGlonassGps:
        GlonassGpsParam = *UtcParam; GlonassGpsParam.flag = 1;
        break;
\tcase NavDataGpsUtc:''')
    replace('inc/GNavBit.h','\tint SetEphemeris(int svid, PGPS_EPHEMERIS Eph);','\tint SetEphemeris(int svid, PGPS_EPHEMERIS Eph);\n\tint SetTimeOffsets(double tauC, double tauGps);')
    replace('src/GNavBit.cpp','#include <math.h>','#include <cmath>')
    replace('src/GNavBit.cpp','int GNavBit::SetIonoUtc(PIONO_PARAM IonoParam, PUTC_PARAM UtcParam)', '''int GNavBit::SetTimeOffsets(double tauC, double tauGps)
{
    if(!std::isfinite(tauC) || !std::isfinite(tauGps)) return 1;
    double c = std::round(std::ldexp(std::abs(tauC),31));
    double g = std::round(std::ldexp(std::abs(tauGps),30));
    if(c>2147483647. || g>2097151.) return 1;
    unsigned int cBits = (unsigned int)c | ((tauC<0 && c!=0) ? 0x80000000u : 0u);
    unsigned int gBits = (unsigned int)g | ((tauGps<0 && g!=0) ? 0x200000u : 0u);
    for(auto& frame:StringAlm) {
        frame[0][0] = (frame[0][0] & ~0x1fu) | (cBits >> 27);
        frame[0][1] = (frame[0][1] & 0x1fu) | (cBits << 5);
        frame[0][2] = (frame[0][2] & ~0x7ffffe00u) | (gBits << 9);
    }
    return 0;
}

int GNavBit::SetIonoUtc(PIONO_PARAM IonoParam, PUTC_PARAM UtcParam)''')
    replace('inc/GNavBit.h','\tint SetAlmanac(GPS_ALMANAC Alm[]);','\tint SetSystemTime(GLONASS_TIME time);\n\tint SetAlmanac(GPS_ALMANAC Alm[]);')
    replace('src/GNavBit.cpp','int GNavBit::SetAlmanac(GPS_ALMANAC Alm[])', '''int GNavBit::SetSystemTime(GLONASS_TIME time)
{
    if(time.LeapYear<1 || time.LeapYear>31 || time.Day<1 || time.Day>1461) return 1;
    for(auto& frame:StringAlm) {
        frame[0][0]=(5 << 16) | COMPOSE_BITS(time.Day,5,11);
        frame[0][1]=COMPOSE_BITS(time.LeapYear >> 1,0,4);
        frame[0][2]=((time.LeapYear & 1) << 31) | 0x100;
    }
    return 0;
}

int GNavBit::SetAlmanac(GPS_ALMANAC Alm[])''')
    replace('IFdataGen.cpp','\tNavBitArray[DataBitGNav]->SetAlmanac((PGPS_ALMANAC)NavData.GetGlonassAlmanac());',
            '''\tif(OutputParam.FreqSelect[GlonassSystem] && static_cast<GNavBit*>(NavBitArray[DataBitGNav])->SetSystemTime(GlonassTime)) return 1;
\tNavBitArray[DataBitGNav]->SetAlmanac((PGPS_ALMANAC)NavData.GetGlonassAlmanac());
    if(OutputParam.FreqSelect[GlonassSystem]) {
        auto utc = NavData.GetGlonassUtcParam(), gps = NavData.GetGlonassGpsParam();
        if(!(utc->flag & 1) || !(gps->flag & 1) || utc->A1!=0 || gps->A1!=0 ||
           static_cast<GNavBit*>(NavBitArray[DataBitGNav])->SetTimeOffsets(-utc->A0,-gps->A0)) {
            std::cerr<<"Missing or unsupported GLONASS time offsets\\n";return 1;
        }
    }''')
    files['inc/GalileoCboc.h']=(ROOT/'galileo_cboc.h').read_text()
    replace('src/SatIfSignal.cpp','#include "FastMath.h"','#include "FastMath.h"\n#include "GalileoCboc.h"')
    replace('src/SatIfSignal.cpp','complex_number PrnValue;','complex_number PrnValue, DataValue;')
    replace('src/SatIfSignal.cpp','PrnValue = PrnSequence->DataPrn[DataChip] ? -DataSignal : DataSignal;',
            'PrnValue = DataValue = PrnSequence->DataPrn[DataChip] ? -DataSignal : DataSignal;')
    replace('src/SatIfSignal.cpp','if (IsBoc && (ChipCount & 1))',
            'if (System == GalileoSystem && SignalIndex == SIGNAL_INDEX_E1)\n        PrnValue = GalileoCboc(CurChip,DataValue,PrnValue-DataValue);\n    else if (IsBoc && (ChipCount & 1))')
    files['inc/BdsMotion.h']=(ROOT/'bds_motion.h').read_text()
    replace('IFdataGen.cpp','struct CommandArguments', '#include "BdsMotion.h"\nBdsMotion SampledMotion;\nstruct CommandArguments')
    replace('IFdataGen.cpp','std::string OutputFile;', 'std::string OutputFile;\n\tstd::string MotionFile;')
    replace('IFdataGen.cpp','"--tag", "-t",', '"--motion", "-u", "--tag", "-t",')
    replace('IFdataGen.cpp','case 6:\t// --tag', '''case 6:
            if(i+1>=argc || argv[i+1][0]=='-') return false;
            Arguments.MotionFile=argv[++i];break;
        case 7:\t// --tag''')
    replace('IFdataGen.cpp','\t// initial variables', '''    if(!Arguments.MotionFile.empty()) {
        try {SampledMotion.load(Arguments.MotionFile,Trajectory.GetTimeLength());}
        catch(const std::exception& e) {std::cerr<<e.what()<<"\\n";return 1;}
    }
\t// initial variables''')
    replace('IFdataGen.cpp','SpeedLocalToEcef(StartPos, StartVel, CurPos);',
            'SpeedLocalToEcef(StartPos, StartVel, CurPos);\n\tif(SampledMotion.active()) CurPos=SampledMotion.at(0);')
    replace('IFdataGen.cpp','!Trajectory.GetNextPosVelECEF(0.001, CurPos)',
            '!(SampledMotion.active() ? SampledMotion.next(CurPos) : Trajectory.GetNextPosVelECEF(0.001, CurPos))')
    files['inc/SignalEnvironmentCurve.h']=(ROOT/'signal_environment_curve.h').read_text()
    replace('inc/SatIfSignal.h','void GetIfSample(GNSS_TIME CurTime);',
            'void GetIfSample(GNSS_TIME CurTime, double attenuationDb=0);\n\tint EnvironmentCN0() const;\n\tdouble EnvironmentElevation() const;\n\tint EnvironmentSvid() const { return Svid; }')
    replace('src/SatIfSignal.cpp','void CSatIfSignal::GetIfSample(GNSS_TIME CurTime)',
            'int CSatIfSignal::EnvironmentCN0() const { return SatParam ? SatParam->CN0 : 0; }\n\ndouble CSatIfSignal::EnvironmentElevation() const { return SatParam ? SatParam->Elevation : -2; }\n\nvoid CSatIfSignal::GetIfSample(GNSS_TIME CurTime, double attenuationDb)')
    replace('src/SatIfSignal.cpp','Amp = pow(10, (SatParam->CN0 - 3000) / 2000.) / sqrt(SampleNumber);',
            'Amp = pow(10, (SatParam->CN0 - 3000) / 2000.) / sqrt(SampleNumber);\n\tif(attenuationDb!=0) Amp *= pow(10,-attenuationDb/20); // amplitude only; navigation and phase stay untouched')
    replace('IFdataGen.cpp','#include "BdsMotion.h"','#include "BdsMotion.h"\n#include "SignalEnvironmentCurve.h"')
    replace('IFdataGen.cpp','bool OutputTag;', 'bool OutputTag;\n\tint Environment=-1;\n\tuint32_t EnvironmentSeed=1;')
    replace('IFdataGen.cpp','"--tag", "-t",','"--tag", "-t",\n        "--environment", "-Q",\n        "--environment-seed", "-S",')
    replace('IFdataGen.cpp','case 7:\t// --tag','''case 8: case 9: {
            if(i+1>=argc)return false;
            std::string value=argv[++i];
            if(value.empty() || value.find_first_not_of("0123456789")!=std::string::npos)return false;
            try {
                auto parsed=std::stoull(value);
                if((index==8 && parsed>3) || (index==9 && parsed>2147483647ULL))return false;
                if(index==8)Arguments.Environment=(int)parsed;else Arguments.EnvironmentSeed=(uint32_t)parsed;
            } catch(...) {return false;}
            break;
        }
        case 7:\t// --tag''')
    replace('IFdataGen.cpp','\tNoiseArray = new complex_number[OutputParam.SampleFreq];','''    if(TotalChannelNumber==0) {
        fprintf(stderr,"[ERROR] No usable satellite channels; refusing noise-only output.\\n");
        if(IfFile)fclose(IfFile);
        return 1;
    }
    int environmentRanks[TOTAL_SAT_CHANNEL]={0};
    double environmentDb[TOTAL_SAT_CHANNEL]={0};
    if(Arguments.Environment>=0) {
        srand(Arguments.EnvironmentSeed); // noise is generated sequentially before channel workers
        for(int a=0;a<TotalChannelNumber;a++)for(int b=0;b<TotalChannelNumber;b++)
            if(SatIfSignal[b]->EnvironmentCN0()>SatIfSignal[a]->EnvironmentCN0() ||
               (SatIfSignal[b]->EnvironmentCN0()==SatIfSignal[a]->EnvironmentCN0() &&
                (SatIfSignal[b]->EnvironmentElevation()>SatIfSignal[a]->EnvironmentElevation() ||
                 (SatIfSignal[b]->EnvironmentElevation()==SatIfSignal[a]->EnvironmentElevation() &&
                  SatIfSignal[b]->EnvironmentSvid()<SatIfSignal[a]->EnvironmentSvid()))))environmentRanks[a]++;
        // Equal configured C/N0 uses elevation, following GPS antenna-gain ordering before SVID.
        for(int a=0;a<TotalChannelNumber;a++)printf("ENV RANK svid=%d cn0=%d elevation=%.12g rank=%d\\n",
            SatIfSignal[a]->EnvironmentSvid(),SatIfSignal[a]->EnvironmentCN0(),SatIfSignal[a]->EnvironmentElevation(),environmentRanks[a]);
        printf("Environment=%d seed=%u; six strongest protected; relative attenuation, uncalibrated C/N0\\n",Arguments.Environment,Arguments.EnvironmentSeed);
    }
\tNoiseArray = new complex_number[OutputParam.SampleFreq];''')
    replace('IFdataGen.cpp','\t\texec_cycle ++;','''        exec_cycle ++;
        if(Arguments.Environment>=0)for(int ch=0;ch<TotalChannelNumber;ch++)
            environmentDb[ch]=env_attenuation(Arguments.Environment,environmentRanks[ch],TotalChannelNumber,
                SatIfSignal[ch]->EnvironmentSvid(),(exec_cycle-1)*.001,Arguments.EnvironmentSeed);''')
    replace('IFdataGen.cpp','SatIfSignal[i]->GetIfSample(CurTime);','SatIfSignal[i]->GetIfSample(CurTime,environmentDb[i]);',3)
    replace('IFdataGen.cpp','NoiseArray[i] = GenerateNoise(1.0);',
            'NoiseArray[i] = GenerateNoise(Arguments.Environment<0 ? 1.0 : env_noise_sigma(Arguments.Environment)/14.0);')
    files['BdsNativeCheck.cpp']=(ROOT/'bds_native_check.cpp').read_text()
    return files


def build(android=False):
    folder=ROOT/'output/bds-source'
    folder.mkdir(parents=True,exist_ok=True)
    files=patched_files()
    for name,text in files.items():
        target=folder/name;target.parent.mkdir(parents=True,exist_ok=True)
        if not target.exists() or target.read_text()!=text:target.write_text(text)
    digest=hashlib.sha256(''.join(name+'\0'+text for name,text in sorted(files.items())).encode()).hexdigest()
    provenance={'revision':REVISION,'patchedSourceSHA256':digest,
        'fixes':['BDSB decoding and beta coefficients','zero-initialized BDS model slots and bounded optional RINEX header fields','optional model time defaults','latest valid BDS ionosphere pair','separate BDS ionosphere object','floating-point endpoint tolerance 10ns','validated 10 Hz ECEF motion input','FDMA half-cycle phase wraps before unsigned conversion','GNAV current calendar without unavailable almanac','GNAV signed tauC/tauGPS from validated RINEX GLUT/GLGP','Galileo RINEX3 NeQuick coefficients and region flags reach INAV storage','Galileo E1 CBOC subcarriers and opposite pilot BOC6 polarity','GLONASS group delay initialization, typed clock drift, GPS Klobuchar time and FDMA ionosphere scaling','Per-channel Galileo WN/TOW template copy and serial shared-table initialization'],
        'configurationFixes':['Order-independent system/signal/enable selection','Compact JSON literal closing delimiters','Opt-in shared attenuation and relative noise with deterministic seed']}
    (folder/'provenance.json').write_text(json.dumps(provenance,indent=2))
    cmake='''cmake_minimum_required(VERSION 3.16)
project(BdsExperiment LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
file(GLOB SRC src/*.cpp)
add_executable(IFdataGen IFdataGen.cpp ${SRC})
add_executable(BdsNativeCheck BdsNativeCheck.cpp ${SRC})
target_include_directories(BdsNativeCheck PRIVATE inc)
target_include_directories(IFdataGen PRIVATE inc)
target_compile_options(IFdataGen PRIVATE -O3)
if(ANDROID)
 target_compile_options(IFdataGen PRIVATE -fopenmp -flto=thin)
 target_link_options(IFdataGen PRIVATE -fopenmp -static-openmp -flto=thin)
 target_link_options(IFdataGen PRIVATE -Wl,-z,max-page-size=16384)
elseif(APPLE)
 find_path(OMP_INCLUDE_DIR omp.h HINTS /opt/homebrew/opt/libomp/include /usr/local/opt/libomp/include)
 find_library(OMP_LIBRARY omp HINTS /opt/homebrew/opt/libomp/lib /usr/local/opt/libomp/lib)
 if(NOT OMP_INCLUDE_DIR OR NOT OMP_LIBRARY)
  message(FATAL_ERROR "Mac native generation requires an installed libomp")
 endif()
 target_include_directories(IFdataGen PRIVATE ${OMP_INCLUDE_DIR})
 target_compile_options(IFdataGen PRIVATE -Xpreprocessor -fopenmp)
 target_link_libraries(IFdataGen PRIVATE ${OMP_LIBRARY})
endif()
'''
    if not (folder/'CMakeLists.txt').exists() or (folder/'CMakeLists.txt').read_text()!=cmake:(folder/'CMakeLists.txt').write_text(cmake)
    output=ROOT/('output/bds-build-android' if android else 'output/bds-build-current')
    command=['cmake','-S',str(folder),'-B',str(output),'-DCMAKE_BUILD_TYPE=Release']
    if android:command+=['-DCMAKE_TOOLCHAIN_FILE='+str(NDK/'build/cmake/android.toolchain.cmake'),'-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-26','-DANDROID_STL=c++_static']
    subprocess.run(command,check=True)
    subprocess.run(['cmake','--build',str(output),'-j4'],check=True)
    with (output/'IFdataGen').open('rb')as stream:binary_hash=hashlib.file_digest(stream,'sha256').hexdigest()
    (output/'provenance.json').write_text(json.dumps(provenance|{'androidThinLTO':android,'androidOpenMP':android,'macosOpenMP':not android,'target':'android-arm64-v8a' if android else 'macos-native','binarySHA256':binary_hash},indent=2))
    return output/'IFdataGen'

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--android',action='store_true')
    print(build(parser.parse_args().android))
