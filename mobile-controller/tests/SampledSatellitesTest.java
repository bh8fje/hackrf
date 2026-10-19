package local.hackrf.controller;
import java.io.IOException;
import java.util.Arrays;
public class SampledSatellitesTest {
    public static void main(String[] args)throws Exception {
        String rows="\n| 01 | +8 | 02 | -11 | 03 | +5 | 04 | +36 |\nTotal channels: 4\n";
        assert Arrays.equals(SampledSatellites.ids("BeiDou B1I with IF +0kHz:"+rows,false),new int[]{1,2,3,4});
        assert Arrays.equals(SampledSatellites.ids("Galileo E1 with IF +0kHz:"+rows,true),new int[]{1,2,3,4});
        assert Arrays.equals(SampledSatellites.ids("GLONASS G1 with IF +0kHz:"+rows,"glonass"),new int[]{1,2,3,4});
        try{SampledSatellites.ids("GLONASS G1 with IF +0kHz:"+rows.replace("| 04 |","| 25 |"),"glonass");throw new AssertionError();}catch(IOException expected){}
        for(String bad:new String[]{"Total channels: 0",rows, "BeiDou B1I with IF +0kHz:"+rows.replace("| 04 |","| 03 |"),"BeiDou B1I with IF +0kHz:"+rows.replace("| 04 |","| 00 |"),"BeiDou B1I with IF +0kHz:"+rows.replace("channels: 4","channels: 5")}){
            try{SampledSatellites.ids(bad,false);throw new AssertionError("Invalid signal table accepted");}catch(IOException expected){}
        }
        try{SampledSatellites.ids("Galileo E1 with IF +0kHz:"+rows.replace("| 04 |","| 37 |"),true);throw new AssertionError();}catch(IOException expected){}
        System.out.println("PASS native BDS/Galileo channel evidence, empty/mixed/duplicate/range rejection");
    }
}
