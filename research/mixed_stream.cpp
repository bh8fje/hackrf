// Offline IQ8 stream mixer; centered FIRs share zero physical delay. No RF control.
#include <algorithm>
#include <array>
#include <cmath>
#include <complex>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <cerrno>
#include <atomic>
#include <condition_variable>
#include <exception>
#include <mutex>
#include <sys/select.h>
#include <thread>
#include <stdexcept>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif
using C=std::complex<float>;
constexpr int OUT_RATE=18000000, HALF=48, FLOAT_CACHE=16384, OUTPUT_BLOCK=32768, CARRIER_PERIOD=12000;
constexpr double PI=3.14159265358979323846;
struct Input {
 int fd=-1,rate,phases; size_t bytes=0,samples=0; const int8_t* data=nullptr;
 std::atomic_bool* cancellation=nullptr;
 bool streaming=false;std::vector<int8_t> buffer;size_t base=0,loaded=0,totalRead=0;
 std::array<float,FLOAT_CACHE> floatI{},floatQ{};size_t floatBase=0,floatCount=0;
 std::vector<std::array<float,2*HALF>> filters;
 Input(const char* path,int r,int p,double cutoff,double seconds=0,bool forceStream=false):rate(r),phases(p) {
  fd=open(path,O_RDONLY);struct stat st{};
  if(fd<0 || fstat(fd,&st))throw std::runtime_error("Cannot open IQ8 input");
  streaming=S_ISFIFO(st.st_mode)||forceStream;
  if(S_ISFIFO(st.st_mode) && fcntl(fd,F_SETFL,fcntl(fd,F_GETFL)|O_NONBLOCK)<0)throw std::runtime_error("Cannot make FIFO reads cancellable");
  if(!S_ISREG(st.st_mode) && !S_ISFIFO(st.st_mode))throw std::runtime_error("Input must be regular file or FIFO");
  if(streaming && seconds<=0)throw std::runtime_error("Streaming input requires --seconds");
  if(seconds>0) {
   double length=seconds*rate;
   samples=size_t(std::llround(length));
   if(samples==0 || std::abs(length-double(samples))>1e-5)throw std::runtime_error("Duration must contain integral input samples");
   bytes=samples*2;
   if(S_ISREG(st.st_mode) && size_t(st.st_size)!=bytes)throw std::runtime_error("Input length disagrees with --seconds");
  } else {
   if(st.st_size<=0 || st.st_size%2)throw std::runtime_error("Input must be nonempty interleaved IQ8");
   bytes=st.st_size;samples=bytes/2;
  }
  if(streaming){buffer.resize(65536);data=buffer.data();}
  else {data=(const int8_t*)mmap(nullptr,bytes,PROT_READ,MAP_PRIVATE,fd,0);if(data==MAP_FAILED)throw std::runtime_error("Cannot map input");}
  filters.resize(phases);
  for(int phase=0;phase<phases;phase++) {
   double frac=double(phase)/phases,sum=0;
   for(int j=0;j<2*HALF;j++) {
    double x=j-(HALF-1)-frac;
    double sinc=std::abs(x)<1e-12?2*cutoff:std::sin(2*PI*cutoff*x)/(PI*x);
    double w=.42+.5*std::cos(PI*x/HALF)+.08*std::cos(2*PI*x/HALF);
    double h=sinc*w;filters[phase][j]=h;sum+=h;
   }
   for(auto& h:filters[phase])h/=sum;
  }
 }
 ~Input(){if(!streaming && data && data!=MAP_FAILED)munmap((void*)data,bytes);if(fd>=0)close(fd);}
 ssize_t readSome(void* target,size_t length) {
  for(;;){
   if(cancellation && cancellation->load(std::memory_order_relaxed))throw std::runtime_error("Mixer input cancelled");
   ssize_t got=read(fd,target,length);
   if(got>=0)return got;
   if(errno==EINTR)continue;
   if(errno!=EAGAIN && errno!=EWOULDBLOCK)return got;
   // Darwin FIFO wakeups may be deferred; cap idle retries at 1 ms, not a USB-sized stall.
   if(fd>=FD_SETSIZE)throw std::runtime_error("IQ8 descriptor exceeds select capacity");
   fd_set readers;FD_ZERO(&readers);FD_SET(fd,&readers);timeval timeout{0,1000};
   int ready=select(fd+1,&readers,nullptr,nullptr,&timeout);
   if(ready<0 && errno!=EINTR)throw std::runtime_error("IQ8 input select failed");
  }
 }
 void ensureRange(size_t first,size_t last) {
  if(!streaming)return;
  if(first*2<base || last>samples || last<first)throw std::runtime_error("Invalid streaming filter window");
  size_t need=2*last;
  if(base+loaded>=need)return;
  size_t drop=2*first-base;
  if(drop>loaded)throw std::runtime_error("Nonsequential streaming access");
  std::memmove(buffer.data(),buffer.data()+drop,loaded-drop);loaded-=drop;base+=drop;
  while(base+loaded<need) {
   size_t request=std::min(buffer.size()-loaded,bytes-totalRead);
   ssize_t got=readSome(buffer.data()+loaded,request);
   if(got<0 && errno==EINTR)continue;
   if(got<=0)throw std::runtime_error("Premature EOF or read failure in IQ8 stream");
   loaded+=size_t(got);totalRead+=size_t(got);
  }
 }
 void ensure(size_t index) {
  ensureRange(index>=HALF-1?index-(HALF-1):0,std::min(samples,index+HALF+1));
 }
 void cacheFloats(size_t first) {
  if(first>=floatBase && first+2*HALF<=floatBase+floatCount)return;
  floatBase=first;floatCount=std::min(size_t(FLOAT_CACHE),samples-first);
  ensureRange(first,first+floatCount);
  const int8_t* raw=data+(first*2-(streaming?base:0));size_t k=0;
#if defined(__aarch64__)
  for(;k+16<=floatCount;k+=16){
   int8x16x2_t pair=vld2q_s8(raw+2*k);
   for(int component=0;component<2;component++){
    float* out=(component==0?floatI.data():floatQ.data())+k;
    int16x8_t low=vmovl_s8(vget_low_s8(pair.val[component])),high=vmovl_s8(vget_high_s8(pair.val[component]));
    vst1q_f32(out,vcvtq_f32_s32(vmovl_s16(vget_low_s16(low))));
    vst1q_f32(out+4,vcvtq_f32_s32(vmovl_s16(vget_high_s16(low))));
    vst1q_f32(out+8,vcvtq_f32_s32(vmovl_s16(vget_low_s16(high))));
    vst1q_f32(out+12,vcvtq_f32_s32(vmovl_s16(vget_high_s16(high))));
   }
  }
#endif
  for(;k<floatCount;k++){floatI[k]=raw[2*k];floatQ[k]=raw[2*k+1];}
 }
 void finish() {
  if(!streaming)return;
  if(totalRead!=bytes)throw std::runtime_error("IQ8 stream length mismatch");
  char extra;ssize_t got;
  do {got=readSome(&extra,1);}while(got<0 && errno==EINTR);
  if(got!=0)throw std::runtime_error("IQ8 stream exceeds duration or read failed");
 }
 __attribute__((always_inline)) C at(uint64_t n) {
  uint64_t scaled=n*uint64_t(rate),index=scaled/OUT_RATE;
  int phase=int((scaled%OUT_RATE)*phases/OUT_RATE);
  float re=0,im=0;const auto& h=filters[phase];
  int64_t first=int64_t(index)-(HALF-1);
  if(first>=0 && uint64_t(first+2*HALF)<=samples) {
#if defined(MIXED_REFERENCE_INT8)
   ensure(index);
   const int8_t* p=data+(2*first-(streaming?base:0));
#if defined(__aarch64__)
   float32x4_t ri=vdupq_n_f32(0),rq=vdupq_n_f32(0);
   for(int j=0;j<2*HALF;j+=16) {
    int8x16x2_t pair=vld2q_s8(p+2*j);
    int16x8_t il=vmovl_s8(vget_low_s8(pair.val[0])),ih=vmovl_s8(vget_high_s8(pair.val[0]));
    int16x8_t ql=vmovl_s8(vget_low_s8(pair.val[1])),qh=vmovl_s8(vget_high_s8(pair.val[1]));
    float32x4_t w0=vld1q_f32(h.data()+j),w1=vld1q_f32(h.data()+j+4);
    float32x4_t w2=vld1q_f32(h.data()+j+8),w3=vld1q_f32(h.data()+j+12);
    ri=vfmaq_f32(ri,vcvtq_f32_s32(vmovl_s16(vget_low_s16(il))),w0);
    ri=vfmaq_f32(ri,vcvtq_f32_s32(vmovl_s16(vget_high_s16(il))),w1);
    ri=vfmaq_f32(ri,vcvtq_f32_s32(vmovl_s16(vget_low_s16(ih))),w2);
    ri=vfmaq_f32(ri,vcvtq_f32_s32(vmovl_s16(vget_high_s16(ih))),w3);
    rq=vfmaq_f32(rq,vcvtq_f32_s32(vmovl_s16(vget_low_s16(ql))),w0);
    rq=vfmaq_f32(rq,vcvtq_f32_s32(vmovl_s16(vget_high_s16(ql))),w1);
    rq=vfmaq_f32(rq,vcvtq_f32_s32(vmovl_s16(vget_low_s16(qh))),w2);
    rq=vfmaq_f32(rq,vcvtq_f32_s32(vmovl_s16(vget_high_s16(qh))),w3);
   }
   re=vaddvq_f32(ri);im=vaddvq_f32(rq);
#else
   for(int j=0;j<2*HALF;j++){re+=p[2*j]*h[j];im+=p[2*j+1]*h[j];}
#endif
#else
   cacheFloats(size_t(first));
   const float* pi=floatI.data()+(size_t(first)-floatBase);
   const float* pq=floatQ.data()+(size_t(first)-floatBase);
#if defined(__aarch64__)
   float32x4_t i0=vdupq_n_f32(0),i1=i0,i2=i0,i3=i0;
   float32x4_t q0=i0,q1=i0,q2=i0,q3=i0;
   for(int j=0;j<2*HALF;j+=16){
    float32x4_t w0=vld1q_f32(h.data()+j),w1=vld1q_f32(h.data()+j+4);
    float32x4_t w2=vld1q_f32(h.data()+j+8),w3=vld1q_f32(h.data()+j+12);
    i0=vfmaq_f32(i0,vld1q_f32(pi+j),w0);q0=vfmaq_f32(q0,vld1q_f32(pq+j),w0);
    i1=vfmaq_f32(i1,vld1q_f32(pi+j+4),w1);q1=vfmaq_f32(q1,vld1q_f32(pq+j+4),w1);
    i2=vfmaq_f32(i2,vld1q_f32(pi+j+8),w2);q2=vfmaq_f32(q2,vld1q_f32(pq+j+8),w2);
    i3=vfmaq_f32(i3,vld1q_f32(pi+j+12),w3);q3=vfmaq_f32(q3,vld1q_f32(pq+j+12),w3);
   }
   re=vaddvq_f32(vaddq_f32(vaddq_f32(i0,i1),vaddq_f32(i2,i3)));
   im=vaddvq_f32(vaddq_f32(vaddq_f32(q0,q1),vaddq_f32(q2,q3)));
#else
   for(int j=0;j<2*HALF;j++){re+=pi[j]*h[j];im+=pq[j]*h[j];}
#endif
#endif
  } else {
   ensure(index);
   for(int j=0;j<2*HALF;j++) {
    int64_t k=first+j;
    if(k>=0 && uint64_t(k)<samples){size_t offset=2*k-(streaming?base:0);re+=data[offset]*h[j];im+=data[offset+1]*h[j];}
   }
  }
  return {re,im};
 }
};
// Each persistent worker owns one input and one bounded output block.
struct Worker {
 Input& input;const std::array<C,CARRIER_PERIOD>& oscillator;float gain;
 std::atomic_bool& cancellation;std::array<C,OUTPUT_BLOCK> values{};
 std::mutex mutex;std::condition_variable changed;std::thread thread;
 bool pending=false,complete=false,terminate=false;uint64_t begin=0;size_t size=0;std::exception_ptr failure;
 Worker(Input& source,const std::array<C,CARRIER_PERIOD>& lut,float weight,std::atomic_bool& cancelled)
  :input(source),oscillator(lut),gain(weight),cancellation(cancelled){
  input.cancellation=&cancellation;
  thread=std::thread([this]{
   for(;;){
    uint64_t start;size_t count;
    {std::unique_lock<std::mutex> lock(mutex);changed.wait(lock,[this]{return pending||terminate;});if(terminate)return;start=begin;count=size;pending=false;}
    try{
     for(size_t j=0;j<count;j++){
      if(cancellation.load(std::memory_order_relaxed))throw std::runtime_error("Mixer worker cancelled");
      C rot=oscillator[(start+j)%CARRIER_PERIOD];
      values[j]=gain*input.at(start+j)*rot;
     }
    }catch(...){failure=std::current_exception();cancellation.store(true,std::memory_order_relaxed);}
    {std::lock_guard<std::mutex> lock(mutex);complete=true;}changed.notify_all();
    if(failure)return;
   }
  });
 }
 void submit(uint64_t start,size_t count){std::lock_guard<std::mutex> lock(mutex);begin=start;size=count;complete=false;pending=true;changed.notify_all();}
 void wait(){std::unique_lock<std::mutex> lock(mutex);changed.wait(lock,[this]{return complete;});if(failure)std::rethrow_exception(failure);}
 ~Worker(){cancellation.store(true,std::memory_order_relaxed);{std::lock_guard<std::mutex> lock(mutex);terminate=true;}changed.notify_all();if(thread.joinable())thread.join();}
};
double weight(const char* text) {
 size_t end=0;double value=std::stod(text,&end);uint64_t bits=0;
 std::memcpy(&bits,&value,sizeof(bits));
 // Bitwise finite check survives -ffast-math assumptions at the CLI boundary.
 if(end!=std::strlen(text) || (bits&0x7ff0000000000000ULL)==0x7ff0000000000000ULL)
  throw std::runtime_error("Weight must be a finite number");
 return value;
}
int main(int argc,char** argv)try {
 if(argc<3)throw std::runtime_error("Usage: mixed_stream gps-iq8 bds-iq8 [output-or-- [gps-weight [bds-weight]]] [--seconds N] [--force-stream]");
 double seconds=0;bool forceStream=false;std::vector<std::string> args;
 for(int i=3;i<argc;i++) {
  if(std::strcmp(argv[i],"--seconds")==0 || std::strcmp(argv[i],"--duration-seconds")==0){if(++i>=argc)throw std::runtime_error("Missing --seconds value");seconds=weight(argv[i]);if(seconds<=0||seconds>600)throw std::runtime_error("Require 0 < seconds <=600; production caller must enforce 60..600");}
  else if(std::strcmp(argv[i],"--force-stream")==0)forceStream=true;
  else args.emplace_back(argv[i]);
 }
 if(args.size()>3)throw std::runtime_error("Too many arguments");
 double gw=args.size()>1?weight(args[1].c_str()):.35,bw=args.size()>2?weight(args[2].c_str()):.25;
 if(gw<0||bw<0||gw+bw>1||gw<=bw)throw std::runtime_error("Require GPS weight > BDS weight >=0, total <=1");
 Input gps(argv[1],2600000,90,.43,seconds,forceStream),bds(argv[2],5000000,18,.43,seconds,forceStream);
 if(uint64_t(gps.samples)*5000000!=uint64_t(bds.samples)*2600000)throw std::runtime_error("Input durations differ");
 uint64_t scaled=uint64_t(gps.samples)*OUT_RATE;
 if(scaled%2600000)throw std::runtime_error("Input duration is not an integral output sample count");
 uint64_t count=scaled/2600000,clipped=0;
 FILE* out=stdout;
 if(!args.empty() && args[0]!="-"){int fd=open(args[0].c_str(),O_WRONLY|O_CREAT|O_EXCL,0600);if(fd<0)throw std::runtime_error("Output must not already exist");out=fdopen(fd,"wb");if(!out)throw std::runtime_error("Cannot open output");}
 std::vector<int8_t> block;block.reserve(65536);
 std::array<C,CARRIER_PERIOD> gpsOscillator,bdsOscillator;
 for(size_t k=0;k<CARRIER_PERIOD;k++) {
  double gpsPhase=2*PI*double((k*5115)%CARRIER_PERIOD)/CARRIER_PERIOD;
  double bdsPhase=-2*PI*double((k*4433)%CARRIER_PERIOD)/CARRIER_PERIOD;
  gpsOscillator[k]=C(float(std::cos(gpsPhase)),float(std::sin(gpsPhase)));
  bdsOscillator[k]=C(float(std::cos(bdsPhase)),float(std::sin(bdsPhase)));
 }
#if defined(MIXED_SERIAL_REFERENCE)
 // Exact periodic lookup avoids block boundaries, drift, and per-sample trigonometry.
 for(uint64_t n=0;n<count;n++) {
  C v=float(gw)*gps.at(n)*gpsOscillator[n%CARRIER_PERIOD]+float(bw)*bds.at(n)*bdsOscillator[n%CARRIER_PERIOD];
  for(double value:{v.real(),v.imag()}) {long q=std::lround(value);if(q<-128||q>127)clipped++;block.push_back(int8_t(std::clamp(q,-128L,127L)));}
  if(block.size()==65536){if(fwrite(block.data(),1,block.size(),out)!=block.size())throw std::runtime_error("Output write failed");block.clear();}
 }
#else
 std::atomic_bool cancellation{false};
 Worker gpsWorker(gps,gpsOscillator,float(gw),cancellation);
 Worker bdsWorker(bds,bdsOscillator,float(bw),cancellation);
 for(uint64_t first=0;first<count;first+=OUTPUT_BLOCK){
  size_t length=size_t(std::min(uint64_t(OUTPUT_BLOCK),count-first));
  gpsWorker.submit(first,length);bdsWorker.submit(first,length);
  gpsWorker.wait();bdsWorker.wait();
  block.clear();
  for(size_t j=0;j<length;j++){
   C value=gpsWorker.values[j]+bdsWorker.values[j];
   for(float component:{value.real(),value.imag()}){long q=std::lround(component);if(q<-128||q>127)clipped++;block.push_back(int8_t(std::clamp(q,-128L,127L)));}
  }
  if(fwrite(block.data(),1,block.size(),out)!=block.size())throw std::runtime_error("Output write failed");
 }
 block.clear();
#endif
 gps.finish();bds.finish();
 if(!block.empty() && fwrite(block.data(),1,block.size(),out)!=block.size())throw std::runtime_error("Output write failed");
 if(fflush(out))throw std::runtime_error("Output flush failed");if(out!=stdout && fclose(out))throw std::runtime_error("Output close failed");
 fprintf(stderr,"{\"samples\":%llu,\"bytes\":%llu,\"clippedComponents\":%llu,\"gpsWeight\":%.8g,\"bdsWeight\":%.8g,\"rfCalibrated\":false,\"firTaps\":96,\"delaySeconds\":0}\n",(unsigned long long)count,(unsigned long long)(count*2),(unsigned long long)clipped,gw,bw);
 return 0;
}catch(const std::exception& e){fprintf(stderr,"mixed_stream: %s\n",e.what());return 1;}
