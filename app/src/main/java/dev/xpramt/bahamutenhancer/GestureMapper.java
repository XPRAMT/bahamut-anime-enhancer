package dev.xpramt.bahamutenhancer;

/** Pure mapping used by the hook and host-side regression tests. */
public final class GestureMapper {
    public static final float SWEEP_HEIGHT_FRACTION = 0.80f;
    public static final float SAFE_EDGE_FRACTION = 0.10f;

    private GestureMapper() { }

    /**
     * Keeps touch starts in the top and bottom 10% out of brightness/volume gesture handling.
     * Coordinates are local to the player view, which fills the screen during playback.
     */
    public static boolean isWithinSafeArea(float y, int viewHeight) {
        if (viewHeight <= 0) {
            return false;
        }
        float edgeHeight = viewHeight * SAFE_EDGE_FRACTION;
        return y >= edgeHeight && y <= viewHeight - edgeHeight;
    }

    /**
     * Maps a vertical displacement to a normalized level.  Moving upward raises the level;
     * moving downward lowers it.  The result is absolute from ACTION_DOWN, never incremental.
     */
    public static float map(float startLevel, float downY, float currentY, int viewHeight) {
        return map(startLevel, downY, currentY, viewHeight,
                Math.round(SWEEP_HEIGHT_FRACTION * 100.0f));
    }

    public static float map(float startLevel, float downY, float currentY, int viewHeight,
                            int sweepPercent) {
        if (viewHeight <= 0) {
            return clamp(startLevel);
        }
        int clampedPercent = Math.max(1, Math.min(100, sweepPercent));
        float span = viewHeight * (clampedPercent / 100.0f);
        float displacement = currentY - downY;
        return clamp(startLevel - (displacement / span));
    }

    public static int levelToStep(float normalizedLevel, int maximum) {
        if (maximum <= 0) {
            return 0;
        }
        return Math.round(clamp(normalizedLevel) * maximum);
    }

    /**
     * Reports whether the gesture has crossed one quantized system step.  The module uses this
     * as its activation gate, rather than the player's much larger generic touch threshold.
     */
    public static boolean crossesStep(float startLevel, float downY, float currentY, int viewHeight,
                                      int sweepPercent, int maximum) {
        int startStep = levelToStep(startLevel, maximum);
        int currentStep = levelToStep(
                map(startLevel, downY, currentY, viewHeight, sweepPercent), maximum);
        return currentStep != startStep;
    }

    public static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
