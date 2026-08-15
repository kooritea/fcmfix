package sumicya.fcmself.util;

/** Receiver-name checks used by the app browser; kept Android-free so it can be unit tested. */
public final class FcmReceiverDetector {
    private static final String FIREBASE_IID_RECEIVER =
            "com.google.firebase.iid.FirebaseInstanceIdReceiver";
    private static final String APP_MEASUREMENT_RECEIVER =
            "com.google.android.gms.measurement.AppMeasurementReceiver";

    private FcmReceiverDetector() {
    }

    public static boolean isFcmReceiver(String className) {
        if (className == null || APP_MEASUREMENT_RECEIVER.equals(className)) {
            return false;
        }
        return FIREBASE_IID_RECEIVER.equals(className)
                || className.endsWith(".FirebaseInstanceIdReceiver")
                || className.contains("FirebaseMessaging");
    }
}
