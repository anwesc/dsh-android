package dev.dsh.android;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.Manifest;
import android.content.IntentFilter;
import android.app.AlertDialog;
import android.widget.Toast;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Full-screen WebView showing the harness served by {@link DshService} from this app. */
public final class MainActivity extends Activity {
    private static final String TAG = "DshActivity";
    private static final String STYLE_ID = "dsh-mobile-fix";
    private static final int REQUEST_STORAGE = 1001;

    /** Intent extra asking the activity to open the maintenance panel. */
    static final String EXTRA_OPEN_PANEL = "panel";

    private WebView webView;
    private FrameLayout rootView;
    private TextView statusView;
    private Bridge webViewBridge;

    private String mobileCss;
    private boolean receiverRegistered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (DshService.ACTION_READY.equals(action)) {
                String url = intent.getStringExtra(DshService.EXTRA_URL);
                if (url != null && webView != null) {
                    if (statusView != null) {
                        statusView.setVisibility(android.view.View.GONE);
                    }
                    webView.loadUrl(url);
                }
            } else if (DshService.ACTION_FAILED.equals(action)) {
                showStatus("启动失败：" + intent.getStringExtra(DshService.EXTRA_ERROR));
            } else if (DshService.ACTION_CHECK_UPDATE.equals(action)) {
                checkForUpdates();
            } else if (DshService.ACTION_RESTORE_PROMPT.equals(action)) {
                promptRestore();
            } else if (DshService.ACTION_RELOAD.equals(action)) {
                if (webView != null) {
                    webView.reload();
                }
            } else if (DshService.ACTION_PROGRESS.equals(action)) {
                String progress = intent.getStringExtra(DshService.EXTRA_PROGRESS);
                if (progress != null) {
                    showStatus(progress);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView.setWebContentsDebuggingEnabled(true);
        webViewBridge = new Bridge();
        mobileCss = readAsset("mobile.css");

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        // The sign-in flow opens the provider page with window.open(); without multi-window
        // support that popup is dropped and the dialog waits forever.
        // Sign-in opens the provider page with window.open(); that page does not render inside
        // a bare WebView, so the call is forwarded to the system browser through a JS bridge.
        settings.setSupportMultipleWindows(false);

        webView.addJavascriptInterface(webViewBridge, "DSHAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                injectMobileCss(view);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                boolean loopback = host != null
                        && (host.equals("127.0.0.1") || host.equals("localhost"));
                if (loopback || !"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
                    return false;
                }
                openExternally(uri);
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.w(TAG, "load failed: " + request.getUrl() + " -> " + error.getDescription());
                    showStatus("页面加载失败：" + error.getDescription());
                }
            }
        });

        // Long press on the page's left rail opens the panel; it never reaches a text field, so
        // selection inside the composer keeps working.
        final float railWidth = 140 * getResources().getDisplayMetrics().density;
        final android.os.Handler longPress = new android.os.Handler(getMainLooper());
        final Runnable openPanel = () -> ControlPanel.show(this, this::showBanner);
        final float[] downAt = new float[2];
        webView.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downAt[0] = event.getX();
                    downAt[1] = event.getY();
                    if (downAt[0] < railWidth) {
                        longPress.postDelayed(openPanel, 650);
                    }
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    // A scroll gesture cancels the press; tiny jitter does not.
                    if (Math.abs(event.getX() - downAt[0]) > 24 || Math.abs(event.getY() - downAt[1]) > 24) {
                        longPress.removeCallbacks(openPanel);
                    }
                    break;
                default:
                    longPress.removeCallbacks(openPanel);
                    break;
            }
            return false;
        });

        statusView = new TextView(this);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(Color.parseColor("#DDDDDD"));
        statusView.setTextSize(16f);
        statusView.setPadding(48, 48, 48, 48);
        statusView.setBackgroundColor(Color.parseColor("#111111"));
        // The splash overlay stays visible when boot fails, so a long press always reaches the panel.
        statusView.setOnLongClickListener(view -> {
            ControlPanel.show(this, this::showBanner);
            return true;
        });

        FrameLayout root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(statusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootView = root;
        setContentView(root);
        showStatus("正在启动 DeepSeek Harness…");

        // Test hook: an explicit url extra bypasses the bundled runtime.
        if (getIntent().getBooleanExtra(EXTRA_OPEN_PANEL, false)) {
            getIntent().removeExtra(EXTRA_OPEN_PANEL);
            statusView.postDelayed(() -> ControlPanel.show(this, this::showBanner), 400);
        }
        String debugUrl = getIntent().getStringExtra("url");
        if (debugUrl != null && !debugUrl.isEmpty()) {
            webView.loadUrl(debugUrl);
            return;
        }

        if (DshService.currentUrl != null) {
            webView.loadUrl(DshService.currentUrl);
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(DshService.ACTION_READY);
        filter.addAction(DshService.ACTION_FAILED);
        filter.addAction(DshService.ACTION_PROGRESS);
        filter.addAction(DshService.ACTION_RELOAD);
        filter.addAction(DshService.ACTION_CHECK_UPDATE);
        filter.addAction(DshService.ACTION_RESTORE_PROMPT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverRegistered = true;

        startRuntime();
        if (getSharedPreferences("dsh", MODE_PRIVATE).getBoolean("auto_update", true)) {
            checkForUpdates();
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_STORAGE);
        }
    }

    /**
     * Render a status message while the runtime boots.
     *
     * @param message text shown centred on a dark background
     */
    private void showStatus(String message) {
        if (statusView == null) {
            return;
        }
        statusView.setText(message);
        statusView.setVisibility(android.view.View.VISIBLE);
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Install the narrow-screen stylesheet into the loaded document.
     *
     * @param view the WebView whose document just finished loading
     */
    private void injectMobileCss(WebView view) {
        if (mobileCss == null || mobileCss.isEmpty()) {
            return;
        }
        String script = "(function(){"

                + "window.open=function(u){"
                + "function open(v){try{if(v&&/^https?:/.test(String(v))&&window.DSHAndroid)"
                + "window.DSHAndroid.openExternal(String(v));}catch(e){}}"
                + "var loc={};"
                + "Object.defineProperty(loc,'href',{configurable:true,get:function(){return '';},"
                + "set:function(v){open(v);}});"
                + "loc.assign=function(v){open(v);};loc.replace=function(v){open(v);};"
                + "var proxy={document:{write:function(){},close:function(){}},focus:function(){},"
                + "blur:function(){},close:function(){}};"
                + "Object.defineProperty(proxy,'location',{configurable:true,get:function(){return loc;},"
                + "set:function(v){open(v);}});"
                + "if(u)open(u);return proxy;};"
                + "var id='" + STYLE_ID + "';"
                + "var el=document.getElementById(id);"
                + "if(!el){el=document.createElement('style');el.id=id;document.head.appendChild(el);}"
                + "el.textContent=" + JSONObject.quote(mobileCss) + ";})();";
        view.evaluateJavascript(script, null);
    }

    /**
     * Read a packaged asset as UTF-8 text.
     *
     * @param name asset path relative to the assets root
     * @return the asset contents, or an empty string when the asset cannot be read
     */
    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException error) {
            return "";
        }
    }

    /**
     * Ask before rolling the profile back: the rollback discards whatever the agent edited.
     */
    private void promptRestore() {
        runOnUiThread(() -> {
            if (isFinishing()) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("启动失败")
                    .setMessage("检测到 DSH 无法启动，可能配置文件被修改。\n\n"
                            + "回滚会用上一次成功启动的配置覆盖当前配置，期间新增的改动会丢失。")
                    .setPositiveButton("回滚并重启", (dialog, which) -> {
                        Intent intent = new Intent(this, DshService.class)
                                .setAction(DshService.ACTION_DO_RESTORE);
                        startForegroundService(intent);
                    })
                    .setNegativeButton("保持现状", null)
                    .show();
        });
    }

    /** Look for a newer published APK in the background and offer to install it. */
    private void checkForUpdates() {
        Thread thread = new Thread(() -> {
            try {
                UpdateChecker.Release release = UpdateChecker.latest();
                String current = UpdateChecker.currentVersion(this);
                if (release == null || !UpdateChecker.isNewer(release.version, current)) {
                    Log.i(TAG, "app up to date (" + current + ")");
                    return;
                }
                runOnUiThread(() -> Toast.makeText(this,
                        "发现新版本 " + release.version + "，正在下载…", Toast.LENGTH_SHORT).show());
                java.io.File apk = UpdateChecker.download(this, release);
                runOnUiThread(() -> {
                    Toast.makeText(this, "下载完成，开始安装", Toast.LENGTH_SHORT).show();
                    UpdateChecker.install(this, apk);
                });
            } catch (java.io.IOException error) {
                Log.w(TAG, "update check failed", error);
            }
        }, "dsh-update");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Ask for shared-storage access, then boot the runtime either way.
     *
     * @param requestCode the request identifier
     * @param permissions requested permissions
     * @param grantResults grant result per permission
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // The running service resolved its workspace before the grant; restart it so the
            // shared folder becomes the workspace.
            stopService(new Intent(this, DshService.class));
            showStatus("正在启动 DeepSeek Harness…");
            startRuntime();
        }
    }

    /** Start the foreground service that owns the bundled runtime. */
    private void startRuntime() {
        Intent service = new Intent(this, DshService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }

    /** Bridges page calls into the app: sign-in URLs go to the system browser. */
    /** Banner shown near the top of the screen for panel results. */
    private TextView bannerView;

    /**
     * Show a short message in the upper part of the screen and fade it out.
     *
     * @param text message to show
     */
    private void showBanner(String text) {
        runOnUiThread(() -> {
            if (rootView == null) {
                return;
            }
            if (true) {
                Toast toast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
                toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, (int) (96 * getResources().getDisplayMetrics().density));
                toast.show();
                return;
            }
            if (bannerView == null) {
                bannerView = new TextView(this);
                bannerView.setTextColor(0xFFF0F0F0);
                bannerView.setTextSize(13f);
                bannerView.setPadding(40, 24, 40, 24);
                android.graphics.drawable.GradientDrawable background =
                        new android.graphics.drawable.GradientDrawable();
                background.setColor(0xF21F1F22);
                background.setCornerRadius(28f);
                bannerView.setBackground(background);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                params.topMargin = (int) (72 * getResources().getDisplayMetrics().density);
                rootView.addView(bannerView, params);
            }
            bannerView.animate().cancel();
            bannerView.setText(text);
            bannerView.setAlpha(0f);
            bannerView.setVisibility(android.view.View.VISIBLE);
            bannerView.animate().alpha(1f).setDuration(140).withEndAction(() ->
                    bannerView.animate().alpha(0f).setStartDelay(2200).setDuration(240)
                            .withEndAction(() -> bannerView.setVisibility(android.view.View.INVISIBLE))
                            .start()).start();
        });
    }

    private final class Bridge {
        /**
         * Open a URL outside the WebView.
         *
         * @param url target URL supplied by the page
         */
        @JavascriptInterface
        public void openExternal(String url) {
            if (url == null || url.isEmpty()) {
                return;
            }
            runOnUiThread(() -> openExternally(Uri.parse(url)));
        }

        /** Open the maintenance panel from the in-page button. */
        @JavascriptInterface
        public void panel() {
            runOnUiThread(() -> ControlPanel.show(MainActivity.this, MainActivity.this::showBanner));
        }
    }

    /**
     * Open a URL in the device browser, which owns the sign-in session.
     *
     * @param uri target URL
     */
    private void openExternally(Uri uri) {
        Log.i(TAG, "opening externally: " + uri);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException missing) {
            Log.w(TAG, "no browser for " + uri, missing);
            showStatus("没有可用的浏览器，请手动打开：" + uri);
        }
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }
}
