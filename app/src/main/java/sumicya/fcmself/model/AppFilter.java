package sumicya.fcmself.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure search + tab filtering, shared by the grid and by the counters in the dock. */
public final class AppFilter {

    private AppFilter() {
    }

    /**
     * @param query already trimmed and lower-cased ({@code null} is treated as empty).
     * @return a new list; the source list and its entries are never modified.
     */
    public static List<AppInfo> apply(List<AppInfo> source, FilterMode mode, String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<AppInfo> filtered = new ArrayList<>(source.size());
        for (AppInfo info : source) {
            if (!info.matches(normalized)) {
                continue;
            }
            switch (mode) {
                case FCM_ONLY:
                    if (!info.includeFcm) continue;
                    break;
                case ALLOWED:
                    if (!info.isAllow) continue;
                    break;
                case DISALLOWED:
                    if (info.isAllow) continue;
                    break;
                case ALL:
                default:
                    break;
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