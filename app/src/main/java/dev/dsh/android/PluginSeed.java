package dev.dsh.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Seeds the bundled {@code dsh-qqbot-guard} plugin into the harness profile on every boot.
 *
 * <p>The profile lives inside the rootfs ({@code rootfs/root/.dsh/profiles/web}), so a plugin that
 * ships with the APK has to be installed there rather than through the plugin market. This runs
 * before the harness starts and is idempotent: it re-adds the bundle name and repairs a profile
 * that was migrated from an older install, edited by the agent, or rolled back by
 * {@link SafetyNet}.</p>
 *
 * <p><b>Two sources, one package name.</b> The same package can also be installed from npm
 * ({@code dsh plugin --profile web add dsh-qqbot-guard}); the loader resolves bundles by directory
 * name, so both sources write the same {@code node_modules/dsh-qqbot-guard} and one has to win.
 * The installed copy is kept unless it is missing, incomplete, unparsable, or strictly older than
 * the bundled one (compared with {@link SemVer}). A market install therefore survives every boot,
 * while an APK that ships a newer build still upgrades everyone.</p>
 *
 * <p>Deliberately not registered as a {@code dependencies} entry: a {@code file:} specifier makes
 * later pnpm operations depend on the container path layout. The loader resolves bundles from
 * {@code node_modules} by name, so copying the directory plus listing it in
 * {@code dsh.profile.bundles} is enough — and if a package-manager run prunes it, the next boot
 * puts it back.</p>
 */
final class PluginSeed {
    private static final String TAG = "DshSeed";
    /** Package name; must match the directory under node_modules and the bundle list entry. */
    static final String PLUGIN = "dsh-qqbot-guard";
    /** Asset directory holding the plugin sources. */
    private static final String ASSET_DIR = "plugin-seed/" + PLUGIN;
    /** Config file read by the plugin itself; lets the app set keywords without editing YAML. */
    private static final String CONFIG_NAME = "qqbot-guard.json";
    /** Keywords seeded on first boot; the user can edit the JSON afterwards. */
    private static final String[] DEFAULT_TRIGGERS = {"大肥鱼", "肥鱼", "鱼鱼"};

    private PluginSeed() {
    }

    /**
     * Install the plugin into the profile and make sure it is activated.
     *
     * <p>Never throws: a broken seed must not stop the harness from booting.</p>
     *
     * @param context application context, used for its asset manager
     * @param runtime unpacked runtime directory containing {@code rootfs/}
     * @return true when the plugin is in place and listed as a bundle
     */
    static boolean install(Context context, File runtime) {
        File home = new File(runtime, "rootfs/root/.dsh");
        File profile = new File(home, "profiles/web");
        if (!profile.isDirectory()) {
            Log.i(TAG, "profile not found, skipping plugin seed: " + profile);
            return false;
        }
        File pluginDir = new File(profile, "node_modules/" + PLUGIN);
        String bundled = bundledVersion(context.getAssets());
        String installed = installedVersion(pluginDir);
        boolean refresh = shouldRefresh(pluginDir, bundled, installed);
        boolean copied = !refresh || copyPlugin(context.getAssets(), pluginDir);
        boolean listed = ensureBundle(profile);
        ensureConfig(home);
        Log.i(TAG, "plugin seed: action=" + (refresh ? "refresh" : "keep")
                + " bundled=" + describe(bundled) + " installed=" + describe(installed)
                + " copied=" + copied + " listed=" + listed + " at " + profile);
        return copied && listed;
    }

    /**
     * Decide whether the bundled copy should replace what is already in the profile.
     *
     * <p>Installed wins by default — that is what makes a market (or {@code dsh plugin add})
     * install stick. The seed only steps in when the profile copy cannot serve requests:</p>
     *
     * <ul>
     *   <li>{@code index.js} or the bundle patch is gone (pnpm pruned them, or a copy was
     *       interrupted) → repair;</li>
     *   <li>no {@code package.json} → nothing usable is installed;</li>
     *   <li>the installed version is unparsable → the copy is damaged → repair;</li>
     *   <li>both versions parse and the bundled one is strictly newer → upgrade.</li>
     * </ul>
     *
     * <p>An unparsable <em>bundled</em> version never overwrites a valid installed one: a build
     * whose own version tag is broken must not clobber a working profile.</p>
     *
     * @param pluginDir installed plugin directory inside the profile
     * @param bundled   version declared by the asset {@code package.json}, null when unreadable
     * @param installed version declared by the profile {@code package.json}, null when missing
     * @return true when the assets must be copied over the installed directory
     */
    private static boolean shouldRefresh(File pluginDir, String bundled, String installed) {
        if (!new File(pluginDir, "index.js").isFile()
                || !new File(pluginDir, "cordis.patch.yml").isFile()
                || installed == null) {
            return true;
        }
        if (!SemVer.isValid(installed)) {
            Log.w(TAG, "installed plugin version is not semver, repairing: " + installed);
            return true;
        }
        if (!SemVer.isValid(bundled)) {
            return false;
        }
        return SemVer.compare(bundled, installed) > 0;
    }

