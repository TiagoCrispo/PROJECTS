package com.mendozameteo.x10;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class HttpTextTransportTest {
    @Test public void authenticatedRedirectMayStayOnSameHttpsHost() {
        assertTrue(HttpTextTransport.authenticatedRedirectAllowed(
                "https://ws1.smn.gob.ar/v1/warning/alert/location/123",
                "/v1/warning/alert/location/456"));
    }

    @Test public void authenticatedRedirectCannotLeakJwtToDifferentHostOrHttp() {
        assertFalse(HttpTextTransport.authenticatedRedirectAllowed(
                "https://ws1.smn.gob.ar/v1/warning/alert/location/123",
                "https://example.com/steal"));
        assertFalse(HttpTextTransport.authenticatedRedirectAllowed(
                "https://ws1.smn.gob.ar/v1/warning/alert/location/123",
                "http://ws1.smn.gob.ar/insecure"));
    }
}
