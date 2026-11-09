package dev.xpramt.bahamutenhancer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class GestureMapperTest {
    private static final int HEIGHT = 1_000;

    @Test
    public void legacyPerMoveMappingIsEventFrequencyDependent() {
        int oneMove = legacyBrightness(128, -800.0f);
        int eightyMoves = 128;
        for (int index = 0; index < 80; index++) {
            eightyMoves = legacyBrightness(eightyMoves, -10.0f);
        }
        assertNotEquals("The original per-MOVE scheme must expose the reported instability", oneMove, eightyMoves);
    }

    @Test
    public void eightyPercentUpwardSweepReachesOneHundredPercent() {
        assertEquals(1.0f, GestureMapper.map(0.0f, 900.0f, 100.0f, HEIGHT), 0.0001f);
    }

    @Test
    public void eightyPercentDownwardSweepReachesZeroPercent() {
        assertEquals(0.0f, GestureMapper.map(1.0f, 100.0f, 900.0f, HEIGHT), 0.0001f);
    }

    @Test
    public void resultIsInvariantToMoveEventSegmentation() {
        float oneMove = GestureMapper.map(0.25f, 900.0f, 340.0f, HEIGHT);
        float manyMoves = 0.25f;
        for (int index = 1; index <= 56; index++) {
            manyMoves = GestureMapper.map(0.25f, 900.0f, 900.0f - (index * 10.0f), HEIGHT);
        }
        assertEquals(oneMove, manyMoves, 0.0001f);
    }

    @Test
    public void volumeQuantizationUsesTheFinalAbsoluteLevel() {
        assertEquals(15, GestureMapper.levelToStep(1.0f, 15));
        assertEquals(0, GestureMapper.levelToStep(0.0f, 15));
        assertEquals(8, GestureMapper.levelToStep(0.5f, 15));
    }

    @Test
    public void configuredSweepDistanceControlsTheFullRange() {
        assertEquals(1.0f, GestureMapper.map(0.0f, 900.0f, 400.0f, HEIGHT, 50), 0.0001f);
        assertEquals(1.0f, GestureMapper.map(0.0f, 900.0f, 100.0f, HEIGHT, 80), 0.0001f);
        assertEquals(100, GestureMapper.levelToStep(1.0f, 100));
        assertEquals(150, GestureMapper.levelToStep(1.0f, 150));
    }

    @Test
    public void quantizationKeepsTheRequestedSmallestStep() {
        assertEquals(1, GestureMapper.levelToStep(0.014f, 100));
        assertEquals(1, GestureMapper.levelToStep(0.006f, 150));
        assertEquals(149, GestureMapper.levelToStep(0.994f, 150));
    }

    @Test
    public void eachRateLimitedWindowUsesItsActualPixelDistance() {
        float firstWindow = GestureMapper.map(0.50f, 800.0f, 720.0f, HEIGHT, 80);
        float secondWindow = GestureMapper.map(firstWindow, 720.0f, 600.0f, HEIGHT, 80);
        assertEquals(0.60f, firstWindow, 0.0001f);
        assertEquals(0.75f, secondWindow, 0.0001f);
    }

    @Test
    public void quantizationAvoidsRepeatedWritesWithinTheSameStep() {
        int first = GestureMapper.levelToStep(0.500f, 100);
        int nearby = GestureMapper.levelToStep(0.504f, 100);
        assertEquals(first, nearby);
    }

    @Test
    public void aLaterWindowAccountsForAllMovementSinceTheLastWrite() {
        float level = GestureMapper.map(0.20f, 800.0f, 560.0f, HEIGHT, 80);
        assertEquals(0.50f, level, 0.0001f);
        assertEquals(50, GestureMapper.levelToStep(level, 100));
    }

    @Test
    public void oneQuantizedBrightnessStepStartsTheGesture() {
        assertEquals(false, GestureMapper.crossesStep(0.50f, 500.0f, 500.0f, HEIGHT, 80, 100));
        assertEquals(true, GestureMapper.crossesStep(0.50f, 500.0f, 492.0f, HEIGHT, 80, 100));
    }

    @Test
    public void oneQuantizedVolumeStepStartsTheGesture() {
        float startingLevel = 8.0f / 15.0f;
        assertEquals(false, GestureMapper.crossesStep(startingLevel, 500.0f, 500.0f,
                HEIGHT, 80, 15));
        assertEquals(true, GestureMapper.crossesStep(startingLevel, 500.0f, 473.0f,
                HEIGHT, 80, 15));
    }

    private static int legacyBrightness(int current, float moveDelta) {
        int delta = ((int) (-((moveDelta / HEIGHT) * 255.0f))) / 4;
        if (delta == 0) {
            delta = moveDelta < 0.0f ? 17 : -17;
        }
        return Math.max(1, Math.min(255, current + delta));
    }
}
