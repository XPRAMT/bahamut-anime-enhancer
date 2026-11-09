package dev.xpramt.bahamutenhancer;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Replaces Anime1's event-count-dependent brightness/volume movement with an absolute
 * ACTION_DOWN-relative mapping.  The target's 80% of player height maps to the whole range.
 */
public final class HookEntry extends XposedModule {
    private static final String TARGET_PACKAGE = "tw.com.gamer.android.animad";
    private static final String SETTINGS_FRAGMENT = "tw.com.gamer.android.animad.PlaybackSettingFragment";
    private static final long SYSTEM_WRITE_INTERVAL_MS = 50L;
    private static final String[] CONTROLLERS = {
            "tw.com.gamer.android.animad.player.AnimeController",
            "tw.com.gamer.android.animad.party.ui.PartyController",
            "tw.com.gamer.android.animad.player.portrait.PortraitAnimeController"
    };
    private static final Map<Object, GestureState> STATES =
            Collections.synchronizedMap(new WeakHashMap<Object, GestureState>());

    /** Class loaders of the target hooked by this generation; handed to the next one on a hot reload. */
    private final List<ClassLoader> hooked = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Xp.init(this);
        Xp.log("module loaded in " + param.getProcessName() + " api=" + getApiVersion()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (TARGET_PACKAGE.equals(param.getPackageName())) {
            installHooks(param.getClassLoader());
        }
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        List<ClassLoader> loaders;
        synchronized (hooked) {
            loaders = new ArrayList<>(hooked);
        }
        if (loaders.isEmpty()) {
            Xp.log("hot reload refused: nothing hooked yet");
            return false;
        }
        // Only the target's class loaders cross: nothing created under this module's class loader.
        param.setSavedInstanceState(loaders.toArray());
        Xp.log("hot reloading");
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        super.onHotReloaded(param); // unhooks every hook of the old generation
        Xp.init(this);
        Xp.log("hot reloaded in " + param.getProcessName());
        Object saved = param.getSavedInstanceState();
        if (saved instanceof Object[]) {
            for (Object loader : (Object[]) saved) {
                installHooks((ClassLoader) loader);
            }
        }
    }

    private void installHooks(ClassLoader classLoader) {
        hooked.add(classLoader);
        for (String className : CONTROLLERS) {
            hookController(className, classLoader);
        }
        hookSettingsPanel(classLoader);
    }

    /** Adds the module's rows to the player's playback settings panel once its view exists. */
    private void hookSettingsPanel(ClassLoader classLoader) {
        try {
            Class<?> fragment = Xp.findClass(SETTINGS_FRAGMENT, classLoader);
            Xp.findAndHookMethod(fragment, "onViewCreated", View.class, android.os.Bundle.class,
                    new Xp.MethodHook() {
                        @Override
                        protected void after(Xp.Param param) {
                            PlaybackSettingUi.inject((View) param.args[0]);
                        }
                    });
            log("Hooked " + SETTINGS_FRAGMENT);
        } catch (Throwable throwable) {
            log("Skipped " + SETTINGS_FRAGMENT + ": " + throwable.getClass().getSimpleName());
        }
    }

