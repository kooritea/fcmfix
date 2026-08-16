package sumicya.fcmself.core;

/**
 * Single source of truth for every string that crosses a process boundary.
 *
 * <p>The UI process (MainActivity and friends) and the hooked processes (system_server,
 * com.miui.powerkeeper, …) exchange broadcasts and remote preferences. Keeping the keys in one
 * framework-free class makes a typo a compile error instead of a silently dead feature.
 */
public final class Contract {

    private Contract() {
    }

    /** Remote preferences file shared through the libxposed service. */
    public static final String PREFERENCES_NAME = "config";

    public static final String UPDATE_CONFIG_ACTION = "sumicya.fcmself.update.config";
    public static final String HEALTH_REQUEST_ACTION = "sumicya.fcmself.health.request";
    public static final String HEALTH_RESPONSE_ACTION = "sumicya.fcmself.health.response";
    public static final String FCM_EVENT_ACTION = "sumicya.fcmself.fcm.event";
    public static final String LOG_ACTION = "sumicya.fcmself.log";
    public static final String UPDATE_CONFIG_PERMISSION =
            "sumicya.fcmself.permission.UPDATE_CONFIG";

    /** Marks a configuration that has been written at least once by the app. */
    public static final String KEY_INIT = "init";
    public static final String KEY_ALLOW_LIST = "allowList";
    public static final String KEY_DISABLE_AUTO_CLEAN = "disableAutoCleanNotification";
    public static final String KEY_INCLUDE_ICEBOX = "includeIceBoxDisableApp";
    public static final String KEY_NO_RESPONSE_NOTIFICATION = "noResponseNotification";
    public static final String KEY_LAST_FCM_ERROR_CODE = "lastFcmErrorCode";

    /** Boolean switches surfaced by the FAB menu, in display order. */
    public static final String[] BOOLEAN_KEYS = {
            KEY_DISABLE_AUTO_CLEAN,
            KEY_INCLUDE_ICEBOX,
            KEY_NO_RESPONSE_NOTIFICATION
    };

    public static final String EXTRA_NONCE = "nonce";
    public static final String EXTRA_PROCESS = "process";
    public static final String EXTRA_BOOT_COMPLETE = "bootComplete";
    public static final String EXTRA_CONFIG_READY = "configReady";
    public static final String EXTRA_MODULES = "modules";
    public static final String EXTRA_DETAILS = "details";

    /** FCM event extras */
    public static final String EXTRA_FCM_PACKAGE = "fcmPackage";
    public static final String EXTRA_FCM_ACTION = "fcmAction";
    public static final String EXTRA_FCM_FIX = "fcmFix";
    public static final String EXTRA_FCM_TIMESTAMP = "fcmTimestamp";

    /** Separates hook name and hook state inside one {@link #EXTRA_DETAILS} entry. */
    public static final char DETAIL_SEPARATOR = '\u0000';

    public static final String PACKAGE_GMS = "com.google.android.gms";
    public static final String PACKAGE_POWERKEEPER = "com.miui.powerkeeper";
    public static final String PACKAGE_SELF = "sumicya.fcmself";
    public static final String GMS_DIAGNOSTICS_ACTIVITY =
            "com.google.android.gms.gcm.GcmDiagnostics";
}
