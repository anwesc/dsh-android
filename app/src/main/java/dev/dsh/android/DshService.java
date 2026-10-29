package dev.dsh.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.OutputStream;
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
    /** Loopback control port for restart/reload requests from inside the container. */
    private static final int CONTROL_PORT = 3081;
    /** Intent extra holding the progress line. */
    static final String EXTRA_PROGRESS = "progress";
    /** Intent extra and broadcast extra holding the URL or the failure text. */
    static final String EXTRA_URL = "url";
    static final String EXTRA_ERROR = "error";

    private static final String TAG = "DshService";
    private static final String CHANNEL_ID = "dsh-runtime";
    private static final int NOTIFICATION_ID = 0x4453;
    private static final Pattern URL_PATTERN = Pattern.compile("dsh web:\\s+(http\\S+)");

    /** Latest harness URL, shared with a re-created activity. */
    static volatile String currentUrl;

    private Thread worker;
    private Process process;
    private PowerManager.WakeLock wakeLock;
    private long bootStartedAt;
    private volatile File runtimeDir;
    private final Object serverLock = new Object();
    private volatile boolean restarting;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:runtime");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("正在启动 DeepSeek Harness…"));

        String known = currentUrl;
        if (known != null) {
            broadcast(ACTION_READY, EXTRA_URL, known);
            return START_STICKY;
        }
        if (worker == null) {
            worker = new Thread(this::bootRuntime, "dsh-runtime");
            worker.start();
        }
        return START_STICKY;
    }

    /** Unpack the runtime (first run only), start the server and publish its URL. */
    private void bootRuntime() {
        long started = System.currentTimeMillis();
        bootStartedAt = started;
        try {
            File runtime = RuntimeInstaller.install(this, (percent, stage) -> {
                updateNotification(stage + " " + percent + "%");
                broadcast(ACTION_PROGRESS, EXTRA_PROGRESS, describe(percent, stage));
            });
            if (!new File(runtime, "bin/proot").canExecute()) {
                Log.w(TAG, "launcher missing, unpacking again");
                runtime = RuntimeInstaller.reinstall(this, (percent, stage) ->
                        updateNotification(stage + " " + percent + "%"));
            }
            Log.i(TAG, "runtime ready in " + (System.currentTimeMillis() - started) + "ms");
            runtimeDir = runtime;
            startControlServer();
            startServer(runtime);
        } catch (Throwable error) {
            if (restarting) {
                Log.i(TAG, "previous harness stopped for restart");
                return;
            }
            Log.e(TAG, "runtime boot failed", error);
            broadcast(ACTION_FAILED, EXTRA_ERROR, String.valueOf(error.getMessage()));
            updateNotification("启动失败");
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
        command.add("-b");
        command.add(data.work.getAbsolutePath() + ":/root/work");
        command.add("-b");
        command.add(data.home.getAbsolutePath() + ":/root/home");
        publishDocs(data);
        command.add("-w");
        command.add("/root");
        command.add("/usr/bin/env");
        command.add("-i");
        command.add("HOME=/root");
        command.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        command.add("TERM=xterm-256color");
        command.add("LANG=C.UTF-8");
        // Cache V8 bytecode: module compilation repeats on every cold start otherwise.
        command.add("NODE_COMPILE_CACHE=/root/.cache/node-compile");
        command.add("/usr/local/bin/dsh");
        int port = pickPort();
        Log.i(TAG, "serving on 127.0.0.1:" + port);
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

        broadcast(ACTION_PROGRESS, EXTRA_PROGRESS, "正在启动服务…");
        try {
            process = builder.start();
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
            process = builder.start();
        }
        updateNotification("正在启动服务…");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Log.i(TAG, line);
                Matcher matcher = URL_PATTERN.matcher(line);
                if (matcher.find() && currentUrl == null) {
                    Log.i(TAG, "harness ready in " + (System.currentTimeMillis() - bootStartedAt) + "ms");
                    currentUrl = matcher.group(1);
                    updateNotification("已就绪");
                    broadcast(ACTION_READY, EXTRA_URL, currentUrl);
                }
            }
        }
        int status;
        try {
            status = process.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        }
        Log.w(TAG, "harness exited with " + status);
        currentUrl = null;
        broadcast(ACTION_FAILED, EXTRA_ERROR, "harness exited with " + status);
        stopSelf();
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
        if (requestLine != null && requestLine.startsWith("POST /restart")) {
            restartHarness();
            body = "{\"status\":\"restarting\"}";
        } else if (requestLine != null && requestLine.startsWith("POST /reload")) {
            broadcast(ACTION_RELOAD, EXTRA_PROGRESS, "");
            body = "{\"status\":\"reloading\"}";
        } else {
            String url = currentUrl;
            body = "{\"url\":\"" + (url == null ? "" : url) + "\"}";
        }
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                + payload.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(payload);
        out.flush();
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
            Log.i(TAG, "old harness stopped");
            currentUrl = null;
            try {
                startServer(dir);
            } catch (IOException error) {
                Log.e(TAG, "restart failed", error);
                broadcast(ACTION_FAILED, EXTRA_ERROR, String.valueOf(error.getMessage()));
            } finally {
                restarting = false;
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
            byte[] bytes = in.readAllBytes();
            if (target.isFile() && target.length() == bytes.length
                    && java.util.Arrays.equals(java.nio.file.Files.readAllBytes(target.toPath()), bytes)) {
                return;
            }
            java.nio.file.Files.write(target.toPath(), bytes);
        } catch (IOException error) {
            Log.w(TAG, "cannot publish " + target, error);
        }
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
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("DeepSeek Harness")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
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
