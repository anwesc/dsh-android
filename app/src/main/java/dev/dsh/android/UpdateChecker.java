package dev.dsh.android;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks the GitHub releases feed for a newer APK and hands it to the system installer.
 */
final class UpdateChecker {
    private static final String TAG = "DshUpdate";
    private static final String LATEST =
            "https://api.github.com/repos/anwesc/dsh-android/releases/latest";
    /**
     * Update manifest. The release asset is written by CI next to the APK, so it always
     * matches what was published (version, size, SHA-256, mirrors); the GitHub API stays as
     * a fallback for when the manifest cannot be reached.
     */
    private static final String[] MANIFESTS = {
            "https://github.com/anwesc/dsh-android/releases/latest/download/update.json",
    };

    private UpdateChecker() {
    }

    /** One published release that carries an installable APK. */
    static final class Release {
        final String version;
        final String apkUrl;
        /** Release notes, trimmed for display. */
        final String notes;
        /** SHA-256 of the APK as published, or null when the source did not provide one. */
        final String sha256;
        /** Every URL that serves the APK, primary first. */
        final java.util.List<String> urls;

        Release(String version, String apkUrl) {
            this(version, apkUrl, "", null);
        }

        Release(String version, String apkUrl, String notes) {
            this(version, apkUrl, notes, null);
        }

        Release(String version, String apkUrl, String notes, String sha256) {
            this(version, apkUrl, notes, sha256, null);
        }

