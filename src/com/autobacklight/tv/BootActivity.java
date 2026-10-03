package com.autobacklight.tv;

import android.app.Activity;
import android.os.Bundle;

public class BootActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BacklightService.start(this);
        finish();
        overridePendingTransition(0, 0);
    }
}
