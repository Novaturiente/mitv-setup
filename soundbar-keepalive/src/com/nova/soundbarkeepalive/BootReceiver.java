package com.nova.soundbarkeepalive;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Android 14 permits starting a media-playback foreground service after boot. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            context.startForegroundService(new Intent(context, KeepAliveService.class));
            Log.i("SoundbarKeepAlive", "Boot completed: service start requested");
        } catch (RuntimeException e) {
            Log.e("SoundbarKeepAlive", "Boot startup failed", e);
        }
    }
}
