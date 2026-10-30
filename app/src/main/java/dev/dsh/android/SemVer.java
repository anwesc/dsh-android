package dev.dsh.android;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Semantic version comparison, used only by the {@link PluginSeed} precedence rule.
 *
 * <p>Implemented: {@code major.minor.patch} with an optional {@code -prerelease} suffix;
 * build metadata after {@code +} is ignored, exactly like semver §10. A pre-release sorts
 * below the matching release (semver §11), so {@code 1.0.0-beta.2 < 1.0.0}.</p>
 *
 * <p>Deliberately free of Android APIs: the seed decision is the one piece of the boot path
 * that is pure logic, so it is covered by a JVM unit test ({@code SemVerTest}).</p>
 */
final class SemVer {

    private static final Pattern VERSION =
            Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$");

    private SemVer() {
    }

    /** Whether {@code version} is a plain semantic version this class can compare. */
    static boolean isValid(String version) {
        return parse(version) != null;
    }

    /**
     * Compare two semantic versions.
     *
     * @return positive when {@code left} is newer, negative when older, {@code 0} when equal
     * @throws IllegalArgumentException when either side is not a semantic version — callers
     *                                  must check {@link #isValid} first, because an unparsable
     *                                  version on either side means "cannot tell", not "older"
     */
    static int compare(String left, String right) {
        Part a = parse(left);
        Part b = parse(right);
        if (a == null || b == null) {
            throw new IllegalArgumentException("not a semantic version: " + (a == null ? left : right));
        }
        for (int i = 0; i < a.core.length; i++) {
            int order = Integer.compare(a.core[i], b.core[i]);
            if (order != 0) {
                return order;
            }
        }
        if (a.pre == null || b.pre == null) {
            if (a.pre == null && b.pre == null) {
                return 0;
            }
            return a.pre == null ? 1 : -1;
        }
        return comparePrerelease(a.pre, b.pre);
    }

    /** Split a version into numeric core plus optional prerelease identifiers; null when invalid. */
    private static Part parse(String version) {
        if (version == null) {
            return null;
        }
        Matcher match = VERSION.matcher(version.trim());
        if (!match.matches()) {
            return null;
        }
        int[] core = new int[3];
        for (int i = 0; i < 3; i++) {
            String digits = match.group(i + 1);
            // A core number wider than 9 digits would overflow int; no real plugin needs that.
            if (digits.length() > 9) {
                return null;
            }
            core[i] = Integer.parseInt(digits);
        }
        String prerelease = match.group(4);
        return new Part(core, prerelease == null ? null : prerelease.split("\\.", -1));
    }

    /** semver §11: numeric identifiers compare numerically and rank below alphanumeric ones. */
    private static int comparePrerelease(String[] a, String[] b) {
        int shared = Math.min(a.length, b.length);
        for (int i = 0; i < shared; i++) {
            boolean aNumeric = isNumeric(a[i]);
            boolean bNumeric = isNumeric(b[i]);
            if (aNumeric && bNumeric) {
                int order = compareNumeric(a[i], b[i]);
                if (order != 0) {
                    return order;
                }
            } else if (aNumeric != bNumeric) {
                return aNumeric ? -1 : 1;
            } else {
                int order = a[i].compareTo(b[i]);
                if (order != 0) {
                    return order;
                }
            }
        }
        // A shorter identifier list sorts first: 1.0.0-alpha < 1.0.0-alpha.1.
        return Integer.compare(a.length, b.length);
    }

    /** Compare digit strings as numbers without parsing (avoids overflow on silly input). */
    private static int compareNumeric(String a, String b) {
        String left = stripLeadingZeros(a);
        String right = stripLeadingZeros(b);
        if (left.length() != right.length()) {
            return Integer.compare(left.length(), right.length());
        }
        return left.compareTo(right);
    }

    private static String stripLeadingZeros(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') {
            start++;
        }
        return digits.substring(start);
    }

    private static boolean isNumeric(String identifier) {
        if (identifier.isEmpty()) {
            return false;
        }
        for (int i = 0; i < identifier.length(); i++) {
            if (!Character.isDigit(identifier.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Parsed version: three core numbers plus optional prerelease identifiers. */
    private static final class Part {
        final int[] core;
        final String[] pre;

        Part(int[] core, String[] pre) {
            this.core = core;
            this.pre = pre;
        }
    }
}
