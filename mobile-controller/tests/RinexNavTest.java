package local.hackrf.controller;
import java.nio.file.*;
import java.time.Instant;
import java.io.IOException;

public class RinexNavTest {
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        String broadcast = Files.readString(root.resolve("broadcast.rnx")), models = Files.readString(root.resolve("models.rnx"));
        String leaps = Files.readString(root.resolve("leaps.dat")), expected = Files.readString(root.resolve("expected.rnx"));
        String model3=String.format(java.util.Locale.US,"%-60s%s%n%-60s%s%n%-60s%s%n%-60s%s%n",
            "     3.05           N: GNSS NAV DATA","RINEX VERSION / TYPE",
            String.format(java.util.Locale.US,"GPSA %12.4E%12.4E%12.4E%12.4E",1e-8,2e-8,3e-8,4e-8),"IONOSPHERIC CORR",
            String.format(java.util.Locale.US,"GPSB %12.4E%12.4E%12.4E%12.4E",1e-8,2e-8,3e-8,4e-8),"IONOSPHERIC CORR",
            String.format(java.util.Locale.US,"GPUT %17.10E%16.9E%7d%5d",1e-9,2e-15,589824,2440),"TIME SYSTEM CORR");
        assert RinexNav.hasModels(model3);
        assert RinexNav.modelHeaders(model3).contains("DELTA-UTC: A0,A1,T,W");
        Instant gps = Instant.parse("2026-10-16T16:08:02Z");
        assert RinexNav.gpsMinusUTC(leaps, Instant.parse("2026-10-16T16:07:44Z")) == 18;
        String output = RinexNav.convert(broadcast, models, gps, 18);
        String bounded=RinexNav.convert(broadcast,models,gps,18,600);
        assert bounded.contains("END OF HEADER");
        String[] narrow=broadcast.split("\\R");
        for(int n=0;n<narrow.length;n++)if(narrow[n].matches("^G\\d{2} .*"))narrow[n+7]=String.format(java.util.Locale.US,"    %19.12e%19.12e",999900000.0,.5);
        String shortFit=String.join("\n",narrow);
        RinexNav.convert(shortFit,models,gps,18,60);
        try{RinexNav.convert(shortFit,models,gps,18,600);throw new AssertionError("End exceeds fit interval");}catch(IOException ok){}

        try{RinexNav.convert(broadcast,models,gps.plusSeconds(3600),18,600);throw new AssertionError("Uncovered end accepted");}catch(IOException ok){}
        try{RinexNav.convert(broadcast,models,gps,18,601);throw new AssertionError("Oversized window accepted");}catch(IOException ok){}
        assert output.equals(expected) : "Android conversion differs from Python reference";
        assert RinexNav.convert(RinexNav.broadcast3(expected),expected,gps,18).equals(expected) : "Domestic RINEX2 round trip changed navigation";
        assert RinexNav.production("header\nCCRINEXN V1.6.0 UX  CDDIS               16-OCT-26 16:26     PGM / RUN BY / DATE").equals(Instant.parse("2026-10-16T16:26:00Z"));
        assert RinexNav.production("header\nBNC 2.13.7 rtproc 20261016 170832 UTC PGM / RUN BY / DATE").equals(Instant.parse("2026-10-16T17:08:32Z"));
        assert RinexNav.production("header\nteqc 2019Feb25 CORS 20261016 16:45:01UTCPGM / RUN BY / DATE").equals(Instant.parse("2026-10-16T16:45:01Z"));
        try {RinexNav.production("<html>error</html>");throw new AssertionError("Missing production accepted");}catch(IOException ok){}
        try { RinexNav.convert(broadcast, models, gps.plusSeconds(86400*30L), 18); throw new AssertionError("Old model accepted"); } catch (IOException ok) { }
        try { RinexNav.convert(broadcast, "<html>error</html>", gps,18); throw new AssertionError("HTML accepted"); } catch (IOException ok) { }
        try { RinexNav.gpsMinusUTC(leaps, Instant.parse("2028-01-01T00:00:00Z")); throw new AssertionError("Expired leaps accepted"); } catch (IOException ok) { }
        System.out.println("PASS: Android navigation conversion equals Python bytes; stale model/HTML/expired leap table rejected");
    }
}
