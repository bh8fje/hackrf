package local.hackrf.controller;

import java.io.IOException;
import java.util.TreeSet;
import java.util.regex.*;

/** Parse the native signal table, independently of navigation record coverage. */
final class SampledSatellites {
    static int[] ids(String log,boolean galileo)throws IOException {
        return ids(log,galileo?"galileo":"bds");
    }
    static int[] ids(String log,String system)throws IOException {
        boolean galileo=system.equals("galileo"),glonass=system.equals("glonass");
        if(!galileo&&!glonass&&!system.equals("bds"))throw new IOException("不支持的导航系统");
        String heading=galileo?"Galileo E1 with IF ":glonass?"GLONASS G1 with IF ":"BeiDou B1I with IF ";
        int begin=log.indexOf(heading),end=log.indexOf("Total channels:",Math.max(0,begin));
        if(begin<0||end<begin)throw new IOException("原生生成器未启用目标导航信号");
        TreeSet<Integer> ids=new TreeSet<>();Matcher rows=Pattern.compile("\\|\\s*(\\d{2})\\s*\\|\\s*[+-]?\\d+\\s*").matcher(log.substring(begin,end));
        while(rows.find()){
            int id=Integer.parseInt(rows.group(1));
            if(id<1||id>(galileo?36:glonass?24:63)||!ids.add(id))throw new IOException("原生卫星清单无效或重复");
        }
        Matcher total=Pattern.compile("Total channels:\\s*(\\d+)").matcher(log.substring(end));
        if(ids.size()<4||!total.find()||Integer.parseInt(total.group(1))!=ids.size())throw new IOException("原生可见卫星不足四颗或通道数量不匹配");
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }
}
