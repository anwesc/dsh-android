package dev.dsh.android;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Maintenance dialog styled like the harness: dark rounded sheet with action rows. Results are
 * reported through a callback so the host screen can show them where it likes.
 */
final class ControlPanel {
    private static final String TAG = "DshControl";
    private static final String PREF_AUTO_UPDATE = "auto_update";
    private static final int BACKGROUND = 0xFF1C1C1E;
    private static final int ROW = 0xFF2A2A2D;
    private static final int TEXT = 0xFFECECEC;
    private static final int MUTED = 0xFF8E8E93;

    /** Receives the outcome of an action. */
    interface Reporter {
        /**
         * Report the result of a panel action.
         *
         * @param text human readable message
         */
        void report(String text);
    }

    private ControlPanel() {
    }

    /**
     * Show the panel.
     *
     * @param context activity that owns the dialog
     * @param reporter receives action results
     */
    static void show(Context context, Reporter reporter) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(context);
        sheet.setOrientation(LinearLayout.VERTICAL);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (18 * density);
        sheet.setPadding(pad, pad, pad, pad / 2);
        sheet.setBackground(rounded(BACKGROUND, 22 * density));

        TextView title = new TextView(context);
        title.setText("控制面板");
        title.setTextColor(TEXT);
        title.setTextSize(17f);
        title.setPadding((int) (4 * density), 0, 0, (int) (12 * density));
        sheet.addView(title);

        LinearLayout switchRow = row(context, density);
        TextView switchLabel = new TextView(context);
        switchLabel.setText("启动时自动检查更新");
        switchLabel.setTextColor(TEXT);
        switchLabel.setTextSize(14f);
        switchLabel.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch autoUpdate = new Switch(context);
        autoUpdate.setChecked(context.getSharedPreferences("dsh", Context.MODE_PRIVATE)
                .getBoolean(PREF_AUTO_UPDATE, true));
        autoUpdate.setOnCheckedChangeListener((button, checked) -> {
            context.getSharedPreferences("dsh", Context.MODE_PRIVATE).edit()
                    .putBoolean(PREF_AUTO_UPDATE, checked).apply();
            reporter.report(checked ? "已开启自动检查更新" : "已关闭自动检查更新");
        });
        switchRow.addView(switchLabel);
        switchRow.addView(autoUpdate);
        sheet.addView(switchRow);

        addAction(context, sheet, density, "刷新页面", "/reload", reporter);
        addAction(context, sheet, density, "重启服务", "/restart", reporter);
        addAction(context, sheet, density, "检查更新", "/update", reporter);
        addAction(context, sheet, density, "导出家目录", "/export", reporter);
        addAction(context, sheet, density, "备份当前配置", "/backup", reporter);
        addAction(context, sheet, density, "回滚到上次可用配置", "/restore", reporter);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(sheet);

        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(rounded(0x00000000, 0));
            window.setLayout((int) (context.getResources().getDisplayMetrics().widthPixels * 0.86f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private static LinearLayout row(Context context, float density) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (14 * density);
        row.setPadding(pad, pad, pad, pad);
        row.setBackground(rounded(ROW, 14 * density));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = (int) (8 * density);
        row.setLayoutParams(params);
        // Pressed state: without it a tap looks like it did nothing.
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(0xFF3A3A3E, 14 * density));
        states.addState(new int[]{}, rounded(ROW, 14 * density));
        row.setBackground(states);
        return row;
    }

    private static void addAction(Context context, LinearLayout sheet, float density, String label,
                                  String path, Reporter reporter) {
        LinearLayout row = row(context, density);
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextColor(TEXT);
        text.setTextSize(14f);
        row.addView(text);
        row.setOnClickListener(view -> {
            row.setEnabled(false);
            text.setText(label + " · 执行中…");
            reporter.report(label + "：执行中…");
            // Network on the main thread throws NetworkOnMainThreadException and kills the app.
            // When the control port is down (harness failed to boot) the service actions still work.
            if ("/restart".equals(path) || "/restore".equals(path)) {
                String action = "/restore".equals(path)
                        ? DshService.ACTION_DO_RESTORE : DshService.ACTION_DO_RESTART;
                android.content.Intent intent = new android.content.Intent(context, DshService.class)
                        .setAction(action);
                context.startForegroundService(intent);
                text.setText(label);
                row.setEnabled(true);
                if ("/restore".equals(path)) {
                    toast(context, "已请求回滚，服务将重启");
                } else {
                    toast(context, "已请求重启服务");
                }
                return;
            }
            Thread worker = new Thread(() -> {
                String body = post(path);
                reporter.report(label + "：" + explain(path, body));
                view.post(() -> {
                    row.setEnabled(true);
                    text.setText(label);
                });
            }, "dsh-panel");
            worker.setDaemon(true);
            worker.start();
        });
        sheet.addView(row);
    }

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static void toast(android.content.Context context, String text) {
        android.widget.Toast toast = android.widget.Toast.makeText(context, text,
                android.widget.Toast.LENGTH_SHORT);
        toast.setGravity(android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL,
                0, (int) (96 * context.getResources().getDisplayMetrics().density));
        toast.show();
    }

    private static String explain(String path, String body) {
        if (body.startsWith("!")) {
            return body.substring(1);
        }
        String status = field(body, "status");
        String url = field(body, "url");
        if (url != null && !url.isEmpty()) {
            return "当前地址 " + url;
        }
        if ("reloading".equals(status)) {
            return "页面已重新加载";
        }
        if ("restarting".equals(status)) {
            return "服务重启中，约 20 秒后恢复";
        }
        if ("exporting".equals(status)) {
            return "正在导出到 /sdcard/DSH/dsh";
        }
        if ("backed-up".equals(status)) {
            String at = field(body, "at");
            return "已备份：" + (at == null ? "last-good" : at);
        }
        if ("restored".equals(status)) {
            String from = field(body, "from");
            return "已回滚到 " + (from == null ? "上次可用配置" : from) + "，正在重启";
        }
        if ("no-backup".equals(status)) {
            return "没有可用备份（需先成功启动一次）";
        }
        if ("latest".equals(status)) {
            String version = field(body, "version");
            return "已是最新版本（" + (version == null ? "" : "v" + version) + "）";
        }
        if ("update".equals(status)) {
            String version = field(body, "version");
            return "发现新版本 " + (version == null ? "" : "v" + version) + "，正在下载…";
        }
        if ("offline".equals(status)) {
            return "网络异常，无法连接版本库";
        }
        if ("checking".equals(status)) {
            return "已开始检查，有新版本会提示";
        }
        return body.isEmpty() ? "无响应（服务未就绪？）" : body;
    }

    private static String field(String json, String key) {
        String needle = "\"" + key + "\":";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int start = at + needle.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) {
            start++;
        }
        int end = start;
        while (end < json.length() && json.charAt(end) != '"' && json.charAt(end) != '}'
                && json.charAt(end) != ',') {
            end++;
        }
        return json.substring(start, end).trim();
    }

    private static String post(String path) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:3081" + path).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            int code = connection.getResponseCode();
            if (code != 200) {
                return "!服务返回 HTTP " + code;
            }
            StringBuilder body = new StringBuilder();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    body.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
            return body.toString();
        } catch (IOException error) {
            Log.w(TAG, "control call failed: " + path, error);
            return "!服务未就绪（还在启动？）";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
