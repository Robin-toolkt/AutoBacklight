package com.autobacklight.tv;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Remote-friendly settings screen. Built in code so the app needs no layout resources. */
public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView info;
    private Button toggle, gestureToggle;
    private TextView minValue, maxValue, intervalValue;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(32), dp(48), dp(32));
        root.setBackgroundColor(Color.rgb(0x14, 0x1C, 0x27));

        TextView title = text("Adaptive Brightness", 30);
        title.setTextColor(Color.rgb(0xFF, 0xC8, 0x3D));
        root.addView(title);
        root.addView(text("Uses the TV camera to measure room light and adjusts the screen brightness to match. "
                + "Raise your hand to open a spotlight window that zooms in and follows you.", 16));

        info = text("", 18);
        info.setPadding(0, dp(16), 0, dp(16));
        root.addView(info);

        toggle = button("", v -> setEnabled(!prefs.getBoolean(Prefs.ENABLED, false)));
        root.addView(toggle);
        gestureToggle = button("", v -> setGestures(!prefs.getBoolean(Prefs.GESTURES, false)));
        root.addView(gestureToggle);

        minValue = new TextView(this);
        maxValue = new TextView(this);
        intervalValue = new TextView(this);
        root.addView(stepper("Lowest brightness", minValue, Prefs.MIN_BL, Prefs.DEFAULT_MIN_BL, 0, 100, 5));
        root.addView(stepper("Highest brightness", maxValue, Prefs.MAX_BL, Prefs.DEFAULT_MAX_BL, 0, 100, 5));
        root.addView(stepper("Check every (seconds)", intervalValue, Prefs.INTERVAL, Prefs.DEFAULT_INTERVAL, 2, 120, 2));

        root.addView(button("Calibrate: the room is DARK right now", v -> calibrate(Prefs.CAL_DARK)));
        root.addView(button("Calibrate: the room is BRIGHT right now", v -> calibrate(Prefs.CAL_BRIGHT)));
        root.addView(button("Reset calibration (learn automatically)", v -> {
            prefs.edit().remove(Prefs.CAL_DARK).remove(Prefs.CAL_BRIGHT)
                    .remove(Prefs.AUTO_MIN).remove(Prefs.AUTO_MAX).apply();
            toast("Calibration reset");
        }));
        root.addView(button("Re-test camera exposure mode", v -> {
            prefs.edit().remove(Prefs.MEASURE_MODE).apply();
            if (BacklightService.running) {
                startService(new Intent(this, BacklightService.class).putExtra("resample", true));
            }
            toast("Camera will be re-tested");
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(0x14, 0x1C, 0x27));
        scroll.addView(root);
        setContentView(scroll);
        toggle.requestFocus();

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        }
        if (BacklightService.anythingEnabled(prefs) && !BacklightService.running) {
            BacklightService.start(this);
        }
    }

    private void setGestures(boolean enable) {
        if (enable && !Settings.canDrawOverlays(this)) {
            toast("Needs: adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            return;
        }
        if (enable && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            toast("Allow camera access first");
            return;
        }
        prefs.edit().putBoolean(Prefs.GESTURES, enable).apply();
        if (enable || BacklightService.running) BacklightService.reconfigure(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresher);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresher);
        super.onPause();
    }

    private void setEnabled(boolean enable) {
        if (enable) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
                toast("Allow camera access first");
                return;
            }
            if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
                toast("Needs: adb shell pm grant " + getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
                return;
            }
            int current = Settings.Global.getInt(getContentResolver(), BacklightService.SETTING, -1);
            prefs.edit().putBoolean(Prefs.ENABLED, true).putInt(Prefs.ORIGINAL_BL, current).apply();
            BacklightService.reconfigure(this);
        } else {
            prefs.edit().putBoolean(Prefs.ENABLED, false).apply();
            if (BacklightService.running) BacklightService.reconfigure(this);
            int original = prefs.getInt(Prefs.ORIGINAL_BL, -1);
            if (original >= 0) {
                try {
                    Settings.Global.putInt(getContentResolver(), BacklightService.SETTING, original);
                } catch (SecurityException ignored) {
                }
            }
        }
        refresh();
    }

    private void calibrate(String key) {
        float level = BacklightService.lastLogLight;
        if (!BacklightService.running || Float.isNaN(level)) {
            toast("Turn Adaptive Brightness on and wait for a light reading first");
            return;
        }
        prefs.edit().putFloat(key, level).apply();
        toast("Saved");
    }

    private void refresh() {
        boolean enabled = prefs.getBoolean(Prefs.ENABLED, false);
        toggle.setText(enabled ? "Adaptive Brightness: ON  (press to turn off)" : "Adaptive Brightness: OFF  (press to turn on)");
        boolean gestures = prefs.getBoolean(Prefs.GESTURES, false);
        gestureToggle.setText(gestures ? "Hand-raise spotlight: ON  (press to turn off)" : "Hand-raise spotlight: OFF  (press to turn on)");
        minValue.setText(String.valueOf(prefs.getInt(Prefs.MIN_BL, Prefs.DEFAULT_MIN_BL)));
        maxValue.setText(String.valueOf(prefs.getInt(Prefs.MAX_BL, Prefs.DEFAULT_MAX_BL)));
        intervalValue.setText(String.valueOf(prefs.getInt(Prefs.INTERVAL, Prefs.DEFAULT_INTERVAL)));

        int current = Settings.Global.getInt(getContentResolver(), BacklightService.SETTING, -1);
        float light = BacklightService.lastLogLight;
        StringBuilder sb = new StringBuilder();
        sb.append("Status: ").append(BacklightService.running ? BacklightService.status : "Stopped").append('\n');
        sb.append("Camera mode: ").append(BacklightService.modeLabel).append('\n');
        sb.append("Room light: ").append(Float.isNaN(light) ? "-" : String.format(Locale.US, "%.2f", light))
                .append("   (camera brightness ").append(BacklightService.lastLuma < 0 ? "-" : BacklightService.lastLuma + "/255").append(")\n");
        sb.append("Light range: ").append(range()).append('\n');
        sb.append("TV brightness now: ").append(current < 0 ? "-" : current).append('\n');
        sb.append("Spotlight: ").append(BacklightService.running ? BacklightService.gestureStatus : "Off");
        info.setText(sb.toString());
    }

    private String range() {
        boolean calDark = prefs.contains(Prefs.CAL_DARK), calBright = prefs.contains(Prefs.CAL_BRIGHT);
        if (!calDark && !calBright && !prefs.contains(Prefs.AUTO_MIN)) return "learning...";
        float dark = prefs.getFloat(Prefs.CAL_DARK, prefs.getFloat(Prefs.AUTO_MIN, 0));
        float bright = prefs.getFloat(Prefs.CAL_BRIGHT, prefs.getFloat(Prefs.AUTO_MAX, 0));
        return String.format(Locale.US, "dark %.2f%s  to  bright %.2f%s",
                dark, calDark ? " (set)" : "", bright, calBright ? " (set)" : "");
    }

    // ---------------------------------------------------------------- view helpers

    private View stepper(String label, TextView value, String key, int def, int min, int max, int step) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = text(label, 18);
        row.addView(l, new LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(button(" - ", v -> bump(key, def, -step, min, max)));
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        value.setTextColor(Color.WHITE);
        value.setGravity(Gravity.CENTER);
        row.addView(value, new LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(button(" + ", v -> bump(key, def, step, min, max)));
        return row;
    }

    private void bump(String key, int def, int delta, int min, int max) {
        int v = Math.max(min, Math.min(max, prefs.getInt(key, def) + delta));
        SharedPreferences.Editor e = prefs.edit().putInt(key, v);
        // keep darkest <= brightest
        if (key.equals(Prefs.MIN_BL) && v > prefs.getInt(Prefs.MAX_BL, Prefs.DEFAULT_MAX_BL)) e.putInt(Prefs.MAX_BL, v);
        if (key.equals(Prefs.MAX_BL) && v < prefs.getInt(Prefs.MIN_BL, Prefs.DEFAULT_MIN_BL)) e.putInt(Prefs.MIN_BL, v);
        e.apply();
        refresh();
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.rgb(0xE6, 0xEA, 0xF0));
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setOnClickListener(l);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
