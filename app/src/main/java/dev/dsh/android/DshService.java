package dev.dsh.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Foreground service that owns the bundled Linux runtime and serves the harness on loopback.
 *
 * <p>A foreground service keeps the runtime out of the cached-process freezer, which otherwise
 * suspends the server while the WebView activity is on screen.</p>
 */
public final class DshService extends Service {
    /** Broadcast action carrying the harness URL once the server prints it. */
    static final String ACTION_READY = "dev.dsh.android.action.READY";
    /** Broadcast action carrying a startup failure message. */
    static final String ACTION_FAILED = "dev.dsh.android.action.FAILED";
    /** Broadcast action carrying boot progress text for the launch screen. */
    static final String ACTION_PROGRESS = "dev.dsh.android.action.PROGRESS";
    /** Broadcast action asking the WebView to reload (after a plugin install). */
    static final String ACTION_RELOAD = "dev.dsh.android.action.RELOAD";
    /** Broadcast action asking the activity to check for an app update. */
    static final String ACTION_CHECK_UPDATE = "dev.dsh.android.action.CHECK_UPDATE";
    /** Broadcast action telling the activity a newer version is available. */
    static final String ACTION_UPDATE_AVAILABLE = "dev.dsh.android.action.UPDATE_AVAILABLE";
    /** Broadcast action asking the activity to install the already downloaded APK. */
    static final String ACTION_INSTALL_READY = "dev.dsh.android.action.INSTALL_READY";
    /** Service action: download an update in the background. */
    static final String ACTION_DOWNLOAD = "dev.dsh.android.action.DOWNLOAD";
    static final String EXTRA_VERSION = "version";
    /** Broadcast action telling the activity that a failed boot can be rolled back. */
    static final String ACTION_RESTORE_PROMPT = "dev.dsh.android.action.RESTORE_PROMPT";
    /** Service action that performs the rollback the user confirmed. */
    static final String ACTION_DO_RESTORE = "dev.dsh.android.action.DO_RESTORE";
    /** Service action that restarts the harness without going through the control port. */
    static final String ACTION_DO_RESTART = "dev.dsh.android.action.DO_RESTART";
    /** Preference storing whether the app checks for updates on its own. */
    private static final String PREF_AUTO_UPDATE = "auto_update";
    /** Preference mirroring the loopback control token for in-app callers. */
    private static final String PREF_CONTROL_TOKEN = "control_token";
    /** Loopback control port for restart/reload requests from inside the container. */
    private static final int CONTROL_PORT = 3081;
    /** Intent extra holding the progress line. */
    static final String EXTRA_PROGRESS = "progress";
    /** Intent extra and broadcast extra holding the URL or the failure text. */
    static final String EXTRA_URL = "url";
    static final String EXTRA_ERROR = "error";

    private static final String TAG = "DshService";
    static final String CHANNEL_ID = "dsh-runtime";
    private static final int NOTIFICATION_ID = 0x4453;
    private static final Pattern URL_PATTERN = Pattern.compile("dsh web:\\s+(http\\S+)");

    /** Latest harness URL, shared with a re-created activity. */
    static volatile String currentUrl;

    /** Random token required by the loopback control port; regenerated per process. */
    private volatile String controlToken;

    /** Preference holding the boot timeline of the most recent start, one stage per line. */
    private static final String PREF_BOOT_TIMELINE = "boot_timeline";
    /** Preference holding the exit reason of the most recent harness stop. */
    private static final String PREF_LAST_EXIT = "last_exit";
    /** Rotating copy of the harness output, readable through the control port. */
    private static final String HARNESS_LOG = "harness.log";
    /** Cap on the harness log before it is rotated to {@code harness.log.1}. */
    private static final long HARNESS_LOG_LIMIT = 512 * 1024;
    /** Stages kept from the last start. */
    private static final int TIMELINE_IN_MEMORY = 40;
    /** Consecutive failed starts that stop the automatic restart loop. */
    private static final int MAX_BOOT_FAILURES = 3;
    /** Failures older than this no longer count against the restart loop. */
    private static final long FAILURE_WINDOW_MS = 10 * 60_000;
    /** A boot that stayed up this long counts as healthy and clears the failure count. */
    private static final long STABLE_BOOT_MS = 60_000;

