package dev.dsh.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Exposes the downloaded update APK to the system installer as a content URI, which Android 7+
 * requires instead of a file URI.
 */
public final class ApkProvider extends ContentProvider {
    /** Authority declared in the manifest. */
    static final String AUTHORITY = "dev.dsh.android.apk";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null || uri.getLastPathSegment() == null) {
            throw new FileNotFoundException("no path");
        }
        String name = uri.getLastPathSegment();
        File apk = new File(getContext().getCacheDir(), name);
        if (!apk.isFile()) {
            apk = new File("/storage/emulated/0/DSH/work", name);
        }
        if (!apk.isFile()) {
            apk = new File("/storage/emulated/0/DSH/updates", name);
        }
        if (!apk.isFile()) {
            throw new FileNotFoundException(name);
        }
        return ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
