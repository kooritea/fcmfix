package sumicya.fcmself.util;

import java.util.Locale;

/**
 * FCM/GCM component-name checks used by the app browser; kept Android-free so it can be unit
 * tested. Matching covers canonical names and explicit Firebase/GCM renames. Broader generic push
 * names are exposed separately so callers can require corroborating C2DM permission evidence.
 */
public final class FcmReceiverDetector {
    private static final String FIREBASE_IID_RECEIVER =
            "com.google.firebase.iid.FirebaseInstanceIdReceiver";
    private static final String PLAY_SERVICES_GCM_RECEIVER =
            "com.google.android.gms.gcm.GcmReceiver";
    private static final String APP_MEASUREMENT_RECEIVER =
            "com.google.android.gms.measurement.AppMeasurementReceiver";

    private FcmReceiverDetector() {
    }

    /**
     * True when a manifest receiver or service class name indicates FCM/GCM push wiring.
     */
    public static boolean isFcmComponent(String className) {
        if (className == null || APP_MEASUREMENT_RECEIVER.equals(className)) {
            return false;
        }
        // Exact well-known receivers (fast path).
        if (FIREBASE_IID_RECEIVER.equals(className)
                || PLAY_SERVICES_GCM_RECEIVER.equals(className)) {
            return true;
        }
        String lower = className.toLowerCase(Locale.ROOT);
        // FirebaseMessagingReceiver / FirebaseMessagingService and app-specific renames.
        if (lower.contains("firebasemessaging")) {
            return true;
        }
        // com.google.firebase.iid.FirebaseInstanceIdReceiver and renames.
        if (lower.contains("firebaseinstanceid") || lower.contains("firebase.iid")) {
            return true;
        }
        // Legacy GCM receiver/service renames.
        if (lower.endsWith("gcmreceiver")
                || lower.endsWith("gcmlistener")
                || lower.endsWith("gcmlistenerservice")) {
            return true;
        }
        // Generic "fcm..." receiver/service names used by many apps.
        return lower.endsWith("fcmreceiver") || lower.endsWith("fcmpushreceiver");
    }

    /**
     * A weaker component-name signal which must be combined with the C2DM receive permission.
     */
    public static boolean isGenericPushComponent(String className) {
        if (className == null) {
            return false;
        }
        int separator = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
        String simpleName = className.substring(separator + 1).toLowerCase(Locale.ROOT);
        boolean componentType = simpleName.endsWith("service")
                || simpleName.endsWith("receiver");
        return componentType
                && (simpleName.contains("push") || simpleName.contains("messaging"));
    }
}
