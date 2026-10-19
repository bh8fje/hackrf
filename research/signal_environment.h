/* Mild offline reception environment; no change to code/carrier phase or navigation bits. */
#include <stdint.h>
static uint32_t env_rng=1;
static int env_ranks[MAX_SAT],env_count;
static double env_noise_table[4096];
static uint32_t env_random(void) {
    env_rng^=env_rng<<13;env_rng^=env_rng>>17;env_rng^=env_rng<<5;return env_rng;
}
static void env_init(uint32_t seed) {
    env_rng=seed?seed:1;
    env_count=-1;
    for(int n=0;n<4096;n+=2){
        double u=(env_random()+1.0)/4294967297.0,v=(env_random()+1.0)/4294967297.0;
        double r=sqrt(-2*log(u));
        env_noise_table[n]=r*cos(2*PI*v);env_noise_table[n+1]=r*sin(2*PI*v);
    }
}
#include "signal_environment_curve.h"
static int env_sample(int raw,int profile) {
    /* shortcut: relative noise only; absolute C/N0 requires receiver-side calibration. */
    double sample=raw/16.0+env_noise_sigma(profile)*env_noise_table[env_random()&4095];
    int value=(int)lrint(sample);
    return value>127?127:value< -128?-128:value;
}

static void env_apply(channel_t *channels,int *gains,int profile,double seconds,uint32_t seed) {
    if(env_count<0){
        env_count=0;
        for(int sv=0;sv<MAX_SAT;sv++)env_ranks[sv]=MAX_SAT;
        for(int i=0;i<MAX_CHAN;i++)if(channels[i].prn>0){
            int rank=0;env_count++;
            for(int j=0;j<MAX_CHAN;j++)if(channels[j].prn>0 &&
                (gains[j]>gains[i]||(gains[j]==gains[i]&&channels[j].prn<channels[i].prn)))rank++;
            env_ranks[channels[i].prn-1]=rank;
        }
    }
    for(int i=0;i<MAX_CHAN;i++)if(channels[i].prn>0){
        int rank=env_ranks[channels[i].prn-1];
        double db=env_attenuation(profile,rank,env_count,channels[i].prn,seconds,seed);
        gains[i]=(int)lrint(gains[i]*pow(10,-db/20));
        if((int)lrint(seconds*10)%50==0)
            fprintf(stderr,"ENV t=%.1f PRN=%02d rank=%d attenuationDb=%.2f\n",seconds,channels[i].prn,rank,db);
    }
}
