/* Shared relative attenuation curve; absolute C/N0 remains uncalibrated. */
#ifndef SIGNAL_ENVIRONMENT_CURVE_H
#define SIGNAL_ENVIRONMENT_CURVE_H
#include <stdint.h>
#include <math.h>
static double env_attenuation(int profile,int rank,int count,int prn,double seconds,uint32_t seed) {
    double phase=(seed%997)*.013+prn*1.71;
    if(profile==3){
        // Near-window office: stable partial attenuation, without outdoor walk-by fades.
        return rank<6 ? 1+.4*sin(seconds*.018+phase) : 8+2*sin(seconds*.018+phase);
    }
    double db=(profile==0?1.0:2.0)*(.5+.5*sin(seconds*.09+phase));
    if(profile>0&&rank>=6&&count>6&&seconds>=15){
        double shifted=seconds-15,period=profile==1?28:34;
        int slot=(int)(shifted/period);
        int target=6+(int)(((uint32_t)slot+seed%(count-6))%(count-6));
        if(rank==target){
            double x=fmod(shifted,period)/period;
            double envelope=pow(sin(3.1415926535898*x),2);
            db+=(profile==1?42:48)*envelope;
        }
    }
    return db;
}
/* Relative noise scale only; engines retain their native sample normalization. */
static double env_noise_sigma(int profile) { return profile==0 ? 14 : profile==3 ? 20 : 18; }
#endif
