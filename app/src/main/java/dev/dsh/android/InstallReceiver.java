package dev.dsh.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/**
 * Reports the result of a PackageInstaller session to the user.
 */
public final class InstallReceiver extends BroadcastReceiver {
    private static final String TAG = "DshInstall";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        Log.i(TAG, "install status " + status + " " + message);
        String text;
        if (status == PackageInstaller.STATUS_SUCCESS) {
            text = "安装完成";
        } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The system asks the user to confirm; forward its intent.
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        } else {
            text = "安装未完成（" + (message == null ? "已取消" : message) + "）";
        }
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
