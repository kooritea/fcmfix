package sumicya.fcmself;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Preserves fcmfix's boot wake-up compatibility for OEMs that otherwise defer starting the module
 * app after reboot. The receiver intentionally does no disk or binder work on the broadcast path.
 */
public final class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "fcmself";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "boot completed; module app process is available");
        }
    }
}
