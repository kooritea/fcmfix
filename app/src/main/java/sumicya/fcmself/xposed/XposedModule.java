package sumicya.fcmself.xposed;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import sumicya.fcmself.core.Contract;
import sumicya.fcmself.libxposed.XposedBridge;

/**
 * Shared base for all Xposed hooks: context bootstrap, remote preferences, health reporting,
 * temporary allow-list helpers, and FCM intent classification.
 */
public abstract class XposedModule {

    protected static final String UPDATE_CONFIG_ACTION = "sumicya.fcmself.UPDATE_CONFIG";
    protected static final String HEALTH_REQUEST_ACTION = "sumicya.fcmself.HEALTH_REQUEST";
    protected static final String UPDATE_CONFIG_PERMISSION = "sumicya.fcmself.permission.UPDATE_CONFIG";

    protected static volatile Context context;
    protected static volatile String selfPackageName = "android";
    protected static volatile boolean isBootComplete;
    private static volatile boolean isInitReceiver;

    private static final ConcurrentHashMap<String, String> HEALTH_DETAILS = new ConcurrentHashMap<>();
    private static final AtomicInteger NOTIFY_ID_SEQ = new AtomicInteger(1);

    protected final ClassLoader classLoader;

    protected XposedModule(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    // --- remainder of full simplified source (27k) continues from artifacts ---
    // This placeholder will be replaced - DO NOT USE
}
