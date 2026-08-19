package com.mendozameteo.x10;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WidgetContextPolicyTest {
    private static NotificationLocation.Point personalized(double lat, double lon) {
        return new NotificationLocation.Point(lat, lon, true, 1_000L);
    }

    private static NotificationLocation.Point utn() {
        return new NotificationLocation.Point(-32.896748, -68.853418, false, Long.MAX_VALUE);
    }

    @Test public void cacheKeySeparatesPersonalizedFromUtn() {
        assertEquals("local", WidgetContextPolicy.forecastCacheKey(personalized(-32.89, -68.84)));
        assertEquals("utn", WidgetContextPolicy.forecastCacheKey(utn()));
    }

    @Test public void utnCacheIsReusableOnlyForUtnContext() {
        assertTrue(WidgetContextPolicy.sameContext(false, -32.896748, -68.853418, utn()));
        assertFalse(WidgetContextPolicy.sameContext(false, -32.896748, -68.853418,
                personalized(-32.89, -68.84)));
    }

    @Test public void personalizedCacheRejectsMaterialMove() {
        NotificationLocation.Point capital = personalized(-32.8895, -68.8458);
        assertTrue(WidgetContextPolicy.sameContext(true, -32.8900, -68.8460, capital));
        assertFalse(WidgetContextPolicy.sameContext(true, -34.6177, -68.3301, capital));
    }

    @Test public void invalidCachedCoordinatesAreRejected() {
        assertFalse(WidgetContextPolicy.sameContext(true, Double.NaN, -68.84,
                personalized(-32.89, -68.84)));
    }
}