        Release(String version, String apkUrl, String notes, String sha256,
                java.util.List<String> mirrors) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.notes = notes == null ? "" : notes;
            this.sha256 = sha256;
            java.util.List<String> all = new java.util.ArrayList<>();
            all.add(apkUrl);
            if (mirrors != null) {
                for (String mirror : mirrors) {
                    if (mirror != null && !mirror.isEmpty() && !all.contains(mirror)) {
                        all.add(mirror);
                    }
                }
            }
            this.urls = java.util.Collections.unmodifiableList(all);
        }
    }

    /**
     * Current application version.
     *
     * @param context application context
     * @return versionName, or "0" when unavailable
     */
    static String currentVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException missing) {
            return "0";
        }
    }

    /**
     * Ask the published update manifests, then the GitHub API, for the latest release.
     *
     * <p>The API is rate limited and blocked in some networks, so a manifest published next to
     * the release is tried first; it also carries the APK's SHA-256.</p>
     *
     * @return the newest release with an APK asset, or null when none qualifies
     * @throws IOException when every source fails
     */
    static Release latest() throws IOException {
        IOException failure = null;
        // The manifests are useful but optional, so they get a small shared budget while the
        // GitHub API stays the dependable fallback for a slow or filtered network.
        long deadline = System.currentTimeMillis() + 8_000;
        for (String source : MANIFESTS) {
            if (System.currentTimeMillis() > deadline) {
                Log.w(TAG, "manifest budget exhausted before " + source);
                break;
            }
            try {
                Release release = fetchManifest(source);
                if (release != null) {
                    return release;
                }
            } catch (IOException error) {
                Log.w(TAG, "manifest " + source + " unavailable", error);
                failure = error;
            }
        }
        try {
            return fetchApi();
        } catch (IOException error) {
            if (failure != null) {
                error.addSuppressed(failure);
            }
            throw error;
        }
    }

    /**
     * Parse one update manifest.
     *
     * @param url manifest URL
     * @return the described release, or null when the manifest is incomplete
     * @throws IOException when the request fails
     */
    private static Release fetchManifest(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        // Manifests are best-effort: a slow or blocked host must not stall the update check.
        connection.setConnectTimeout(4_000);
        connection.setReadTimeout(5_000);
        try (InputStream in = connection.getInputStream()) {
            String body = new String(readFully(in), java.nio.charset.StandardCharsets.UTF_8);
            JSONObject manifest = new JSONObject(body);
            String version = manifest.optString("version", "");
            if (version.startsWith("v") || version.startsWith("V")) {
                version = version.substring(1);
            }
            String sha = manifest.optString("sha256", "");
            java.util.List<String> urls = new java.util.ArrayList<>();
            org.json.JSONArray list = manifest.optJSONArray("urls");
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    String item = list.optString(i, "");
                    if (!item.isEmpty()) {
                        urls.add(item);
                    }
                }
            }
            String apk = manifest.optString("url", "");
            if (apk.isEmpty() && !urls.isEmpty()) {
                apk = urls.remove(0);
            }
            if (version.isEmpty() || apk.isEmpty()) {
                return null;
            }
            return new Release(version, apk, plain(manifest.optString("notes", "")),
                    sha.isEmpty() ? null : sha, urls);
        } catch (org.json.JSONException malformed) {
            throw new IOException("malformed manifest", malformed);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Ask GitHub for the latest release.
     *
     * @return the newest release with an APK asset, or null when none qualifies
     * @throws IOException when the request fails
     */
    private static Release fetchApi() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST).openConnection();
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        try (InputStream in = connection.getInputStream()) {
            String body = new String(readFully(in), java.nio.charset.StandardCharsets.UTF_8);
            JSONObject release = new JSONObject(body);
            String tag = release.optString("tag_name", "");
            if (tag.startsWith("v") || tag.startsWith("V")) {
                tag = tag.substring(1);
            }
            String notes = plain(release.optString("body", ""));
            String apk = null;
            String sha = null;
            org.json.JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.optString("name", "");
                    if (name.endsWith("-release.apk") || name.equals("app-release.apk")) {
                        apk = asset.optString("browser_download_url", null);
                        String digest = asset.optString("digest", "");
                        if (digest.startsWith("sha256:")) {
                            sha = digest.substring("sha256:".length());
                        }
                        break;
                    }
                }
            }
            return apk == null ? null : new Release(tag, apk, notes, sha);
        } catch (org.json.JSONException malformed) {
            throw new IOException("malformed release payload", malformed);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Strip Markdown so release notes read cleanly in a dialog.
     *
     * @param markdown raw release body
     * @return plain text, capped for display
     */
    private static String plain(String markdown) {
        StringBuilder out = new StringBuilder();
        for (String line : markdown.split("\n")) {
            String text = line.trim();
            if (text.startsWith("|") || text.startsWith("---")) {
                continue;
            }
            text = text.replaceAll("^#+\\s*", "")
                    .replace("**", "")
                    .replace("`", "")
                    .replace("§", "");
            if (text.startsWith("- ")) {
                text = "• " + text.substring(2);
            }
            out.append(text).append('\n');
        }
        String result = out.toString().replaceAll("\n{3,}", "\n\n").trim();
        return result.length() > 900 ? result.substring(0, 900) + "…" : result;
    }

    /**
     * Compare two dotted versions, ignoring a leading "v" and any pre-release suffix.
     *
     * @param candidate version to test
     * @param installed version currently running
     * @return true when candidate is strictly newer
     */
    static boolean isNewer(String candidate, String installed) {
        int[] a = parse(candidate);
        int[] b = parse(installed);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int left = i < a.length ? a[i] : 0;
            int right = i < b.length ? b[i] : 0;
            if (left != right) {
                return left > right;
            }
        }
        return false;
    }

    private static int[] parse(String version) {
        String cleaned = version == null ? "" : version.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1);
        }
        int dash = cleaned.indexOf('-');
        if (dash > 0) {
            cleaned = cleaned.substring(0, dash);
        }
        String[] parts = cleaned.split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException notNumeric) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }

    /** Receives download progress so the UI can show a bar. */
    interface Progress {
        /**
         * Report progress.
         *
         * @param bytes bytes written so far
         * @param total total bytes, or -1 when unknown
         * @return false to abort the download
         */
        boolean onProgress(long bytes, long total);
    }

    /**
     * Download the release APK into the cache directory.
     *
     * @param context application context
     * @param release release to fetch
     * @param progress optional progress callback
     * @return the downloaded file, or null when the callback aborted
     * @throws IOException when the download fails
     */
    static File download(Context context, Release release, Progress progress) throws IOException {
        IOException failure = null;
        boolean first = true;
        for (String url : release.urls) {
            try {
                if (!first) {
                    // A partial file belongs to the previous mirror; restart it cleanly.
                    new File(context.getCacheDir(), "update.apk.part").delete();
                }
                first = false;
                File apk = downloadOnce(context, url, progress);
                if (apk == null) {
                    return null;
                }
                if (release.sha256 != null) {
                    String actual = sha256(apk);
                    if (!release.sha256.equalsIgnoreCase(actual)) {
                        apk.delete();
                        throw new IOException("SHA-256 mismatch: published " + release.sha256
                                + ", downloaded " + actual);
                    }
                }
                return apk;
            } catch (IOException error) {
                Log.w(TAG, "download from " + url + " failed", error);
                failure = error;
            }
        }
        throw failure == null ? new IOException("no download source") : failure;
    }

    /**
     * Fetch a URL into the update cache, resuming a partial file when possible.
     *
     * @param context application context
     * @param url APK URL
     * @param progress optional progress callback
     * @return the downloaded file, or null when the callback aborted
     * @throws IOException when the download fails
     */
    private static File downloadOnce(Context context, String url, Progress progress)
            throws IOException {
        pruneIdleResume(context);
        File target = new File(context.getCacheDir(), "update.apk");
        File partial = new File(context.getCacheDir(), "update.apk.part");
        long offset = partial.isFile() ? partial.length() : 0L;

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        if (offset > 0) {
            // Resume: GitHub release assets honour Range, so an interrupted download continues.
            connection.setRequestProperty("Range", "bytes=" + offset + "-");
        }
        int code = connection.getResponseCode();
        boolean appending = code == 206 && offset > 0;
        if (code != 200 && code != 206) {
            connection.disconnect();
            throw new IOException("HTTP " + code);
        }
        if (!appending) {
            offset = 0;
            partial.delete();
        }
        long total = connection.getContentLengthLong();
        if (total > 0) {
            total += offset;
        }
        long written = offset;
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(partial, appending)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                written += read;
                if (progress != null && !progress.onProgress(written, total)) {
                    // Keep the partial file so the next attempt resumes.
                    return null;
                }
            }
        } finally {
            connection.disconnect();
        }
        if (!partial.renameTo(target)) {
            throw new IOException("cannot publish download");
        }
        return target;
    }

    /**
     * Return the cached update APK, but only when it is the release we expect.
     *
     * <p>Validating the archive alone is not enough: a package left over from an older release
     * still parses, and the installer then rejects it as a downgrade — or, if the user's build
     * already lags behind, silently installs the wrong version. So the package name, version and
     * signing certificate all have to match before a cached file is offered.</p>
     *
     * @param context application context
     * @param expectedVersion release tag this cache should hold, or null to skip the version check
     * @return the APK file, or null when it is missing, truncated, or from another build
     */
    static File cachedApk(Context context, String expectedVersion) {
        return verifiedApk(context, new File(context.getCacheDir(), "update.apk"),
                expectedVersion, true);
    }

    /**
     * Validate an APK belonging to this app.
     *
     * @param context application context
     * @param apk candidate file
     * @param expectedVersion required versionName, or null to accept any version
     * @param deleteWhenStale true to remove the file once it is proven unusable
     * @return the file when it can be installed over this app, otherwise null
     */
    static File verifiedApk(Context context, File apk, String expectedVersion,
                            boolean deleteWhenStale) {
        if (!apk.isFile()) {
            return null;
        }
        // A truncated archive still starts with "PK"; only the package parser rejects it.
        android.content.pm.PackageInfo info = archive(context, apk);
        if (info == null) {
            Log.w(TAG, "ignoring unreadable update package " + apk.getAbsolutePath());
            discard(apk, deleteWhenStale);
            return null;
        }
        if (!context.getPackageName().equals(info.packageName)) {
            Log.w(TAG, "ignoring update package for " + info.packageName);
            discard(apk, deleteWhenStale);
            return null;
        }
        if (expectedVersion != null && !expectedVersion.equals(info.versionName)) {
            Log.w(TAG, "cached update is v" + info.versionName + ", expected v" + expectedVersion);
            discard(apk, deleteWhenStale);
            return null;
        }
        if (!sameSigner(context, info)) {
            Log.w(TAG, "cached update is signed differently from this app");
            discard(apk, deleteWhenStale);
            return null;
        }
        return apk;
    }

    /**
     * Read the archive's package metadata.
     *
     * @param context application context
     * @param apk APK on disk
     * @return parsed metadata, or null when the file is not a valid package
     */
    private static android.content.pm.PackageInfo archive(Context context, File apk) {
        try {
            return context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(),
                    android.content.pm.PackageManager.GET_SIGNATURES);
        } catch (RuntimeException unreadable) {
            Log.w(TAG, "cannot parse " + apk.getName(), unreadable);
            return null;
        }
    }

    /**
     * Check that a candidate APK carries the same signing certificate as the installed app.
     *
     * @param context application context
     * @param candidate parsed candidate package
     * @return true when both certificates match, or when either one is unavailable
     */
    private static boolean sameSigner(Context context, android.content.pm.PackageInfo candidate) {
        android.content.pm.Signature[] theirs = candidate.signatures;
        if (theirs == null || theirs.length == 0) {
            return true;
        }
        try {
            android.content.pm.PackageInfo installed = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(),
                            android.content.pm.PackageManager.GET_SIGNATURES);
            android.content.pm.Signature[] ours = installed.signatures;
            if (ours == null || ours.length == 0) {
                return true;
            }
            return java.util.Arrays.equals(ours[0].toByteArray(), theirs[0].toByteArray());
        } catch (android.content.pm.PackageManager.NameNotFoundException absent) {
            return true;
        }
    }

    /**
     * Remove a download that cannot be installed, together with its resume file.
     *
     * @param apk APK file inside the cache directory
     * @param delete false when the caller only wants the verdict
     */
    private static void discard(File apk, boolean delete) {
        if (!delete) {
            return;
        }
        File partial = new File(apk.getParentFile(), apk.getName() + ".part");
        boolean removed = apk.delete() | partial.delete();
        if (removed) {
            Log.i(TAG, "discarded stale update package " + apk.getName());
        }
    }

    /**
     * Delete downloaded packages, for example once the installer reported success.
     *
     * @param context application context
     */
    static void clearCache(Context context) {
        File dir = context.getCacheDir();
        boolean removed = new File(dir, "update.apk").delete()
                | new File(dir, "update.apk.part").delete();
        if (removed) {
            Log.i(TAG, "cleared downloaded update");
        }
    }

    /**
     * Drop a resume file that has been idle for a week: resuming a 350 MB download is cheaper than
     * keeping the fragment forever on a phone that is short on space.
     *
     * @param context application context
     */
    static void pruneIdleResume(Context context) {
        File partial = new File(context.getCacheDir(), "update.apk.part");
        long idle = System.currentTimeMillis() - partial.lastModified();
        if (partial.isFile() && idle > 7L * 24 * 60 * 60 * 1000 && partial.delete()) {
            Log.i(TAG, "dropped idle resume file");
        }
        // A download the user never installed is dead weight after a month; a new one is
        // reproducible from the release page.
        File apk = new File(context.getCacheDir(), "update.apk");
        long age = System.currentTimeMillis() - apk.lastModified();
        if (apk.isFile() && age > 30L * 24 * 60 * 60 * 1000 && apk.delete()) {
            Log.i(TAG, "dropped month-old update package");
        }
    }

    /**
     * Clean up after an earlier update run, once per service start.
     *
     * <p>The plain {@code ACTION_VIEW} installer path never reports success back to us, so a
     * package the user did install stays in the cache. Anything that is no longer newer than the
     * running build is useless either way — drop it instead of waiting for the next release.</p>
     *
     * @param context application context
     */
    static void sweepOnLaunch(Context context) {
        pruneIdleResume(context);
        File apk = new File(context.getCacheDir(), "update.apk");
        if (!apk.isFile()) {
            return;
        }
        String current = currentVersion(context);
        android.content.pm.PackageInfo info = archive(context, apk);
        if (info == null) {
            Log.w(TAG, "dropping unreadable update package at launch");
            clearCache(context);
            return;
        }
        if (!isNewer(info.versionName, current)) {
            Log.i(TAG, "dropping update package v" + info.versionName
                    + " at launch (running v" + current + ")");
            clearCache(context);
        }
    }

    /**
     * Hand a downloaded APK to the system installer.
     *
     * @param context activity context
     * @param apk downloaded file inside the cache directory
     */
    static void install(Context context, File apk) {
        // The system installer reads the APK through our content provider; launching it from the
        // foreground activity avoids the background-activity block that made older builds no-op.
        android.net.Uri uri = new android.net.Uri.Builder()
                .scheme("content")
                .authority(ApkProvider.AUTHORITY)
                .appendPath(apk.getName())
                .build();
        android.content.Intent view = new android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(view);
            Log.i(TAG, "installer launched for " + apk.getName());
            return;
        } catch (android.content.ActivityNotFoundException missing) {
            Log.w(TAG, "no installer activity, falling back to session", missing);
        }
        try {
            android.content.pm.PackageInstaller installer = context.getPackageManager()
                    .getPackageInstaller();
            android.content.pm.PackageInstaller.SessionParams params =
                    new android.content.pm.PackageInstaller.SessionParams(
                            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            try (android.content.pm.PackageInstaller.Session session = installer.openSession(sessionId);
                 java.io.FileInputStream in = new java.io.FileInputStream(apk)) {
                try (java.io.OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                    byte[] buffer = new byte[1 << 16];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    session.fsync(out);
                }
                // The confirm dialog must be launched from a visible activity; a background
                // receiver is blocked from starting it on Android 10+.
                android.content.Intent result = new android.content.Intent(context, MainActivity.class);
                android.app.PendingIntent pending = android.app.PendingIntent.getActivity(context, 44,
                        result, android.app.PendingIntent.FLAG_UPDATE_CURRENT
                                | android.app.PendingIntent.FLAG_MUTABLE);
                session.commit(pending.getIntentSender());
            }
            Log.i(TAG, "install session " + sessionId + " committed for " + apk.length() + " bytes");
        } catch (IOException | SecurityException error) {
            Log.e(TAG, "cannot start install", error);
            android.widget.Toast.makeText(context, "无法启动安装：" + error.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Compute the SHA-256 of a file.
     *
     * @param file downloaded APK
     * @return lowercase hex digest
     * @throws IOException when the file cannot be read
     */
    private static String sha256(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            java.security.MessageDigest digest;
            try {
                digest = java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IOException("SHA-256 unavailable", impossible);
            }
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
