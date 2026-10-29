package dev.dsh.android;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;

/**
 * Keeps a known-good copy of the profile configuration and restores it when a boot fails.
 *
 * <p>Anything the agent edits inside the container (cordis.yml, cordis.patch.yml, package.json)
 * can stop the harness from starting. A successful boot snapshots those files; the next boot that
 * fails puts the snapshot back before trying again.</p>
 */
final class SafetyNet {
    private static final String TAG = "DshSafety";
    /** Profile files that decide whether the harness can start. */
    private static final List<String> WATCHED = Arrays.asList(
            "cordis.yml",
            "cordis.patch.yml",
            "package.json",
            "pnpm-lock.yaml",
            "pnpm-workspace.yaml",
            "compatibility.json",
            ".npmrc");
    /** Marker written before a boot attempt, removed once the harness reports its URL. */
    private static final String PENDING = "boot.pending";
    private static final String GOOD = "last-good";
    private static final String GOOD_META = "last-good.meta";
    /** Manual snapshots kept on disk; older ones are pruned. */
    private static final int KEEP_MANUAL = 5;
    private static final String MANUAL_PREFIX = "manual-";

    private final File state;

    SafetyNet(File filesDir) {
        this.state = new File(filesDir, "safety");
    }

    private File profileDir(File runtime) {
        return new File(runtime, "rootfs/root/.dsh/profiles/web");
    }

    /**
     * Record that a boot is being attempted. A leftover marker on the next start means the
     * previous attempt never reached the harness URL.
     *
     * @return true when the previous attempt failed and a restore is warranted
     */
    boolean beginBoot() {
        boolean previousFailed = new File(state, PENDING).isFile();
        try {
            if (!state.isDirectory() && !state.mkdirs()) {
                throw new IOException("cannot create " + state);
            }
            new File(state, PENDING).createNewFile();
        } catch (IOException error) {
            Log.w(TAG, "cannot write boot marker", error);
        }
        return previousFailed;
    }

    /**
     * Snapshot the profile configuration once the harness is serving.
     *
     * @param runtime unpacked runtime directory
     */
    void markGood(File runtime) {
        File target = new File(state, GOOD);
        try {
            deleteTree(target);
            copyConfig(profileDir(runtime), target);
            Files.write(new File(state, GOOD_META).toPath(),
                    String.valueOf(System.currentTimeMillis()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.deleteIfExists(new File(state, PENDING).toPath());
        } catch (IOException error) {
            Log.w(TAG, "cannot snapshot profile", error);
        }
    }

    /**
     * Take a manual snapshot before editing configuration. Manual snapshots are never overwritten
     * by an automatic boot snapshot, so the pre-edit state stays recoverable.
     *
     * @param runtime unpacked runtime directory
     * @return slot name, or null when the snapshot could not be written
     */
    String createManual(File runtime) {
        String slot = MANUAL_PREFIX + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                java.util.Locale.US).format(new java.util.Date());
        try {
            File target = new File(state, slot);
            copyConfig(profileDir(runtime), target);
            java.nio.file.Files.write(new File(target, "meta.txt").toPath(),
                    String.valueOf(System.currentTimeMillis())
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            pruneManual();
            return slot;
        } catch (IOException error) {
            Log.w(TAG, "cannot create manual snapshot", error);
            return null;
        }
    }

    /**
     * List manual snapshots, newest first.
     *
     * @return slot names
     */
    java.util.List<String> manualSlots() {
        java.util.List<String> slots = new java.util.ArrayList<>();
        File[] children = state.listFiles();
        if (children == null) {
            return slots;
        }
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith(MANUAL_PREFIX)) {
                slots.add(child.getName());
            }
        }
        java.util.Collections.sort(slots);
        java.util.Collections.reverse(slots);
        return slots;
    }

    /**
     * Restore a specific snapshot.
     *
     * @param runtime unpacked runtime directory
     * @param slot snapshot directory name, or null for the automatic last-good
     * @return true when the configuration was copied back
     */
    boolean restoreSlot(File runtime, String slot) {
        if (slot == null || slot.isEmpty() || "last-good".equals(slot)) {
            return restore(runtime);
        }
        File source = new File(state, slot);
        if (!source.isDirectory()) {
            return false;
        }
        try {
            copyConfig(source, profileDir(runtime));
            Log.i(TAG, "restored profile from " + slot);
            return true;
        } catch (IOException error) {
            Log.w(TAG, "cannot restore " + slot, error);
            return false;
        }
    }

