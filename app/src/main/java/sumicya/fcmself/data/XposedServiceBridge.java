package sumicya.fcmself.data;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Process-wide binding to the libxposed framework service.
 *
 * <p>{@link XposedServiceHelper} keeps a single static listener and consumes its binder cache on
 * the first registration, so the listener must be installed exactly once per process: a second
 * registration from a recreated Activity would never receive another {@code onServiceBind}.
 * Screens attach and detach a weakly held {@link Listener} instead.
 */
public final class XposedServiceBridge {

    private static final String TAG = "XposedServiceBridge";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private static volatile XposedService service;
    private static volatile WeakReference<Listener> listenerRef = new WeakReference<>(null);

    /** Callbacks are always delivered on the main thread. */
    public interface Listener {
        /** @return false when the screen can no longer render (finishing/destroyed). */
        boolean isActive();

        void onXposedServiceConnected();

        void onXposedServiceLost();
    }

    /** Static by design: XposedServiceHelper retains this process-wide listener indefinitely. */
    private static final XposedServiceHelper.OnServiceListener SERVICE_LISTENER =
            new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(@NonNull XposedService bound) {
                    service = bound;
                    MAIN.post(() -> {
                        Listener listener = activeListener();
                        if (listener != null) {
                            listener.onXposedServiceConnected();
                        }
                    });
                }

                @Override
                public void onServiceDied(@NonNull XposedService died) {
                    if (service == died) {
                        service = null;
                    }
                    MAIN.post(() -> {
                        Listener listener = activeListener();
                        if (listener != null) {
                            listener.onXposedServiceLost();
                        }
                    });
                }
            };

    private XposedServiceBridge() {
    }

    /**
     * Registers the process-wide framework listener (only ever once) and immediately syncs a
     * screen against a service that may already be bound from a previous Activity instance.
     */
    public static void attach(Listener listener) {
        listenerRef = new WeakReference<>(listener);
        if (REGISTERED.compareAndSet(false, true)) {
            try {
                XposedServiceHelper.registerListener(SERVICE_LISTENER);
            } catch (Throwable e) {
                REGISTERED.set(false);
                Log.e(TAG, "registerListener: " + e);
            }
        } else if (service != null) {
            // Already bound before this screen existed — no new callback is coming.
            listener.onXposedServiceConnected();
        }
    }

    public static void detach(Listener listener) {
        if (listenerRef.get() == listener) {
            listenerRef = new WeakReference<>(null);
        }
    }

    /** True once {@link XposedServiceHelper#registerListener} succeeded in this process. */
    public static boolean isListenerRegistered() {
        return REGISTERED.get();
    }

    public static boolean isConnected() {
        return service != null;
    }

    /**
     * @return null when no service is bound.
     * @throws Throwable when the bound service refuses the call; callers decide whether that is a
     *                   user-visible configuration error.
     */
    public static SharedPreferences remotePreferences(String name) {
        XposedService current = service;
        return current == null ? null : current.getRemotePreferences(name);
    }

    private static Listener activeListener() {
        Listener listener = listenerRef.get();
        return listener != null && listener.isActive() ? listener : null;
    }
}
