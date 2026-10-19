#ifndef BDS_MOTION_H
#define BDS_MOTION_H
#include <array>
#include <cmath>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>
#include "BasicTypes.h"

// Match the existing GPS 10 Hz ECEF input with linear position interpolation.
class BdsMotion {
    std::vector<std::array<double,4>> points;
    int milliseconds=0, durationMs=0;
public:
    bool active() const {return !points.empty();}
    void load(const std::string& file,double duration) {
        if(!std::isfinite(duration)||duration<60||duration>600||std::abs(duration-std::round(duration))>1e-9)
            throw std::runtime_error("Invalid sampled trajectory duration: "+std::to_string(duration)+" seconds (expected 60..600)");
        std::ifstream input(file);if(!input)throw std::runtime_error("Cannot open sampled trajectory");
        std::vector<std::array<double,4>> parsed;std::string line;
        while(std::getline(input,line)) {
            if(line.size()>256||parsed.size()>6001)throw std::runtime_error("Sampled trajectory too large");
            std::istringstream row(line);std::array<double,4> p;char comma;
            if(!(row>>p[0]>>comma)||comma!=','||!(row>>p[1]>>comma)||comma!=','||!(row>>p[2]>>comma)||comma!=','||!(row>>p[3]))
                throw std::runtime_error("Malformed sampled trajectory");
            row>>std::ws;if(!row.eof())throw std::runtime_error("Trailing sampled trajectory data");
            for(double value:p)if(!std::isfinite(value))throw std::runtime_error("Nonfinite sampled trajectory");
            if(std::abs(p[0]-parsed.size()*.1)>1e-7)throw std::runtime_error("Trajectory must start at zero and use 10 Hz epochs");
            double radius=std::hypot(std::hypot(p[1],p[2]),p[3]);
            if(radius<6e6||radius>7e6)throw std::runtime_error("Invalid ECEF trajectory position");
            if(!parsed.empty()) {
                const auto& prior=parsed.back();
                if(std::hypot(std::hypot(p[1]-prior[1],p[2]-prior[2]),p[3]-prior[3])>10)
                    throw std::runtime_error("Trajectory exceeds 100 metres per second");
            }
            parsed.push_back(p);
        }
        if(input.bad()||parsed.size()!=size_t(std::lround(duration*10))+2)
            throw std::runtime_error("Incomplete sampled trajectory including endpoint guard");
        points=std::move(parsed);milliseconds=0;durationMs=int(std::lround(duration*1000));
    }
    KINEMATIC_INFO at(int ms) const {
        size_t index=ms/100;double fraction=(ms%100)/100.;
        const auto& a=points.at(index);const auto& b=points.at(index+1);
        KINEMATIC_INFO p={};
        p.x=a[1]+fraction*(b[1]-a[1]);p.y=a[2]+fraction*(b[2]-a[2]);p.z=a[3]+fraction*(b[3]-a[3]);
        p.vx=(b[1]-a[1])*10;p.vy=(b[2]-a[2])*10;p.vz=(b[3]-a[3])*10;return p;
    }
    bool next(KINEMATIC_INFO& p) {if(++milliseconds>durationMs)return false;p=at(milliseconds);return true;}
};
#endif
