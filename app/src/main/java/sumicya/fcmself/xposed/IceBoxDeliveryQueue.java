package sumicya.fcmself.xposed;

import android.content.Context;
import android.os.SystemClock;

import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.util.IceboxUtils;

import java.lang.reflect.Member;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the deferred delivery queue for broadcasts targeting IceBox-frozen apps.
 *
 * <p>When a FCM broadcast arrives for an app that is frozen by IceBox, this queue holds the
 * broadcast arguments, activates the app via IceBox, and replays the queued broadcasts once the
 * app becomes available. Each package has its own queue bounded at
 * {@link #MAX_DEFERRED_PER_PACKAGE} entries to prevent memory exhaustion under burst traffic.
 */
public final class IceBoxDeliveryQueue {

    /**
     * Maximum deferred broadcasts retained per package. Beyond this, the broadcast is delivered
     * synchronously (the caller continues) rather than silently dropping messages.
     */
    public static final int MAX_DEFERRED_PER_PACKAGE = 64;

    /** Timeout for waiting IceBox to finish enabling a package. */
    public static final long ICEBOX_TIMEOUT_MS = 30_000L;

    /** Two workers prevent one 30s timeout from blocking every other package. */
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "fcmself-icebox");
        t.setDaemon(true);
        return t;
    });

    private static final ConcurrentHashMap<String, PackageQueue> QUEUES = new ConcurrentHashMap<>();

    // ── Package state ──────────────────────────────────────────────────────

    /** One deferred "intercepted broadcast" for replay. */
    private static final class DeferredBroadcast {
        final Member method;
        final Object receiver;
        final Object[] args;

        DeferredBroadcast(Member method, Object receiver, Object[] args) {
            this.method = method;
            this.receiver = receiver;
            this.args = args.clone();
            for (int i = 0; i < this.args.length; i++) {
                if (this.args[i] instanceof android.content.Intent) {
                    this.args[i] = new android.content.Intent((android.content.Intent) this.args[i]);
                }
            }
        }
    }

    /** Per-package queue with a running flag for the worker. */
    private static final class PackageQueue {
        final ConcurrentLinkedQueue<DeferredBroadcast> broadcasts = new ConcurrentLinkedQueue<>();
        final AtomicInteger size = new AtomicInteger();
        final AtomicBoolean workerRunning = new AtomicBoolean();
    }

    // ── Public API ─────────────────────────────────────────────────────────

    private IceBoxDeliveryQueue() {
    }

    /**
     * Attempts to defer delivery for a frozen app. Returns true when the broadcast was queued and
     * the caller should abort the current synchronous delivery; false if the queue is full and the
     * caller should deliver synchronously.
     */
    public static boolean deferOrDeliver(
            String packageName,
            Member method,
            Object receiver,
            Object[] args) {
        PackageQueue queue = QUEUES.computeIfAbsent(
                packageName, ignored -> new PackageQueue());
        int queued = queue.size.incrementAndGet();
        if (queued > MAX_DEFERRED_PER_PACKAGE) {
            queue.size.decrementAndGet();
            return false;
        }
        queue.broadcasts.offer(new DeferredBroadcast(method, receiver, args));
        startWorker(packageName, queue);
        return true;
    }

    // ── Internal ───────────────────────────────────────────────────────────

    private static void startWorker(String packageName, PackageQueue queue) {
        if (!queue.workerRunning.compareAndSet(false, true)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                Context ctx = XposedModule.getModuleContext();
                if (ctx == null) {
                    return;
                }
                if (!IceboxUtils.isAppEnabled(ctx, packageName)) {
                    IceboxUtils.activeApp(ctx, packageName);
                    long startedAt = SystemClock.elapsedRealtime();
                    long deadline = startedAt + ICEBOX_TIMEOUT_MS;
                    while (!IceboxUtils.isAppEnabled(ctx, packageName)) {
                        long now = SystemClock.elapsedRealtime();
                        if (now >= deadline) break;
                        long elapsed = now - startedAt;
                        long pollInterval = elapsed < 2_000L
                                ? 100L
                                : elapsed < 10_000L ? 250L : 500L;
                        try {
                            Thread.sleep(Math.min(pollInterval, deadline - now));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                if (IceboxUtils.isAppEnabled(ctx, packageName)) {
                    XposedModule.printLog("Send deferred forced-start broadcasts: " + packageName, true);
                } else {
                    XposedModule.printLog("Waiting for IceBox to activate the app timed out: "
                            + packageName, true);
                }

                DeferredBroadcast deferred;
                while ((deferred = queue.broadcasts.poll()) != null) {
                    queue.size.decrementAndGet();
                    try {
                        XposedBridge.invokeOriginalMethod(
                                deferred.method, deferred.receiver, deferred.args);
                    } catch (Throwable e) {
                        XposedModule.printLog("Send Deferred Broadcast Error: " + packageName
                                + " " + e.getMessage(), true);
                    }
                }
            } finally {
                queue.workerRunning.set(false);
                // Close the enqueue-vs-drain race: a delivery may arrive after poll() returned null
                // but before workerRunning was cleared.
                if (!queue.broadcasts.isEmpty()) {
                    startWorker(packageName, queue);
                }
            }
        });
    }
}