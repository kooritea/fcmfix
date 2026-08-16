package sumicya.fcmself.data;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import sumicya.fcmself.model.AppInfo;

/**
 * Bounded, process-wide icon cache with off-main-thread decoding.
 *
 * <p>Scrolling through hundreds of applications must not retain every icon, so the cache is an
 * {@link LruCache}. Decoding runs on a small pool that never blocks the package scan, and results
 * of a stale scan generation are dropped instead of poisoning the cache after a package change.
 */
public final class IconLoader {

    private static final int CACHE_ENTRIES = 96;
    private static final LruCache<String, Drawable> CACHE = new LruCache<>(CACHE_ENTRIES);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService ICON_EXEC = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())),
            r -> {
                Thread thread = new Thread(r, "fcmself-icon");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });

    /** Invoked on the main thread once an icon became available in the cache. */
    public interface IconReady {
        void onIconReady(String packageName);
    }

    private IconLoader() {
    }

    public static Drawable cached(String packageName) {
        return CACHE.get(packageName);
    }

    public static void evict(String packageName) {
        CACHE.remove(packageName);
    }

    public static void evictAll() {
        CACHE.evictAll();
    }

    /**
     * Loads the icon for {@code info} unless a load is already in flight for it.
     *
     * @param fallback icon used when the package manager refuses to hand out the real one.
     */
    public static void load(
            PackageManager packageManager,
            AppInfo info,
            Drawable fallback,
            IconReady callback) {
        if (info.iconLoading) {
            return;
        }
        info.iconLoading = true;
        final String packageName = info.packageName;
        final int generation = AppCatalog.generation();
        ICON_EXEC.execute(() -> {
            Drawable loaded;
            try {
                loaded = packageManager.getApplicationIcon(packageName);
            } catch (Throwable t) {
                loaded = fallback;
            }
            if (loaded != null && generation == AppCatalog.generation()) {
                CACHE.put(packageName, loaded);
            }
            info.iconLoading = false;
            MAIN.post(() -> callback.onIconReady(packageName));
        });
    }
}
