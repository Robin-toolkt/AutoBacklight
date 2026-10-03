package com.autobacklight.tv;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String ENABLED = "enabled";
    static final String MIN_BL = "minBacklight";
    static final String MAX_BL = "maxBacklight";
    static final String INTERVAL = "intervalSeconds";
    static final String CAL_DARK = "calDark";        // log10 light level the user marked as "dark"
    static final String CAL_BRIGHT = "calBright";    // log10 light level the user marked as "bright"
    static final String AUTO_MIN = "autoMin";        // darkest log10 level seen so far
    static final String AUTO_MAX = "autoMax";        // brightest log10 level seen so far
    static final String ORIGINAL_BL = "originalBacklight";
    static final String MEASURE_MODE = "measureMode"; // "manual" or "auto"; absent = not detected yet
    static final String GESTURES = "gestures";        // hand-raise spotlight on/off

    static final int DEFAULT_MIN_BL = 10;
    static final int DEFAULT_MAX_BL = 80;
    static final int DEFAULT_INTERVAL = 10;

    private Prefs() {}

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("config", Context.MODE_PRIVATE);
    }
}