    private void hookController(String className, ClassLoader classLoader) {
        try {
            Class<?> controller = Xp.findClass(className, classLoader);
            Xp.findAndHookMethod(controller, "onTouch", View.class, MotionEvent.class,
                    new Xp.MethodHook() {
                        @Override
                        protected void before(Xp.Param param) {
                            trackTouch(param.thisObject, (View) param.args[0], (MotionEvent) param.args[1]);
                        }

                        @Override
                        protected void after(Xp.Param param) {
                            MotionEvent event = (MotionEvent) param.args[1];
                            if (event != null && event.getActionMasked() == MotionEvent.ACTION_UP) {
                                GestureState state = STATES.get(param.thisObject);
                                if (state != null && !state.enabled) {
                                    STATES.remove(param.thisObject);
                                }
                            }
                        }
                    });
            Xp.findAndHookMethod(controller, "showBrightnessWidget", float.class,
                    new Xp.MethodHook() {
                        @Override
                        protected void before(Xp.Param param) {
                            if (hasTrackedGesture(param.thisObject)
                                    && applyBrightness(param.thisObject)) {
                                param.setResult(null);
                            }
                        }
                    });
            Xp.findAndHookMethod(controller, "showVolumeWidget", float.class,
                    new Xp.MethodHook() {
                        @Override
                        protected void before(Xp.Param param) {
                            if (hasTrackedGesture(param.thisObject)
                                    && applyVolume(param.thisObject)) {
                                param.setResult(null);
                            }
                        }
                    });
            hookFastForward2x(controller);
            hookDoubleTapAsPlayPause(className, classLoader);
            log("Hooked " + className);
        } catch (Throwable throwable) {
            log("Skipped " + className + ": " + throwable.getClass().getSimpleName());
        }
    }

