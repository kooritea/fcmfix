package sumicya.fcmself.xposed;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import sumicya.fcmself.libxposed.XC_MethodHook;
import sumicya.fcmself.libxposed.XposedBridge;
import sumicya.fcmself.libxposed.XposedHelpers;

import sumicya.fcmself.util.IceboxUtils;
import sumicya.fcmself.util.XposedUtils;

/**
 * Core FCM wake-up fix for Android 16. Locked path only (BroadcastController / AMS).
 */
public class BroadcastFix extends XposedModule {

    private static volatile Class<?> sPemClass;
    private static volatile Method sPemAddMethod;
    private static volatile Object sPemService;
    private static volatile boolean sPemResolved;
    private static final long TEMP_ALLOWLIST_DURATION_MS = 5_000L;
    private static final long TEMP_ALLOWLIST_REAPPLY_MS = 2_500L;
    private static final ConcurrentHashMap<String, Long> LAST_TEMP_ALLOWLIST =
            new ConcurrentHashMap<>();

    private static final ExecutorService ICEBOX_EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "fcmself-icebox");
        t.setDaemon(true);
        return t;
    });

    private static final ConcurrentLinkedQueue<String> pendingIcebox = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean iceboxWorkerRunning = new AtomicBoolean(false);

    public BroadcastFix(ClassLoader classLoader) {
        super(classLoader);
        this.startHook();
    }

    private void startHook() {
        boolean lockedInstalled = false;
        try {
            lockedInstalled = this.startHookBroadcastIntentLocked();
        } catch (Throwable e) {
            printLog("hook error broadcastIntentLocked:" + e.getMessage());
        }
        boolean resultHookInstalled = false;
        try {
            this.startHookScheduleResultTo();
            resultHookInstalled = true;
        } catch (Throwable e) {
            printLog("hook error scheduleResultTo:" + e.getMessage());
        }
        // A16-only: locked path is required; no high-level fallback.
        reportHookHealth(
                "BroadcastFix",
                (lockedInstalled ? "核心 locked Hook" : "核心 Hook 未命中")
                        + (resultHookInstalled ? "；结果回调已安装" : "；结果回调不适用")
                        + "；临时豁免 5 秒");
    }

    // NOTE: Full simplified source continues — truncated in this call for length; will follow with complete file if needed.
}
