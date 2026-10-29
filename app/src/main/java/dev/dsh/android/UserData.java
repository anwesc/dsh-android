package dev.dsh.android;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Resolves the directories shared with the phone's file manager: the workspace and the exported
 * harness home. The harness profile stays inside the unpacked image; {@link RuntimeInstaller}
 * migrates it across image updates.
 */
final class UserData {
    private static final String TAG = "DshUserData";
    /** Folder created on shared storage so a file manager can reach these directories. */
    private static final String SHARED_DIR = "DSH";

    final File work;
    /** Mirror of the harness home (sessions, plugins, settings) for the file manager. */
    final File dsh;

    private UserData(File work, File dsh) {
        this.work = work;
        this.dsh = dsh;
    }

    /**
     * Resolve the shared directories, preferring shared storage.
     *
     * @param context application context
     * @return the prepared directories
     * @throws IOException when no directory can be created
     */
    static UserData prepare(Context context) throws IOException {
        File shared = sharedRoot(context);
        File work;
        if (shared != null) {
            work = new File(shared, "work");
        } else {
            File root = new File(context.getFilesDir(), "userdata");
            work = new File(root, "work");
        }
        ensure(work);
        File dsh = new File(shared != null ? shared : new File(context.getFilesDir(), "userdata"), "dsh");
        return new UserData(work, dsh);
    }

    private static void ensure(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
    }

    /**
     * Resolve the shared workspace root, creating it when storage permission is held.
     *
     * @param context application context
     * @return the folder, or null when shared storage is unavailable or denied
     */
    private static File sharedRoot(Context context) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        // Canonical FUSE path: the /sdcard symlink resolves through /storage/self, which proot
        // cannot sanitize when it prepares a bind.
        File dir = new File("/storage/emulated/0", SHARED_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
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
