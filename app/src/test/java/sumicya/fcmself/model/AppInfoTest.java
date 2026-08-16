package sumicya.fcmself.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppInfoTest {

    @Test
    public void stableIdIsDeterministicPerPackage() {
        assertEquals(
                AppInfo.stableIdForPackage("com.example.app"),
                new AppInfo("Example", "com.example.app", true, false).stableId);
    }

    @Test
    public void stableIdDistinguishesPackages() {
        assertNotEquals(
                AppInfo.stableIdForPackage("com.example.app"),
                AppInfo.stableIdForPackage("com.example.app2"));
    }

    @Test
    public void searchMatchesNameAndPackageCaseInsensitively() {
        AppInfo info = new AppInfo("Signal Messenger", "org.thoughtcrime.securesms", true, false);
        assertTrue(info.matches(""));
        assertTrue(info.matches("signal"));
        assertTrue(info.matches("securesms"));
        assertFalse(info.matches("telegram"));
    }

    @Test
    public void statusStateFollowsAllowAndDetection() {
        AppInfo allowed = new AppInfo("A", "a", false, true);
        assertEquals(1, allowed.statusState(true));

        AppInfo detected = new AppInfo("B", "b", true, false);
        assertEquals(-1, detected.statusState(true));
        // Configuration not read yet: never render a cross the user cannot act on.
        assertEquals(0, detected.statusState(false));

        AppInfo unsupported = new AppInfo("C", "c", false, false);
        assertEquals(0, unsupported.statusState(true));
    }

    @Test
    public void structuralCopyDropsAllowState() {
        AppInfo source = new AppInfo("A", "a", true, true);
        AppInfo copy = source.structuralCopy();
        assertFalse(copy.isAllow);
        assertEquals(source.packageName, copy.packageName);
        assertEquals(source.includeFcm, copy.includeFcm);
    }
}
