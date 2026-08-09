package sumicya.fcmself;

public final class FcmConstants {
    private FcmConstants() {}

    public static final String TAG = "FcmSelf";
    public static final String GMS_PACKAGE = "com.google.android.gms";

    // FCM Actions
    public static final String C2DM_RECEIVE_SUFFIX = ".android.c2dm.intent.RECEIVE";
    public static final String FIREBASE_MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT";
    public static final String FIREBASE_INSTANCE_ID_EVENT = "com.google.firebase.INSTANCE_ID_EVENT";

    // Intent Flags
    public static final int FLAG_INCLUDE_STOPPED_PACKAGES = 0x00000020;

    // PowerExemptionManager reason codes
    public static final int POWER_EXEMPTION_REASON_FCM = 102;
    public static final int POWER_EXEMPTION_REASON_FALLBACK = 0;
    public static final long POWER_EXEMPTION_DURATION_MS = 10_000L;

    // Broadcast intent locked user ID override
    public static final int USER_ID_ALL_OVERRIDE = 11;
    public static final int USER_ID_NONE = -1;
}
