package dev.dsh.android;

/**
 * Palette shared by the native dialogs. The page reports its own theme through the JS bridge, so
 * a light harness does not get dark maintenance sheets.
 */
final class Theme {
    private static volatile boolean dark = true;

    private Theme() {
    }

    /**
     * Update the theme from the page.
     *
     * @param mode "light" or "dark"
     */
    static void set(String mode) {
        dark = !"light".equalsIgnoreCase(mode);
    }

    static boolean isDark() {
        return dark;
    }

    static int background() {
        return dark ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    static int row() {
        return dark ? 0xFF2A2A2D : 0xFFF2F2F5;
    }

    static int text() {
        return dark ? 0xFFECECEC : 0xFF1A1A1A;
    }

    static int muted() {
        return dark ? 0xFF9A9A9F : 0xFF6B6B70;
    }

    static int accent() {
        return dark ? 0xFF7FD1C7 : 0xFF0F7A6F;
    }

    static int danger() {
        return dark ? 0xFFE08A7A : 0xFFB4402C;
    }
}
