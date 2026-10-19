#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>
#include "gps-sdr-sim/gpssim.h"
#include "signal_environment.h"
int main(void){
    for(int p=0;p<4;p++)for(int count=4;count<=16;count++)for(int seed=0;seed<5;seed++){
        double previous[16]={0};
        for(int n=0;n<=6000;n++){
            int deeplyFaded=0;
            for(int rank=0;rank<count;rank++){
                double db=env_attenuation(p,rank,count,rank+1,n*.1,seed);
                assert(isfinite(db)&&db>=0&&db<=50);
                if(rank<6)assert(db<=2.000001);
                if(db>20)deeplyFaded++;
                if(n)assert(fabs(db-previous[rank])<.6);
                previous[rank]=db;
            }
            assert(deeplyFaded<=1);
        }
    }
    channel_t channels[MAX_CHAN]={0},snapshot[MAX_CHAN];int gains[MAX_CHAN]={0};
    for(int i=0;i<12;i++){channels[i].prn=i+1;gains[i]=128-i*3;}
    memcpy(snapshot,channels,sizeof(channels));env_init(42);
    env_apply(channels,gains,2,29,42);
    assert(memcmp(snapshot,channels,sizeof(channels))==0);
    assert(env_count==12&&env_ranks[0]==0&&env_ranks[11]==11);
    for(int i=0;i<12;i++)gains[i]=128+i;
    env_apply(channels,gains,2,29.1,42);assert(env_ranks[0]==0&&env_ranks[11]==11);
    env_init(42);int first=env_sample(0,1);env_init(42);assert(env_sample(0,1)==first);
    double sum=0,squares=0;int clipped=0;
    for(int n=0;n<1000000;n++){int v=env_sample(0,1);sum+=v;squares+=v*v;if(v==127||v==-128)clipped++;}
    assert(fabs(sum/1000000)<1);assert(squares/1000000>270&&squares/1000000<380);assert(clipped==0);
    assert(env_sample(100000,1)==127);assert(env_sample(-100000,1)==-128);
    puts("PASS mild profiles 600s, strongest six protected, one fade at most, continuous envelopes, repeatable bounded noise");
}
