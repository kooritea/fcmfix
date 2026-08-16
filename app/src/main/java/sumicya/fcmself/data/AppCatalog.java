package sumicya.fcmself.data;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import sumicya.fcmself.model.AppCatalogOrder;
import sumicya.fcmself.model.AppInfo;

import sumicya.fcmself.model.AppCatalogOrder;
import sumicya.fcmself.model.AppInfo;

/**
 * Process-wide, self-invalidating snapshot of the installed applications.
 *
 * <p>The scan ({@code getInstalledPackages} + label loading + FCM component matching) is expensive
 * and completely independent of any screen, so it lives here instead of inside an Activity:
 *
 * <ul>
 *   <li>a completed scan is reused across rotations and Activity recreations;</li>
 *   <li>concurrent requests are coalesced while a scan is running;</li>
 *   <li>a package event during enumeration bumps the generation and discards the stale result;</li>
 *   <li>results are delivered on the main thread to the currently active {@link Listener} only.</li>
 * </ul>
 */
public final class AppCatalog {

    private static final String TAG = "AppCatalog";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService BG = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "fcmself-apps");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private static final Object LOCK = new Object();
    /** Kept in the order the scan produced; entries carry no allow-state and no icon state. */
    private static List<AppInfo> cachedList;
    private static boolean loadInProgress;
    private static int generation;
    /** Weak so a destroyed screen is never resurrected by a late scan. */
    private static WeakReference<Listener> listenerRef = new WeakReference<>(null);

    /** Receives finished scans on the main thread. */
    public interface Listener {
        /** @return false when the screen can no longer render (finishing/destroyed). */
        boolean isActive();

        /**
         * @param initialLoad true for the first scan of the process; only then is it meaningful to
         *                    warn the user that the package list could not be read.
         */
        void onAppListReady(List<AppInfo> list, boolean initialLoad);
    }

    private AppCatalog() {
    }

    public static void setListener(Listener listener) {
        listenerRef = new WeakReference<>(listener);
    }

    public static void clearListener(Listener listener) {
        if (listenerRef.get() == listener) {
            listenerRef = new WeakReference<>(null);
        }
    }

    /** Monotonic counter; every package event invalidates work started before it. */
    public static int generation() {
        synchronized (LOCK) {
            return generation;
        }
    }

    public static void invalidate() {
        synchronized (LOCK) {
            cachedList = null;
            generation++;
        }
    }

    /** Delivers the cached snapshot, or starts a scan when none is available. */
    public static void load(Context context, Set<String> allowSnapshot) {
        final Context appContext = context.getApplicationContext();
        final List<AppInfo> cached;
        final int scanGeneration;
        synchronized (LOCK) {
            cached = cachedList;
            scanGeneration = generation;
            if (cached == null) {
                if (loadInProgress) {
                    return;
                }
                loadInProgress = true;
            }
        }
        if (cached != null) {
            deliver(AppCatalogOrder.structuralCopy(cached), false);
            return;
        }

        // Snapshot state and the application-scoped PackageManager before leaving the main thread,
        // so the worker never retains a destroyed Activity during a slow package scan.
        final Set<String> allowList = new HashSet<>(allowSnapshot);
        final PackageManager packageManager = appContext.getPackageManager();
        BG.execute(() -> {
            List<AppInfo> scanned;
            try {
                scanned = buildAppList(packageManager, allowList);
            } catch (Throwable t) {
                Log.e(TAG, "buildAppList: " + t);
                scanned = new ArrayList<>();
            }
            final List<AppInfo> result;
            final boolean invalidated;
            synchronized (LOCK) {
                invalidated = scanGeneration != generation;
                if (!invalidated && cachedList == null) {
                    cachedList = AppCatalogOrder.structuralCopy(scanned);
                }
                result = invalidated ? null : cachedList;
                loadInProgress = false;
            }
            MAIN.post(() -> {
                Listener listener = activeListener();
                if (listener == null) {
                    return;
                }
                if (invalidated || generation() != scanGeneration) {
                    // A package changed during enumeration. Discard the stale snapshot and start
                    // one coalesced scan against the new PackageManager state.
                    load(appContext, allowList);
                    return;
                }
                listener.onAppListReady(AppCatalogOrder.structuralCopy(result), true);
            });
        });
    }

    /** Incrementally re-reads a single package instead of rescanning every installed app. */
    public static void refreshPackage(
            Context context, String packageName, boolean removed, Set<String> allowSnapshot) {
        final Context appContext = context.getApplicationContext();
        final boolean requireFullScan;
        synchronized (LOCK) {
            requireFullScan = cachedList == null || loadInProgress;
            generation++;
            if (requireFullScan) {
                cachedList = null;
            }
        }
        if (requireFullScan) {
            load(appContext, allowSnapshot);
            return;
        }

        final PackageManager packageManager = appContext.getPackageManager();
        final Set<String> allowList = new HashSet<>(allowSnapshot);
        BG.execute(() -> {
            AppInfo refreshed =
                    removed ? null : loadSingleApp(packageManager, packageName, allowList);
            final List<AppInfo> result;
            synchronized (LOCK) {
                if (cachedList == null) {
                    result = null;
                } else {
                    List<AppInfo> updated = AppCatalogOrder.structuralCopy(cachedList);
                    updated.removeIf(item -> packageName.equals(item.packageName));
                    if (refreshed != null) {
                        updated.add(refreshed);
                    }
                    AppCatalogOrder.sort(updated, allowList);
                    cachedList = AppCatalogOrder.structuralCopy(updated);
                    result = cachedList;
                }
            }
            MAIN.post(() -> {
                Listener listener = activeListener();
                if (listener == null) {
                    return;
                }
                if (result == null) {
                    load(appContext, allowList);
                } else {
                    listener.onAppListReady(AppCatalogOrder.structuralCopy(result), false);
                }
            });
        });
    }

    private static Listener activeListener() {
        Listener listener = listenerRef.get();
        return listener != null && listener.isActive() ? listener : null;
    }

    private static void deliver(List<AppInfo> list, boolean initialLoad) {
        Listener listener = activeListener();
        if (listener != null) {
            listener.onAppListReady(list, initialLoad);
        }
    }

    static List<AppInfo> buildAppList(PackageManager pm, Set<String> allowList) {
        return AppScanner.scanAll(pm, allowList);
    }

    static AppInfo loadSingleApp(PackageManager pm, String packageName, Set<String> allowList) {
        return AppScanner.scanOne(pm, packageName, allowList);
    }
