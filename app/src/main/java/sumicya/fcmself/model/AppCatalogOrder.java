package sumicya.fcmself.model;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ordering and projection helpers for the application list.
 *
 * <p>Grouping is intentionally stable: allowed apps first, then apps where an FCM entry point was
 * detected, then everything else; alphabetically by display name inside a group, using the device
 * locale's collator so Chinese labels sort the way the user expects.
 */
public final class AppCatalogOrder {

    public static final int GROUP_ALLOWED = 0;
    public static final int GROUP_FCM = 1;
    public static final int GROUP_OTHER = 2;

    private AppCatalogOrder() {
    }

    public static int groupOf(AppInfo info, Set<String> allowList) {
        if (allowList.contains(info.packageName)) {
            return GROUP_ALLOWED;
        }
        return info.includeFcm ? GROUP_FCM : GROUP_OTHER;
    }

    public static void sort(List<AppInfo> list, Set<String> allowList) {
        final Collator collator = Collator.getInstance(Locale.getDefault());
        list.sort((left, right) -> {
            int leftGroup = groupOf(left, allowList);
            int rightGroup = groupOf(right, allowList);
            if (leftGroup != rightGroup) {
                return Integer.compare(leftGroup, rightGroup);
            }
            return collator.compare(left.name, right.name);
        });
    }

    /**
     * Rebuilds mutable wrappers from a snapshot. The shared cache is never handed out directly, so
     * a live screen can flip {@link AppInfo#isAllow} without mutating what other screens read.
     */
    public static List<AppInfo> structuralCopy(List<AppInfo> source) {
        List<AppInfo> copy = new ArrayList<>(source.size());
        for (AppInfo info : source) {
            copy.add(info.structuralCopy());
        }
        return copy;
    }
}
