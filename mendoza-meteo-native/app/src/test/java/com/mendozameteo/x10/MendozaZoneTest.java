package com.mendozameteo.x10;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class MendozaZoneTest {
    @Test public void classifiesGranMendoza() {
        assertEquals(MendozaZone.Kind.GRAN_MENDOZA, MendozaZone.classify(-32.89, -68.84));
    }

    @Test public void classifiesPrecordilleraPiedmont() {
        assertEquals(MendozaZone.Kind.PRECORDILLERA_PIEDEMONTE, MendozaZone.classify(-32.95, -69.08));
    }

    @Test public void classifiesValleDeUco() {
        assertEquals(MendozaZone.Kind.VALLE_DE_UCO, MendozaZone.classify(-33.57, -69.02));
    }

    @Test public void classifiesSouth() {
        assertEquals(MendozaZone.Kind.SOUTH, MendozaZone.classify(-34.62, -68.33));
    }

    @Test public void eastRequiresLongerPersistence() {
        assertEquals(3, MendozaZone.classify(-33.00, -68.20).minimumPersistenceHours);
    }
}
