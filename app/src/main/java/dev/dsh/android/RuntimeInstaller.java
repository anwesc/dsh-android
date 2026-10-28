package dev.dsh.android;

import android.content.Context;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * Unpacks the bundled Linux runtime (proot, its libraries, and the arm64 rootfs image) into the
 * app's private storage so the foreground service can start the harness without network access.
 */
final class RuntimeInstaller {
    private static final String TAG = "DshRuntime";
    /** Gzip-compressed tar of the arm64 rootfs; the extension avoids aapt2's .gz rewriting. */
    private static final String ROOTFS_ASSET = "rootfs.bin";
    /** Entry count of {@link #ROOTFS_ASSET}, used for unpack progress. */
    private static final int ROOTFS_ENTRIES = 86964;
    /** Layout version; bump when the extracted tree gains or loses files. */
    private static final String LAYOUT_VERSION = "8";

    /** Receives coarse unpack progress for the notification and the loading screen. */
    interface Progress {
        /**
         * @param percent completion between 0 and 100
         * @param stage human-readable stage label
         */
        void onProgress(int percent, String stage);
    }

    private RuntimeInstaller() {
    }

    /**
     * Ensure the runtime is present, unpacking it on first use.
     *
     * @param context application context whose files directory hosts the runtime
     * @param progress progress callback, may be null
     * @return the runtime directory containing {@code bin/}, {@code lib/} and {@code rootfs/}
     * @throws IOException when an asset cannot be read or written
     */
    static File install(Context context, Progress progress) throws IOException {
        File runtime = new File(context.getFilesDir(), "runtime");
        File ready = new File(runtime, ".ready");
        if (ready.isFile() && LAYOUT_VERSION.equals(readMarker(ready))) {
            report(progress, 100, "runtime ready");
            return runtime;
        }

        File staged = new File(context.getFilesDir(), "runtime.new");
        deleteRecursively(staged);
        File bin = new File(staged, "bin");
        File lib = new File(staged, "lib");
        File rootfs = new File(staged, "rootfs");
        if (!bin.mkdirs() || !lib.mkdirs() || !rootfs.mkdirs()) {
            throw new IOException("cannot create runtime directories under " + runtime);
        }

        report(progress, 1, "unpacking proot");
        File proot = new File(bin, "proot");
        copyAsset(context, "runtime/proot", proot);
        File talloc = new File(lib, "libtalloc.so.2");
        copyAsset(context, "runtime/libtalloc.so.2", talloc);
        File shmem = new File(lib, "libandroid-shmem.so");
        copyAsset(context, "runtime/libandroid-shmem.so", shmem);
        File loaderDir = new File(staged, "libexec/proot");
        if (!loaderDir.mkdirs() && !loaderDir.isDirectory()) {
            throw new IOException("cannot create " + loaderDir);
        }
        File loader = new File(loaderDir, "loader");
        copyAsset(context, "runtime/libexec/loader", loader);
        File loader32 = new File(loaderDir, "loader32");
        copyAsset(context, "runtime/libexec/loader32", loader32);
        setMode(loader, 0700);
        setMode(loader32, 0700);
        setMode(proot, 0700);
        setMode(talloc, 0600);
        setMode(shmem, 0600);

        report(progress, 3, "unpacking harness runtime");
        extractRootfs(context, rootfs, progress);

        report(progress, 97, "keeping your sessions");
        try {
            migrateProfile(runtime, staged);
        } catch (IOException migrationError) {
            // A migration failure must not block the fresh image from starting.
            Log.w(TAG, "cannot migrate the previous profile", migrationError);
        }

        // Swap directories without leaving a window where "runtime" is missing: a start that
        // raced the swap would fail with ENOENT on bin/proot.
        File previous = new File(context.getFilesDir(), "runtime.old");
        deleteRecursively(previous);
        if (runtime.exists() && !runtime.renameTo(previous)) {
            throw new IOException("cannot retire runtime " + runtime);
        }
        if (!staged.renameTo(runtime)) {
            if (previous.exists()) {
                previous.renameTo(runtime);
            }
            throw new IOException("cannot publish runtime " + runtime);
        }
        deleteRecursively(previous);
        File marker = new File(runtime, ".ready");
        try (java.io.FileWriter writer = new java.io.FileWriter(marker)) {
            writer.write(LAYOUT_VERSION);
        }
        report(progress, 100, "runtime ready");
        return runtime;
    }

    /**
     * Discard the unpacked runtime and unpack it again, used when the launcher is missing.
     *
     * @param context application context whose files directory hosts the runtime
     * @param progress progress callback, may be null
     * @return the runtime directory
     * @throws IOException when unpacking fails
     */
    static File reinstall(Context context, Progress progress) throws IOException {
        deleteRecursively(new File(context.getFilesDir(), "runtime"));
        return install(context, progress);
    }

