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
        sheet.setBackground(rounded(Theme.background(), 22 * density));

        TextView title = new TextView(context);
        title.setText("控制面板");
        title.setTextColor(Theme.text());
        title.setTextSize(17f);
        title.setPadding((int) (4 * density), 0, 0, (int) (12 * density));
        sheet.addView(title);

        section(context, sheet, density, "常用");
        addAction(context, sheet, density, "刷新页面", "/reload", reporter);
        addAction(context, sheet, density, "重启服务", "/restart", reporter);

        section(context, sheet, density, "更新");
        addAction(context, sheet, density, "检查更新", "/update", reporter);
        LinearLayout switchRow = row(context, density);
        TextView switchLabel = new TextView(context);
        switchLabel.setText("启动时自动检查更新");
        switchLabel.setTextColor(Theme.text());
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
        int accent = Theme.accent();
        int[][] states = {{android.R.attr.state_checked}, {}};
        autoUpdate.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{
                accent, Theme.muted()}));
        autoUpdate.setTrackTintList(new android.content.res.ColorStateList(states, new int[]{
                withAlpha(accent, 0.45f), withAlpha(Theme.muted(), 0.30f)}));
        switchRow.addView(switchLabel);
        switchRow.addView(autoUpdate);
        sheet.addView(switchRow);

        section(context, sheet, density, "数据");
        addAction(context, sheet, density, "备份当前配置", "/backup", reporter);
        LinearLayout list = row(context, density);
        TextView listText = new TextView(context);
        listText.setText("查看备份并选择回滚");
        listText.setTextColor(Theme.text());
        listText.setTextSize(14f);
        list.addView(listText);
        list.setOnClickListener(view -> showBackups(context, density, reporter));
        sheet.addView(list);
        addAction(context, sheet, density, "导出家目录", "/export", reporter);

        section(context, sheet, density, "危险操作");
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

    /**
     * Apply alpha to a packed ARGB color.
     *
     * @param color source color
     * @param alpha 0..1 alpha
     * @return color with the requested alpha
     */
    private static int withAlpha(int color, float alpha) {
        int a = Math.round(255 * alpha);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static void section(Context context, LinearLayout sheet, float density, String title) {
        TextView label = new TextView(context);
        label.setText(title);
        label.setTextColor(Theme.muted());
        label.setTextSize(11.5f);
        label.setPadding((int) (4 * density), (int) (10 * density), 0, (int) (6 * density));
        sheet.addView(label);
    }

    private static LinearLayout row(Context context, float density) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (14 * density);
        row.setPadding(pad, pad, pad, pad);
        row.setBackground(rounded(Theme.row(), 14 * density));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = (int) (8 * density);
        row.setLayoutParams(params);
        // Pressed state: without it a tap looks like it did nothing.
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(0xFF3A3A3E, 14 * density));
        states.addState(new int[]{}, rounded(Theme.row(), 14 * density));
        row.setBackground(states);
        return row;
    }

    private static void addAction(Context context, LinearLayout sheet, float density, String label,
                                  String path, Reporter reporter) {
        LinearLayout row = row(context, density);
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextColor("/restore".equals(path) ? Theme.danger() : Theme.text());
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

    /**
     * List snapshots and restore the one the user taps.
     *
     * @param context activity context
     * @param density display density
     * @param reporter result callback
     */
    private static void showBackups(android.content.Context context, float density, Reporter reporter) {
        Thread worker = new Thread(() -> {
            String body = post("/backups");
            java.util.List<String> slots = new java.util.ArrayList<>();
            int at = body.indexOf("[");
            int end = body.indexOf("]");
            if (at >= 0 && end > at) {
                for (String part : body.substring(at + 1, end).split(",")) {
                    String slot = part.replace("\"", "").trim();
                    if (!slot.isEmpty()) {
                        slots.add(slot);
                    }
                }
            }
            if (slots.isEmpty()) {
                reporter.report("还没有手动备份，先点「备份当前配置」");
                return;
            }
            android.app.Activity activity = (android.app.Activity) context;
            activity.runOnUiThread(() -> {
                String[] labels = slots.toArray(new String[0]);
                Dialogs.list(activity, "选择要回滚的备份", labels, which -> {
                    String slot = labels[which];
                    Thread restore = new Thread(() -> {
                        String result = post("/restore?slot=" + slot);
                        reporter.report("回滚 " + slot + "：" + explain("/restore", result));
                    }, "dsh-restore");
                    restore.setDaemon(true);
                    restore.start();
                });
            });
        }, "dsh-backups");
        worker.setDaemon(true);
        worker.start();
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
            String slot = field(body, "slot");
            return "已备份 " + (slot == null ? "" : slot) + "（手机上见 /sdcard/DSH/backups）";
        }
        if ("restored".equals(status)) {
            String from = field(body, "from");
            return "已回滚到 " + (from == null ? "上次可用配置" : from) + "，正在重启";
        }
        if ("no-backup".equals(status)) {
            return "没有可用备份（需先成功启动一次）";
        }
        if ("cached".equals(status)) {
            String version = field(body, "version");
            return "已下载 v" + (version == null ? "" : version) + "，已弹出安装提示";
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
