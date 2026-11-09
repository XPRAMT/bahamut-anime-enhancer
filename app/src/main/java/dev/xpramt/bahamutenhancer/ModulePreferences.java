package dev.xpramt.bahamutenhancer;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings. The only copy lives in the player's own data folder (a separate preferences file inside the player
 * app), written from its playback settings panel and read by the hooks in the same process, so nothing has to
 * cross an app boundary.
 */
final class ModulePreferences {
    static final String PREFS_NAME = "bahamutenhancer";
    static final String KEY_SWEEP_PERCENT = "sweep_percent";
    static final String KEY_HIGH_PRECISION_GESTURES = "high_precision_gestures";
    static final String KEY_DOUBLE_TAP_PLAY_PAUSE = "double_tap_play_pause";
    static final int DEFAULT_SWEEP_PERCENT = 80;
    static final boolean DEFAULT_HIGH_PRECISION_GESTURES = false;
    static final boolean DEFAULT_DOUBLE_TAP_PLAY_PAUSE = true;

    private static volatile SharedPreferences prefs;

    private ModulePreferences() { }

    /** The player's application context is not there yet very early in the process; then the defaults apply. */
    private static SharedPreferences prefs() {
        SharedPreferences current = prefs;
        if (current == null) {
            try {
                Application application = (Application) Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null);
                if (application != null) {
                    current = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs = current;
                }
            } catch (Throwable throwable) {
                Xp.log("player preferences unavailable: " + throwable);
            }
        }
        return current;
    }

    static void set(String key, Object value) {
        SharedPreferences current = prefs();
        if (current == null) {
            return;
        }
        SharedPreferences.Editor editor = current.edit();
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, clamp((Integer) value));
        } else {
            return;
        }
        editor.apply();
    }

    static int loadSweepPercentForHook() {
        SharedPreferences current = prefs();
        try {
            return current == null ? DEFAULT_SWEEP_PERCENT
                    : clamp(current.getInt(KEY_SWEEP_PERCENT, DEFAULT_SWEEP_PERCENT));
        } catch (RuntimeException ignored) {
            return DEFAULT_SWEEP_PERCENT;
        }
    }

    static boolean loadHighPrecisionGesturesForHook() {
        SharedPreferences current = prefs();
        try {
            return current == null ? DEFAULT_HIGH_PRECISION_GESTURES
                    : current.getBoolean(KEY_HIGH_PRECISION_GESTURES, DEFAULT_HIGH_PRECISION_GESTURES);
        } catch (RuntimeException ignored) {
            return DEFAULT_HIGH_PRECISION_GESTURES;
        }
    }

    static boolean loadDoubleTapPlayPauseForHook() {
        SharedPreferences current = prefs();
        try {
            return current == null ? DEFAULT_DOUBLE_TAP_PLAY_PAUSE
                    : current.getBoolean(KEY_DOUBLE_TAP_PLAY_PAUSE, DEFAULT_DOUBLE_TAP_PLAY_PAUSE);
        } catch (RuntimeException ignored) {
            return DEFAULT_DOUBLE_TAP_PLAY_PAUSE;
        }
    }

    static int clamp(int percent) {
        return Math.max(1, Math.min(100, percent));
    }
}
