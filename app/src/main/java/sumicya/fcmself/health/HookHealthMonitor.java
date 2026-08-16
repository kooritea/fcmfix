package sumicya.fcmself.health;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import sumicya.fcmself.core.Contract;

/**
 * Asks every hooked process how it is doing and streams the rendered answer back to the panel.
 *
 * <p>Requests are nonce tagged: answers to a previous request (or from another instance of the
 * app) are ignored, and a short timeout re-renders the panel so processes that never answer are
 * shown as "未响应" instead of leaving the panel on "正在检查…".
 */
public final class HookHealthMonitor extends BroadcastReceiver {

    private static final String TAG = "HookHealthMonitor";
    private static final long RESPONSE_GRACE_MS = 700L;

    /** Live state of the UI process, folded into the rendered report. */
    public interface StatusProvider {
        boolean isServiceConnected();

        boolean isConfigReady();
    }

    /** Receives the rendered panel text on the main thread. */
    public interface Output {
        void onHealthText(String text);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, HookHealthReport.ProcessReport> responses = new HashMap<>();
    private final Context context;
    private final StatusProvider statusProvider;

    private boolean registered;
    private String nonce;
    private Output output;

    public HookHealthMonitor(Context context, StatusProvider statusProvider) {
        this.context = context.getApplicationContext();
        this.statusProvider = statusProvider;
    }

    /** Starts a new query; previous answers are discarded. */
    public void request(Output target) {
        register();
        output = target;
        nonce = UUID.randomUUID().toString();
        responses.clear();

        Intent request = new Intent(Contract.HEALTH_REQUEST_ACTION);
        request.putExtra(Contract.EXTRA_NONCE, nonce);
        context.sendBroadcast(request);

        final String expected = nonce;
        main.postDelayed(() -> {
            if (expected.equals(nonce)) {
                publish();
            }
        }, RESPONSE_GRACE_MS);
    }

    /** Stops rendering into the current panel (the panel was collapsed). */
    public void stop() {
        output = null;
        nonce = null;
    }

    /** Releases the receiver; call from {@code onDestroy}. */
    public void release() {
        stop();
        if (!registered) {
            return;
        }
        try {
            context.unregisterReceiver(this);
        } catch (Throwable ignored) {
            // Already gone; nothing to release.
        } finally {
            registered = false;
        }
    }

    @Override
    public void onReceive(Context ignored, Intent intent) {
        if (!Contract.HEALTH_RESPONSE_ACTION.equals(intent.getAction())
                || nonce == null
                || !nonce.equals(intent.getStringExtra(Contract.EXTRA_NONCE))) {
            return;
        }
        String process = intent.getStringExtra(Contract.EXTRA_PROCESS);
        if (process == null) {
            return;
        }
        ArrayList<String> modules =
                intent.getStringArrayListExtra(Contract.EXTRA_MODULES);
        ArrayList<String> details =
                intent.getStringArrayListExtra(Contract.EXTRA_DETAILS);
        responses.put(process, new HookHealthReport.ProcessReport(
                intent.getBooleanExtra(Contract.EXTRA_BOOT_COMPLETE, false),
                intent.getBooleanExtra(Contract.EXTRA_CONFIG_READY, false),
                modules,
                details));
        publish();
    }

    private void publish() {
        Output target = output;
        if (target == null) {
            return;
        }
        target.onHealthText(HookHealthReport.render(
                statusProvider.isServiceConnected(),
                statusProvider.isConfigReady(),
                responses));
    }

    private void register() {
        if (registered) {
            return;
        }
        try {
            ContextCompat.registerReceiver(
                    context,
                    this,
                    new IntentFilter(Contract.HEALTH_RESPONSE_ACTION),
                    ContextCompat.RECEIVER_EXPORTED);
            registered = true;
        } catch (Throwable e) {
            Log.w(TAG, "register: " + e);
        }
    }
}
