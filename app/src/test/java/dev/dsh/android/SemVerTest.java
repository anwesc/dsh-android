package dev.dsh.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for {@link SemVer}, the rule that decides whether the APK may overwrite a plugin
 * already installed in the profile.
 *
 * <p>Runs on the JVM ({@code ./gradlew testDebugUnitTest}): mis-ordering two versions is the
 * difference between "a market install survives every boot" and "it is silently rolled back".</p>
 */
public class SemVerTest {

    @Test
    public void acceptsPlainAndSuffixedVersions() {
        assertTrue(SemVer.isValid("1.0.0"));
        assertTrue(SemVer.isValid("0.1.0"));
        assertTrue(SemVer.isValid("10.20.30"));
        assertTrue(SemVer.isValid("1.2.3-beta.2"));
        assertTrue(SemVer.isValid("1.2.3+build.5"));
        assertTrue(SemVer.isValid("1.2.3-rc.1+sha.abc"));
        assertTrue("surrounding whitespace is tolerated", SemVer.isValid(" 1.0.0 "));
    }

    @Test
    public void rejectsMalformedVersions() {
        assertFalse(SemVer.isValid(null));
        assertFalse(SemVer.isValid(""));
        assertFalse(SemVer.isValid("1.0"));
        assertFalse(SemVer.isValid("1.0.0.0"));
        assertFalse(SemVer.isValid("v1.0.0"));
        assertFalse(SemVer.isValid("1.0.0-"));
        assertFalse(SemVer.isValid("1.0.0 beta"));
        assertFalse("a 10-digit core number would overflow int", SemVer.isValid("1234567890.0.0"));
    }

    @Test
    public void ordersCoreNumbers() {
        assertTrue(SemVer.compare("1.0.0", "0.9.9") > 0);
        assertTrue(SemVer.compare("0.1.0", "1.0.0") < 0);
        assertEquals(0, SemVer.compare("1.0.0", "1.0.0"));
        assertEquals("build metadata is ignored", 0, SemVer.compare("1.0.0+abc", "1.0.0+xyz"));
    }

    @Test
    public void prereleaseSortsBelowRelease() {
        assertTrue(SemVer.compare("1.0.0", "1.0.0-beta") > 0);
        assertTrue(SemVer.compare("1.0.0-beta", "1.0.0") < 0);
        // The ordering semver §11 uses as its own example chain.
        String[] chain = {
                "1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
                "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0",
        };
        for (int i = 1; i < chain.length; i++) {
            assertTrue(chain[i - 1] + " < " + chain[i], SemVer.compare(chain[i - 1], chain[i]) < 0);
            assertTrue(chain[i] + " > " + chain[i - 1], SemVer.compare(chain[i], chain[i - 1]) > 0);
        }
    }

    @Test
    public void numericPrereleaseRanksBelowAlphanumeric() {
        assertTrue(SemVer.compare("1.0.0-2", "1.0.0-alpha") < 0);
        assertEquals("leading zeros do not change the number", 0,
                SemVer.compare("1.0.0-007", "1.0.0-7"));
    }

    @Test
    public void compareRejectsInvalidInput() {
        try {
            SemVer.compare("1.0", "1.0.0");
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("compare must reject an unparsable version");
    }
}
