package dev.dsh.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Entry point for the maintenance dialog when it is opened outside the main screen.
 */
public final class ControlActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ControlPanel.show(this, text -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }
}
