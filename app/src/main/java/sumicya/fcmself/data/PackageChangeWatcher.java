package sumicya.fcmself.data;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;

/**
 * Watches package install/remove/replace events so the grid stays in sync without polling.
 *
 * <p>These are protected system broadcasts (only the OS can send them), so the receiver is
 * registered non-exported: that narrows the attack surface and still receives every system package
 * event — exporting it would let no legitimate extra sender in.
 */
public final class PackageChangeWatcher extends BroadcastReceiver {

    private static final String TAG = "PackageChangeWatcher";

    public interface Callbacks {
        /** A change that cannot be attributed to a single package: rebuild everything. */
        void onAllPackagesChanged();

        void onPackageChanged(String packageName, boolean removed);
    }

    private final Callbacks callbacks;
    private boolean registered;

    public PackageChangeWatcher(Callbacks callbacks) {
        this.callbacks = callbacks;
    }

    public void register(Context context) {
        if (registered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(this, filter);
            }
            registered = true;
        } catch (Throwable e) {
            Log.w(TAG, "register: " + e);
        }
    }

    public void unregister(Context context) {
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
    public void onReceive(Context context, Intent intent) {
        String packageName = intent.getData() != null
                ? intent.getData().getSchemeSpecificPart()
                : null;
        if (packageName == null) {
            IconLoader.evictAll();
            callbacks.onAllPackagesChanged();
            return;
        }
        IconLoader.evict(packageName);
        boolean removed = Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction());
        if (removed && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            // PACKAGE_ADDED/REPLACED will provide the new metadata moments later.
            return;
        }
        callbacks.onPackageChanged(packageName, removed);
    }
}
