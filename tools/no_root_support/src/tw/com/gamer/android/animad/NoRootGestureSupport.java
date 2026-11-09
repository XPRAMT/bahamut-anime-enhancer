package tw.com.gamer.android.animad;

import android.app.Activity;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.SeekBar;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * In-process counterpart of the validated LSPosed gesture state machine.
 *
 * It deliberately uses only Android public APIs plus reflection on the host controller's own
 * fields, allowing the same code to run inside the repackaged app without an Xposed runtime.
 */
public final class NoRootGestureSupport {
    private static final float SWEEP_FRACTION = 0.80f;
    private static final long WRITE_INTERVAL_MS = 50L;
    private static final int BRIGHTNESS_GESTURE = 2;
    private static final int VOLUME_GESTURE = 3;
    private static final Map<Object, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<Object, State>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private NoRootGestureSupport() { }

    public static void trackTouch(Object controller, View view, MotionEvent event) {
        if (controller == null || event == null) {
            return;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            State state = new State();
            state.downX = event.getX();
            state.currentX = state.downX;
            state.downY = event.getY();
            state.currentY = state.downY;
            state.width = Math.max(view == null ? 0 : view.getWidth(), 1);
            state.height = Math.max(view == null ? 0 : view.getHeight(), 1);
            STATES.put(controller, state);
            return;
        }

        State state = STATES.get(controller);
        if (state == null) {
            return;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            state.currentX = event.getX();
            state.currentY = event.getY();
            if (view != null && view.getWidth() > 0) {
                state.width = view.getWidth();
            }
            if (view != null && view.getHeight() > 0) {
                state.height = view.getHeight();
            }
            int gesture = currentGesture(controller);
            if (gesture == BRIGHTNESS_GESTURE) {
                state.activeGesture = gesture;
                applyBrightness(controller, state, false);
            } else if (gesture == VOLUME_GESTURE) {
                state.activeGesture = gesture;
                applyVolume(controller, state, false);
            }
        } else if (action == MotionEvent.ACTION_UP) {
            state.currentX = event.getX();
            state.currentY = event.getY();
            if (state.activeGesture == BRIGHTNESS_GESTURE || currentGesture(controller) == BRIGHTNESS_GESTURE) {
                state.activeGesture = BRIGHTNESS_GESTURE;
                applyBrightness(controller, state, true);
            } else if (state.activeGesture == VOLUME_GESTURE || currentGesture(controller) == VOLUME_GESTURE) {
                state.activeGesture = VOLUME_GESTURE;
                applyVolume(controller, state, true);
            }
            STATES.remove(controller);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            STATES.remove(controller);
        }
    }

    /** Called at the top of the host's original brightness widget method. */
    public static boolean applyBrightness(Object controller) {
        State state = STATES.get(controller);
        if (state == null) {
            return false;
        }
        if (currentGesture(controller) == BRIGHTNESS_GESTURE) {
            state.activeGesture = BRIGHTNESS_GESTURE;
        }
        return state.activeGesture == BRIGHTNESS_GESTURE && applyBrightness(controller, state, false);
    }

    /** Called at the top of the host's original volume widget method. */
    public static boolean applyVolume(Object controller) {
        State state = STATES.get(controller);
        if (state == null) {
            return false;
        }
        if (currentGesture(controller) == VOLUME_GESTURE) {
            state.activeGesture = VOLUME_GESTURE;
        }
        return state.activeGesture == VOLUME_GESTURE && applyVolume(controller, state, false);
    }

    private static boolean applyBrightness(Object controller, State state, boolean force) {
        Activity activity = activity(controller);
        if (activity == null) {
            return false;
        }
        if (Float.isNaN(state.brightnessLevel)) {
            state.brightnessLevel = readBrightness(controller, activity);
            state.lastBrightnessY = state.downY;
            state.lastBrightnessStep = levelToStep(state.brightnessLevel, 100);
        }
        int step = levelToStep(map(state.brightnessLevel, state.lastBrightnessY, state.currentY,
                state.height), 100);
        if (!force && step == state.lastBrightnessStep) {
            return true;
        }
        long now = SystemClock.uptimeMillis();
        if (!force && now < state.lastBrightnessWriteAt + WRITE_INTERVAL_MS) {
            scheduleBrightness(controller, state, state.lastBrightnessWriteAt + WRITE_INTERVAL_MS);
            return true;
        }
        state.lastBrightnessWriteAt = now;
        state.lastBrightnessStep = step;
        state.brightnessLevel = step / 100.0f;
        state.lastBrightnessY = state.currentY;
        setInt(controller, "currentBrightness", Math.round(state.brightnessLevel * 255.0f));
        Window window = activity.getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.screenBrightness = state.brightnessLevel;
        window.setAttributes(attributes);
        updateWidget(activity, "brightness_widget_seekbar", "brightness_widget_icon",
                state.brightnessLevel, overlayShown(controller));
        return true;
    }

