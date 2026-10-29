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

    private UpdateChecker() {
    }

    /** One published release that carries an installable APK. */
    static final class Release {
        final String version;
        final String apkUrl;

        Release(String version, String apkUrl) {
            this.version = version;
            this.apkUrl = apkUrl;
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
     * Ask GitHub for the latest release.
     *
     * @return the newest release with an APK asset, or null when none qualifies
     * @throws IOException when the request fails
     */
    static Release latest() throws IOException {
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
            String apk = null;
            org.json.JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.optString("name", "");
                    if (name.endsWith("-release.apk") || name.equals("app-release.apk")) {
                        apk = asset.optString("browser_download_url", null);
                        break;
                    }
                }
            }
            return apk == null ? null : new Release(tag, apk);
        } catch (org.json.JSONException malformed) {
            throw new IOException("malformed release payload", malformed);
        } finally {
            connection.disconnect();
        }
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
        File target = new File(context.getCacheDir(), "update.apk");
        File partial = new File(context.getCacheDir(), "update.apk.part");
        long offset = partial.isFile() ? partial.length() : 0L;

        HttpURLConnection connection = (HttpURLConnection) new URL(release.apkUrl).openConnection();
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
     * Return the cached update APK only when it is a complete archive.
     *
     * @param context application context
     * @return the APK file, or null when it is missing or truncated
     */
    static File cachedApk(Context context) {
        return validApk(context, new File(context.getCacheDir(), "update.apk"));
    }

    /**
     * Validate an APK file with the package parser.
     *
     * @param context application context
     * @param apk candidate file
     * @return the file when it parses as a package, otherwise null
     */
    static File validApk(Context context, File apk) {
        if (!apk.isFile()) {
            return null;
        }
        // A truncated archive still starts with "PK"; only the package parser rejects it.
        android.content.pm.PackageInfo info =
                context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        return info != null ? apk : null;
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
