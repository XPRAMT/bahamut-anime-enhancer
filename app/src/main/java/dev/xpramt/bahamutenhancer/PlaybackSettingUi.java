package dev.xpramt.bahamutenhancer;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Adds the module's settings to the player's own "播放設定" panel. Every row is built from views inflated
 * out of the panel's own layout (title, summary, switch, divider), so it keeps the app's styling.
 */
final class PlaybackSettingUi {
    private static final String TARGET_PACKAGE = "tw.com.gamer.android.animad";
    private static final String BLOCK_TAG = "bahamutenhancer.block";

    private PlaybackSettingUi() { }

    /** {@code root} is the panel fragment's root view (a ScrollView holding a ConstraintLayout). */
    static void inject(View root) {
        Context context = root.getContext();
        ViewGroup panel = findPanel(root);
        if (panel == null || panel.findViewWithTag(BLOCK_TAG) != null) {
            return;
        }
        int layoutId = id(context, "layout", "player_playback_setting_layout");
        int anchorId = id(context, "id", "ui_lock_summary");
        if (layoutId == 0 || anchorId == 0) {
            Xp.log("settings panel layout not found; module rows not added");
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;

        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setTag(BLOCK_TAG);
        addSwitchRow(block, context, layoutId, density,
                Labels.HIGH_PRECISION_TITLE, Labels.HIGH_PRECISION_SUMMARY,
                ModulePreferences.loadHighPrecisionGesturesForHook(),
                ModulePreferences.KEY_HIGH_PRECISION_GESTURES);
        addSwitchRow(block, context, layoutId, density,
                Labels.DOUBLE_TAP_TITLE, Labels.DOUBLE_TAP_SUMMARY,
                ModulePreferences.loadDoubleTapPlayPauseForHook(),
                ModulePreferences.KEY_DOUBLE_TAP_PLAY_PAUSE);
        addSweepRow(block, context, layoutId, density);

        try {
            panel.addView(block, constraintParams(panel, anchorId));
            Xp.log("module rows added to the playback settings panel");
        } catch (Throwable throwable) {
            Xp.log("adding module rows failed: " + throwable);
        }
    }

    private static ViewGroup findPanel(View root) {
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            if (group.getClass().getName().endsWith("ConstraintLayout")) {
                return group;
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                ViewGroup found = findPanel(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** ConstraintLayout belongs to the target app, so its LayoutParams are built by reflection. */
    private static ViewGroup.LayoutParams constraintParams(ViewGroup panel, int anchorId) throws Exception {
        Class<?> type = Class.forName(panel.getClass().getName() + "$LayoutParams", false,
                panel.getClass().getClassLoader());
        Constructor<?> constructor = type.getConstructor(int.class, int.class);
        ViewGroup.LayoutParams params = (ViewGroup.LayoutParams) constructor.newInstance(0,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        setInt(type, params, "topToBottom", anchorId);
        setInt(type, params, "startToStart", 0);
        setInt(type, params, "endToEnd", 0);
        return params;
    }

    private static void setInt(Class<?> type, Object target, String name, int value) throws Exception {
        Field field = type.getField(name);
        field.setInt(target, value);
    }

    /** A fresh copy of the panel's layout, used only as a source of styled views. */
    private static View inflate(Context context, int layoutId) {
        return LayoutInflater.from(context).inflate(layoutId, null, false);
    }

    private static <T extends View> T take(View template, String name) {
        @SuppressWarnings("unchecked")
        T view = (T) template.findViewById(id(template.getContext(), "id", name));
        ((ViewGroup) view.getParent()).removeView(view);
        return view;
    }

    private static void addDivider(LinearLayout block, Context context, int layoutId, float density) {
        View divider = take(inflate(context, layoutId), "separate_line_1");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(density));
        params.topMargin = Math.round(28 * density);
        block.addView(divider, params);
    }

    private static LinearLayout.LayoutParams wrap(int topDp, float density) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Math.round(topDp * density);
        return params;
    }

    private static void addSwitchRow(LinearLayout block, Context context, int layoutId, float density,
                                     String title, String summary, boolean checked, String key) {
        addDivider(block, context, layoutId, density);
        View template = inflate(context, layoutId);
        TextView titleView = take(template, "ui_lock_title");
        TextView summaryView = take(template, "ui_lock_summary");
        CompoundButton toggle = take(template, "ui_lock_switch");
        titleView.setText(title);
        summaryView.setText(summary);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, isChecked) -> save(key, isChecked));

        block.addView(titleView, wrap(16, density));
        LinearLayout line = new LinearLayout(context);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(summaryView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        block.addView(line, wrap(8, density));
    }

    private static void addSweepRow(LinearLayout block, Context context, int layoutId, float density) {
        addDivider(block, context, layoutId, density);
        View template = inflate(context, layoutId);
        TextView titleView = take(template, "ui_lock_title");
        TextView summaryView = take(template, "ui_lock_summary");
        int percent = ModulePreferences.loadSweepPercentForHook();
        titleView.setText(Labels.SWEEP_TITLE);
        summaryView.setText(Labels.sweepSummary(percent));

        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(99);
        seekBar.setProgress(percent - 1);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                summaryView.setText(Labels.sweepSummary(progress + 1));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                save(ModulePreferences.KEY_SWEEP_PERCENT, bar.getProgress() + 1);
            }
        });

        block.addView(titleView, wrap(16, density));
        block.addView(summaryView, wrap(8, density));
        LinearLayout.LayoutParams barParams = wrap(4, density);
        barParams.bottomMargin = Math.round(4 * density);
        block.addView(seekBar, barParams);
    }

    /** Stored in the player's own preferences and applied at once: the hooks read the same file. */
    private static void save(String key, Object value) {
        ModulePreferences.set(key, value);
    }

    private static int id(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, TARGET_PACKAGE);
    }
}
