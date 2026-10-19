package local.hackrf.controller;
public class PlaceCoordinatesTest {
    public static void main(String[] args){
        for(String input:new String[]{"104.0668,30.5728","104.0668，30.5728","104.0668 30.5728","104.0668;30.5728"}){
            double[] p=PlaceCoordinates.parse(input);assert p[0]==30.5728 && p[1]==104.0668;
        }
        assert PlaceCoordinates.parse("成都天府广场")==null;
        assert PlaceCoordinates.parse("人民南路123号")==null;
        for(String input:new String[]{"181,30","104,86","104,30,4","104,","1e999,30","104"}){
            try{PlaceCoordinates.parse(input);throw new AssertionError("Invalid coordinates accepted: "+input);}catch(IllegalArgumentException expected){}
        }
        System.out.println("PASS: explicit longitude-first coordinates, Unicode separators, address detection and invalid input rejection");
    }
}
