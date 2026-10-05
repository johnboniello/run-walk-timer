package com.johnboniello.runwalktimer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

/** Hosts the timer screen (assets/index.html). All timing and sound live in TimerService. */
public class MainActivity extends Activity {
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.bg));
        if (Build.VERSION.SDK_INT >= 30) {
            // Draw behind the system bars and pad the page clear of them.
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
                // Shrink the page above the keyboard so the field being edited stays visible.
                v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard));
                return WindowInsets.CONSUMED;
            });
        }

        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.bg));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        web.addJavascriptInterface(new Bridge(), "Native");
        root.addView(web);
        setContentView(root);

        // Pick up a workout that survived the app being closed or killed.
        if (TimerService.restore(this)) {
            startForegroundService(new Intent(this, TimerService.class));
        }
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    private void send(String action, String settings) {
        Intent i = new Intent(this, TimerService.class).setAction(action);
        if (settings != null) i.putExtra(TimerService.EXTRA_SETTINGS, settings);
        if (TimerService.ACTION_START.equals(action)) startForegroundService(i);
        else startService(i);
    }

    private class Bridge {
        @JavascriptInterface
        public String getState() {
            return TimerService.stateJson();
        }

        @JavascriptInterface
        public void start(String settingsJson) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                }
                send(TimerService.ACTION_START, settingsJson);
            });
        }

        @JavascriptInterface
        public void pause() {
            runOnUiThread(() -> send(TimerService.ACTION_PAUSE, null));
        }

        @JavascriptInterface
        public void reset() {
            runOnUiThread(() -> send(TimerService.ACTION_RESET, null));
        }

        @JavascriptInterface
        public void test() {
            runOnUiThread(() -> send(TimerService.ACTION_TEST, null));
        }
    }
}
