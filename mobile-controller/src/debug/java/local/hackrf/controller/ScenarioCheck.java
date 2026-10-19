package local.hackrf.controller;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.time.Instant;
import java.util.zip.*;

final class ScenarioCheck {
    static JSONObject valid() throws Exception {
        long now = System.currentTimeMillis();
        JSONObject nav = new JSONObject().put("mode", "current-broadcast").put("startUTC", Instant.ofEpochMilli(now+90000).toString())
                .put("broadcastProducedAtUTC", Instant.ofEpochMilli(now).toString());
        return new JSONObject().put("schema", 1).put("type", "hackrf-gps-iq").put("format", "s8-iq").put("sampleRate", 2600000)
                .put("frequencyHz", 1575420000L).put("durationSeconds", 60).put("iqBytes", 312000000L).put("iqSHA256", new String(new char[64]).replace('\0','0'))
                .put("passed", true).put("validatedBy", "GNSS-SDR").put("validFixes", 100).put("maximumError3dM", 2).put("navigation", nav);
    }
    static void run(Context context) throws Exception {
        JSONObject m = valid(); m.put("maximumError3dM",30); ScenarioStore.validate(m);
        if (ScenarioStore.delay(m, System.currentTimeMillis()) <= 0) throw new AssertionError("Valid future scene rejected");
        try { ScenarioStore.delay(m, System.currentTimeMillis()+100000); throw new AssertionError("Stale scene accepted"); } catch (IOException expected) { }
        for (String key : new String[]{"durationSeconds", "passed", "maximumError3dM"}) {
            JSONObject bad = new JSONObject(m.toString());
            if (key.equals("durationSeconds")) bad.put(key, 60.5);
            else if (key.equals("passed")) bad.put(key, "true");
            else bad.put(key, 30.01);
            try { ScenarioStore.validate(bad); throw new AssertionError("Bad metadata accepted: " + key); } catch (IOException expected) { }
        }
        String previous = context.getSharedPreferences("controller",0).getString("scenario", "");
        for (String entry : new String[]{"../manifest.json", "manifest.json"}) {
            File file = File.createTempFile("bad-scene", ".hriq", context.getCacheDir());
            try {
                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
                    zip.putNextEntry(new ZipEntry(entry)); zip.write(m.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
                    zip.putNextEntry(new ZipEntry("signal.bin")); zip.write(new byte[]{1,2}); zip.closeEntry();
                }
                try { ScenarioStore.importArchive(context, Uri.fromFile(file)); throw new AssertionError("Bad archive accepted"); } catch (IOException expected) { }
                if (!previous.equals(context.getSharedPreferences("controller",0).getString("scenario",""))) throw new AssertionError("Failed import replaced scene");
            } finally { file.delete(); }
        }
    }
}
