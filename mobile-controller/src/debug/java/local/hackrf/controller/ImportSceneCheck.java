package local.hackrf.controller;

import android.app.Activity;
import android.app.Instrumentation;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;

/** Developer provisioning through the same checked importer; never starts USB or RF. */
public class ImportSceneCheck extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        File source = new File(getTargetContext().getCacheDir(), "import.hriq");
        try {
            if (TransmitService.busy) throw new IllegalStateException("先停止传输，再进行开发导入");
            File folder = ScenarioStore.importArchive(getTargetContext(), Uri.fromFile(source));
            result.putString("stream", "PASS: imported and SHA-256 verified: " + folder.getName() + "; no USB or RF started");
            finish(Activity.RESULT_OK, result);
        } catch (Exception e) { result.putString("stream", "FAIL: " + e); finish(Activity.RESULT_CANCELED, result); }
        finally { source.delete(); }
    }
}
