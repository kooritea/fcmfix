package sumicya.fcmself.model;

import java.util.ArrayList;
import java.util.List;

/** Pure search + tab filtering, shared by the grid and by the counters in the dock. */
public final class AppFilter {

    private AppFilter() {
    }

    /**
     * @param query already trimmed and lower-cased ({@code null} is treated as empty).
     * @return a new list; the source list and its entries are never modified.
     */
    public static List<AppInfo> apply(List<AppInfo> source, FilterMode mode, String query) {
        String normalized = query == null ? "" : query;
        List<AppInfo> filtered = new ArrayList<>(source.size());
        for (AppInfo info : source) {
            if (!info.matches(normalized)) {
                continue;
            }
            if (mode == FilterMode.FCM_ONLY && !info.includeFcm) {
                continue;
            }
            filtered.add(info);
        }
        return filtered;
    }

    public static int countFcm(List<AppInfo> source) {
        int total = 0;
        for (AppInfo info : source) {
            if (info.includeFcm) {
                total++;
            }
        }
        return total;
    }
}
