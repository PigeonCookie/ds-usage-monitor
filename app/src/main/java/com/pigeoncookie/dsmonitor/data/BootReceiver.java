package com.pigeoncookie.dsmonitor.data;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.pigeoncookie.dsmonitor.core.Prefs;

/** Re-arms the hourly sampler after a reboot (persisted jobs usually survive, this is a safety net). */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) {
            return;
        }
        if (Prefs.hourlySampling(context)) {
            SampleJobService.schedule(context);
        }
    }
}