    private Thread worker;
    private Process process;
    private PowerManager.WakeLock wakeLock;
    private long bootStartedAt;
    private volatile File runtimeDir;
    private volatile UserData sharedDirs;
    private SafetyNet safetyNet;
    private final Object serverLock = new Object();
    private volatile boolean restarting;
    /** Boot stage labels with their offset in ms, newest last. */
    private final List<String> bootStages = new ArrayList<>();
    /** Number of failed starts since the last healthy boot. */
    private int bootFailures;
    /** When the failure window opened, in {@link SystemClock#elapsedRealtime()} ms. */
    private long firstFailureAt;
    /** Set once the restart loop gave up; a manual restart clears it. */
    private volatile boolean restartSuppressed;
    /** Handler used to space out automatic restarts off the boot thread. */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** Last lines of harness output, kept for the post-mortem report. */
    private final java.util.ArrayDeque<String> recentOutput = new java.util.ArrayDeque<>();
    /** Opened while the harness runs; null between starts. */
    private volatile PrintWriter harnessLog;
    /** Last control request or harness activity, in {@link SystemClock#elapsedRealtime()} ms. */
    private volatile long lastActivity;
    /** Leases that ask the harness to stay up (owner -> expiry in elapsedRealtime ms). */
    private final java.util.Map<String, Long> leases = new java.util.concurrent.ConcurrentHashMap<>();
    /** True when a bundled always-on plugin (the chat bot) is configured. */
    private volatile boolean resident;
    /** How long one control request keeps the CPU awake after it returns. */
    private static final long ACTIVITY_HOLD_MS = 60_000;
    /** Suspends the harness after this long without requests or leases (when enabled). */
    private static final long IDLE_CHECK_MS = 60_000;
    /** Releases the wake lock once activity has stopped for {@link #ACTIVITY_HOLD_MS}. */
    private final Runnable holdTimeout = this::maybeReleaseWake;
    /** Periodic idle check; only runs when the user enabled idle shutdown. */
    private final Runnable idleCheck = this::checkIdle;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        controlToken = getSharedPreferences("dsh", MODE_PRIVATE)
                .getString(PREF_CONTROL_TOKEN, null);
        if (controlToken == null || controlToken.isEmpty()) {
            controlToken = UUID.randomUUID().toString().replace("-", "");
            getSharedPreferences("dsh", MODE_PRIVATE).edit()
                    .putString(PREF_CONTROL_TOKEN, controlToken).apply();
        }
        // No wake lock here: the service is started at boot and usually idles. It is held only
        // while a boot/restart is in flight and for a short window after each request.
        touchActivity();
        // Parsing the cached APK costs a moment, so keep it off the main thread; a stale package
        // (already installed, or older than this build) is dropped here instead of lingering.
        Thread sweep = new Thread(() -> UpdateChecker.sweepOnLaunch(this), "dsh-update-sweep");
        sweep.setDaemon(true);
        sweep.start();
    }

    /**
     * Mark the harness as active and keep the CPU awake for a short grace period.
     */
    private void touchActivity() {
        lastActivity = SystemClock.elapsedRealtime();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power == null) {
            return;
        }
        if (wakeLock == null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:runtime");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
            Log.i(TAG, "wake lock held");
        }
        mainHandler.removeCallbacks(holdTimeout);
        mainHandler.postDelayed(holdTimeout, ACTIVITY_HOLD_MS);
    }

    private void maybeReleaseWake() {
        long idleFor = SystemClock.elapsedRealtime() - lastActivity;
        if (idleFor < ACTIVITY_HOLD_MS) {
            return;
        }
        if (currentUrl == null || activeLeases() > 0) {
            // Still booting, or a resident plugin (chat bot, watcher) asked to stay awake.
            mainHandler.postDelayed(holdTimeout, ACTIVITY_HOLD_MS);
            return;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "wake lock released after " + idleFor + "ms idle");
        }
    }

    /**
     * Whether the bundled QQ keyword watcher is configured and not switched off.
     *
     * @param data shared directories resolved for this boot
     * @return true when the watcher is expected to keep a connection open
     */
    private boolean qqbotEnabled(UserData data) {
        for (String name : new String[] {"qqbot-guard.json", "qqbot.json"}) {
            File config = new File(data.dsh, name);
            if (!config.isFile()) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(config), java.nio.charset.StandardCharsets.UTF_8))) {
                StringBuilder text = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line).append('\n');
                }
                return new org.json.JSONObject(text.toString()).optBoolean("enabled", true);
            } catch (Exception error) {
                Log.w(TAG, "cannot read " + config, error);
                return true;
            }
        }
        return false;
    }

    private int activeLeases() {
        long now = SystemClock.elapsedRealtime();
        int count = 0;
        for (java.util.Iterator<java.util.Map.Entry<String, Long>> it = leases.entrySet().iterator();
                it.hasNext(); ) {
            if (it.next().getValue() < now) {
                it.remove();
            } else {
                count++;
            }
        }
        return count + (resident ? 1 : 0);
    }

    private String leasesJson() {
        long now = SystemClock.elapsedRealtime();
        StringBuilder out = new StringBuilder("{");
        for (java.util.Map.Entry<String, Long> entry : leases.entrySet()) {
            if (entry.getValue() < now) {
                continue;
            }
            if (out.length() > 1) {
                out.append(",");
            }
            out.append("\"").append(escape(entry.getKey())).append("\":")
                    .append(Math.max(0, (entry.getValue() - now) / 1000));
        }
        return out.append("}").toString();
    }

    /**
     * Register or refresh a lease so the harness survives idle shutdown.
     *
     * @param method HTTP method; only POST/PUT register a lease
     * @param query query string carrying owner and ttl
     * @return JSON body for the control response
     */
    private String handleLease(String method, String query) {
        if ("GET".equals(method)) {
            return "{\"leases\":" + leasesJson() + "}";
        }
        String owner = queryValue(query, "owner");
        if (owner == null || owner.isEmpty()) {
            return "{\"error\":\"owner required\"}";
        }
        int ttl = 120;
        try {
            String raw = queryValue(query, "ttl");
            if (raw != null) {
                ttl = Integer.parseInt(raw);
            }
        } catch (NumberFormatException ignored) {
            // Keep the default; a malformed ttl should not drop the lease.
        }
        ttl = Math.max(30, Math.min(3600, ttl));
        leases.put(owner, SystemClock.elapsedRealtime() + ttl * 1000L);
        touchActivity();
        Log.i(TAG, "lease " + owner + " for " + ttl + "s");
        return "{\"owner\":\"" + escape(owner) + "\",\"ttl\":" + ttl + ",\"leases\":"
                + leasesJson() + "}";
    }

    private String queryValue(String query, String key) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    /** Minutes of inactivity before the harness is suspended; 0 disables idle shutdown. */
    private int idleMinutes() {
        return getSharedPreferences("dsh", MODE_PRIVATE).getInt("idle_minutes", 0);
    }

    private void setAutoIdle(int minutes) {
        getSharedPreferences("dsh", MODE_PRIVATE).edit()
                .putInt("idle_minutes", Math.max(0, Math.min(240, minutes))).apply();
    }

    /** Called once the harness is ready; starts the idle timer when the user enabled it. */
    private void scheduleIdleCheck() {
        mainHandler.removeCallbacks(idleCheck);
        if (idleMinutes() > 0) {
            mainHandler.postDelayed(idleCheck, IDLE_CHECK_MS);
        }
    }

    private void checkIdle() {
        int minutes = idleMinutes();
        if (minutes <= 0) {
            return;
        }
        long idleFor = SystemClock.elapsedRealtime() - lastActivity;
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        boolean screenOff = power != null && !power.isInteractive();
        boolean idle = currentUrl != null && activeLeases() == 0 && screenOff
                && idleFor >= minutes * 60_000L;
        if (idle) {
            Log.i(TAG, "idle for " + idleFor + "ms with no lease; stopping the harness");
            Thread stop = new Thread(() -> {
                releaseWake();
                stopHarness();
                stopSelf();
            }, "dsh-idle-stop");
            stop.setDaemon(true);
            stop.start();
            return;
        }
        mainHandler.postDelayed(idleCheck, IDLE_CHECK_MS);
    }

    private void releaseWake() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DOWNLOAD.equals(intent.getAction())) {
            startForeground(NOTIFICATION_ID, buildNotification("正在准备下载更新…"));
            startDownload(intent.getStringExtra(EXTRA_URL), intent.getStringExtra(EXTRA_VERSION));
            return START_STICKY;
        }
        if (intent != null && ACTION_DO_RESTART.equals(intent.getAction())) {
            startForeground(NOTIFICATION_ID, buildNotification("正在重启服务…"));
            restartSuppressed = false;
            bootFailures = 0;
            File dir = runtimeDir;
            if (dir != null && process != null) {
                restartHarness();
            } else {
                startBoot();
            }
            return START_STICKY;
        }
        if (intent != null && ACTION_DO_RESTORE.equals(intent.getAction())) {
            startForeground(NOTIFICATION_ID, buildNotification("正在回滚配置…"));
            boolean restored = safetyNet != null && safetyNet.restore(runtimeDir);
            Log.i(TAG, "manual rollback: " + restored);
            if (restored) {
                restartHarness();
            }
            return START_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification("正在启动 DeepSeek Harness…"));

        String known = currentUrl;
        if (known != null) {
            broadcast(ACTION_READY, EXTRA_URL, known);
            return START_STICKY;
        }
        if (worker == null || !worker.isAlive()) {
            startBoot();
        }
        return START_STICKY;
    }

    /**
     * Start the boot thread unless one is already running.
     *
     * <p>Resets the timeline so the report describes this start, not an earlier one.</p>
     */
    private void startBoot() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        touchActivity();
        resetTimeline("service start");
        worker = new Thread(this::bootRuntime, "dsh-runtime");
        worker.start();
    }

    /**
     * Copy the boot-time node hooks into app storage so the container can import them.
     *
     * @return directory bound at /root/.dshboot, or null when it cannot be prepared
     */
    private File prepareBootFiles() {
        File dir = new File(getFilesDir(), "boot");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create " + dir);
            return null;
        }
        File loader = new File(dir, "res-hooks.mjs");
        try (java.io.InputStream in = getAssets().open("boot/res-hooks.mjs");
             FileOutputStream out = new FileOutputStream(loader)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (IOException error) {
            Log.w(TAG, "cannot prepare " + loader, error);
            return null;
        }
        return dir;
    }

    /**
     * Start a fresh timeline for a new attempt.
     *
     * @param first stage label for the attempt itself
     */
    private void resetTimeline(String first) {
        bootStartedAt = SystemClock.elapsedRealtime();
        synchronized (bootStages) {
            bootStages.clear();
        }
        stage(first);
    }

    /** Unpack the runtime (first run only), start the server and publish its URL. */
    private void bootRuntime() {
        long started = SystemClock.elapsedRealtime();
        try {
            safetyNet = new SafetyNet(getFilesDir());
            File runtime = RuntimeInstaller.install(this, (percent, stage) -> {
                updateNotification(stage + " " + percent + "%");
                broadcast(ACTION_PROGRESS, EXTRA_PROGRESS, describe(percent, stage));
            });
            if (!new File(runtime, "bin/proot").canExecute()) {
                Log.w(TAG, "launcher missing, unpacking again");
                runtime = RuntimeInstaller.reinstall(this, (percent, stage) ->
                        updateNotification(stage + " " + percent + "%"));
            }
            Log.i(TAG, "runtime ready in " + (SystemClock.elapsedRealtime() - started) + "ms");
            stage("runtime ready");
            runtimeDir = runtime;
            // Seed the bundled plugin before SafetyNet snapshots the profile, so the copy it
            // records as "good" already contains the plugin.
            PluginSeed.install(this, runtime);
            stage("plugin seed");
            boolean previousBootFailed = safetyNet.beginBoot();
            startControlServer();
            stage("control server");
            startServer(runtime);
            if (currentUrl == null && safetyNet.hasGood()) {
                // Rolling back discards whatever the agent edited, so it needs a human decision.
                requestRestoreConfirm(previousBootFailed);
            }
        } catch (Throwable error) {
            if (restarting) {
                Log.i(TAG, "previous harness stopped for restart");
                return;
            }
            Log.e(TAG, "runtime boot failed", error);
            broadcast(ACTION_FAILED, EXTRA_ERROR, String.valueOf(error.getMessage()));
            stage("boot failed: " + error.getMessage());
            persistTimeline();
            scheduleRestart("boot failed");
        }
    }

    /**
     * Spawn proot and stream its stdout until the harness reports its URL.
     *
     * @param runtime unpacked runtime directory
     * @throws IOException when the process cannot be started
     */
    private void startServer(File runtime) throws IOException {
        File proot = new File(runtime, "bin/proot");
        File lib = new File(runtime, "lib");
        File rootfs = new File(runtime, "rootfs");
        publishControlToken(rootfs);

        List<String> command = new ArrayList<>();
        command.add(proot.getAbsolutePath());
        // Android denies hardlinks; proot emulates them as symlinks. Required by the native
        // addon packages, which pnpm installs as links into its store.
        command.add("--link2symlink");
        // Kill traced children when proot exits, otherwise a restart leaves node holding the port.
        command.add("--kill-on-exit");
        command.add("-0");
        command.add("-r");
        command.add(rootfs.getAbsolutePath());
        command.add("-b");
        command.add("/dev");
        command.add("-b");
        command.add("/proc");
        command.add("-b");
        command.add("/sys");
        UserData data = UserData.prepare(this);
        sharedDirs = data;
        command.add("-b");
        command.add(data.work.getAbsolutePath() + ":/root/work");
        // Keep the V8 compile cache across runtime image swaps: an image update replaces the
        // whole runtime directory, so a cache inside it would be thrown away every upgrade.
        File compileCache = new File(getFilesDir(), "node-compile");
        if (compileCache.isDirectory() || compileCache.mkdirs()) {
            command.add("-b");
            command.add(compileCache.getAbsolutePath() + ":/root/.cache/node-compile");
        }
        // Resolution cache: the first boot records every (specifier, parent) pair, later boots
        // replay it instead of walking the file system again, which is the dominant cost of a
        // cold start under proot.
        File bootDir = prepareBootFiles();
        if (bootDir != null) {
            command.add("-b");
            command.add(bootDir.getAbsolutePath() + ":/root/.dshboot");
        }
        // The image ships public resolvers, which cannot see hosts on a private network
        // (corporate proxies, LAN model servers); mirror the phone's current DNS instead.
        File resolv = prepareResolvConf();
        if (resolv != null) {
            command.add("-b");
            command.add(resolv.getAbsolutePath() + ":/etc/resolv.conf");
        }
        command.add("-w");
        command.add("/root/work");
        command.add("/usr/bin/env");
        command.add("-i");
        command.add("HOME=/root");
        command.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        command.add("TERM=xterm-256color");
        command.add("LANG=C.UTF-8");
        // Cache V8 bytecode: module compilation repeats on every cold start otherwise.
        command.add("NODE_COMPILE_CACHE=/root/.cache/node-compile");
        if (bootDir != null) {
            command.add("NODE_OPTIONS=--import /root/.dshboot/res-hooks.mjs");
        }
        int port = pickPort();
        Log.i(TAG, "serving on 127.0.0.1:" + port);
        command.add("/usr/local/bin/dsh");
        command.add("web");
        command.add("--no-open");
        command.add("--port");
        command.add(String.valueOf(port));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(runtime);
        builder.redirectErrorStream(true);
        builder.environment().put("LD_LIBRARY_PATH", lib.getAbsolutePath());
        builder.environment().put("PROOT_LOADER", new File(runtime, "libexec/proot/loader").getAbsolutePath());
        builder.environment().put("PROOT_TMP_DIR", getCacheDir().getAbsolutePath());
        // The container itself ships UTC; follow the phone so timestamps and schedules match.
        String zone = java.util.TimeZone.getDefault().getID();
        builder.environment().put("TZ", zone);
        applyTimezone(runtime, zone);

        broadcast(ACTION_PROGRESS, EXTRA_PROGRESS, "正在启动服务…");
        stage("spawning proot");
        long spawnedAt = SystemClock.elapsedRealtime();
        Process current;
        try {
            current = builder.start();
        } catch (IOException startFailure) {
            // A swap or a partial unpack can leave the launcher briefly unreadable; verify and
            // retry once before failing the boot.
            if (!proot.canExecute()) {
                throw new IOException("launcher missing at " + proot, startFailure);
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted before retry", startFailure);
            }
            current = builder.start();
        }
        process = current;
        Log.i(TAG, "proot spawned in " + (SystemClock.elapsedRealtime() - spawnedAt) + "ms");
        updateNotification("正在启动服务…");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(current.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    stage("first engine output");
                }
                Log.i(TAG, line);
                recordOutput(line);
                Matcher matcher = URL_PATTERN.matcher(line);
                if (matcher.find() && currentUrl == null) {
                    Log.i(TAG, "harness ready in " + (SystemClock.elapsedRealtime() - bootStartedAt) + "ms");
                    currentUrl = matcher.group(1);
                    onReady();
                }
            }
        }
        int status;
        try {
            status = current.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        if (process != current) {
            // A restart already replaced this process; its exit says nothing about the current one.
            Log.i(TAG, "harness " + status + " was replaced by a restart");
            return;
        }
        Log.w(TAG, "harness exited with " + status);
        persistTimeline();
        handleHarnessExit(status);
    }

    /**
     * Publish the URL, snapshot the profile and get the non-essential copies off the boot path.
     *
     * <p>Called from the reader thread the moment the harness prints its URL. The workspace
     * export and the guide copies used to run before {@code proot} even started, where they
     * competed with the engine for the disk and the CPU during the slowest part of the boot.</p>
     */
    private void onReady() {
        stage("ready");
        persistTimeline();
        if (safetyNet != null && runtimeDir != null) {
            safetyNet.markGood(runtimeDir);
            mirrorBackup();
        }
        UserData data = sharedDirs;
        resident = data != null && qqbotEnabled(data);
        if (resident) {
            Log.i(TAG, "qqbot configured; the harness stays awake while idle");
        }
        updateNotification("已就绪");
        broadcast(ACTION_READY, EXTRA_URL, currentUrl);
        publishAfterReady();
        scheduleIdleCheck();
    }

    /**
     * Run the copies that are useful but not needed to reach a serving harness.
     *
     * <p>Runs on its own thread after the URL is out, so the phone can show the UI while the
     * shared workspace catches up.</p>
     */
    private void publishAfterReady() {
        final UserData data = sharedDirs;
        if (data == null) {
            return;
        }
        Thread thread = new Thread(() -> {
            long started = SystemClock.elapsedRealtime();
            publishDocs(data);
            publishHome(data);
            Log.i(TAG, "post-ready copies in " + (SystemClock.elapsedRealtime() - started) + "ms");
        }, "dsh-post-ready");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Record a boot stage together with its offset from the start of this boot.
     *
     * @param name short, stable stage label
     */
    private void stage(String name) {
        long offset = SystemClock.elapsedRealtime() - bootStartedAt;
        synchronized (bootStages) {
            bootStages.add(name + " " + offset + "ms");
            if (bootStages.size() > TIMELINE_IN_MEMORY) {
                bootStages.remove(0);
            }
        }
        Log.i(TAG, "boot: " + name + " at " + offset + "ms");
    }

    /** Persist the timeline of the latest boot so /diag can show it after a restart. */
    private void persistTimeline() {
        List<String> copy;
        synchronized (bootStages) {
            copy = new ArrayList<>(bootStages);
        }
        getSharedPreferences("dsh", MODE_PRIVATE).edit()
                .putString(PREF_BOOT_TIMELINE, String.join("\n", copy)).apply();
    }

    /**
     * Keep the tail of the harness output on disk.
     *
     * <p>Output used to go to logcat only, which is gone once the process dies — exactly the case
     * a crash report has to explain.</p>
     *
     * @param line one line of harness output
     */
    private void recordOutput(String line) {
        synchronized (recentOutput) {
            recentOutput.addLast(line);
            while (recentOutput.size() > 40) {
                recentOutput.removeFirst();
            }
        }
        PrintWriter writer = harnessLog;
        if (writer == null) {
            File log = new File(getFilesDir(), HARNESS_LOG);
            try {
                if (log.length() > HARNESS_LOG_LIMIT) {
                    java.nio.file.Files.move(log.toPath(),
                            new File(getFilesDir(), HARNESS_LOG + ".1").toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                writer = new PrintWriter(new FileOutputStream(log, true), true);
                harnessLog = writer;
            } catch (IOException error) {
                Log.w(TAG, "cannot write harness log", error);
                return;
            }
        }
        writer.println(line);
    }

    /**
     * Handle the harness process ending: record why, then restart unless the loop gave up.
     *
     * @param status exit code reported by proot
     */
    private void handleHarnessExit(int status) {
        if (restarting) {
            return;
        }
        long ranFor = SystemClock.elapsedRealtime() - bootStartedAt;
        String reason = "harness exited with " + status + " after " + ranFor + "ms"
                + (currentUrl == null ? " (never ready)" : "");
        getSharedPreferences("dsh", MODE_PRIVATE).edit()
                .putString(PREF_LAST_EXIT, reason + "\n" + tailOutput(6)).apply();
        Log.w(TAG, reason);
        currentUrl = null;
        broadcast(ACTION_FAILED, EXTRA_ERROR, "服务意外退出：" + status);
        if (ranFor >= STABLE_BOOT_MS) {
            bootFailures = 0;
            firstFailureAt = 0;
        }
        scheduleRestart(reason);
    }

    /**
     * Last lines of harness output, oldest first.
     *
     * @param count how many lines to keep
     * @return newline-joined lines
     */
    private String tailOutput(int count) {
        List<String> lines;
        synchronized (recentOutput) {
            lines = new ArrayList<>(recentOutput);
        }
        int from = Math.max(0, lines.size() - count);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    /**
     * Restart the harness after a failure, backing off and giving up after repeated failures.
     *
     * @param reason short description for the log
     */
    private void scheduleRestart(String reason) {
        if (restartSuppressed) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (firstFailureAt == 0 || now - firstFailureAt > FAILURE_WINDOW_MS) {
            firstFailureAt = now;
            bootFailures = 0;
        }
        bootFailures++;
        if (bootFailures > MAX_BOOT_FAILURES) {
            degrade(reason);
            return;
        }
        long delay = 1000L << (bootFailures - 1);
        Log.i(TAG, "restart " + bootFailures + "/" + MAX_BOOT_FAILURES + " in " + delay + "ms");
        final File dir = runtimeDir;
        if (dir == null) {
            // The failure happened before the runtime was unpacked; the next attempt has to
            // redo the setup steps, not just respawn the harness.
            mainHandler.postDelayed(() -> {
                if (!restartSuppressed && process == null) {
                    startBoot();
                }
            }, delay);
            return;
        }
        mainHandler.postDelayed(() -> {
            if (restartSuppressed || process != null) {
                return;
            }
            Thread thread = new Thread(() -> {
                try {
                    currentUrl = null;
                    resetTimeline("auto restart");
                    startServer(dir);
                } catch (IOException error) {
                    Log.e(TAG, "restart failed", error);
                    scheduleRestart("restart failed: " + error.getMessage());
                }
            }, "dsh-restart");
            thread.setDaemon(true);
            thread.start();
        }, delay);
    }

    /**
     * Stop the automatic restart loop and leave the user a way back in.
     *
     * @param reason why the loop stopped
     */
    private void degrade(String reason) {
        restartSuppressed = true;
        Log.e(TAG, "giving up after " + bootFailures + " failures: " + reason);
        Intent retry = new Intent(this, DshService.class).setAction(ACTION_DO_RESTART);
        android.app.PendingIntent pending = android.app.PendingIntent.getService(
                this, 2, retry,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.NotificationManager manager =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            android.app.Notification note = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("DeepSeek Harness 已停止")
                    .setContentText("服务反复退出（" + reason + "）。点按重试。")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .build();
            manager.notify(NOTIFICATION_ID + 2, note);
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    /** Close the rotating harness log; null is fine. */
    private void closeHarnessLog() {
        PrintWriter writer = harnessLog;
        harnessLog = null;
        if (writer != null) {
            writer.close();
        }
    }

    /**
     * Post-mortem snapshot for the control panel: boot timeline, last exit, recent output.
     *
     * @return JSON body
     */
    private String diagReport() {
        String timeline = getSharedPreferences("dsh", MODE_PRIVATE).getString(PREF_BOOT_TIMELINE, "");
        String lastExit = getSharedPreferences("dsh", MODE_PRIVATE).getString(PREF_LAST_EXIT, "");
        String output = tailOutput(40);
        return "{\"timeline\":\"" + escape(timeline) + "\",\"last_exit\":\"" + escape(lastExit)
                + "\",\"output\":\"" + escape(output) + "\"}";
    }

    /** The tail of the harness stdout/stderr, for remote debugging (`GET /log`). */
    private String logTailReport() {
        File log = new File(getFilesDir(), HARNESS_LOG);
        if (!log.isFile()) {
            return "{\"size\":0,\"log\":\"\"}";
        }
        long size = log.length();
        long from = Math.max(0, size - 96L * 1024L);
        StringBuilder text = new StringBuilder();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log, "r")) {
            raf.seek(from);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = raf.read(buffer)) > 0) {
                text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
        } catch (IOException failure) {
            return "{\"size\":" + size + ",\"error\":\"" + escape(failure.toString()) + "\"}";
        }
        return "{\"size\":" + size + ",\"log\":\"" + escape(text.toString()) + "\"}";
    }

    /** Probe the configured model endpoint with the stored key (`GET /net`). */
    private String netReport() {
        File home = new File(runtimeDir, "rootfs/root/.dsh");
        String key = readCredential(new File(home, ".credentials.yaml"), "DEEPSEEK_API_KEY");
        String baseURL = "https://api.deepseek.com";
        File patch = new File(home, "profiles/web/cordis.patch.yml");
        if (patch.isFile()) {
            for (String line : readLines(patch)) {
                String t = line.trim();
                if (t.startsWith("baseURL:")) {
                    baseURL = t.substring("baseURL:".length()).trim().replace("\"", "").replace("'", "");
                }
            }
        }
        String root = baseURL.replaceAll("/+$", "");
        if (!root.endsWith("/v1")) {
            root = root + "/v1";
        }
        StringBuilder out = new StringBuilder("{");
        out.append("\"base\":\"").append(escape(baseURL)).append("\",\"key_len\":").append(key.length());
        out.append(",\"dns\":\"").append(escape(dns(baseURL))).append("\"");
        String messages = "{\"model\":\"deepseek-flash\",\"max_tokens\":16,\"stream\":true,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        out.append(",\"messages\":").append(postJson(root + "/messages", messages, key,
                "x-api-key", "anthropic-version: 2023-06-01"));
        String chat = "{\"model\":\"deepseek-flash\",\"max_tokens\":16,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        out.append(",\"chat\":").append(postJson(root + "/chat/completions", chat, key,
                "Authorization", null));
        out.append(",\"container\":").append(containerNet(runtimeDir, root + "/messages", key));
        out.append("}");
        return out.toString();
    }

    /**
     * Ask the container to fetch a URL, so DNS and TLS inside proot can be checked directly.
     *
     * @param runtime unpacked runtime directory
     * @param url endpoint to fetch
     * @return JSON with the exit state and captured output
     */
    private String containerNet(File runtime, String url, String key) {
        try {
            File proot = new File(runtime, "bin/proot");
            File rootfs = new File(runtime, "rootfs");
            List<String> command = new ArrayList<>();
            command.add(proot.getAbsolutePath());
            command.add("--link2symlink");
            command.add("--kill-on-exit");
            command.add("-0");
            command.add("-r");
            command.add(rootfs.getAbsolutePath());
            command.add("-b");
            command.add("/dev");
            command.add("-b");
            command.add("/proc");
            command.add("-b");
            command.add("/sys");
            UserData data = UserData.prepare(this);
            command.add("-b");
            command.add(data.work.getAbsolutePath() + ":/root/work");
            File resolv = prepareResolvConf();
            if (resolv != null) {
                command.add("-b");
                command.add(resolv.getAbsolutePath() + ":/etc/resolv.conf");
            }
            command.add("-w");
            command.add("/root/work");
            command.add("/usr/bin/env");
            command.add("-i");
            command.add("HOME=/root");
            command.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
            command.add("/usr/local/bin/node");
            command.add("-e");
            command.add("const u=process.argv[1],k=process.argv[2];"
                    + "const o={method:'POST',headers:{'x-api-key':k,'anthropic-version':'2023-06-01',"
                    + "'content-type':'application/json'},body:JSON.stringify({model:'deepseek-flash',"
                    + "max_tokens:8,messages:[{role:'user',content:'hi'}]})};"
                    + "fetch(u,o).then(r=>r.text().then(t=>console.log('CONTAINER_OK',r.status,t.slice(0,60))))"
                    + ".catch(e=>console.log('CONTAINER_FAIL',String(e&&(e.cause&&e.cause.code||e.code)||e)));");
            command.add(url);
            command.add(key);
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(runtime);
            builder.redirectErrorStream(true);
            builder.environment().put("LD_LIBRARY_PATH", new File(runtime, "lib").getAbsolutePath());
            builder.environment().put("PROOT_LOADER",
                    new File(runtime, "libexec/proot/loader").getAbsolutePath());
            builder.environment().put("PROOT_TMP_DIR", getCacheDir().getAbsolutePath());
            Process probe = builder.start();
            StringBuffer text = new StringBuffer();
            Thread reader = new Thread(() -> {
                try (BufferedReader stream = new BufferedReader(new InputStreamReader(
                        probe.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = stream.readLine()) != null && text.length() < 2000) {
                        text.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                    // best effort
                }
            }, "dsh-net-probe");
            reader.setDaemon(true);
            reader.start();
            boolean done = probe.waitFor(25, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                probe.destroyForcibly();
            }
            reader.join(1500);
            return "{\"done\":" + done + ",\"out\":\"" + escape(text.toString()) + "\"}";
        } catch (Exception failure) {
            return "{\"error\":\"" + escape(failure.toString()) + "\"}";
        }
    }

    /**
     * Mirror the phone's DNS servers into a file the container can mount at /etc/resolv.conf.
     * The image ships public resolvers, which cannot resolve hosts on a private network.
     *
     * @return the generated file, or null when no server is known
     */
    private File prepareResolvConf() {
        java.util.LinkedHashSet<String> servers = new java.util.LinkedHashSet<>();
        try {
            android.net.ConnectivityManager manager =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (manager != null) {
                for (android.net.Network network : manager.getAllNetworks()) {
                    android.net.LinkProperties props = manager.getLinkProperties(network);
                    if (props == null) {
                        continue;
                    }
                    for (java.net.InetAddress dns : props.getDnsServers()) {
                        String host = dns.getHostAddress();
                        if (host != null && !host.isEmpty()) {
                            servers.add(host);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // fall back to the image resolvers
        }
        if (servers.isEmpty()) {
            return null;
        }
        File file = new File(getFilesDir(), "resolv.conf");
        StringBuilder text = new StringBuilder();
        for (String server : servers) {
            text.append("nameserver ").append(server).append('\n');
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException failure) {
            Log.w(TAG, "cannot write resolv.conf", failure);
            return null;
        }
        return file;
    }

    private static String dns(String baseURL) {
        try {
            return InetAddress.getByName(new java.net.URL(baseURL).getHost()).getHostAddress();
        } catch (Exception failure) {
            return "FAIL " + failure;
        }
    }

    private static String readCredential(File file, String name) {
        for (String line : readLines(file)) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equals(name)) {
                return line.substring(colon + 1).trim().replace("\"", "").replace("'", "");
            }
        }
        return "";
    }

    private static List<String> readLines(File file) {
        List<String> lines = new ArrayList<>();
        if (!file.isFile()) {
            return lines;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException ignored) {
            // best effort
        }
        return lines;
    }

    private static String postJson(String url, String body, String key, String authHeader, String extra) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("content-type", "application/json");
            conn.setRequestProperty("accept", "text/event-stream");
            conn.setRequestProperty(authHeader, ("Authorization".equals(authHeader) ? "Bearer " : "") + key);
            if (extra != null) {
                int c = extra.indexOf(':');
                conn.setRequestProperty(extra.substring(0, c), extra.substring(c + 1).trim());
            }
            try (OutputStream stream = conn.getOutputStream()) {
                stream.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStreamLike in = new InputStreamLike(conn, code);
            String text = in.read(600);
            return "{\"status\":" + code + ",\"body\":\"" + escape(text) + "\"}";
        } catch (Exception failure) {
            return "{\"error\":\"" + escape(failure.toString()) + "\"}";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Minimal reader that works for both success and error bodies. */
    private static final class InputStreamLike {
        private final java.net.HttpURLConnection conn;
        private final int code;

        InputStreamLike(java.net.HttpURLConnection conn, int code) {
            this.conn = conn;
            this.code = code;
        }

        String read(int limit) throws IOException {
            java.io.InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) {
                return "";
            }
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buffer = new char[512];
                int read;
                while (text.length() < limit && (read = reader.read(buffer)) > 0) {
                    text.append(buffer, 0, read);
                }
            }
            return text.length() > limit ? text.substring(0, limit) : text.toString();
        }
    }

    /**
     * Escape a string for a JSON string literal.
     *
     * @param text raw text
     * @return escaped text without the surrounding quotes
     */
    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    /**
     * Ask the user whether to roll the profile back to the last configuration that started.
     *
     * @param previousBootFailed whether the previous attempt also failed
     */
    private void requestRestoreConfirm(boolean previousBootFailed) {
        Log.w(TAG, "boot failed; offering rollback (previous failure: " + previousBootFailed + ")");
        broadcast(ACTION_RESTORE_PROMPT, EXTRA_PROGRESS, "boot failed");
        android.app.NotificationManager manager =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        android.content.Intent restore = new android.content.Intent(this, DshService.class)
                .setAction(ACTION_DO_RESTORE);
        android.app.PendingIntent pending = android.app.PendingIntent.getService(
                this, 1, restore,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("DSH 启动失败")
                .setContentText("配置可能被修改。点击回滚到上一次可用的配置。")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        manager.notify(NOTIFICATION_ID + 1, notification);
    }

    /**
     * Serve POST /restart and POST /reload on loopback so the agent can apply plugin changes
     * without the user reopening the app.
     */
    private void startControlServer() {
        Thread thread = new Thread(() -> {
            try (ServerSocket server = new ServerSocket(CONTROL_PORT, 8, InetAddress.getByName("127.0.0.1"))) {
                Log.i(TAG, "control server on 127.0.0.1:" + CONTROL_PORT);
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        handleControl(socket);
                    } catch (IOException error) {
                        Log.w(TAG, "control request failed", error);
                    } catch (RuntimeException error) {
                        Log.w(TAG, "control request crashed", error);
                    }
                }
            } catch (IOException error) {
                Log.w(TAG, "control server unavailable", error);
            }
        }, "dsh-control");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleControl(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = reader.readLine();
        String body;
        // Exact path matching: prefix checks made /update swallow /update/auto and /update/forget.
        String path = "";
        String method = "";
        if (requestLine != null) {
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2) {
                method = parts[0];
                path = parts[1];
            }
        }
        int mark = path.indexOf('?');
        String route = mark < 0 ? path : path.substring(0, mark);
        String query = mark < 0 ? "" : path.substring(mark + 1);
        String tokenHeader = null;
        String header;
        while ((header = reader.readLine()) != null && !header.isEmpty()) {
            int colon = header.indexOf(':');
            if (colon > 0 && "X-Dsh-Token".equalsIgnoreCase(header.substring(0, colon).trim())) {
                tokenHeader = header.substring(colon + 1).trim();
                break;
            }
        }
        int status = 200;
        touchActivity();

        if (!controlTokenOk(tokenHeader)) {
            status = 403;
            body = "{\"error\":\"unauthorized\"}";
        } else if ("GET".equals(method) && "/".equals(route)) {
            String url = currentUrl;
            body = "{\"url\":\"" + (url == null ? "" : url) + "\"}";
        } else if ("/backups".equals(route)) {
            StringBuilder slots = new StringBuilder();
            if (safetyNet != null) {
                for (String slot : safetyNet.manualSlots()) {
                    if (slots.length() > 0) {
                        slots.append(",");
                    }
                    slots.append("\"").append(slot).append("\"");
                }
            }
            body = "{\"slots\":[" + slots + "],\"last-good\":"
                    + (safetyNet != null && safetyNet.hasGood()) + "}";
        } else if ("/diag".equals(route)) {
            body = diagReport();
        } else if ("/log".equals(route)) {
            body = logTailReport();
        } else if ("/net".equals(route)) {
            body = netReport();
        } else if ("/lease".equals(route)) {
            body = handleLease(method, query);
        } else if ("/leases".equals(route)) {
            body = "{\"leases\":" + leasesJson() + "}";
        } else if ("/idle".equals(route)) {
            if ("POST".equals(method)) {
                String raw = queryValue(query, "minutes");
                int minutes = 0;
                try {
                    minutes = raw == null ? 0 : Integer.parseInt(raw);
                } catch (NumberFormatException ignored) {
                    // Treat a malformed value as "disabled" instead of failing the request.
                }
                setAutoIdle(minutes);
                scheduleIdleCheck();
            }
            body = "{\"minutes\":" + idleMinutes() + "}";
        } else if ("/update/auto".equals(route)) {
            if ("POST".equals(method)) {
                boolean enabled = !query.contains("enabled=false");
                setAutoUpdate(enabled);
                body = "{\"enabled\":" + enabled + "}";
            } else {
                body = "{\"enabled\":" + autoUpdateEnabled() + "}";
            }
        } else if ("/update/forget".equals(route)) {
            File cached = new File(getCacheDir(), "update.apk");
            File partial = new File(getCacheDir(), "update.apk.part");
            boolean removed = cached.delete() | partial.delete();
            getSharedPreferences("dsh", MODE_PRIVATE).edit().remove("update_version").apply();
            body = "{\"status\":\"" + (removed ? "cleared" : "nothing") + "\"}";
        } else if ("/update".equals(route)) {
            body = updateStatus();
        } else if ("/install".equals(route)) {
            String expected = getSharedPreferences("dsh", MODE_PRIVATE)
                    .getString("update_version", null);
            File apk = UpdateChecker.cachedApk(this, expected);
            if (apk == null && sharedDirs != null) {
                // Legacy drop folder: validate but never delete a file the user put there.
                apk = UpdateChecker.verifiedApk(this, new File(sharedDirs.work, "update.apk"),
                        expected, false);
            }
            if (apk != null) {
                broadcast(ACTION_INSTALL_READY, EXTRA_PROGRESS, apk.getAbsolutePath());
                body = "{\"status\":\"installing\",\"size\":" + apk.length() + "}";
            } else {
                body = "{\"status\":\"no-file\"}";
            }
        } else if ("/backup".equals(route)) {
            String slot = null;
            String where = "";
            if (safetyNet != null && runtimeDir != null) {
                slot = safetyNet.createManual(runtimeDir);
                if (slot != null) {
                    File mirror = mirrorDir(slot);
                    if (mirror != null && safetyNet.mirrorSlot(slot, mirror) > 0) {
                        where = mirror.getAbsolutePath();
                    }
                }
            }
            body = "{\"status\":\"" + (slot != null ? "backed-up" : "failed")
                    + "\",\"slot\":\"" + (slot == null ? "" : slot)
                    + "\",\"at\":\"" + where.replace("\"", "") + "\"}";
        } else if ("/restore".equals(route)) {
            String slot = queryParam("?" + query, "slot");
            String from = slot == null ? (safetyNet == null ? "无" : safetyNet.describeGood()) : slot;
            boolean restored = safetyNet != null && safetyNet.restoreSlot(runtimeDir, slot);
            body = "{\"status\":\"" + (restored ? "restored" : "no-backup")
                    + "\",\"from\":\"" + from.replace("\"", "") + "\"}";
            if (restored) {
                restartHarness();
            }
        } else if ("/export".equals(route)) {
            publishHome(null);
            body = "{\"status\":\"exporting\"}";
        } else if ("/restart".equals(route)) {
            int delay = 0;
            String raw = queryValue(query, "delay");
            if (!raw.isEmpty()) {
                try {
                    delay = Math.max(0, Math.min(120, Integer.parseInt(raw)));
                } catch (NumberFormatException ignored) {
                    delay = 0;
                }
            }
            if (delay > 0) {
                Log.i(TAG, "restart scheduled in " + delay + "s");
                mainHandler.postDelayed(this::restartHarness, delay * 1000L);
                body = "{\"status\":\"scheduled\",\"delay\":" + delay + "}";
            } else {
                restartHarness();
                body = "{\"status\":\"restarting\"}";
            }
        } else if ("/reload".equals(route)) {
            broadcast(ACTION_RELOAD, EXTRA_PROGRESS, "");
            body = "{\"status\":\"reloading\"}";
        } else {
            String url = currentUrl;
            body = "{\"url\":\"" + (url == null ? "" : url) + "\"}";
        }

        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        String statusLine = status == 200 ? "HTTP/1.1 200 OK" : "HTTP/1.1 403 Forbidden";
        out.write((statusLine + "\r\nContent-Type: application/json\r\nContent-Length: "
                + payload.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.flush();
    }

    /** Publish the control token inside the container so its shell can call the port. */
    private void publishControlToken(File rootfs) {
        File home = new File(rootfs, "root/.dsh");
        if (!home.isDirectory()) {
            Log.w(TAG, "cannot publish control token: no " + home);
            return;
        }
        try (FileOutputStream out = new FileOutputStream(new File(home, ".control-token"))) {
            out.write(controlToken.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            Log.w(TAG, "cannot publish control token", error);
        }
    }

    /** True when the caller presented the current control token. */
    private boolean controlTokenOk(String presented) {
        String expected = controlToken;
        return expected != null && !expected.isEmpty() && expected.equals(presented);
    }

    /** Stop the current harness process and start it again, publishing a fresh URL. */
    private void restartHarness() {
        File dir = runtimeDir;
        if (dir == null) {
            return;
        }
        // Stop and start off the control thread: waiting for proot to exit would block /reload and
        // the next status request.
        Thread thread = new Thread(() -> {
            restarting = true;
            Log.i(TAG, "restart requested");
            stopHarness();
            restarting = false;
            Log.i(TAG, "old harness stopped");
            currentUrl = null;
            resetTimeline("manual restart");
            try {
                startServer(dir);
            } catch (IOException error) {
                Log.e(TAG, "restart failed", error);
                broadcast(ACTION_FAILED, EXTRA_ERROR, String.valueOf(error.getMessage()));
                scheduleRestart("restart failed: " + error.getMessage());
            }
        }, "dsh-restart");
        thread.start();
    }

    /**
     * Terminate the harness and every child it spawned. Killing proot alone leaves the node
     * process holding the loopback port, so the replacement cannot bind and exits.
     */
    private void stopHarness() {
        stopHarness(true);
    }

    /**
     * Terminate the harness process.
     *
     * @param waitForExit whether to block until it is gone; never true on the main thread
     */
    private void stopHarness(boolean waitForExit) {
        Process current = process;
        process = null;
        if (current == null) {
            return;
        }
        // SIGTERM lets proot clean up its tracees (--kill-on-exit); SIGKILL alone orphans node,
        // which keeps holding the loopback port.
        current.destroy();
        try {
            if (!current.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                current.destroyForcibly();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
        if (!waitForExit) {
            return;
        }
        try {
            if (!current.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.w(TAG, "harness did not exit in time; starting a replacement anyway");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Point the container's /etc/localtime and /etc/timezone at the phone's zone.
     *
     * @param runtime unpacked runtime directory
     * @param zone IANA zone id from the device
     */
    private void applyTimezone(File runtime, String zone) {
        File rootfs = new File(runtime, "rootfs");
        File zoneInfo = new File(rootfs, "usr/share/zoneinfo/" + zone);
        File localtime = new File(rootfs, "etc/localtime");
        try {
            if (zoneInfo.isFile()) {
                java.nio.file.Files.deleteIfExists(localtime.toPath());
                java.nio.file.Files.createSymbolicLink(localtime.toPath(),
                        java.nio.file.Paths.get("/usr/share/zoneinfo/" + zone));
            }
            java.nio.file.Files.write(new File(rootfs, "etc/timezone").toPath(),
                    (zone + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException error) {
            Log.w(TAG, "cannot apply timezone " + zone, error);
        }
    }

    /**
     * Pick a loopback port the harness can bind. A stale process from a previous install may still
     * hold 3080, so the next free port in range is used instead of failing the boot.
     *
     * @return a bindable port
     */
    private int pickPort() {
        for (int port = 3080; port < 3100; port++) {
            if (port == CONTROL_PORT) {
                continue;
            }
            try (java.net.ServerSocket probe = new java.net.ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
                return port;
            } catch (IOException busy) {
                // try the next one
            }
        }
        return 3080;
    }

    /**
     * Mirror the harness home into shared storage so the phone's file manager can read sessions,
     * plugins and settings.
     *
     * @param data shared directories, or null to reuse the last resolution
     */
    private void publishHome(UserData data) {
        final UserData resolved = data != null ? data : this.sharedDirs;
        if (resolved == null) {
            return;
        }
        this.sharedDirs = resolved;
        Thread thread = new Thread(() -> {
            try {
                File runtime = runtimeDir;
                if (runtime == null) {
                    return;
                }
                int files = DshHomeExporter.export(runtime, resolved.dsh);
                Log.i(TAG, "exported " + files + " harness files to " + resolved.dsh);
            } catch (IOException error) {
                Log.w(TAG, "harness home export failed", error);
            }
        }, "dsh-export");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Copy the operator guide and the recommended plugin list into the shared workspace, so the
     * agent reads them as workspace files and the user sees them on the phone.
     *
     * @param data resolved shared directories
     */
    private void publishDocs(UserData data) {
        writeIfChanged(new File(data.work, "DSH-ANDROID.md"), "docs/guide.md");
        writeIfChanged(new File(data.work, "推荐插件.md"), "docs/plugins.md");
    }

    /**
     * Copy a packaged document when the destination differs, so the workspace does not churn on
     * every boot and edits to the asset reach existing installs.
     *
     * @param target destination inside the shared workspace
     * @param asset asset path
     */
    private void writeIfChanged(File target, String asset) {
        try (java.io.InputStream in = getAssets().open(asset)) {
            byte[] bytes = readFully(in);
            if (target.isFile() && target.length() == bytes.length
                    && java.util.Arrays.equals(java.nio.file.Files.readAllBytes(target.toPath()), bytes)) {
                return;
            }
            java.nio.file.Files.write(target.toPath(), bytes);
        } catch (IOException error) {
            Log.w(TAG, "cannot publish " + target, error);
        }
    }

    /**
     * Read a stream to the end without {@code InputStream.readAllBytes}, which older ART builds on
     * this device do not provide.
     *
     * @param in stream to drain
     * @return the stream contents
     * @throws IOException when reading fails
     */
    private static byte[] readFully(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Directory for mirrored manual snapshots under shared storage.
     *
     * @param slot snapshot name
     * @return target directory, or null when shared storage is unavailable
     */
    private File mirrorDir(String slot) {
        UserData data = sharedDirs;
        if (data == null || data.backup.getParentFile() == null) {
            return null;
        }
        return new File(new File(data.backup.getParentFile(), "backups"), slot);
    }

    /**
     * Read a query parameter from the request line.
     *
     * @param requestLine raw HTTP request line
     * @param name parameter name
     * @return decoded value, or null when absent
     */
    private String queryParam(String requestLine, String name) {
        int at = requestLine.indexOf(name + "=");
        if (at < 0) {
            return null;
        }
        int start = at + name.length() + 1;
        int end = requestLine.indexOf(' ', start);
        String raw = end < 0 ? requestLine.substring(start) : requestLine.substring(start, end);
        try {
            return java.net.URLDecoder.decode(raw, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return raw;
        }
    }

    /**
     * Download the update in the background and report through the ongoing notification, so the
     * activity can be closed while 350 MB comes down.
     *
     * @param url APK download URL
     * @param version release version, for messages
     */
    private void startDownload(String url, String version) {
        // Remember what this download is supposed to be; the installer flow validates the cached
        // file against it, so an older package lying around cannot be installed by mistake.
        getSharedPreferences("dsh", MODE_PRIVATE).edit()
                .putString("update_version", version).apply();
        Thread thread = new Thread(() -> {
            android.app.NotificationManager manager =
                    (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            try {
                UpdateChecker.Release release = new UpdateChecker.Release(version, url);
                java.io.File apk = UpdateChecker.download(this, release, (written, total) -> {
                    int percent = total > 0 ? (int) (written * 100 / total) : -1;
                    if (manager != null) {
                        android.app.Notification note = new Notification.Builder(this, CHANNEL_ID)
                                .setContentTitle("正在下载更新 v" + version)
                                .setContentText(percent + "% · " + (written / 1048576) + "/"
                                        + (total > 0 ? total / 1048576 : 0) + " MB")
                                .setSmallIcon(android.R.drawable.stat_sys_download)
                                .setProgress(100, Math.max(percent, 0), percent < 0)
                                .setOngoing(true)
                                .setOnlyAlertOnce(true)
                                .build();
                        manager.notify(NOTIFICATION_ID + 1, note);
                    }
                    return true;
                });
                if (manager != null) {
                    manager.cancel(NOTIFICATION_ID + 1);
                }
                if (apk != null) {
                    broadcast(ACTION_INSTALL_READY, EXTRA_PROGRESS, apk.getAbsolutePath());
                }
            } catch (IOException error) {
                Log.w(TAG, "download failed", error);
                if (manager != null) {
                    manager.cancel(NOTIFICATION_ID + 1);
                }
                broadcast(ACTION_FAILED, EXTRA_ERROR, "更新下载失败：网络异常");
            }
        }, "dsh-download");
        thread.setDaemon(true);
        thread.start();
    }

    /** Refresh the visible copy of the known-good snapshot. */
    private void mirrorBackup() {
        UserData data = sharedDirs;
        if (safetyNet == null || data == null) {
            return;
        }
        Thread thread = new Thread(() -> {
            int files = safetyNet.mirrorTo(data.backup);
            if (files > 0) {
                Log.i(TAG, "mirrored " + files + " config files to " + data.backup);
            }
        }, "dsh-backup-mirror");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Check the release feed and report the outcome for the control endpoint.
     *
     * @return JSON body describing the check result
     */
    private String updateStatus() {
        try {
            UpdateChecker.Release release = UpdateChecker.latest();
            String current = UpdateChecker.currentVersion(this);
            if (release != null && UpdateChecker.isNewer(release.version, current)) {
                // Already downloaded? Offer the cached APK instead of fetching 350 MB again —
                // but only when the cached file really is this release.
                if (UpdateChecker.cachedApk(this, release.version) != null) {
                    broadcast(ACTION_UPDATE_AVAILABLE, EXTRA_PROGRESS, release.version);
                    return "{\"status\":\"cached\",\"version\":\"" + release.version + "\"}";
                }
                broadcast(ACTION_UPDATE_AVAILABLE, EXTRA_PROGRESS, release.version);
                return "{\"status\":\"update\",\"version\":\"" + release.version + "\"}";
            }
            return "{\"status\":\"latest\",\"version\":\"" + current + "\"}";
        } catch (IOException offline) {
            Log.w(TAG, "update check failed", offline);
            return "{\"status\":\"offline\"}";
        }
    }

    /**
     * Whether the app may check for updates on its own.
     *
     * @return true when automatic checks are enabled
     */
    private boolean autoUpdateEnabled() {
        return getSharedPreferences("dsh", MODE_PRIVATE).getBoolean(PREF_AUTO_UPDATE, true);
    }

    /**
     * Persist the automatic update preference.
     *
     * @param enabled new value
     */
    private void setAutoUpdate(boolean enabled) {
        getSharedPreferences("dsh", MODE_PRIVATE).edit().putBoolean(PREF_AUTO_UPDATE, enabled).apply();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "DSH 运行时", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持 DeepSeek Harness 在本机运行");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        android.content.Intent panel = new android.content.Intent(this, PanelReceiver.class);
        android.app.PendingIntent open = android.app.PendingIntent.getBroadcast(
                this, 43, panel,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("DeepSeek Harness")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this,
                                android.R.drawable.ic_menu_manage),
                        "控制面板", open).build())
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    /**
     * Render one progress step for the launch screen.
     *
     * @param percent completion between 0 and 100
     * @param stage raw stage label from the installer
     * @return localized, user-facing line
     */
    private String describe(int percent, String stage) {
        if (stage != null && stage.contains("unpacking harness runtime")) {
            return "正在解压运行时 " + percent + "%（首次启动约 1–2 分钟）";
        }
        if (stage != null && stage.contains("unpacking proot")) {
            return "正在准备运行时…";
        }
        if (stage != null && stage.contains("keeping your sessions")) {
            return "正在保留会话数据…";
        }
        return "正在准备运行时…";
    }

    private void broadcast(String action, String key, String value) {
        Intent intent = new Intent(action).setPackage(getPackageName()).putExtra(key, value);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        currentUrl = null;
        mainHandler.removeCallbacksAndMessages(null);
        closeHarnessLog();
        stopHarness();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
