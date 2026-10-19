package local.hackrf.controller;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Strict GPS LNAV conversion shared with research/live_nav.py's reference fixtures. */
final class RinexNav {
    static final Instant GPS_EPOCH = Instant.parse("1980-01-06T00:00:00Z");
    private static final String[] LABELS = {"ION ALPHA", "ION BETA", "DELTA-UTC: A0,A1,T,W"};
    static String part(String s, int start, int end) { return s.substring(Math.min(start,s.length()),Math.min(end,s.length())); }
    static double number(String s) throws IOException {
        try { double n = Double.parseDouble(s.trim().replace('D','E')); if (!Double.isFinite(n)) throw new IOException("非有限导航参数"); return n; }
        catch (NumberFormatException e) { throw new IOException("导航数值格式无效",e); }
    }
    static String pad(String s, int length) { StringBuilder result = new StringBuilder(s); while (result.length()<length) result.append(' '); return result.toString(); }
    static int gpsMinusUTC(String table, Instant utc) throws IOException {
        Matcher expiry = Pattern.compile("File expires on (\\d+ \\w+ \\d{4})").matcher(table);
        if (!expiry.find()) throw new IOException("闰秒表缺少到期日期");
        Instant expiration = LocalDate.parse(expiry.group(1),DateTimeFormatter.ofPattern("d MMMM uuuu",Locale.ENGLISH)).atStartOfDay().toInstant(ZoneOffset.UTC);
        if (!utc.isBefore(expiration)) throw new IOException("闰秒表已到期");
        int tai = -1;
        for (String row : table.split("\\R")) {
            if (row.trim().isEmpty() || row.trim().startsWith("#")) continue;
            String[] fields = row.trim().split("\\s+"); if (fields.length < 5) throw new IOException("闰秒表记录不完整");
            Instant epoch = LocalDate.of(Integer.parseInt(fields[3]),Integer.parseInt(fields[2]),Integer.parseInt(fields[1])).atStartOfDay().toInstant(ZoneOffset.UTC);
            if (!utc.isBefore(epoch)) tai = Integer.parseInt(fields[4]);
        }
        if (tai < 19 || tai >= 100) throw new IOException("不支持的闰秒偏差");
        return tai-19;
    }
    private static final class Record {
        Instant toc, toe; int prn; boolean healthy; String text;
    }
    static Instant production(String text) throws IOException {
        String[] rows=text.split("\\R");if(rows.length<2)throw new IOException("导航缺少生产时间");String row=rows[1];
        Matcher current=Pattern.compile("(\\d{8}) (\\d{6}) UTC").matcher(row);
        if(current.find())return LocalDateTime.parse(current.group(1)+current.group(2),DateTimeFormatter.ofPattern("uuuuMMddHHmmss")).toInstant(ZoneOffset.UTC);
        Matcher teqc=Pattern.compile("(\\d{8}) (\\d{2}:\\d{2}:\\d{2})UTC").matcher(row);
        if(teqc.find())return LocalDateTime.parse(teqc.group(1)+teqc.group(2),DateTimeFormatter.ofPattern("uuuuMMddHH:mm:ss")).toInstant(ZoneOffset.UTC);
        Matcher legacy=Pattern.compile("(\\d{2}-[A-Za-z]{3}-\\d{2}) (\\d{2}:\\d{2})").matcher(row);
        if(legacy.find())return LocalDateTime.parse(legacy.group(1).toUpperCase(Locale.US)+" "+legacy.group(2),new java.time.format.DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern("dd-MMM-uu HH:mm").toFormatter(Locale.US)).toInstant(ZoneOffset.UTC);
        throw new IOException("无法核对导航生产时间");
    }
    static String broadcast3(String text) throws IOException {
        String[] rows=text.split("\\R");double version=number(part(rows[0],0,9));if(version>=3 && version<4)return text;
        if(version<2 || version>=3 || !part(rows[0],20,21).equals("N"))throw new IOException("需要GPS RINEX2或3星历");
        StringBuilder result=new StringBuilder(pad("     3.05           N: GNSS NAV DATA    G: GPS",60)+"RINEX VERSION / TYPE\n");int end=-1;
        for(int i=1;i<rows.length;i++){result.append(rows[i]).append('\n');if(part(rows[i],60,rows[i].length()).startsWith("END OF HEADER")){end=i;break;}}
        if(end<0)throw new IOException("RINEX2头缺失");
        try{for(int i=end+1;i<rows.length;i+=8){if(rows[i].trim().isEmpty())continue;if(i+7>=rows.length)throw new IOException("RINEX2轨道记录截断");
            String[] fields=part(rows[i],0,22).trim().split("\\s+");if(fields.length!=7)throw new IOException("RINEX2日期字段无效");int year=Integer.parseInt(fields[1]);year+=year<80?2000:1900;
            double second=number(fields[6]);if(second!=(int)second)throw new IOException("不支持非整数GPS参考秒");
            result.append(String.format(Locale.US,"G%02d %04d %02d %02d %02d %02d %02d",Integer.parseInt(fields[0]),year,Integer.parseInt(fields[2]),Integer.parseInt(fields[3]),Integer.parseInt(fields[4]),Integer.parseInt(fields[5]),(int)second));result.append(rows[i].substring(22)).append('\n');
            for(int k=1;k<=7;k++){if(!rows[i+k].startsWith("   "))throw new IOException("RINEX2轨道缩进无效");result.append(' ').append(rows[i+k]).append('\n');}
        }}catch(IndexOutOfBoundsException|NumberFormatException e){throw new IOException("RINEX2轨道格式无效",e);}return result.toString();
    }
    static String modelHeaders(String text) throws IOException {
        StringBuilder out=new StringBuilder();
        for(String line:text.split("\\R")){
            String label=part(line,60,line.length()).trim(),tag=part(line,0,4);
            if(label.equals("IONOSPHERIC CORR") && (tag.equals("GPSA") || tag.equals("GPSB"))){
                String body="  ";for(int j=0;j<4;j++)body+=String.format(Locale.US,"%12.4E",number(part(line,5+j*12,17+j*12)));
                out.append(pad(body,60)).append(tag.equals("GPSA")?"ION ALPHA":"ION BETA").append('\n');
            }else if(label.equals("TIME SYSTEM CORR") && tag.equals("GPUT")){
                String body=String.format(Locale.US,"   %19.11E%19.11E%9d%9d",number(part(line,5,22)),number(part(line,22,38)),Integer.parseInt(part(line,38,45).trim()),Integer.parseInt(part(line,45,50).trim()));
                out.append(pad(body,60)).append("DELTA-UTC: A0,A1,T,W").append('\n');
            }else out.append(line).append('\n');
        }
        return out.toString();
    }
    static boolean hasModels(String text){
        java.util.HashSet<String> labels=new java.util.HashSet<>();
        try{for(String row:modelHeaders(text).split("\\R"))if(row.length()>=60)labels.add(row.substring(60).trim());}catch(IOException|NumberFormatException invalid){return false;}
        return labels.containsAll(java.util.Arrays.asList(LABELS));
    }
    static String convert(String broadcast, String models, Instant startGPS, int offset) throws IOException {
        return convert(broadcast,models,startGPS,offset,0);
    }
    static String convert(String broadcast,String models,Instant startGPS,int offset,int duration) throws IOException {
        if(duration<0 || duration>600)throw new IOException("星历校验时长无效");
        try { return convertChecked(broadcast,models,startGPS,offset,duration); }
        catch (IndexOutOfBoundsException | NumberFormatException | DateTimeException e) { throw new IOException("导航记录格式无效",e); }
    }
    private static String convertChecked(String broadcast, String models, Instant startGPS, int offset,int duration) throws IOException {
        Instant endGPS=startGPS.plusSeconds(duration);
        String[] lines = broadcast.split("\\R"), modelLines = modelHeaders(models).split("\\R");
        if (lines.length == 0 || number(part(lines[0],0,9)) < 3 || number(part(lines[0],0,9)) >= 4) throw new IOException("需要RINEX3广播星历");
        int end = -1;
        for (int i=0;i<lines.length;i++) if (part(lines[i],60,lines[i].length()).startsWith("END OF HEADER")) { end=i; break; }
        if (end < 0) throw new IOException("导航文件头不完整");
        if (modelLines.length == 0 || !modelLines[0].contains("RINEX VERSION / TYPE")) throw new IOException("模型来源不是RINEX");
        Map<String,String> headers = new HashMap<>();
        for (String row : modelLines) if (row.length() >= 60) headers.put(row.substring(60).trim(),row);
        for (String label : LABELS) if (!headers.containsKey(label)) throw new IOException("电离层/UTC模型缺失");
        for (int h=0;h<2;h++) for (int j=0;j<4;j++) number(part(headers.get(LABELS[h]),2+j*12,14+j*12));
        String delta = headers.get(LABELS[2]); number(part(delta,3,22)); number(part(delta,22,41));
        int tot = Integer.parseInt(part(delta,41,50).trim()), week = Integer.parseInt(part(delta,50,59).trim());
        if (tot < 0 || tot >= 604800 || tot%4096 != 0 || week < 0 || Math.abs(Duration.between(GPS_EPOCH.plusSeconds(week*604800L+tot),startGPS).getSeconds()) > 1209600) throw new IOException("UTC模型无效或过期");
        if (headers.containsKey("LEAP SECONDS") && Integer.parseInt(part(headers.get("LEAP SECONDS"),0,6).trim()) != offset) throw new IOException("广播与IERS闰秒不一致");
        ArrayList<Record> records = new ArrayList<>();
        for (int i=end+1;i<lines.length;i++) {
            String row = lines[i]; if (!row.matches("^G\\d{2} \\d{4} .*")) continue;
            if (i+7 >= lines.length) throw new IOException("GPS记录被截断");
            Record record = new Record(); record.prn = Integer.parseInt(row.substring(1,3));
            String[] date = row.substring(4,23).trim().split("\\s+");
            LocalDateTime epoch = LocalDateTime.of(Integer.parseInt(date[0]),Integer.parseInt(date[1]),Integer.parseInt(date[2]),Integer.parseInt(date[3]),Integer.parseInt(date[4]),Integer.parseInt(date[5]));
            record.toc = epoch.toInstant(ZoneOffset.UTC);
            for (int j=0;j<3;j++) number(part(row,23+j*19,42+j*19));
            ArrayList<ArrayList<Double>> values = new ArrayList<>();
            for (int k=1;k<=7;k++) {
                if (!lines[i+k].startsWith("    ")) throw new IOException("GPS轨道记录不完整");
                ArrayList<Double> v = new ArrayList<>();
                for (int j=0;j<4;j++) { String cell = part(lines[i+k],4+j*19,4+(j+1)*19); if (!cell.trim().isEmpty()) v.add(number(cell)); }
                if ((k<=6 && v.size()!=4) || (k==7 && v.size()<1)) throw new IOException("GPS轨道参数缺失");
                values.add(v);
            }
            if (record.prn < 1 || record.prn > 32) throw new IOException("GPS PRN无效");
            record.toe = GPS_EPOCH.plusSeconds(values.get(4).get(2).longValue()*604800L).plusMillis(Math.round(values.get(2).get(0)*1000));
            record.healthy = values.get(5).get(1) == 0;
            if(duration>0){
                double iode=values.get(0).get(0),iodc=values.get(5).get(3);
                if(iode!=Math.rint(iode)||iodc!=Math.rint(iodc)||iode<0||iode>255||iodc<0||iodc>1023||((int)iodc&255)!=(int)iode)continue;
                double fit=values.get(6).size()>1?values.get(6).get(1):4;
                // shortcut: cap all fit intervals at four hours; extended-fit support needs separate validation.
                long half=(long)(Math.min(fit>0?fit:4,4)*1800);
                if(!record.healthy || Math.abs(Duration.between(record.toc,startGPS).getSeconds())>=3600
                    || Math.abs(Duration.between(record.toe,startGPS).getSeconds())>half
                    || Math.abs(Duration.between(record.toe,endGPS).getSeconds())>half)continue;
            }
            StringBuilder output = new StringBuilder(String.format(Locale.US,"%2d %02d %2d %2d %2d %2d %4.1f",record.prn,epoch.getYear()%100,epoch.getMonthValue(),epoch.getDayOfMonth(),epoch.getHour(),epoch.getMinute(),(double)epoch.getSecond()));
            output.append(part(row,23,80)).append('\n');
            for (int k=1;k<=7;k++) output.append("   ").append(lines[i+k].substring(4)).append('\n');
            record.text = output.toString();
            if (Math.abs(Duration.between(record.toc,startGPS).getSeconds()) <= 10800) records.add(record);
        }
        records.sort(Comparator.comparing((Record r)->r.toc).thenComparingInt(r->r.prn).thenComparing(r->r.toe).thenComparing(r->r.healthy).thenComparing(r->r.text));
        if(duration>0 && !records.isEmpty()){
            // The pinned generator groups TOCs into hour-wide sets; emit one coherent, fully covered set.
            Instant newest=records.get(records.size()-1).toc;
            LinkedHashMap<Integer,Record> latest=new LinkedHashMap<>();
            for(Record r:records)if(Duration.between(r.toc,newest).getSeconds()<=3600)latest.put(r.prn,r);
            records=new ArrayList<>(latest.values());
            records.sort(Comparator.comparing((Record r)->r.toc).thenComparingInt(r->r.prn));
        }
        HashSet<Integer> fresh = new HashSet<>();
        for (Record r : records) if (r.healthy && Math.abs(Duration.between(r.toc,startGPS).getSeconds()) < 3600 && Math.abs(Duration.between(r.toe,startGPS).getSeconds()) <= 7200) fresh.add(r.prn);
        if (fresh.size() < 4) throw new IOException("近期健康GPS星历少于4颗");
        if (duration==0 && (startGPS.isBefore(records.get(0).toc) || Duration.between(records.get(records.size()-1).toc,startGPS).getSeconds() > 3600)) throw new IOException("场景超出星历覆盖时段");
        StringBuilder result = new StringBuilder(pad("     2.11           N: GPS NAV DATA",60)+"RINEX VERSION / TYPE\n");
        for (String label : LABELS) result.append(pad(headers.get(label),80)).append('\n');
        result.append(pad(String.format(Locale.US,"%6d",offset),60)).append("LEAP SECONDS\n");
        result.append(pad("",60)).append("END OF HEADER\n");
        for (Record record : records) result.append(record.text);
        return result.toString();
    }
}
