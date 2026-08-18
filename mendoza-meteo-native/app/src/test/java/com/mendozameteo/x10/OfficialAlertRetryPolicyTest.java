package com.mendozameteo.x10;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class OfficialAlertRetryPolicyTest {
    @Test public void timeoutAndNetworkFailuresAreRetryable() {
        assertTrue(OfficialAlertRepository.retryableFailure(
                new WeatherException(WeatherException.Kind.TIMEOUT, "timeout")));
        assertTrue(OfficialAlertRepository.retryableFailure(
                new WeatherException(WeatherException.Kind.NETWORK, "network")));
        assertTrue(OfficialAlertRepository.retryableFailure(
                new WeatherException(WeatherException.Kind.HTTP_RETRYABLE, "503", 503)));
    }

    @Test public void permanentHttpAndInvalidPayloadDoNotLoop() {
        assertFalse(OfficialAlertRepository.retryableFailure(
                new WeatherException(WeatherException.Kind.HTTP_PERMANENT, "403", 403)));
        assertFalse(OfficialAlertRepository.retryableFailure(
                new WeatherException(WeatherException.Kind.INVALID_DATA, "schema")));
    }

    @Test public void suppressedCapFailureIsConsideredWhenApiFailureIsPermanent() {
        WeatherException api403 = new WeatherException(WeatherException.Kind.HTTP_PERMANENT, "api 403", 403);
        api403.addSuppressed(new WeatherException(WeatherException.Kind.TIMEOUT, "cap timeout"));
        assertTrue(OfficialAlertRepository.retryableFailure(api403));
    }
}
