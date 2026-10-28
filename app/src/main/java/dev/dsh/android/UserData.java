package dev.dsh.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Resolves the workspace directory shared with the phone's file manager. The harness profile stays
 * inside the unpacked image so per-file access works under proot; {@link RuntimeInstaller}
 * migrates it across image updates.
 */
final class UserData {
    private static final String TAG = "DshUserData";
    /** Folder name created on shared storage so a file manager can reach the workspace. */
    private static final String SHARED_DIR = "DSH";

    final File work;

    private UserData(File work) {
        this.work = work;
    }

    /**
     * Resolve the workspace directory, preferring shared storage.
     *
     * @param context application context
     * @return the prepared workspace
     * @throws IOException when no directory can be created
     */
    static UserData prepare(Context context) throws IOException {
        File work = sharedWorkspace(context);
        if (work == null) {
            work = new File(new File(context.getFilesDir(), "userdata"), "work");
            if (!work.isDirectory() && !work.mkdirs()) {
                throw new IOException("cannot create " + work);
            }
        }
        return new UserData(work);
    }

    /**
     * Resolve the shared workspace folder, creating it when storage permission is held.
     *
     * @param context application context
     * @return the folder, or null when shared storage is unavailable or denied
     */
    private static File sharedWorkspace(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        // Canonical FUSE path: the /sdcard symlink resolves through /storage/self, which proot
        // cannot sanitize when it prepares a bind.
        File dir = new File("/storage/emulated/0", SHARED_DIR);
        if (!dir.exists()) {
            File external = Environment.getExternalStorageDirectory();
            if (external != null) {
                dir = new File(external, SHARED_DIR);
            }
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create " + dir);
            return null;
        }
        return dir;
    }
}