    /** Only the landscape player has the long-press 2x mode; the portrait and party players do not. */
    private void hookFastForward2x(Class<?> controller) {
        try {
            Xp.findAndHookMethod(controller, "enableFastForward2x", new Xp.MethodHook() {
                @Override
                protected void before(Xp.Param param) {
                    if (ModulePreferences.loadHighPrecisionGesturesForHook()) {
                        // High precision owns vertical touch processing, so never enter 2x mode.
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable ignored) {
            // This controller has no 2x mode, so there is nothing to turn off.
        }
    }

    private void hookDoubleTapAsPlayPause(String controllerClassName, ClassLoader classLoader) {
        try {
            Class<?> gestureListener = Xp.findClass(
                    controllerClassName + "$GestureListener", classLoader);
            Xp.findAndHookMethod(gestureListener, "onDoubleTap", MotionEvent.class,
                    new Xp.MethodHook() {
                        @Override
                        protected void before(Xp.Param param) {
                            if (!ModulePreferences.loadDoubleTapPlayPauseForHook()) {
                                return;
                            }
                            // The original listener maps left/right double taps to +/-10 seconds.
                            // Its center handler already supplies the correct animation and click path.
                            Xp.callMethod(param.thisObject, "onPlayPauseZoneTapped");
                            param.setResult(true);
                        }
                    });
        } catch (Throwable ignored) {
            // Party mode does not expose this listener in every app release.
        }
    }

    private void trackTouch(Object controller, View view, MotionEvent event) {
        if (event == null) {
            return;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            GestureState state = new GestureState();
            state.downX = event.getX();
            state.currentX = state.downX;
            state.downY = event.getY();
            state.currentY = state.downY;
            state.height = Math.max(view == null ? 0 : view.getHeight(), 1);
            state.width = Math.max(view == null ? 0 : view.getWidth(), 1);
            state.sweepPercent = ModulePreferences.loadSweepPercentForHook();
            state.precisionInitialTrigger = ModulePreferences.loadHighPrecisionGesturesForHook();
            // Ignore touches that start in the status/navigation edge zones. Keep the disabled
            // state so the host's native brightness/volume handlers can be suppressed as well.
            state.enabled = GestureMapper.isWithinSafeArea(state.downY, state.height);
            STATES.put(controller, state);
        } else if (action == MotionEvent.ACTION_MOVE) {
            GestureState state = STATES.get(controller);
            if (state != null) {
                state.moveCallbacks++;
                state.historicalSamples += event.getHistorySize();
                state.currentX = event.getX();
                state.currentY = event.getY();
                if (view != null && view.getHeight() > 0) {
                    state.height = view.getHeight();
                }
                if (view != null && view.getWidth() > 0) {
                    state.width = view.getWidth();
                }
                driveRateLimitedUpdate(controller, state);
            }
        } else if (action == MotionEvent.ACTION_UP) {
            GestureState state = STATES.get(controller);
            if (state != null && state.moveCallbacks > 0) {
                log("[DEBUG-motion64] moves=" + state.moveCallbacks
                        + " history=" + state.historicalSamples
                        + " action=up");
            }
            if (state != null) {
                state.currentY = event.getY();
                state.ended = true;
                if (!state.enabled) {
                    // Keep the rejected state until the host's onTouch callback has returned so
                    // any native widget calls made during ACTION_UP are still intercepted.
                    return;
                }
                int gesture = state.activeGesture;
                if (gesture == 2) {
                    applyBrightness(controller);
                } else if (gesture == 3) {
                    applyVolume(controller);
                }
                finishEndedGesture(controller, state);
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            STATES.remove(controller);
        }
    }

    /**
     * The target app may stop calling its widget methods while a gesture is still moving.
     * Drive our own 20 Hz scheduler from every input callback instead of inheriting that gate.
     */
    private void driveRateLimitedUpdate(Object controller, GestureState state) {
        if (!state.enabled) {
            return;
        }
        int detectedGesture = currentGesture(controller);
        if (detectedGesture == 2 || detectedGesture == 3) {
            state.activeGesture = detectedGesture;
        }
        if (state.activeGesture == 0 && state.precisionInitialTrigger
                && isVerticalGesturePastOneStep(controller, state)) {
            // Match the player's native convention: left controls brightness, right controls volume.
            state.activeGesture = state.downX < state.width / 2.0f ? 2 : 3;
        }
        if (state.activeGesture == 2) {
            applyBrightness(controller);
        } else if (state.activeGesture == 3) {
            applyVolume(controller);
        }
    }

    private boolean isVerticalGesturePastOneStep(Object controller, GestureState state) {
        float deltaX = Math.abs(state.currentX - state.downX);
        float deltaY = Math.abs(state.currentY - state.downY);
        if (deltaY <= deltaX) {
            return false;
        }
        if (state.downX < state.width / 2.0f) {
            Activity activity = activity(controller);
            if (activity == null) {
                return false;
            }
            float level = readBrightness(controller, activity);
            return GestureMapper.crossesStep(level, state.downY, state.currentY, state.height,
                    state.sweepPercent, 100);
        }
        AudioManager audioManager;
        try {
            audioManager = (AudioManager) Xp.getObjectField(controller, "audioManager");
        } catch (Throwable ignored) {
            return false;
        }
        if (audioManager == null) {
            return false;
        }
        int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (maximum <= 0) {
            return false;
        }
        float level = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) maximum;
        return GestureMapper.crossesStep(level, state.downY, state.currentY, state.height,
                state.sweepPercent, maximum);
    }

    private boolean applyBrightness(Object controller) {
        GestureState state = STATES.get(controller);
        if (state == null) {
            return false;
        }
        if (!state.enabled) {
            // Treat a rejected safe-zone gesture as handled to prevent the host fallback.
            return true;
        }
        Activity activity = activity(controller);
        if (activity == null) {
            return false;
        }
        boolean firstApplication = !state.brightnessApplied;
        if (Float.isNaN(state.brightnessLevel)) {
            state.brightnessLevel = readBrightness(controller, activity);
            state.lastBrightnessY = state.downY;
            state.lastBrightnessStep = GestureMapper.levelToStep(state.brightnessLevel, 100);
        }
        float level = GestureMapper.map(state.brightnessLevel, state.lastBrightnessY, state.currentY,
                state.height, state.sweepPercent);
        if (firstApplication) {
            state.brightnessApplied = true;
            log("[DEBUG-gesture80] brightness mapping active start=" + state.brightnessLevel
                    + " height=" + state.height + " sweep=" + state.sweepPercent);
        }
        int brightnessPercent = GestureMapper.levelToStep(level, 100);
        if (!firstApplication && brightnessPercent == state.lastBrightnessStep) {
            return true;
        }
        long now = SystemClock.uptimeMillis();
        if (isBrightnessWriteRateLimited(controller, state, now)) {
            return true;
        }
        state.lastBrightnessStep = brightnessPercent;
        state.lastBrightnessWriteAt = now;
        state.brightnessLevel = level;
        state.lastBrightnessY = state.currentY;
        log("[DEBUG-gesture80] brightness step=" + brightnessPercent);
        int brightness = Math.round((brightnessPercent / 100.0f) * 255.0f);
        try {
            Xp.setIntField(controller, "currentBrightness", brightness);
        } catch (Throwable ignored) {
            // The current app version exposes this field; retaining the guard keeps updates fail-safe.
        }
        Window window = activity.getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.screenBrightness = brightnessPercent / 100.0f;
        window.setAttributes(attributes);
        updateWidget(activity, "brightness_widget_seekbar", "brightness_widget_icon",
                brightnessPercent / 100.0f,
                isOverlayShown(controller));
        return true;
    }

    private boolean applyVolume(Object controller) {
        GestureState state = STATES.get(controller);
        if (state == null) {
            return false;
        }
        if (!state.enabled) {
            // Treat a rejected safe-zone gesture as handled to prevent the host fallback.
            return true;
        }
        Activity activity = activity(controller);
        if (activity == null) {
            return false;
        }
        AudioManager audioManager;
        try {
            audioManager = (AudioManager) Xp.getObjectField(controller, "audioManager");
        } catch (Throwable throwable) {
            return false;
        }
        if (audioManager == null) {
            return false;
        }
        int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (maximum <= 0) {
            return false;
        }
        boolean firstApplication = !state.volumeApplied;
        if (Float.isNaN(state.volumeLevel)) {
            state.volumeLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) maximum;
            state.lastVolumeY = state.downY;
            state.lastVolumeStep = GestureMapper.levelToStep(state.volumeLevel, maximum);
        }
        float level = GestureMapper.map(state.volumeLevel, state.lastVolumeY, state.currentY,
                state.height, state.sweepPercent);
        if (firstApplication) {
            state.volumeApplied = true;
            log("[DEBUG-gesture80] volume mapping active start=" + state.volumeLevel
                    + " height=" + state.height + " max=" + maximum
                    + " sweep=" + state.sweepPercent);
        }
        int volumeStep = GestureMapper.levelToStep(level, maximum);
        if (!firstApplication && volumeStep == state.lastVolumeStep) {
            return true;
        }
        long now = SystemClock.uptimeMillis();
        if (isVolumeWriteRateLimited(controller, state, now)) {
            return true;
        }
        state.lastVolumeStep = volumeStep;
        state.lastVolumeWriteAt = now;
        state.volumeLevel = level;
        state.lastVolumeY = state.currentY;
        log("[DEBUG-gesture80] volume step=" + volumeStep + "/" + maximum);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeStep, 0);
        updateWidget(activity, "volume_widget_seekbar", "volume_widget_icon", level,
                isOverlayShown(controller));
        return true;
    }

    private boolean isBrightnessWriteRateLimited(Object controller, GestureState state, long now) {
        long dueAt = state.lastBrightnessWriteAt + SYSTEM_WRITE_INTERVAL_MS;
        if (state.lastBrightnessWriteAt == Long.MIN_VALUE || now >= dueAt) {
            return false;
        }
        if (!state.brightnessFlushScheduled) {
            state.brightnessFlushScheduled = true;
            mainHandler().postAtTime(() -> {
                state.brightnessFlushScheduled = false;
                if (STATES.get(controller) == state) {
                    applyBrightness(controller);
                    finishEndedGesture(controller, state);
                }
            }, dueAt);
        }
        return true;
    }

    private boolean isVolumeWriteRateLimited(Object controller, GestureState state, long now) {
        long dueAt = state.lastVolumeWriteAt + SYSTEM_WRITE_INTERVAL_MS;
        if (state.lastVolumeWriteAt == Long.MIN_VALUE || now >= dueAt) {
            return false;
        }
        if (!state.volumeFlushScheduled) {
            state.volumeFlushScheduled = true;
            mainHandler().postAtTime(() -> {
                state.volumeFlushScheduled = false;
                if (STATES.get(controller) == state) {
                    applyVolume(controller);
                    finishEndedGesture(controller, state);
                }
            }, dueAt);
        }
        return true;
    }

    private static void finishEndedGesture(Object controller, GestureState state) {
        if (state.ended && !state.brightnessFlushScheduled && !state.volumeFlushScheduled) {
            if (state.activeGesture == 2) {
                hideWidget(controller, "hideBrightnessWidget");
            } else if (state.activeGesture == 3) {
                hideWidget(controller, "hideVolumeWidget");
            }
            STATES.remove(controller);
        }
    }

    private static boolean hasTrackedGesture(Object controller) {
        return STATES.get(controller) != null;
    }

    private static void hideWidget(Object controller, String methodName) {
        try {
            Xp.callMethod(controller, methodName);
        } catch (Throwable ignored) {
            // A future player build may rename the widget method; leaving the overlay visible is safe.
        }
    }

    /** The module class is initialized during zygote specialization, before a main looper exists. */
    private static Handler mainHandler() {
        return new Handler(Looper.getMainLooper());
    }

    private static float readBrightness(Object controller, Activity activity) {
        try {
            return GestureMapper.clamp(Xp.getIntField(controller, "currentBrightness") / 255.0f);
        } catch (Throwable ignored) {
            float brightness = activity.getWindow().getAttributes().screenBrightness;
            return brightness < 0.0f ? 0.5f : GestureMapper.clamp(brightness);
        }
    }

    private static Activity activity(Object controller) {
        try {
            Object context = Xp.getObjectField(controller, "context");
            return context instanceof Activity ? (Activity) context : null;
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static boolean isOverlayShown(Object controller) {
        try {
            return Xp.getBooleanField(controller, "isControlOverlayShown");
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static int currentGesture(Object controller) {
        try {
            return Xp.getIntField(controller, "currentGesture");
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void updateWidget(Activity activity, String seekbarName, String iconName,
                                     float level, boolean overlayShown) {
        int seekbarId = activity.getResources().getIdentifier(seekbarName, "id", TARGET_PACKAGE);
        int iconId = activity.getResources().getIdentifier(iconName, "id", TARGET_PACKAGE);
        View seekbarView = activity.findViewById(seekbarId);
        View iconView = activity.findViewById(iconId);
        if (seekbarView instanceof SeekBar) {
            ((SeekBar) seekbarView).setProgress(Math.round(level * 100.0f));
            if (seekbarView.getVisibility() == View.GONE && !overlayShown) {
                seekbarView.setVisibility(View.VISIBLE);
                if (iconView != null) {
                    iconView.setVisibility(View.VISIBLE);
                }
            }
        }
    }

    private static void log(String message) {
        Xp.log(message);
    }

    private static final class GestureState {
        float downX;
        float currentX;
        float downY;
        float currentY;
        int height;
        int width;
        int sweepPercent;
        boolean enabled;
        boolean precisionInitialTrigger;
        float brightnessLevel = Float.NaN;
        float volumeLevel = Float.NaN;
        float lastBrightnessY;
        float lastVolumeY;
        boolean brightnessApplied;
        boolean volumeApplied;
        int lastBrightnessStep = -1;
        int lastVolumeStep = -1;
        int moveCallbacks;
        int historicalSamples;
        long lastBrightnessWriteAt = Long.MIN_VALUE;
        long lastVolumeWriteAt = Long.MIN_VALUE;
        boolean brightnessFlushScheduled;
        boolean volumeFlushScheduled;
        boolean ended;
        int activeGesture;
    }
}
