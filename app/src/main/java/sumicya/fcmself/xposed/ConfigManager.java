package sumicya.fcmself.xposed;

import android.content.SharedPreferences;

import sumicya.fcmself.core.Contract;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe config snapshot manager used by all Xposed hooks.
 *
 * <p>Reads the libxposed remote preferences atomically so hook threads never observe a partially
 * updated config version. The config is stored as an immutable {@link Snapshot} and published
 * through a volatile reference — readers see either the complete old or the complete new state.
 */
public final class ConfigManager {

    private static volatile ConfigManager INSTANCE;

    private volatile Snapshot snapshot = Snapshot.EMPTY;

    /** Hot-path cached boolean keys read from the snapshot. */
    private volatile boolean cachedIncludeIceBox = false;
    private volatile boolean cachedNoResponseNotification = false;
    private volatile boolean cachedDisableAutoClean = false;
    private final AtomicLong cacheGeneration = new AtomicLong(0);
    private long lastCacheLoadGeneration = -1;

    // ── Singleton ──────────────────────────────────────────────────────────

    private ConfigManager() {
    }

    /** Returns the process-wide singleton, creating it on first access. */
    public static ConfigManager get() {
        if (INSTANCE == null) {
            synchronized (ConfigManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ConfigManager();
                }
            }
        }
        return INSTANCE;
    }

    // ── Snapshot ───────────────────────────────────────────────────────────

    /** Immutable config state. */
    public static final class Snapshot {
        public static final Snapshot EMPTY = new Snapshot(
                Collections.emptySet(), false, false, false, false);

        public final Set<String> allowList;
        public final boolean disableAutoCleanNotification;
        public final boolean includeIceBoxDisableApp;
        public final boolean noResponseNotification;
        public final boolean ready;

        public Snapshot(
                Set<String> allowList,
                boolean disableAutoCleanNotification,
                boolean includeIceBoxDisableApp,
                boolean noResponseNotification,
                boolean ready) {
            this.allowList = allowList;
            this.disableAutoCleanNotification = disableAutoCleanNotification;
            this.includeIceBoxDisableApp = includeIceBoxDisableApp;
            this.noResponseNotification = noResponseNotification;
            this.ready = ready;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            switch (key) {
                case Contract.KEY_DISABLE_AUTO_CLEAN:
                    return disableAutoCleanNotification;
                case Contract.KEY_INCLUDE_ICEBOX:
                    return includeIceBoxDisableApp;
                case Contract.KEY_NO_RESPONSE_NOTIFICATION:
                    return noResponseNotification;
                default:
                    return defaultValue;
            }
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Returns the current config snapshot — never null. */
    public Snapshot snapshot() {
        return snapshot;
    }

    /** Returns true when the given package is in the current allow list. */
    public boolean isAllowed(String packageName) {
        return snapshot.allowList.contains(packageName);
    }

    /** Returns true when no packages are in the allow list. */
    public boolean isAllowListEmpty() {
        return snapshot.allowList.isEmpty();
    }

    public boolean isConfigReady() {
        return snapshot.ready;
    }

    public boolean getIncludeIceBox() {
        ensureCacheLoaded();
        return cachedIncludeIceBox;
    }

    public boolean getNoResponseNotification() {
        ensureCacheLoaded();
        return cachedNoResponseNotification;
    }

    public boolean getDisableAutoClean() {
        ensureCacheLoaded();
        return cachedDisableAutoClean;
    }

    public int allowListSize() {
        return snapshot.allowList.size();
    }

    // ── Reload ─────────────────────────────────────────────────────────────

    /**
     * Atomically reloads all config from the given remote preferences.
     * Callers should only pass a bound remote preferences instance.
     */
    public void reload(SharedPreferences remotePreferences) {
        if (remotePreferences == null) {
            return;
        }
        try {
            Set<String> loaded = remotePreferences.getStringSet(Contract.KEY_ALLOW_LIST, null);
            boolean initialized = remotePreferences.getBoolean(Contract.KEY_INIT, false);
            Snapshot current = snapshot;
            Set<String> currentAllowList = current.ready
                    ? current.allowList
                    : null;
            Set<String> newAllowList;
            if (loaded != null) {
                newAllowList = Collections.unmodifiableSet(new HashSet<>(loaded));
            } else if (initialized || currentAllowList == null) {
                newAllowList = Collections.emptySet();
            } else {
                newAllowList = currentAllowList;
            }

            snapshot = new Snapshot(
                    newAllowList,
                    remotePreferences.getBoolean(Contract.KEY_DISABLE_AUTO_CLEAN, false),
                    remotePreferences.getBoolean(Contract.KEY_INCLUDE_ICEBOX, false),
                    remotePreferences.getBoolean(Contract.KEY_NO_RESPONSE_NOTIFICATION, false),
                    true);

            // Invalidate cached booleans so they are re-read on next access.
            cacheGeneration.incrementAndGet();
        } catch (Throwable e) {
            XposedModule.reportHookHealth("Config", "读取失败：" + e.getClass().getSimpleName());
        }
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private void ensureCacheLoaded() {
        long gen = cacheGeneration.get();
        if (lastCacheLoadGeneration == gen) {
            return;
        }
        Snapshot s = snapshot;
        cachedIncludeIceBox = s.includeIceBoxDisableApp;
        cachedNoResponseNotification = s.noResponseNotification;
        cachedDisableAutoClean = s.disableAutoCleanNotification;
        lastCacheLoadGeneration = gen;
    }
}