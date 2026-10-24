package com.nova.soundbarkeepalive;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/** Keeps an HDMI audio signal present while the TV is interactive; never requests audio focus. */
public final class KeepAliveService extends Service {
    private static final String TAG = "SoundbarKeepAlive";
    private static final String CHANNEL = "keepalive";
    private static final String STOP = "com.nova.soundbarkeepalive.STOP";
    private AudioTrack track;
    private boolean registered;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            try {
                updatePlayback();
            } catch (RuntimeException e) {
                Log.e(TAG, "Audio state change failed; stopping", e);
                stopSelf();
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Soundbar keep-alive", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, KeepAliveService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_speaker)
                .setContentTitle("Soundbar keep-alive running")
                .setContentText("Quiet audio while the screen is on. Stop if audible or playback changes.")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
        startForeground(1, n);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            if (track == null) {
                short[] samples = Signal.samples();
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(Signal.RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(samples.length * 2)
                        .build();
                if (track.getState() != AudioTrack.STATE_NO_STATIC_DATA)
                    throw new IllegalStateException("AudioTrack allocation failed");
                if (track.write(samples, 0, samples.length) != samples.length)
                    throw new IllegalStateException("Incomplete PCM buffer write");
                if (track.setLoopPoints(0, Signal.RATE, -1) != AudioTrack.SUCCESS)
                    throw new IllegalStateException("Cannot loop PCM buffer");
                IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
                f.addAction(Intent.ACTION_SCREEN_OFF);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
                else registerReceiver(screen, f);
                registered = true;
            }
            updatePlayback();
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot start keep-alive; stopping", e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void updatePlayback() {
        if (track == null) return;
        boolean on = getSystemService(PowerManager.class).isInteractive();
        if (on && track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) track.play();
        else if (!on && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause();
        Log.i(TAG, on ? "Signal active: " + Signal.HZ + " Hz, peak " + Signal.PEAK
                + "/32768, no audio focus" : "Screen off: signal paused");
    }

    @Override public void onDestroy() {
        if (registered) unregisterReceiver(screen);
        if (track != null) { track.release(); track = null; }
        stopForeground(STOP_FOREGROUND_REMOVE);
        Log.i(TAG, "Stopped; audio released");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
