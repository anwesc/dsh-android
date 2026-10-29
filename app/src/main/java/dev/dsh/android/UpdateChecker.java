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

    /**
     * Download the release APK into the cache directory.
     *
     * @param context application context
     * @param release release to fetch
     * @return the downloaded file
     * @throws IOException when the download fails
     */
    static File download(Context context, Release release) throws IOException {
        File target = new File(context.getCacheDir(), "update.apk");
        HttpURLConnection connection = (HttpURLConnection) new URL(release.apkUrl).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } finally {
            connection.disconnect();
        }
        return target;
    }

    /**
     * Hand a downloaded APK to the system installer.
     *
     * @param context activity context
     * @param apk downloaded file inside the cache directory
     */
    static void install(Context context, File apk) {
        Log.i(TAG, "installing " + apk.length() + " bytes");
        Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(ApkProvider.AUTHORITY)
                .appendPath(apk.getName())
                .build();
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
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
