package dev.dsh.android;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.Manifest;
import android.content.IntentFilter;
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

    private WebView webView;
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

        statusView = new TextView(this);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(Color.parseColor("#DDDDDD"));
        statusView.setTextSize(16f);
        statusView.setPadding(48, 48, 48, 48);
        statusView.setBackgroundColor(Color.parseColor("#111111"));

        FrameLayout root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(statusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        showStatus("正在启动 DeepSeek Harness…");

        // Test hook: an explicit url extra bypasses the bundled runtime.
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
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverRegistered = true;

        startRuntime();
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
