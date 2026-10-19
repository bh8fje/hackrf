package local.hackrf.controller;

import java.io.IOException;
import java.time.*;
import java.util.*;
import static local.hackrf.controller.RinexNav.*;

/** G1 UTC state vectors and time models, matched to the accepted Mac converter. */
final class GlonassNavigation {
    static final int[] FREQUENCIES={1,-4,5,6,1,-4,5,6,-2,-7,0,-1,-2,-7,0,-1,4,-3,3,2,4,-3,3,2};
    static String convert(String broadcast,String models,Instant start,int offset,int duration)throws IOException {
        try{return checked(broadcast,models,start,offset,duration);}
        catch(NumberFormatException|IndexOutOfBoundsException|DateTimeException|ArithmeticException e){throw new IOException("格洛纳斯导航格式无效",e);}
    }
    private static String checked(String broadcast,String models,Instant start,int offset,int duration)throws IOException {
        if(duration<90||duration>180)throw new IOException("格洛纳斯时长须为90–180秒");
        String[] rows=broadcast.split("\\R");double version=number(part(rows[0],0,9));
        if(version<3||version>=4)throw new IOException("格洛纳斯需要RINEX3");
        int end=-1;for(int i=0;i<rows.length;i++)if(part(rows[i],60,rows[i].length()).trim().equals("END OF HEADER")){end=i;break;}
        if(end<0)throw new IOException("导航文件头缺失");
        StringBuilder headers=new StringBuilder();Set<String> tags=new HashSet<>();Integer leaps=null;
        for(String row:models.split("\\R")){
            String label=part(row,60,row.length()).trim(),tag=part(row,0,4).trim();
            if(label.equals("END OF HEADER"))break;
            if(label.equals("IONOSPHERIC CORR")&&(tag.equals("GPSA")||tag.equals("GPSB"))){
                for(int j=0;j<4;j++)number(part(row,5+j*12,17+j*12));
                tags.add(tag);headers.append(row).append('\n');
            }else if(label.equals("TIME SYSTEM CORR")&&Arrays.asList("GLUT","GLGP","GPUT").contains(tag)){
                double a0=number(part(row,5,22)),a1=number(part(row,22,38));
                long tow=Long.parseLong(part(row,38,45).trim()),week=Long.parseLong(part(row,45,50).trim());
                if(tags.contains(tag)||tow<0||tow>=604800||week<0||week>32767)throw new IOException("格洛纳斯时间模型无效");
                boolean glo=tag.equals("GLUT")||tag.equals("GLGP");
                if(!(glo&&tow==0&&week==0)&&Duration.between(GPS_EPOCH.plusSeconds(week*604800+tow),start.plusSeconds(offset)).abs().compareTo(Duration.ofDays(14))>0)throw new IOException("时间模型过期");
                if(glo){int scale=tag.equals("GLUT")?31:30,bits=tag.equals("GLUT")?31:21;
                    if(a1!=0||Math.rint(Math.abs(a0)*Math.scalb(1,scale))>=Math.scalb(1,bits))throw new IOException("不支持的格洛纳斯时间偏移");}
                tags.add(tag);headers.append(row).append('\n');
            }else if(label.equals("LEAP SECONDS"))leaps=Integer.parseInt(part(row,0,6).trim());
        }
        if(!tags.containsAll(Arrays.asList("GPSA","GPSB","GLUT","GLGP"))||leaps==null||leaps!=offset)throw new IOException("格洛纳斯模型不完整或闰秒不一致");
        TreeMap<Integer,String> chosen=new TreeMap<>();Map<Integer,Instant> latest=new HashMap<>();Instant finish=start.plusSeconds(duration);
        for(int i=end+1;i<rows.length;i++){
            String row=rows[i];if(!row.matches("^R\\d{2} \\d{4} .*"))continue;
            int length=version>=3.05?5:4;if(i+length>rows.length)throw new IOException("格洛纳斯状态向量截断");
            int slot=Integer.parseInt(row.substring(1,3));String[] date=part(row,4,23).trim().split("\\s+");
            Instant toc=LocalDateTime.of(Integer.parseInt(date[0]),Integer.parseInt(date[1]),Integer.parseInt(date[2]),Integer.parseInt(date[3]),Integer.parseInt(date[4]),Integer.parseInt(date[5])).toInstant(ZoneOffset.UTC);
            for(int j=0;j<3;j++)number(part(row,23+j*19,42+j*19));
            double[][] v=new double[length-1][4];
            for(int k=0;k<length-1;k++){
                String r=rows[i+k+1];if(!r.startsWith("    "))throw new IOException("格洛纳斯状态向量截断");
                for(int j=0;j<4;j++){String cell=part(r,4+j*19,23+j*19).trim();v[k][j]=cell.isEmpty()?Double.NaN:number(cell);}
            }
            double radius2=0;
            for(int k=0;k<3;k++){
                for(double value:v[k])if(!Double.isFinite(value))throw new IOException("格洛纳斯状态向量缺失");
                radius2+=v[k][0]*v[k][0];
                if(Math.abs(v[k][1])>5||Math.abs(v[k][2])>1e-4)throw new IOException("格洛纳斯速度或加速度无效");
            }
            double channel=v[1][3];if(channel!=Math.rint(channel)||channel< -7||channel>6)throw new IOException("格洛纳斯FDMA频率无效");
            if(Math.sqrt(radius2)<=20000||Math.sqrt(radius2)>=30000)throw new IOException("格洛纳斯轨道半径无效");
            if(toc.atOffset(ZoneOffset.UTC).getMinute()%15!=0||toc.atOffset(ZoneOffset.UTC).getSecond()!=0)throw new IOException("格洛纳斯参考时刻须对齐15分钟");
            boolean healthy=v[0][3]==0;
            if(length==5&&!Double.isNaN(v[3][3])){
                double flag=v[3][3];if(flag!=Math.rint(flag)||flag<0||flag>=8)throw new IOException("格洛纳斯健康标志无效");
                int f=(int)flag;healthy=healthy&&(f&4)==0&&!((f&2)!=0&&(f&1)==0);
            }
            if(slot<1||slot>24||!healthy||Duration.between(toc,start).abs().compareTo(Duration.ofMinutes(30))>=0||Duration.between(toc,finish).abs().compareTo(Duration.ofMinutes(30))>=0)continue;
            if(channel!=FREQUENCIES[slot-1])throw new IOException("格洛纳斯FDMA分配与独立接收机不一致");
            if(!latest.containsKey(slot)||toc.isAfter(latest.get(slot))){
                latest.put(slot,toc);StringBuilder block=new StringBuilder();
                // shortcut: pinned native parser consumes four lines; validate 3.05 flags before dropping its extension.
                for(int k=0;k<4;k++)block.append(rows[i+k]).append('\n');chosen.put(slot,block.toString());
            }
        }
        if(chosen.size()<4)throw new IOException("少于四颗健康格洛纳斯轨道覆盖整个时段");
        StringBuilder out=new StringBuilder(pad("     3.04           N: GNSS NAV DATA    R: GLO",60)+"RINEX VERSION / TYPE\n");
        out.append(headers).append(pad(String.format(Locale.US,"%6d",offset),60)).append("LEAP SECONDS\n").append(pad("",60)).append("END OF HEADER\n");
        for(String block:chosen.values())out.append(block);return out.toString();
    }
}
