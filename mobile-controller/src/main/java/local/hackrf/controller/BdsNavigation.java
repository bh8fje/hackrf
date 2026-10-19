package local.hackrf.controller;

import java.io.IOException;
import java.time.*;
import java.util.*;
import static local.hackrf.controller.RinexNav.*;

/** BDS D1/D2 RINEX3 validation, matched against research/bds_navigation.py. */
final class BdsNavigation {
    static final Instant EPOCH=Instant.parse("2006-01-01T00:00:00Z");
    static String convert(String broadcast,String models,Instant start,int offset,int duration) throws IOException {
        try{return checked(broadcast,models,start,offset,duration);}
        catch(NumberFormatException|IndexOutOfBoundsException|DateTimeException e){throw new IOException("北斗导航格式无效",e);}
    }
    private static String checked(String broadcast,String models,Instant start,int offset,int duration) throws IOException {
        if(duration<60||duration>600)throw new IOException("北斗时长须为60–600秒");
        String[] rows=broadcast.split("\\R");if(rows.length==0||number(part(rows[0],0,9))<3||number(part(rows[0],0,9))>=4)throw new IOException("北斗需要RINEX3");
        int end=-1;for(int i=0;i<rows.length;i++)if(part(rows[i],60,rows[i].length()).trim().equals("END OF HEADER")){end=i;break;}
        if(end<0)throw new IOException("导航文件头缺失");
        StringBuilder headers=new StringBuilder();Set<String> alpha=new HashSet<>(),beta=new HashSet<>();boolean delta=false;
        Instant bdt=start.plusSeconds(offset-14),finish=bdt.plusSeconds(duration);
        for(String row:models.split("\\R")){
            String label=part(row,60,row.length()).trim(),tag=part(row,0,4);
            if(label.equals("END OF HEADER"))break;
            if(label.equals("IONOSPHERIC CORR")&&(tag.equals("BDSA")||tag.equals("BDSB"))){
                for(int j=0;j<4;j++)number(part(row,5+j*12,17+j*12));
                (tag.equals("BDSA")?alpha:beta).add(part(row,53,60).trim());headers.append(row).append('\n');
            }else if(label.equals("TIME SYSTEM CORR")&&tag.equals("BDUT")){
                number(part(row,5,22));number(part(row,22,38));long tow=Long.parseLong(part(row,38,45).trim()),week=Long.parseLong(part(row,45,50).trim());
                if(tow<0||tow>=604800||week<0||Math.abs(Duration.between(EPOCH.plusSeconds(week*604800+tow),bdt).getSeconds())>1209600)throw new IOException("北斗UTC模型无效或过期");
                delta=true;headers.append(row).append('\n');
            }else if(label.equals("LEAP SECONDS")&&Integer.parseInt(part(row,0,6).trim())!=offset)throw new IOException("北斗导航与IERS闰秒不一致");
        }
        if(alpha.isEmpty()||!alpha.equals(beta)||!delta)throw new IOException("北斗BDSA/BDSB/BDUT模型不完整");
        TreeMap<Integer,String> chosen=new TreeMap<>();Map<Integer,Instant> latest=new HashMap<>();
        Instant latestHealthy=null;
        int[][] required={{0,1,2,3},{0,1,2,3},{0,1,2,3},{0,1,2,3},{0,2},{0,1,2,3},{0,1}};
        for(int i=end+1;i<rows.length;i++){
            String row=rows[i];if(!row.matches("^C\\d{2} \\d{4} .*"))continue;
            if(i+7>=rows.length)throw new IOException("北斗轨道记录截断");
            int prn=Integer.parseInt(row.substring(1,3));String[] date=part(row,4,23).trim().split("\\s+");
            Instant toc=LocalDateTime.of(Integer.parseInt(date[0]),Integer.parseInt(date[1]),Integer.parseInt(date[2]),Integer.parseInt(date[3]),Integer.parseInt(date[4]),Integer.parseInt(date[5])).toInstant(ZoneOffset.UTC);
            for(int j=0;j<3;j++)number(part(row,23+j*19,42+j*19));
            double[][] v=new double[7][4];
            for(int k=0;k<7;k++){
                String r=rows[i+k+1];if(!r.startsWith("    "))throw new IOException("北斗轨道记录截断");Arrays.fill(v[k],Double.NaN);
                for(int j=0;j<4;j++){String cell=part(r,4+j*19,23+j*19).trim();if(!cell.isEmpty())v[k][j]=number(cell);}
                for(int j:required[k])if(!Double.isFinite(v[k][j]))throw new IOException("北斗轨道参数不完整");
            }
            if(prn<1||prn>63||v[4][2]!=(long)v[4][2]||v[4][2]<0||v[2][0]<0||v[2][0]>=604800||v[0][0]!=(int)v[0][0]||v[0][0]<0||v[0][0]>31||v[6][1]!=(int)v[6][1]||v[6][1]<0||v[6][1]>31)throw new IOException("北斗周/TOE/AODE/AODC无效");
            if(v[1][1]<0||v[1][1]>=1||v[1][3]<4000||v[1][3]>7000)throw new IOException("北斗轨道离心率或半长轴无效");
            Instant toe=EPOCH.plusSeconds((long)v[4][2]*604800+(long)v[2][0]);boolean covered=v[5][1]==0;
            if(covered&&(latestHealthy==null||toc.isAfter(latestHealthy)))latestHealthy=toc;
            // Hourly updates are not expiration; keep both references within the two-hour orbit window.
            for(Instant t:new Instant[]{bdt,finish})if(Math.abs(Duration.between(toc,t).getSeconds())>7200||Math.abs(Duration.between(toe,t).getSeconds())>7200)covered=false;
            if(covered&&(!latest.containsKey(prn)||toc.isAfter(latest.get(prn)))){latest.put(prn,toc);StringBuilder block=new StringBuilder();for(int k=0;k<8;k++)block.append(rows[i+k]).append('\n');chosen.put(prn,block.toString());}
        }
        if(chosen.size()<4)throw new IOException("少于四颗健康北斗星历覆盖完整模拟时段；可用="+chosen.size()+"，最新健康TOC(BDT)="+latestHealthy+"，要求覆盖至(BDT)="+finish);
        StringBuilder out=new StringBuilder(pad("     3.05           N: GNSS NAV DATA    C: BDS",60)+"RINEX VERSION / TYPE\n");
        out.append(headers).append(pad(String.format(Locale.US,"%6d",offset),60)).append("LEAP SECONDS\n").append(pad("",60)).append("END OF HEADER\n");
        for(String block:chosen.values())out.append(block);return out.toString();
    }
}
