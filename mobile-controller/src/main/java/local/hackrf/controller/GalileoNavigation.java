package local.hackrf.controller;

import java.io.IOException;
import java.time.*;
import java.util.*;
import static local.hackrf.controller.RinexNav.*;

/** Galileo E1 I/NAV input validation, matched against research/galileo_lab.py. */
final class GalileoNavigation {
    static String convert(String broadcast,String models,Instant start,int offset,int duration) throws IOException {
        try{return checked(broadcast,models,start,offset,duration);}
        catch(NumberFormatException|IndexOutOfBoundsException|DateTimeException|ArithmeticException e){throw new IOException("伽利略导航格式无效",e);}
    }
    private static String checked(String broadcast,String models,Instant start,int offset,int duration) throws IOException {
        if(duration<90||duration>180)throw new IOException("伽利略时长须为90–180秒");
        String[] rows=broadcast.split("\\R");
        if(rows.length==0||number(part(rows[0],0,9))<3||number(part(rows[0],0,9))>=4)throw new IOException("伽利略需要RINEX3");
        int end=-1;for(int i=0;i<rows.length;i++)if(part(rows[i],60,rows[i].length()).trim().equals("END OF HEADER")){end=i;break;}
        if(end<0)throw new IOException("导航文件头缺失");
        StringBuilder headers=new StringBuilder();Set<String> tags=new HashSet<>();Integer leaps=null;
        Instant begin=start.plusSeconds(offset),finish=begin.plusSeconds(duration);
        for(String row:models.split("\\R")){
            String label=part(row,60,row.length()).trim(),tag=part(row,0,4).trim();
            if(label.equals("END OF HEADER"))break;
            if(label.equals("IONOSPHERIC CORR")&&Arrays.asList("GPSA","GPSB","GAL").contains(tag)){
                for(int j=0;j<(tag.equals("GAL")?3:4);j++)number(part(row,5+j*12,17+j*12));
                if(tag.equals("GAL")){double flags=number(part(row,41,53));if(flags!=(int)flags||flags<0||flags>=32)throw new IOException("伽利略电离层地区标志无效");}
                tags.add(tag);headers.append(row).append('\n');
            }else if(label.equals("TIME SYSTEM CORR")&&Arrays.asList("GAUT","GPUT").contains(tag)){
                number(part(row,5,22));number(part(row,22,38));long tow=Long.parseLong(part(row,38,45).trim()),week=Long.parseLong(part(row,45,50).trim());
                if(tow<0||tow>=604800||week<0||Duration.between(GPS_EPOCH.plusSeconds(week*604800+tow),begin).abs().compareTo(Duration.ofDays(14))>0)throw new IOException("UTC模型无效或过期");
                tags.add(tag);headers.append(row).append('\n');
            }else if(label.equals("LEAP SECONDS"))leaps=Integer.parseInt(part(row,0,6).trim());
        }
        if(!tags.containsAll(Arrays.asList("GPSA","GPSB","GAL","GAUT"))||leaps==null||leaps!=offset)throw new IOException("伽利略模型不完整或闰秒不一致");
        TreeMap<Integer,String> chosen=new TreeMap<>();Map<Integer,Instant> latest=new HashMap<>();
        int[][] required={{0,1,2,3},{0,1,2,3},{0,1,2,3},{0,1,2,3},{0,1,2},{0,1,2,3},{}};
        for(int i=end+1;i<rows.length;i++){
            String row=rows[i];if(!row.matches("^E\\d{2} \\d{4} .*"))continue;
            if(i+7>=rows.length)throw new IOException("伽利略轨道记录截断");
            int prn=Integer.parseInt(row.substring(1,3));String[] date=part(row,4,23).trim().split("\\s+");
            Instant toc=LocalDateTime.of(Integer.parseInt(date[0]),Integer.parseInt(date[1]),Integer.parseInt(date[2]),Integer.parseInt(date[3]),Integer.parseInt(date[4]),Integer.parseInt(date[5])).toInstant(ZoneOffset.UTC);
            for(int j=0;j<3;j++)number(part(row,23+j*19,42+j*19));
            double[][] v=new double[7][4];
            for(int k=0;k<7;k++){
                String r=rows[i+k+1];if(!r.startsWith("    "))throw new IOException("伽利略轨道记录截断");Arrays.fill(v[k],Double.NaN);
                for(int j=0;j<4;j++){String cell=part(r,4+j*19,23+j*19).trim();if(!cell.isEmpty())v[k][j]=number(cell);}
                for(int j:required[k])if(!Double.isFinite(v[k][j]))throw new IOException("伽利略轨道参数不完整");
            }
            if(prn<1||prn>36)continue;
            double iod=v[0][0],flags=v[4][1],week=v[4][2],health=v[5][1],tow=v[2][0];
            if(iod!=(int)iod||flags!=(int)flags||week!=(long)week||health!=(int)health||iod<0||iod>=1024||flags<0||flags>=1024||week<1024||week>Integer.MAX_VALUE||health<0||health>=512)throw new IOException("伽利略标识或周数无效");
            if(tow<0||tow>=604800||v[1][1]<0||v[1][1]>=1||v[1][3]<5000||v[1][3]>6000)throw new IOException("伽利略轨道无效");
            Instant toe=GPS_EPOCH.plusSeconds((long)week*604800).plusNanos(Math.round(tow*1000000000));boolean covered=(((int)flags)&1)!=0&&health==0;
            for(Instant t:new Instant[]{begin,finish})if(Duration.between(toc,t).abs().compareTo(Duration.ofHours(2))>0||Duration.between(toe,t).abs().compareTo(Duration.ofHours(2))>0)covered=false;
            if(covered&&(!latest.containsKey(prn)||toc.isAfter(latest.get(prn)))){latest.put(prn,toc);StringBuilder block=new StringBuilder();for(int k=0;k<8;k++)block.append(rows[i+k]).append('\n');chosen.put(prn,block.toString());}
        }
        if(chosen.size()<4)throw new IOException("少于四颗健康伽利略I/NAV星历覆盖整个时段");
        StringBuilder out=new StringBuilder(pad("     3.05           N: GNSS NAV DATA    E: GAL",60)+"RINEX VERSION / TYPE\n");
        out.append(headers).append(pad(String.format(Locale.US,"%6d",offset),60)).append("LEAP SECONDS\n").append(pad("",60)).append("END OF HEADER\n");
        for(String block:chosen.values())out.append(block);return out.toString();
    }
}
