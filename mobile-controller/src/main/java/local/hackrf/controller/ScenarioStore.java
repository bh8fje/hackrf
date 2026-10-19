package local.hackrf.controller;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ScenarioStore {
    static File current(Context context) {
        String id = context.getSharedPreferences("controller", 0).getString("scenario", "");
        if (!id.matches("[a-f0-9-]{36}")) return null;
        File folder = new File(context.getFilesDir(), "scenarios/" + id);
        return new File(folder, "manifest.json").isFile() ? folder : null;
    }
    static JSONObject manifest(File folder) throws Exception {
        File file = new File(folder, "manifest.json");
        if (file.length() > 200000) throw new IOException("场景信息过大");
        JSONObject value = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        validate(value);
        if(isSampled(value)) {
            String navName="broadcast-"+system(value)+".rnx";
            JSONObject hashes=value.getJSONObject("preparedFilesSHA256");if(hashes.length()!=3)throw new IOException("导航信号准备文件清单无效");
            for(String name:new String[]{"scenario.json",navName,"emitted.csv"}) {
                File prepared=new File(folder,name);
                if(prepared.length()>1000000 || !hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(prepared.toPath()))).equals(hashes.getString(name)))throw new IOException("导航信号准备文件已变化："+name);
            }
            JSONObject nav=value.getJSONObject("navigation");java.time.Instant start=java.time.Instant.parse(nav.getString("startUTC"));
            File table=new File(folder,"source-iers.dat");if(table.length()>100000)throw new IOException("闰秒表过大");
            String leaps=new String(Files.readAllBytes(table.toPath()),StandardCharsets.US_ASCII);int offset=RinexNav.gpsMinusUTC(leaps,start);
            if(RinexNav.gpsMinusUTC(leaps,start.plusSeconds(value.getInt("durationSeconds")))!=offset)throw new IOException("导航信号场景跨越闰秒");
            String rinex=new String(Files.readAllBytes(new File(folder,navName).toPath()),StandardCharsets.US_ASCII);
            if(isGalileo(value))GalileoNavigation.convert(rinex,rinex,start,offset,value.getInt("durationSeconds"));
            else if(isGlonass(value))GlonassNavigation.convert(rinex,rinex,start,offset,value.getInt("durationSeconds"));
            else BdsNavigation.convert(rinex,rinex,start,offset,value.getInt("durationSeconds"));
        }
        return value;
    }
    static boolean isGalileo(JSONObject m){return "hackrf-galileo-iq".equals(m.optString("type"));}
    static boolean isGlonass(JSONObject m){return "hackrf-glonass-iq".equals(m.optString("type"));}
    static boolean isSampled(JSONObject m){return isBds(m)||isGalileo(m)||isGlonass(m);}
    static String system(JSONObject m){return isGalileo(m)?"galileo":isGlonass(m)?"glonass":isBds(m)?"bds":"gps";}
    static boolean isBds(JSONObject m){return "hackrf-bds-iq".equals(m.optString("type"));}
    static void validate(JSONObject m) throws Exception {
        int duration = m.getInt("durationSeconds");
        int schema = m.getInt("schema");
        boolean bds=isBds(m),galileo=isGalileo(m),glonass=isGlonass(m),sampled=bds||galileo||glonass;int rate=galileo?16000000:glonass?10000000:bds?5000000:2600000;long frequency=glonass?1602000000L:bds?1561098000L:1575420000L;
        boolean mac = schema == 1 && "GNSS-SDR".equals(m.optString("validatedBy")) && m.optInt("validFixes") >= 100
                && Double.isFinite(m.optDouble("maximumError3dM")) && m.optDouble("maximumError3dM") >= 0 && m.optDouble("maximumError3dM") <= 30;
        boolean local = schema == 2 && "android-local".equals(m.optString("origin")) && "length-sha256".equals(m.optString("validatedBy"))
                && Boolean.FALSE.equals(m.opt("softwareReceiverVerified")) && m.optString("generatorSourceSHA256").matches("[a-f0-9]{64}");
        if ((!mac && !local) || !(sampled || "hackrf-gps-iq".equals(m.getString("type"))) || !"s8-iq".equals(m.getString("format"))
                || m.getInt("sampleRate") != rate || m.getLong("frequencyHz") != frequency
                || duration < 60 || duration > 600 || m.getDouble("durationSeconds") != duration || m.getDouble("sampleRate") != rate
                || m.getLong("iqBytes") != duration * (long)rate * 2 || m.getDouble("iqBytes") != m.getLong("iqBytes")
                || !m.getString("iqSHA256").matches("[a-f0-9]{64}") || !Boolean.TRUE.equals(m.opt("passed"))
                || !"current-broadcast".equals(m.getJSONObject("navigation").getString("mode"))) throw new IOException("场景格式或验证信息无效");
        if(sampled && (!local || !Boolean.TRUE.equals(m.opt("hardwareOutputAllowed")) || !Boolean.TRUE.equals(m.opt("plannedTransmission")) || duration<90))throw new IOException("场景尚不满足发送条件");
        OffsetDateTime start=OffsetDateTime.parse(m.getJSONObject("navigation").getString("startUTC"));
        if(sampled && !start.plusSeconds(duration).toInstant().equals(OffsetDateTime.parse(m.getJSONObject("navigation").getString("coverageCheckedThroughUTC")).toInstant()))throw new IOException("导航信号完整时段覆盖不匹配");
        OffsetDateTime.parse(m.getJSONObject("navigation").getString("broadcastProducedAtUTC"));
    }
    static long delay(JSONObject m, long now) throws Exception {
        JSONObject navigation = m.getJSONObject("navigation");
        long start = OffsetDateTime.parse(navigation.getString("startUTC")).toInstant().toEpochMilli();
        long produced = OffsetDateTime.parse(navigation.getString("broadcastProducedAtUTC")).toInstant().toEpochMilli();
        long delay = start-now;
        if (delay < -1000 || delay > (isSampled(m)?600000:240000) || now-produced < -60000 || now-produced > 2100000)
            throw new IOException("场景已过期或时间不合理，请重新生成当前场景");
        return Math.max(0, delay);
    }
    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(); for (byte value : bytes) result.append(String.format(java.util.Locale.US, "%02x", value & 255)); return result.toString();
    }
    static File importArchive(Context context, Uri uri) throws Exception {
        File folder = new File(context.getFilesDir(), "scenarios/" + UUID.randomUUID());
        if (!folder.mkdirs()) throw new IOException("无法创建场景目录");
        boolean complete = false;
        try (InputStream source = context.getContentResolver().openInputStream(uri)) {
            if (source == null) throw new IOException("无法打开场景包");
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(source))) {
                ZipEntry entry = zip.getNextEntry();
                if (entry == null || !"manifest.json".equals(entry.getName())) throw new IOException("包首项必须为 manifest.json");
                ByteArrayOutputStream metadata = new ByteArrayOutputStream();
                byte[] buffer = new byte[262144]; int n;
                while ((n = zip.read(buffer)) != -1) { if (metadata.size()+n > 200000) throw new IOException("场景信息过大"); metadata.write(buffer, 0, n); }
                Files.write(new File(folder, "manifest.json").toPath(), metadata.toByteArray());
                JSONObject m = manifest(folder);
                if (m.getInt("schema") != 1) throw new IOException("外部导入只接受 Mac 独立验证包");
                long expected = m.getLong("iqBytes");
                if (folder.getUsableSpace() < expected + 100000000) throw new IOException("手机存储空间不足");
                entry = zip.getNextEntry();
                if (entry == null || !"signal.bin".equals(entry.getName())) throw new IOException("包缺少 signal.bin");
                MessageDigest digest = MessageDigest.getInstance("SHA-256"); long bytes = 0;
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(new File(folder, "signal.bin")))) {
                    while ((n = zip.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("导入已停止");
                        bytes += n; if (bytes > expected) throw new IOException("I/Q 超过声明长度");
                        digest.update(buffer, 0, n); output.write(buffer, 0, n);
                    }
                }
                if (bytes != expected || !hex(digest.digest()).equals(m.getString("iqSHA256"))) throw new IOException("I/Q 长度或 SHA-256 不匹配");
                if (zip.getNextEntry() != null) throw new IOException("场景包包含额外文件");
            }
            context.getSharedPreferences("controller", 0).edit().putString("scenario", folder.getName()).commit();
            complete = true; return folder;
        } finally {
            if (!complete) { new File(folder, "signal.bin").delete(); new File(folder, "manifest.json").delete(); folder.delete(); }
        }
    }
}
