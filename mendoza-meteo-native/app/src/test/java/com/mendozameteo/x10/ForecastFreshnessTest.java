package com.mendozameteo.x10;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ForecastFreshnessTest {
    private static final long NOW=1_000_000_000L;
    @Test public void classifiesAllFreshnessStates(){
        assertEquals(ForecastFreshness.State.FRESH,ForecastFreshness.classify(NOW-ForecastFreshness.FRESH_MILLIS,NOW));
        assertEquals(ForecastFreshness.State.STALE,ForecastFreshness.classify(NOW-ForecastFreshness.FRESH_MILLIS-1,NOW));
        assertEquals(ForecastFreshness.State.VERY_STALE,ForecastFreshness.classify(NOW-ForecastFreshness.STALE_MILLIS-1,NOW));
        assertEquals(ForecastFreshness.State.EXPIRED,ForecastFreshness.classify(NOW-ForecastFreshness.VERY_STALE_MILLIS-1,NOW));
    }
    @Test public void onlyFreshDataCanDriveAlerts(){
        assertTrue(ForecastFreshness.safeForAlerts(ForecastFreshness.State.FRESH));
        assertFalse(ForecastFreshness.safeForAlerts(ForecastFreshness.State.STALE));
        assertFalse(ForecastFreshness.safeForAlerts(ForecastFreshness.State.VERY_STALE));
        assertFalse(ForecastFreshness.safeForAlerts(ForecastFreshness.State.EXPIRED));
    }
    @Test public void clockSkewDoesNotCreateNegativeAge(){assertEquals(0L,ForecastFreshness.ageMillis(NOW+1000L,NOW));}
}
