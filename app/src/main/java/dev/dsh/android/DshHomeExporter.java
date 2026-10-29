package dev.dsh.android;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Mirrors the harness home ({@code .dsh}: sessions, plugins, settings) into shared storage so the
 * phone's file manager can read it. It is a copy, not a bind mount: the harness takes POSIX locks
 * and watches files there, which shared (FUSE) storage does not support.
 */
final class DshHomeExporter {
    private static final String TAG = "DshExport";

    private DshHomeExporter() {
    }

    /**
     * Copy the runtime's {@code /root/.dsh} into the destination directory.
     *
     * @param runtime unpacked runtime root
     * @param destination visible directory, recreated on every export
     * @return number of files copied
     * @throws IOException when the source is missing or the copy fails
     */
    static int export(File runtime, File destination) throws IOException {
        File source = new File(runtime, "rootfs/root/.dsh");
        if (!source.isDirectory()) {
            throw new IOException("no harness home at " + source);
        }
        deleteRecursively(destination);
        if (!destination.mkdirs()) {
            throw new IOException("cannot create " + destination);
        }
        Path from = source.toPath();
        Path to = destination.toPath();
        final int[] count = {0};
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = to.resolve(from.relativize(file).toString());
                if (Files.isSymbolicLink(file)) {
                    Files.deleteIfExists(target);
                    try {
                        Files.createSymbolicLink(target, Files.readSymbolicLink(file));
                    } catch (IOException linkError) {
                        Log.w(TAG, "skip link " + file);
                    }
                } else {
                    Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                count[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    private static void deleteRecursively(File file) {
        Path path = file.toPath();
        try {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                    for (Path child : children) {
                        deleteRecursively(child.toFile());
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException error) {
            Log.w(TAG, "cannot delete " + path);
        }
    }
}