    private static boolean applyVolume(Object controller, State state, boolean force) {
        Activity activity = activity(controller);
        AudioManager audio = audioManager(controller);
        if (activity == null || audio == null) {
            return false;
        }
        int maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (maximum <= 0) {
            return false;
        }
        if (Float.isNaN(state.volumeLevel)) {
            state.volumeLevel = audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) maximum;
            state.lastVolumeY = state.downY;
            state.lastVolumeStep = levelToStep(state.volumeLevel, maximum);
        }
        int step = levelToStep(map(state.volumeLevel, state.lastVolumeY, state.currentY,
                state.height), maximum);
        if (!force && step == state.lastVolumeStep) {
            return true;
        }
        long now = SystemClock.uptimeMillis();
        if (!force && now < state.lastVolumeWriteAt + WRITE_INTERVAL_MS) {
            scheduleVolume(controller, state, state.lastVolumeWriteAt + WRITE_INTERVAL_MS);
            return true;
        }
        state.lastVolumeWriteAt = now;
        state.lastVolumeStep = step;
        state.volumeLevel = step / (float) maximum;
        state.lastVolumeY = state.currentY;
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0);
        updateWidget(activity, "volume_widget_seekbar", "volume_widget_icon",
                state.volumeLevel, overlayShown(controller));
        return true;
    }

    private static void scheduleBrightness(Object controller, State state, long dueAt) {
        if (state.brightnessPending) {
            return;
        }
        state.brightnessPending = true;
        WeakReference<Object> reference = new WeakReference<Object>(controller);
        MAIN.postAtTime(new Runnable() {
            @Override public void run() {
                state.brightnessPending = false;
                Object current = reference.get();
                if (current != null && STATES.get(current) == state && state.activeGesture == BRIGHTNESS_GESTURE) {
                    applyBrightness(current, state, false);
                }
            }
        }, dueAt);
    }

    private static void scheduleVolume(Object controller, State state, long dueAt) {
        if (state.volumePending) {
            return;
        }
        state.volumePending = true;
        WeakReference<Object> reference = new WeakReference<Object>(controller);
        MAIN.postAtTime(new Runnable() {
            @Override public void run() {
                state.volumePending = false;
                Object current = reference.get();
                if (current != null && STATES.get(current) == state && state.activeGesture == VOLUME_GESTURE) {
                    applyVolume(current, state, false);
                }
            }
        }, dueAt);
    }

    private static float map(float level, float fromY, float toY, int height) {
        if (height <= 0) {
            return clamp(level);
        }
        return clamp(level - ((toY - fromY) / (height * SWEEP_FRACTION)));
    }

    private static int levelToStep(float level, int maximum) {
        return Math.round(clamp(level) * maximum);
    }

    private static float clamp(float level) {
        return Math.max(0.0f, Math.min(1.0f, level));
    }

    private static Activity activity(Object controller) {
        Object context = get(controller, "context");
        return context instanceof Activity ? (Activity) context : null;
    }

    private static AudioManager audioManager(Object controller) {
        Object audio = get(controller, "audioManager");
        return audio instanceof AudioManager ? (AudioManager) audio : null;
    }

    private static int currentGesture(Object controller) {
        Object gesture = get(controller, "currentGesture");
        return gesture instanceof Integer ? (Integer) gesture : 0;
    }

    private static boolean overlayShown(Object controller) {
        Object shown = get(controller, "isControlOverlayShown");
        return shown instanceof Boolean && (Boolean) shown;
    }

    private static float readBrightness(Object controller, Activity activity) {
        Object brightness = get(controller, "currentBrightness");
        if (brightness instanceof Integer) {
            return clamp(((Integer) brightness) / 255.0f);
        }
        float value = activity.getWindow().getAttributes().screenBrightness;
        return value < 0.0f ? 0.5f : clamp(value);
    }

    private static Object get(Object target, String name) {
        try {
            Field field = field(target.getClass(), name);
            return field == null ? null : field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setInt(Object target, String name, int value) {
        try {
            Field field = field(target.getClass(), name);
            if (field != null) {
                field.setInt(target, value);
            }
        } catch (Throwable ignored) {
            // The window update remains valid if a future host version renames the field.
        }
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Search the superclass as the controller base type may own the field.
            }
        }
        return null;
    }

    private static void updateWidget(Activity activity, String seekbarName, String iconName,
                                     float level, boolean overlayShown) {
        int seekbarId = activity.getResources().getIdentifier(seekbarName, "id", activity.getPackageName());
        int iconId = activity.getResources().getIdentifier(iconName, "id", activity.getPackageName());
        View seekbar = activity.findViewById(seekbarId);
        View icon = activity.findViewById(iconId);
        if (seekbar instanceof SeekBar) {
            ((SeekBar) seekbar).setProgress(Math.round(level * 100.0f));
            if (seekbar.getVisibility() == View.GONE && !overlayShown) {
                seekbar.setVisibility(View.VISIBLE);
                if (icon != null) {
                    icon.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    private static final class State {
        float downX;
        float currentX;
        float downY;
        float currentY;
        float brightnessLevel = Float.NaN;
        float volumeLevel = Float.NaN;
        float lastBrightnessY;
        float lastVolumeY;
        int width;
        int height;
        int activeGesture;
        int lastBrightnessStep = -1;
        int lastVolumeStep = -1;
        long lastBrightnessWriteAt = Long.MIN_VALUE;
        long lastVolumeWriteAt = Long.MIN_VALUE;
        boolean brightnessPending;
        boolean volumePending;
    }
}
