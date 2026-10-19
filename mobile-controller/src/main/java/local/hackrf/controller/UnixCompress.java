package local.hackrf.controller;

import java.io.*;

/** Bounded Unix compress (.Z, 10–16 bits) decoder for public RINEX snapshots. */
final class UnixCompress {
    static byte[] decode(byte[] raw,int limit,PhoneMotion.Check check)throws IOException {
        if(raw.length<3||(raw[0]&255)!=31||(raw[1]&255)!=157)throw new IOException("无效的Unix压缩头");
        int flags=raw[2]&255,maxBits=flags&31;
        if((flags&96)!=0||maxBits<10||maxBits>16||limit<0)throw new IOException("不支持的Unix压缩格式");
        boolean block=(flags&128)!=0;int capacity=1<<maxBits;
        int[] prefix=new int[capacity];byte[] suffix=new byte[capacity],stack=new byte[capacity];
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        int width=9,next=block?257:256,old=-1,first=0,bit=24,segment=24;
        while(true){
            check.run();
            if(next>=(1<<width)&&width<maxBits){
                int group=width*8;bit=segment+((bit-segment+group-1)/group)*group;segment=bit;width++;
            }
            if((long)bit+width>(long)raw.length*8)break;
            int code=0;for(int k=0;k<width;k++)code|=((raw[(bit+k)>>>3]>>>((bit+k)&7))&1)<<k;
            bit+=width;
            if(block&&code==256){
                int group=width*8;bit=segment+((bit-segment+group-1)/group)*group;segment=bit;
                width=9;next=257;old=-1;continue;
            }
            if(old<0){
                if(code>255)throw new IOException("Unix压缩首码无效");
                if(out.size()>=limit)throw new IOException("导航解压超出大小限制");
                out.write(code);old=code;first=code;continue;
            }
            int original=code,n=0;
            if(code==next){stack[n++]=(byte)first;code=old;}
            else if(code>next)throw new IOException("Unix压缩字典码无效");
            while(code>=256){
                if(code>=next||n>=stack.length-1)throw new IOException("Unix压缩字典链无效");
                stack[n++]=suffix[code];code=prefix[code];
            }
            first=code;stack[n++]=(byte)first;
            if(n>limit-out.size())throw new IOException("导航解压超出大小限制");
            while(n>0)out.write(stack[--n]);
            if(next<capacity){prefix[next]=old;suffix[next]=(byte)first;next++;}
            old=original;
        }
        return out.toByteArray();
    }
}