    /**
     * Stream the bundled {@code rootfs.tar.gz} into the target directory, preserving symlinks and
     * POSIX permissions.
     *
     * @param context application context holding the asset
     * @param rootfs destination directory
     * @param progress progress callback, may be null
     * @throws IOException when the archive is missing or cannot be written
     */
    private static void extractRootfs(Context context, File rootfs, Progress progress) throws IOException {
        String prefix = rootfs.getCanonicalPath() + File.separator;

        try (InputStream raw = new BufferedInputStream(context.getAssets().open(ROOTFS_ASSET), 1 << 16);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw, true);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {

            byte[] buffer = new byte[1 << 17];
            TarArchiveEntry entry;
            int lastPercent = -1;
            int entries = 0;
            while ((entry = tar.getNextEntry()) != null) {
                entries++;
                String name = entry.getName();
                if (name.equals("./") || name.equals(".") || name.isEmpty()) {
                    continue;
                }
                File target = new File(rootfs, name);
                String canonical = target.getCanonicalPath();
                if (!canonical.startsWith(prefix)) {
                    Log.w(TAG, "skipping entry outside the rootfs: " + name);
                    continue;
                }

                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new IOException("cannot create directory " + target);
                    }
                    setMode(target, entry.getMode());
                    continue;
                }
                if (entry.isSymbolicLink()) {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("cannot create directory " + parent);
                    }
                    Files.deleteIfExists(target.toPath());
                    try {
                        Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(entry.getLinkName()));
                    } catch (IOException error) {
                        Log.w(TAG, "cannot link " + name + " -> " + entry.getLinkName(), error);
                    }
                    continue;
                }
                if (!entry.isFile()) {
                    continue;
                }

                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("cannot create directory " + parent);
                }
                try (FileOutputStream fileOut = new FileOutputStream(target);
                     BufferedOutputStream out = new BufferedOutputStream(fileOut, 1 << 16)) {
                    int read;
                    while ((read = tar.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                setMode(target, entry.getMode());

                if (progress != null && entries % 256 == 0) {
                    int percent = 5 + (int) ((long) entries * 90L / ROOTFS_ENTRIES);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        report(progress, Math.min(percent, 95), "unpacking harness runtime");
                    }
                }
            }
        }
    }

    /**
     * Copy a packaged binary asset to disk.
     *
     * @param context application context holding the asset
     * @param name asset path
     * @param target destination file
     * @throws IOException when the asset or destination cannot be read or written
     */
    private static void copyAsset(Context context, String name, File target) throws IOException {
        try (InputStream in = context.getAssets().open(name);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    /**
     * Apply the permission bits recorded in a tar entry.
     *
     * @param file target file or directory
     * @param mode POSIX mode from the archive
     */
    private static void setMode(File file, int mode) {
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        if ((mode & 0400) != 0) permissions.add(PosixFilePermission.OWNER_READ);
        if ((mode & 0200) != 0) permissions.add(PosixFilePermission.OWNER_WRITE);
        if ((mode & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);
        if ((mode & 0040) != 0) permissions.add(PosixFilePermission.GROUP_READ);
        if ((mode & 0020) != 0) permissions.add(PosixFilePermission.GROUP_WRITE);
        if ((mode & 0010) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE);
        if ((mode & 0004) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
        if ((mode & 0002) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE);
        if ((mode & 0001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE);
        Path path = file.toPath();
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (IOException | UnsupportedOperationException error) {
            Log.w(TAG, "cannot set mode " + Integer.toOctalString(mode) + " on " + path, error);
        }
    }

    /**
     * Copy the previous install's harness profile into the freshly unpacked image so upgrades keep
     * sessions, settings and plugins. The profile lives inside the rootfs; binding it from outside
     * made per-file stat fail under proot, which broke plugin resolution.
     *
     * @param previous runtime directory being replaced, may not exist
     * @param staged freshly unpacked runtime directory
     * @throws IOException when copying fails
     */
    private static void migrateProfile(File previous, File staged) throws IOException {
        File source = new File(previous, "rootfs/root/.dsh");
        if (!source.isDirectory()) {
            return;
        }
        File target = new File(staged, "rootfs/root/.dsh");
        // Copy beside the image profile first: a failed copy must leave the shipped profile (and
        // its plugins) intact rather than deleting it.
        File incoming = new File(staged, "rootfs/root/.dsh.migrated");
        deleteRecursively(incoming);
        copyTree(source.toPath(), incoming.toPath());
        deleteRecursively(target);
        if (!incoming.renameTo(target)) {
            throw new IOException("cannot publish migrated profile " + target);
        }
    }

    /**
     * Copy a directory tree, recreating symbolic links instead of following them.
     *
     * @param source source root
     * @param target destination root
     * @throws IOException when a file cannot be copied
     */
    private static void copyTree(java.nio.file.Path source, java.nio.file.Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<java.nio.file.Path>() {
            @Override
            public FileVisitResult preVisitDirectory(java.nio.file.Path dir, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                java.nio.file.Path destination = target.resolve(source.relativize(file).toString());
                if (Files.isSymbolicLink(file)) {
                    Files.deleteIfExists(destination);
                    Files.createSymbolicLink(destination, Files.readSymbolicLink(file));
                } else {
                    Files.copy(file, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Read the layout marker written by a previous install.
     *
     * @param marker readiness file
     * @return the recorded layout version, or an empty string when unreadable
     */
    private static String readMarker(File marker) {
        try {
            return new String(java.nio.file.Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            return "";
        }
    }

    /**
     * Remove a directory tree, restoring write permission so read-only directories unpacked from
     * the image can be emptied.
     *
     * @param file root of the tree
     */
    private static void deleteRecursively(File file) {
        java.nio.file.Path path = file.toPath();
        try {
            if (java.nio.file.Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                setMode(file, 0700);
                try (java.nio.file.DirectoryStream<java.nio.file.Path> children =
                             java.nio.file.Files.newDirectoryStream(path)) {
                    for (java.nio.file.Path child : children) {
                        deleteRecursively(child.toFile());
                    }
                }
            }
            java.nio.file.Files.deleteIfExists(path);
        } catch (IOException error) {
            Log.w(TAG, "cannot delete " + path, error);
        }
    }

    private static void report(Progress progress, int percent, String stage) {
        if (progress != null) {
            progress.onProgress(percent, stage);
        }
    }

}
