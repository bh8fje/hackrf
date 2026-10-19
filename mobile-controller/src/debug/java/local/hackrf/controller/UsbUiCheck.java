package local.hackrf.controller;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

/** On-device discovery check. Does not request USB access or send USB commands. */
public class UsbUiCheck extends Instrumentation {
    private boolean reportFound, connectFound;
    private int count;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private void inspect(View view) {
        if (view instanceof TextView) {
            String text=((TextView)view).getText().toString();
            if(text.contains("HackRF One 数量："+count)||(count==1&&(text.contains("HackRF 已连接")||text.contains("正在读取 HackRF")||text.contains("已发现 HackRF"))))reportFound=true;
        }
        if (view instanceof Button && java.util.Arrays.asList("连接 HackRF","读取中…","已连接").contains(((Button)view).getText().toString())) {
            connectFound = true;
            if (count != 1 && view.isEnabled()) throw new AssertionError("USB connect availability mismatch");
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup)view;
            for (int i = 0; i < group.getChildCount(); i++) inspect(group.getChildAt(i));
        }
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            ScenarioCheck.run(getTargetContext());
            for (UsbDevice device : getTargetContext().getSystemService(UsbManager.class).getDeviceList().values())
                if (device.getVendorId() == 0x1d50 && device.getProductId() == 0x6089) count++;
            Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            runOnMainSync(() -> inspect(activity.getWindow().getDecorView()));
            if (!reportFound || !connectFound) throw new AssertionError("Missing USB discovery report or connect control");
            result.putString("stream", "PASS: archive traversal/truncation/metadata/time rejection, USB enumeration and connect control; HackRF count=" + count + "; no RF requested");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) { result.putString("stream", "FAIL: " + error); finish(Activity.RESULT_CANCELED, result); }
    }
}
