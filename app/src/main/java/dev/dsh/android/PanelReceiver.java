package dev.dsh.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Notification shortcut: remembers that the panel was requested and brings the activity forward.
 * A flag survives the activity being recreated, which a plain intent extra did not.
 */
public final class PanelReceiver extends BroadcastReceiver {
    /** Preference read and cleared by the activity. */
    static final String PREF_OPEN_PANEL = "open_panel";

    @Override
    public void onReceive(Context context, Intent intent) {
        context.getSharedPreferences("dsh", Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_OPEN_PANEL, true).apply();
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(open);
    }
}
