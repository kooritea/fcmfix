package sumicya.fcmself.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppCatalogOrderTest {

    private static Set<String> allowList(String... packages) {
        return new HashSet<>(Arrays.asList(packages));
    }

    @Test
    public void allowedFirstThenDetectedThenRest() {
        List<AppInfo> list = new ArrayList<>(Arrays.asList(
                new AppInfo("Zebra", "com.example.zebra", false, false),
                new AppInfo("Alpha", "com.example.alpha", true, false),
                new AppInfo("Omega", "com.example.omega", false, true)));
        AppCatalogOrder.sort(list, allowList("com.example.omega"));

        assertEquals("com.example.omega", list.get(0).packageName);
        assertEquals("com.example.alpha", list.get(1).packageName);
        assertEquals("com.example.zebra", list.get(2).packageName);
    }

    @Test
    public void sortsAlphabeticallyInsideAGroup() {
        List<AppInfo> list = new ArrayList<>(Arrays.asList(
                new AppInfo("Beta", "com.example.b", true, false),
                new AppInfo("Alpha", "com.example.a", true, false)));
        AppCatalogOrder.sort(list, allowList());
        assertEquals("Alpha", list.get(0).name);
        assertEquals("Beta", list.get(1).name);
    }

    @Test
    public void groupingUsesTheAllowListRatherThanTheEntryFlag() {
        AppInfo stale = new AppInfo("Stale", "com.example.stale", true, false);
        assertEquals(AppCatalogOrder.GROUP_ALLOWED,
                AppCatalogOrder.groupOf(stale, allowList("com.example.stale")));
        assertEquals(AppCatalogOrder.GROUP_FCM, AppCatalogOrder.groupOf(stale, allowList()));
        assertEquals(AppCatalogOrder.GROUP_OTHER, AppCatalogOrder.groupOf(
                new AppInfo("Plain", "com.example.plain", false, false), allowList()));
    }

    @Test
    public void structuralCopyIsDetachedFromTheSnapshot() {
        List<AppInfo> source = new ArrayList<>(Arrays.asList(
                new AppInfo("Alpha", "com.example.a", true, true)));
        List<AppInfo> copy = AppCatalogOrder.structuralCopy(source);

        assertNotSame(source.get(0), copy.get(0));
        assertFalse(copy.get(0).isAllow);
        copy.get(0).isAllow = true;
        assertEquals(true, source.get(0).isAllow);
        assertEquals(source.get(0).packageName, copy.get(0).packageName);
    }
}
