package sumicya.fcmself.data;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sumicya.fcmself.model.AppCatalogOrder;
import sumicya.fcmself.model.AppInfo;
import sumicya.fcmself.util.FcmReceiverDetector;

/**
 * Pure scanning logic for building the app list — fully separated from caching/delivery.
 *
 * <p>This class has no state and no threading: it takes a {@link PackageManager} and returns
 * a sorted list of {@link AppInfo}. Every result is independent of any previous call.
 */
public final class AppScanner {

    private static final String TAG = "AppScanner";

    private static final String[] FCM_INTENT_ACTIONS = {
            "com.google.android.c2dm.intent.RECEIVE",
            "com.google.android.c2dm.intent.REGISTRATION",
            "com.google.firebase.MESSAGING_EVENT",
            "com.google.firebase.INSTANCE_ID_EVENT"
    };

    private AppScanner() {
    }

    /** Full scan: all installed apps with FCM detection. */
    public static List<AppInfo> scanAll(PackageManager pm, Set<String> allowList) {
        Set<String> fcmPackages = queryFcmIntentPackages(pm, null);
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(
                    PackageManager.GET_RECEIVERS
                            | PackageManager.GET_SERVICES
                            | PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (Throwable t) {
            Log.e(TAG, "getInstalledPackages: " + t);
            return new ArrayList<>();
        }

        List<AppInfo> result = new ArrayList<>(packages.size());
        for (PackageInfo pkg : packages) {
            AppInfo info = buildInfo(pm, pkg, allowList, fcmPackages);
            if (info != null) result.add(info);
        }
        AppCatalogOrder.sort(result, allowList);
        return result;
    }

    /** Single-package scan: returns null when the package is not found. */
    public static AppInfo scanOne(PackageManager pm, String packageName, Set<String> allowList) {
        try {
            int flags = PackageManager.GET_RECEIVERS
                    | PackageManager.GET_SERVICES
                    | PackageManager.MATCH_DISABLED_COMPONENTS;
            PackageInfo pkg = pm.getPackageInfo(packageName, flags);
            Set<String> fcmPackages = queryFcmIntentPackages(pm, packageName);
            return buildInfo(pm, pkg, allowList, fcmPackages);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "scanOne " + packageName + ": " + t);
            return null;
        }
    }

    // ── Package scanning internals ─────────────────────────────────────

    // Package-visible for unit testing.
    static Set<String> queryFcmIntentPackages(PackageManager pm, String packageName) {
        Set<String> packages = new HashSet<>();
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS;
        for (String action : FCM_INTENT_ACTIONS) {
            Intent intent = new Intent(action);
            if (packageName != null) intent.setPackage(packageName);
            try {
                List<ResolveInfo> receivers = pm.queryBroadcastReceivers(intent, flags);
                if (receivers != null) {
                    for (ResolveInfo r : receivers) {
                        if (r != null && r.activityInfo != null && r.activityInfo.packageName != null) {
                            packages.add(r.activityInfo.packageName);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "query receivers failed for " + action + ": " + t);
            }
            try {
                List<ResolveInfo> services = pm.queryIntentServices(intent, flags);
                if (services != null) {
                    for (ResolveInfo r : services) {
                        if (r != null && r.serviceInfo != null && r.serviceInfo.packageName != null) {
                            packages.add(r.serviceInfo.packageName);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "query services failed for " + action + ": " + t);
            }
        }
        return packages;
    }

    private static AppInfo buildInfo(
            PackageManager pm,
            PackageInfo pkg,
            Set<String> allowList,
            Set<String> fcmPackages) {
        if (pkg.applicationInfo == null) return null;
        boolean hasFcm = fcmPackages.contains(pkg.packageName)
                || hasFcmComponent(pkg.receivers)
                || hasFcmComponent(pkg.services);
        String label;
        try {
            label = pkg.applicationInfo.loadLabel(pm).toString();
        } catch (Throwable t) {
            label = pkg.packageName;
        }
        return new AppInfo(label, pkg.packageName, hasFcm,
                allowList.contains(pkg.packageName));
    }

    private static boolean hasFcmComponent(ActivityInfo[] components) {
        if (components == null) return false;
        for (ActivityInfo c : components) {
            if (c != null && FcmReceiverDetector.isFcmComponent(c.name)) return true;
        }
        return false;
    }

    private static boolean hasFcmComponent(ServiceInfo[] components) {
        if (components == null) return false;
        for (ServiceInfo c : components) {
            if (c != null && FcmReceiverDetector.isFcmComponent(c.name)) return true;
        }
        return false;
    }
}