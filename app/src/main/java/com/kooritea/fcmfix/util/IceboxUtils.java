package com.kooritea.fcmfix.util;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

import java.util.Objects;

public class IceboxUtils extends BroadcastReceiver {
    public final static int REQUEST_CODE = 0x2333;
    public final static String PACKAGE_NAME = "com.catchingnow.icebox";
    public final static String SDK_PERMISSION = PACKAGE_NAME + ".SDK";
    private final static int FLAG_HIDDEN = 1 << 27;
    private static final Uri PERMISSION_URI = Uri.parse("content://" + PACKAGE_NAME + ".SDK");
    private static final Uri NO_PERMISSION_URI = Uri.parse("content://" + PACKAGE_NAME + ".STATE");
    private static final String TAG = "IceboxUtils";
    private static boolean isIceBoxWorking = false;
    private static PendingIntent authorizedPendingIntent = null;

    public static PendingIntent queryPermission(Context context) {
        if (authorizedPendingIntent == null) {
            authorizedPendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE, new Intent(context, IceboxUtils.class), PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        return authorizedPendingIntent;
    }

    private static boolean queryWorkMode(Context context) {
        try {
            Bundle extra = new Bundle();
            extra.putParcelable("authorize", queryPermission(context));
            Bundle bundle = context.getContentResolver().call(NO_PERMISSION_URI, "query_mode", null, extra);
            assert bundle != null;
            return !Objects.equals(bundle.getString("work_mode", null), "MODE_NOT_AVAILABLE");
        } catch (Exception e) {
            Log.e(TAG, "[icebox] queryWorkMode: " + e.getMessage());
            return false;
        }
    }

    public static boolean isAppEnabled(Context context, String packageName) {
        try {
            ApplicationInfo applicationInfo = context.getPackageManager().getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS);
            return applicationInfo.enabled && (applicationInfo.flags | FLAG_HIDDEN) != applicationInfo.flags;
        } catch (Exception e) {
            Log.e(TAG, "[icebox] " + packageName + " " + e.getMessage());
        }
        return true;
    }

    @RequiresPermission(SDK_PERMISSION)
    public static void enableApp(Context context, boolean enable, String... packageNames) {
        int userHandle = Process.myUserHandle().hashCode();
        Bundle extra = new Bundle();
        extra.putParcelable("authorize", queryPermission(context));
        extra.putStringArray("package_names", packageNames);
        extra.putInt("user_handle", userHandle);
        extra.putBoolean("enable", enable);
        context.getContentResolver().call(PERMISSION_URI, "set_enable", null, extra);
    }

    public static void activeApp(Context context, String pkg) {
        try {
            if (!isIceBoxWorking) {
                if (ContextCompat.checkSelfPermission(context, SDK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "[icebox] need permission " + pkg);
                    return;
                }
                if (!queryWorkMode(context)) {
                    Log.e(TAG, "[icebox] is not working...");
                    return;
                }
                isIceBoxWorking = true;
            }
            if (!isAppEnabled(context, pkg)) {
                enableApp(context, true, pkg);
                Log.i(TAG, "[icebox] successfully enable " + pkg);
            } else {
                Log.e(TAG, "[icebox] has been enabled " + pkg);
            }
        } catch (Exception e) {
            Log.e(TAG, "[icebox] " + pkg + " " + e.getMessage());
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
    }
}
