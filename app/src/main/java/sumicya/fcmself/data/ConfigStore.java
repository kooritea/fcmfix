package sumicya.fcmself.data;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import sumicya.fcmself.core.Contract;

/**
 * The allow list and the boolean switches, mirrored into the libxposed remote preferences.
 *
 * <p>Every mutation is write-through: the in-memory state is only kept when the remote write
 * succeeded, otherwise it is rolled back so the grid can never show a check mark for something the
 * hooked processes will not honour. A successful write also broadcasts
 * {@link Contract#UPDATE_CONFIG_ACTION} so already running hooks reload without a reboot.
 */
public final class ConfigStore {

    private static final String TAG = "ConfigStore";

    private final Context context;
    private final Set<String> allowList = new HashSet<>();
    private final boolean[] switches = new boolean[Contract.BOOLEAN_KEYS.length];

    /** True once the allow list was read from the remote configuration at least once. */
    private boolean allowListLoaded;
    private boolean lastError;
    private String lastErrorMessage;

    public ConfigStore(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isAllowListLoaded() {
        return allowListLoaded;
    }

    public boolean hasError() {
        return lastError;
    }

    public String lastErrorMessage() {
        return lastErrorMessage;
    }

    /** True when the service is bound and the last configuration access succeeded. */
    public boolean isReady() {
        return XposedServiceBridge.isConnected() && allowListLoaded && !lastError;
    }

    public boolean isAllowed(String packageName) {
        return allowList.contains(packageName);
    }

    public Set<String> allowListSnapshot() {
        return Collections.unmodifiableSet(new HashSet<>(allowList));
    }

    public boolean getBoolean(String key) {
        int index = indexOf(key);
        return index >= 0 && switches[index];
    }

    /** Re-reads everything from the remote preferences. Never partially applies a failed read. */
    public void load() {
        SharedPreferences preferences = preferencesOrNull();
        if (preferences == null) {
            return;
        }
        try {
            // Read every value before changing live state. Remote/binder preferences can throw a
            // type or transport error; a partial read must not clear checks or mix config versions.
            Set<String> remote = preferences.getStringSet(Contract.KEY_ALLOW_LIST, null);
            boolean initialized = preferences.getBoolean(Contract.KEY_INIT, false);
            boolean[] remoteSwitches = new boolean[Contract.BOOLEAN_KEYS.length];
            for (int i = 0; i < Contract.BOOLEAN_KEYS.length; i++) {
                remoteSwitches[i] = preferences.getBoolean(Contract.BOOLEAN_KEYS[i], false);
            }

            Set<String> updated = null;
            if (remote != null) {
                updated = new HashSet<>(remote);
            } else if (initialized) {
                // Config exists but holds no entries — an empty allow list is authoritative.
                updated = new HashSet<>();
            } else {
                // Fresh install or a temporarily unavailable value: keep any known snapshot.
                Log.i(TAG, "load: config not initialised yet, keeping current allowList");
            }

            if (updated != null) {
                allowList.clear();
                allowList.addAll(updated);
                allowListLoaded = true;
            }
            System.arraycopy(remoteSwitches, 0, switches, 0, remoteSwitches.length);
            lastError = false;
            lastErrorMessage = null;
        } catch (Throwable e) {
            Log.e(TAG, "load: " + e);
            lastError = true;
            lastErrorMessage = String.valueOf(e.getMessage());
        }
    }

    /** @return true when the package is allowed after the call; false means nothing was stored. */
    public boolean allow(String packageName) {
        if (!allowList.add(packageName)) {
            return true;
        }
        if (commit()) {
            return true;
        }
        allowList.remove(packageName);
        return false;
    }

    /** @return true when the package is not allowed after the call. */
    public boolean disallow(String packageName) {
        if (!allowList.remove(packageName)) {
            return true;
        }
        if (commit()) {
            return true;
        }
        allowList.add(packageName);
        return false;
    }

    /** Bulk variant used by "enable all FCM"; rolls the whole batch back on failure. */
    public boolean allowAll(Collection<String> packageNames) {
        Set<String> before = new HashSet<>(allowList);
        allowList.addAll(packageNames);
        if (commit()) {
            return true;
        }
        allowList.clear();
        allowList.addAll(before);
        return false;
    }

    /** @return true when the switch is stored; the in-memory value is rolled back otherwise. */
    public boolean setBoolean(String key, boolean value) {
        int index = indexOf(key);
        if (index < 0) {
            return false;
        }
        boolean previous = switches[index];
        switches[index] = value;
        if (commit()) {
            return true;
        }
        // Keep the in-memory menu state aligned with the last successfully persisted value.
        switches[index] = previous;
        return false;
    }

    private boolean commit() {
        try {
            SharedPreferences preferences = preferencesOrNull();
            if (preferences == null) {
                throw new IllegalStateException("XposedService 未连接，无法写入远程配置");
            }
            SharedPreferences.Editor editor = preferences.edit()
                    .putBoolean(Contract.KEY_INIT, true)
                    .putStringSet(Contract.KEY_ALLOW_LIST, new HashSet<>(allowList));
            for (int i = 0; i < Contract.BOOLEAN_KEYS.length; i++) {
                editor.putBoolean(Contract.BOOLEAN_KEYS[i], switches[i]);
            }
            if (!editor.commit()) {
                throw new IllegalStateException("配置写入失败");
            }
            lastError = false;
            lastErrorMessage = null;
            allowListLoaded = true;
            context.sendBroadcast(new Intent(Contract.UPDATE_CONFIG_ACTION));
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "commit: " + e);
            lastError = true;
            lastErrorMessage = String.valueOf(e.getMessage());
            return false;
        }
    }

    private SharedPreferences preferencesOrNull() {
        try {
            return XposedServiceBridge.remotePreferences(Contract.PREFERENCES_NAME);
        } catch (Throwable e) {
            Log.e(TAG, "remotePreferences: " + e);
            lastError = true;
            lastErrorMessage = String.valueOf(e.getMessage());
            return null;
        }
    }

    /** Reads a value the hooks write back, e.g. the last FCM error code seen in GMS. */
    public String readDiagnosticString(String key) {
        SharedPreferences preferences = preferencesOrNull();
        return preferences == null ? null : preferences.getString(key, "");
    }

    private static int indexOf(String key) {
        for (int i = 0; i < Contract.BOOLEAN_KEYS.length; i++) {
            if (Contract.BOOLEAN_KEYS[i].equals(key)) {
                return i;
            }
        }
        return -1;
    }
}
