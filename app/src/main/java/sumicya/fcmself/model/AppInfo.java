package sumicya.fcmself.model;

import java.util.Locale;

/**
 * One installed application as rendered by the grid.
 *
 * <p>Deliberately framework free: the whole list pipeline (scan, sort, filter, diff) is therefore
 * unit testable on the JVM. {@link #isAllow} stays mutable because the grid flips it in place after
 * a successful write; every other field is derived once and never changes.
 */
public final class AppInfo {

    public final String name;
    public final String packageName;
    /** Lower-cased once so search does not re-allocate per keystroke and per row. */
    public final String searchableName;
    public final String searchablePackage;
    /** Precomputed instead of hashing the package on every RecyclerView ID lookup. */
    public final long stableId;
    public final boolean includeFcm;
    public boolean isAllow;
    public volatile boolean iconLoading;

    public AppInfo(String name, String packageName, boolean includeFcm, boolean isAllow) {
        this.name = name;
        this.packageName = packageName;
        this.searchableName = name.toLowerCase(Locale.ROOT);
        this.searchablePackage = packageName.toLowerCase(Locale.ROOT);
        this.stableId = stableIdForPackage(packageName);
        this.includeFcm = includeFcm;
        this.isAllow = isAllow;
    }

    /** @return a copy that carries no allow-state and no icon state. */
    public AppInfo structuralCopy() {
        return new AppInfo(name, packageName, includeFcm, false);
    }

    /** @param query already trimmed and lower-cased; an empty query matches everything. */
    public boolean matches(String query) {
        return query.isEmpty()
                || searchableName.contains(query)
                || searchablePackage.contains(query);
    }

    /**
     * Tri-state marker actually rendered by a row: {@code 1} allowed, {@code -1} FCM but not
     * allowed, {@code 0} neutral (no FCM found, or the configuration is not readable yet).
     */
    public int statusState(boolean allowListLoaded) {
        if (isAllow) {
            return 1;
        }
        if (!includeFcm || !allowListLoaded) {
            return 0;
        }
        return -1;
    }

    /** FNV-1a over the package name: stable across processes and free of collisions in practice. */
    public static long stableIdForPackage(String packageName) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < packageName.length(); i++) {
            hash ^= packageName.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