    /**
     * Copy one snapshot into a visible directory.
     *
     * @param slot snapshot name
     * @param destination target directory (created or reused)
     * @return number of files copied, 0 when the snapshot is missing
     */
    int mirrorSlot(String slot, File destination) {
        File source = new File(state, slot);
        if (!source.isDirectory()) {
            return 0;
        }
        try {
            if (!destination.isDirectory() && !destination.mkdirs()) {
                throw new IOException("cannot create " + destination);
            }
            File[] children = source.listFiles();
            int copied = 0;
            if (children != null) {
                for (File child : children) {
                    if ("meta.txt".equals(child.getName())) {
                        continue;
                    }
                    java.nio.file.Files.copy(child.toPath(),
                            new File(destination, child.getName()).toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
            return copied;
        } catch (IOException error) {
            Log.w(TAG, "cannot mirror " + slot, error);
            return 0;
        }
    }

    private void pruneManual() {
        java.util.List<String> slots = manualSlots();
        for (int i = KEEP_MANUAL; i < slots.size(); i++) {
            try {
                deleteTree(new File(state, slots.get(i)));
            } catch (IOException error) {
                Log.w(TAG, "cannot prune " + slots.get(i), error);
            }
        }
    }

    /**
     * Restore the last known-good configuration.
     *
     * @param runtime unpacked runtime directory
     * @return true when a snapshot existed and was copied back
     */
    boolean restore(File runtime) {
        File source = new File(state, GOOD);
        if (!source.isDirectory()) {
            return false;
        }
        try {
            copyConfig(source, profileDir(runtime));
            Log.i(TAG, "restored profile configuration from the last good boot");
            return true;
        } catch (IOException error) {
            Log.w(TAG, "cannot restore profile", error);
            return false;
        }
    }

    /**
     * Whether a known-good snapshot is available.
     *
     * @return true when a snapshot exists
     */
    boolean hasGood() {
        return new File(state, GOOD).isDirectory();
    }

    /**
     * Copy the snapshot into a directory the file manager can open.
     *
     * @param destination visible directory, replaced on every call
     * @return number of files copied, or 0 when no snapshot exists
     */
    int mirrorTo(File destination) {
        File source = new File(state, GOOD);
        if (!source.isDirectory()) {
            return 0;
        }
        try {
            if (!destination.isDirectory() && !destination.mkdirs()) {
                throw new IOException("cannot create " + destination);
            }
            File[] children = source.listFiles();
            int copied = 0;
            if (children != null) {
                for (File child : children) {
                    java.nio.file.Files.copy(child.toPath(),
                            new File(destination, child.getName()).toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
            java.nio.file.Files.write(new File(destination, "meta.txt").toPath(),
                    ("backup " + describeGood() + System.lineSeparator()).getBytes(
                            java.nio.charset.StandardCharsets.UTF_8));
            return copied;
        } catch (IOException error) {
            Log.w(TAG, "cannot mirror snapshot", error);
            return 0;
        }
    }

    /**
     * Describe the snapshot: when it was taken and where it lives.
     *
     * @return human readable description, or "无" when nothing is stored
     */
    String describeGood() {
        if (!hasGood()) {
            return "无";
        }
        String stamp = "时间未知";
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(new File(state, GOOD_META).toPath());
            long millis = Long.parseLong(new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim());
            stamp = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(millis));
        } catch (IOException | NumberFormatException ignored) {
            // missing metadata: the snapshot predates it
        }
        return stamp + "（" + new File(state, GOOD).getAbsolutePath() + "）";
    }

    private void copyConfig(File from, File to) throws IOException {
        if (!to.isDirectory() && !to.mkdirs()) {
            throw new IOException("cannot create " + to);
        }
        for (String name : WATCHED) {
            File source = new File(from, name);
            File target = new File(to, name);
            if (!source.isFile()) {
                Files.deleteIfExists(target.toPath());
                continue;
            }
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteTree(File file) throws IOException {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("cannot delete " + file);
        }
    }
}
