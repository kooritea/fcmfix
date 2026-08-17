package sumicya.fcmself.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppFilterTest {

    private static List<AppInfo> sample() {
        return new ArrayList<>(Arrays.asList(
                new AppInfo("Signal", "org.thoughtcrime.securesms", true, true),
                new AppInfo("Weather", "com.example.weather", false, false),
                new AppInfo("Mail", "com.example.mail", true, false)));
    }

    @Test
    public void allModeKeepsEverything() {
        assertEquals(3, AppFilter.apply(sample(), FilterMode.ALL, "").size());
    }

    @Test
    public void fcmModeKeepsOnlyDetectedApps() {
        List<AppInfo> filtered = AppFilter.apply(sample(), FilterMode.FCM_ONLY, "");
        assertEquals(2, filtered.size());
        for (AppInfo info : filtered) {
            assertEquals(true, info.includeFcm);
        }
    }

    @Test
    public void queryAppliesOnTopOfTheMode() {
        List<AppInfo> filtered = AppFilter.apply(sample(), FilterMode.FCM_ONLY, "mail");
        assertEquals(1, filtered.size());
        assertEquals("com.example.mail", filtered.get(0).packageName);
    }

    @Test
    public void nullQueryBehavesLikeAnEmptyOne() {
        assertEquals(3, AppFilter.apply(sample(), FilterMode.ALL, null).size());
    }

    @Test
    public void filteringNeitherCopiesEntriesNorTouchesTheSource() {
        List<AppInfo> source = sample();
        AppInfo first = source.get(0);
        List<AppInfo> filtered = AppFilter.apply(source, FilterMode.ALL, "signal");
        assertSame(first, filtered.get(0));
        assertEquals(3, source.size());
    }

    @Test
    public void countsDetectedApps() {
        assertEquals(2, AppFilter.countFcm(sample()));
    }

    @Test
    public void allowedEntriesWithoutDetectionAreHiddenByTheFcmTab() {
        // Manually allowed apps keep their check mark, but the FCM tab lists detections only.
        List<AppInfo> list = new ArrayList<>();
        list.add(new AppInfo("Manual", "com.example.manual", false, true));
        Set<String> allowList = new HashSet<>();
        allowList.add("com.example.manual");
        assertEquals(0, AppFilter.apply(list, FilterMode.FCM_ONLY, "").size());
        assertEquals(1, AppFilter.apply(list, FilterMode.ALL, "").size());
        assertEquals(
                AppCatalogOrder.GROUP_ALLOWED,
                AppCatalogOrder.groupOf(list.get(0), allowList));
    }
}
