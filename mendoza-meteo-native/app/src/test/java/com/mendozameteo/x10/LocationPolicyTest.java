package com.mendozameteo.x10;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocationPolicyTest {
    @Test public void staleDeviceFixIsRejected() {
        assertFalse(LocationPolicy.usableDeviceFix(LocationPolicy.DEVICE_MAX_AGE_MILLIS + 1L, 40f));
        assertFalse(LocationPolicy.usableDeviceFix(1_000L, 15_000f));
    }

    @Test public void veryRecentWeatherScaleFixCanSkipSensorWakeup() {
        assertTrue(LocationPolicy.reusableWithoutSensor(60_000L, 250f));
        assertFalse(LocationPolicy.reusableWithoutSensor(6L * 60L * 1000L, 250f));
    }

    @Test public void newerFixWinsWhenAgeDifferenceIsMaterial() {
        assertTrue(LocationPolicy.candidateIsBetter(30_000L, 700f, 45L * 60L * 1000L, 25f));
        assertFalse(LocationPolicy.candidateIsBetter(45L * 60L * 1000L, 10f, 30_000L, 500f));
    }

    @Test public void accuracyWinsWhenFixesHaveSimilarAge() {
        assertTrue(LocationPolicy.candidateIsBetter(60_000L, 30f, 90_000L, 180f));
    }

    @Test public void preciseSavedCoordinateIsNotReusedAfterApproximateDowngrade() {
        assertFalse(LocationPolicy.usableSaved(5L * 60L * 1000L, 30f, true, false));
        assertTrue(LocationPolicy.usableSaved(5L * 60L * 1000L, 2_000f, false, false));
    }

    @Test public void smallGpsJitterIsStabilizedAtWeatherScale() {
        assertTrue(LocationPolicy.shouldStabilize(-32.896748, -68.853418, 30f, 5L * 60L * 1000L,
                -32.897100, -68.853000, 35f));
    }

    @Test public void meaningfulMovementIsNotStabilized() {
        assertFalse(LocationPolicy.shouldStabilize(-32.896748, -68.853418, 30f, 5L * 60L * 1000L,
                -32.870000, -68.820000, 35f));
    }

    @Test public void savedLocationExpires() {
        assertFalse(LocationPolicy.usableSaved(LocationPolicy.SAVED_MAX_AGE_MILLIS + 1L, 100f, false, false));
    }
}
