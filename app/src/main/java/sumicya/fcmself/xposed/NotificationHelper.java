package sumicya.fcmself.xposed;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper for sending notifications from Xposed hook contexts.
 *
 * <p>All hooks run in the system_server or other privileged processes, so notification permissions
 * are determined by the host process rather than the fcmself APK. Callers must handle potential
 * {@link SecurityException}s where appropriate.
 */
public final class NotificationHelper {

    private static final String CHANNEL_ID = "fcmself";
    private static final String CHANNEL_NAME = "fcmself";
    private static final String CHANNEL_DESC = "[xposed] fcmself";

    /**
     * Monotonic notification ID shared by every fix. {@code System.currentTimeMillis()} overflows
     * int in 2038 and two modules notifying in the same millisecond could collide.
     */
    private static final AtomicInteger NOTIFY_ID_SEQ = new AtomicInteger();

    private final Context context;

    public NotificationHelper(Context context) {
        this.context = context;
    }

    /**
     * Ensures the fcmself notification channel exists. Safe to call repeatedly.
     */
    public void ensureChannel() {
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(CHANNEL_DESC);
            nm.createNotificationChannel(channel);
        }
    }

    /**
     * Sends a simple notification with just a title.
     */
    @SuppressLint({"MissingPermission", "NotificationPermission"})
    public void send(String title) {
        send(title, null, null);
    }

    /**
     * Sends a notification with title and content text.
     */
    @SuppressLint({"MissingPermission", "NotificationPermission"})
    public void send(String title, String content) {
        send(title, content, null);
    }

    /**
     * Sends a notification with title, content, and an optional tap action.
     */
    @SuppressLint({"MissingPermission", "NotificationPermission"})
    public void send(String title, String content, PendingIntent pendingIntent) {
        ensureChannel();
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("[fcmself]" + title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent).setAutoCancel(true);
        }
        try {
            nm.notify(nextId(), builder.build());
        } catch (SecurityException ignored) {
            // Host process may not have notification permission.
        }
    }

    /**
     * Deletes the fcmself notification channel. Used during self-uninstall cleanup.
     */
    public void deleteChannel() {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            if (channel != null) {
                nm.deleteNotificationChannel(channel.getId());
            }
        }
    }

    /** Process-wide positive notification ID; wraps instead of overflowing negative. */
    public static int nextId() {
        return NOTIFY_ID_SEQ.updateAndGet(
                current -> current == Integer.MAX_VALUE ? 1 : current + 1);
    }
}