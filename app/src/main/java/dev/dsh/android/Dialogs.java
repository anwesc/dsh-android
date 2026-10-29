package dev.dsh.android;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Dark, rounded dialogs that match the harness UI instead of the platform default look.
 */
final class Dialogs {

    /** Progress dialog parts, so callers can drive the bar and text. */
    static final class Progress {
        final Dialog dialog;
        final TextView label;
        final ProgressBar bar;

        Progress(Dialog dialog, TextView label, ProgressBar bar) {
            this.dialog = dialog;
            this.label = label;
            this.bar = bar;
        }
    }

    private Dialogs() {
    }

    /**
     * Show a two-choice dialog.
     *
     * @param context activity context
     * @param title headline
     * @param message body text
     * @param positive positive button label
     * @param onPositive action for the positive button
     * @param negative negative button label
     */
    static void choice(Context context, String title, String message, String positive,
                       Runnable onPositive, String negative) {
        choice(context, title, message, positive, onPositive, negative, null);
    }

    /**
     * Show a two-choice dialog with a dismiss callback.
     *
     * @param context activity context
     * @param title headline
     * @param message body text
     * @param positive positive button label
     * @param onPositive action for the positive button
     * @param negative negative button label
     * @param onDismiss runs when the dialog closes for any reason
     */
    static void choice(Context context, String title, String message, String positive,
                       Runnable onPositive, String negative, Runnable onDismiss) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = card(context);
        card.addView(heading(context, title));
        TextView body = new TextView(context);
        body.setText(message);
        body.setTextColor(Theme.muted());
        body.setTextSize(13.5f);
        body.setPadding(0, 0, 0, dp(context, 18));
        card.addView(body);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setGravity(Gravity.END);
        if (negative != null) {
            buttons.addView(button(context, negative, Theme.muted(), () -> dialog.dismiss()));
        }
        buttons.addView(button(context, positive, Theme.accent(), () -> {
            dialog.dismiss();
            if (onPositive != null) {
                onPositive.run();
            }
        }));
        card.addView(buttons);

        dialog.setContentView(card);
        if (onDismiss != null) {
            dialog.setOnDismissListener(ignored -> onDismiss.run());
        }
        size(dialog, context);
        dialog.show();
    }

    /**
     * Show a progress dialog with a horizontal bar.
     *
     * @param context activity context
     * @param title headline
     * @param onCancel cancel action, or null to hide the button
     * @return parts for updating progress
     */
    static Progress progress(Context context, String title, Runnable onCancel) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = card(context);
        card.addView(heading(context, title));

        TextView label = new TextView(context);
        label.setText("准备下载…");
        label.setTextColor(Theme.text());
        label.setTextSize(13.5f);
        label.setPadding(0, 0, 0, dp(context, 10));
        card.addView(label);

        ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        card.addView(bar);

        if (onCancel != null) {
            LinearLayout buttons = new LinearLayout(context);
            buttons.setGravity(Gravity.END);
            buttons.setPadding(0, dp(context, 12), 0, 0);
            buttons.addView(button(context, "取消", Theme.muted(), () -> {
                dialog.dismiss();
                onCancel.run();
            }));
            card.addView(buttons);
        }

        dialog.setContentView(card);
        dialog.setCancelable(false);
        size(dialog, context);
        dialog.show();
        return new Progress(dialog, label, bar);
    }

    /**
     * Show a dark list dialog.
     *
     * @param context activity context
     * @param title headline
     * @param items row labels
     * @param onPick called with the chosen index
     */
    static void list(Context context, String title, String[] items,
                     java.util.function.IntConsumer onPick) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout card = card(context);
        card.addView(heading(context, title));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(context, 12);
            row.setPadding(pad, pad, pad, pad);
            GradientDrawable background = new GradientDrawable();
            background.setColor(Theme.row());
            background.setCornerRadius(dp(context, 12));
            row.setBackground(background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(context, 8);
            row.setLayoutParams(params);
            TextView label = new TextView(context);
            label.setText(items[i]);
            label.setTextColor(Theme.text());
            label.setTextSize(13.5f);
            row.addView(label);
            row.setOnClickListener(view -> {
                dialog.dismiss();
                onPick.accept(index);
            });
            column.addView(row);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(column);
        card.addView(scroll);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(context, 6), 0, 0);
        buttons.addView(button(context, "取消", Theme.muted(), dialog::dismiss));
        card.addView(buttons);

        dialog.setContentView(card);
        size(dialog, context);
        dialog.show();
    }

    private static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 20);
        card.setPadding(pad, pad, pad, dp(context, 8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Theme.background());
        background.setCornerRadius(dp(context, 22));
        card.setBackground(background);
        return card;
    }

    private static TextView heading(Context context, String text) {
        TextView heading = new TextView(context);
        heading.setText(text);
        heading.setTextColor(Theme.text());
        heading.setTextSize(16.5f);
        heading.setPadding(0, 0, 0, dp(context, 10));
        return heading;
    }

    private static TextView button(Context context, String label, int color, Runnable action) {
        TextView button = new TextView(context);
        button.setText(label);
        button.setTextColor(color);
        button.setTextSize(14f);
        int pad = dp(context, 12);
        button.setPadding(pad, pad, pad, pad);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private static void size(Dialog dialog, Context context) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000));
            window.setLayout((int) (context.getResources().getDisplayMetrics().widthPixels * 0.86f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
