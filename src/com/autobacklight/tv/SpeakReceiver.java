package com.autobacklight.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;

/**
 * Plays an audio file in the background with no UI. Protected by the DUMP permission in the
 * manifest, so only adb (the shell user) can trigger it:
 *   adb shell am broadcast -n com.autobacklight.tv/.SpeakReceiver --es file NAME.wav --ei volume 30
 * NAME is looked up in the app's external files dir (/sdcard/Android/data/com.autobacklight.tv/files).
 * The media volume is raised to "volume" for the playback and restored afterwards.
 */
public class SpeakReceiver extends BroadcastReceiver {
    private static MediaPlayer player;
    private static int restoreVolume = -1;

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        AudioManager audio = app.getSystemService(AudioManager.class);
        stop(audio);

        String name = intent.getStringExtra("file");
        if (name == null) return; // no file = just stop
        File file = new File(app.getExternalFilesDir(null), new File(name).getName());
        if (!file.isFile()) {
            Log.w(BacklightService.TAG, "No such file: " + file);
            return;
        }

        int volume = intent.getIntExtra("volume", -1);
        if (volume >= 0) {
            restoreVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    Math.min(volume, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0);
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(file.getAbsolutePath());
            mp.setOnCompletionListener(p -> stop(audio));
            mp.setOnErrorListener((p, what, extra) -> { stop(audio); return true; });
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            Log.w(BacklightService.TAG, "Playback failed", e);
            stop(audio);
        }
    }

    private static void stop(AudioManager audio) {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        if (restoreVolume >= 0) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVolume, 0);
            restoreVolume = -1;
        }
    }
}