    /**
     * Read the version declared by the asset {@code package.json}.
     *
     * @param assets asset manager
     * @return the version string, or null when the asset is missing or unparsable
     */
    private static String bundledVersion(AssetManager assets) {
        try (InputStream input = assets.open(ASSET_DIR + "/package.json")) {
            String text = new String(readAll(input), StandardCharsets.UTF_8);
            String version = new JSONObject(text).optString("version", "").trim();
            return version.isEmpty() ? null : version;
        } catch (Exception error) {
            Log.w(TAG, "cannot read bundled plugin version", error);
            return null;
        }
    }

    /**
     * Read the version declared by the plugin installed in the profile.
     *
     * @param pluginDir installed plugin directory
     * @return the version string, or null when there is no readable {@code package.json}
     */
    private static String installedVersion(File pluginDir) {
        File manifest = new File(pluginDir, "package.json");
        if (!manifest.isFile()) {
            return null;
        }
        try {
            String version = new JSONObject(readText(manifest)).optString("version", "").trim();
            return version.isEmpty() ? null : version;
        } catch (Exception error) {
            Log.w(TAG, "cannot read installed plugin version: " + manifest, error);
            return null;
        }
    }

    private static String describe(String version) {
        return version == null ? "?" : version;
    }

    /**
     * Copy every file of the asset plugin directory into the profile.
     *
     * @param assets asset manager
     * @param target destination directory, created when missing
     * @return true when all files were written
     */
    private static boolean copyPlugin(AssetManager assets, File target) {
        try {
            if (!target.isDirectory() && !target.mkdirs()) {
                throw new IOException("cannot create " + target);
            }
            String[] children = assets.list(ASSET_DIR);
            if (children == null || children.length == 0) {
                Log.w(TAG, "no plugin assets under " + ASSET_DIR);
                return false;
            }
            boolean ok = true;
            for (String child : children) {
                ok &= copyAsset(assets, ASSET_DIR + "/" + child, new File(target, child));
            }
            return ok;
        } catch (Exception error) {
            Log.w(TAG, "cannot copy plugin assets", error);
            return false;
        }
    }

    private static boolean copyAsset(AssetManager assets, String name, File destination) {
        try (InputStream input = assets.open(name)) {
            byte[] bytes = readAll(input);
            writeIfChanged(destination, bytes);
            return true;
        } catch (Exception error) {
            Log.w(TAG, "cannot copy asset " + name, error);
            return false;
        }
    }

    /**
     * Add the plugin to {@code dsh.profile.bundles} in the profile package.json.
     *
     * @param profile profile directory
     * @return true when the bundle list contains the plugin afterwards
     */
    private static boolean ensureBundle(File profile) {
        File manifest = new File(profile, "package.json");
        try {
            String text = readText(manifest);
            JSONObject root = new JSONObject(text);
            JSONObject dsh = root.optJSONObject("dsh");
            if (dsh == null) {
                dsh = new JSONObject();
                root.put("dsh", dsh);
            }
            JSONObject profileSection = dsh.optJSONObject("profile");
            if (profileSection == null) {
                profileSection = new JSONObject();
                dsh.put("profile", profileSection);
            }
            JSONArray bundles = profileSection.optJSONArray("bundles");
            if (bundles == null) {
                bundles = new JSONArray();
                profileSection.put("bundles", bundles);
            }
            for (int i = 0; i < bundles.length(); i++) {
                if (PLUGIN.equals(bundles.optString(i))) {
                    return true;
                }
            }
            bundles.put(PLUGIN);
            writeIfChanged(manifest, root.toString(2).concat("\n"));
            Log.i(TAG, "added " + PLUGIN + " to profile bundles");
            return true;
        } catch (Exception error) {
            Log.w(TAG, "cannot update profile bundles", error);
            return false;
        }
    }

    /**
     * Write the keyword config once. Existing files are left alone so user edits survive upgrades.
     *
     * @param home harness home inside the rootfs
     */
    private static void ensureConfig(File home) {
        File config = new File(home, CONFIG_NAME);
        if (config.isFile()) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("enabled", true);
            JSONArray triggers = new JSONArray();
            for (String trigger : DEFAULT_TRIGGERS) {
                triggers.put(trigger);
            }
            json.put("triggers", triggers);
            json.put("_note", "enabled=false 可整体关掉关键词放行；triggers 是群里不 @ 也能触发回复的词；改完重启 App 生效。");
            if (!home.isDirectory() && !home.mkdirs()) {
                throw new IOException("cannot create " + home);
            }
            writeIfChanged(config, json.toString(2).concat("\n"));
            Log.i(TAG, "wrote default keyword config " + config);
        } catch (Exception error) {
            Log.w(TAG, "cannot write keyword config", error);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return new String(readAll(input), StandardCharsets.UTF_8);
        }
    }

    private static void writeIfChanged(File file, String text) throws IOException {
        writeIfChanged(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeIfChanged(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        if (file.isFile() && file.length() == bytes.length) {
            try (InputStream input = new FileInputStream(file)) {
                if (java.util.Arrays.equals(readAll(input), bytes)) {
                    return;
                }
            }
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }
}
