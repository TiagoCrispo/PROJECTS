package com.mendozameteo.x10;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RetryPolicyTest {
    @Test public void retriesOnlyTransientHttpFailures() {
        assertTrue(RetryPolicy.isRetryableHttpStatus(408));
        assertTrue(RetryPolicy.isRetryableHttpStatus(429));
        assertTrue(RetryPolicy.isRetryableHttpStatus(500));
        assertTrue(RetryPolicy.isRetryableHttpStatus(503));
        assertFalse(RetryPolicy.isRetryableHttpStatus(400));
        assertFalse(RetryPolicy.isRetryableHttpStatus(404));
        assertFalse(RetryPolicy.isRetryableHttpStatus(499));
    }
    @Test public void backoffIsBounded() {
        assertEquals(250L, RetryPolicy.backoffMillis(1));
        assertEquals(500L, RetryPolicy.backoffMillis(2));
        assertEquals(1000L, RetryPolicy.backoffMillis(3));
        assertEquals(1500L, RetryPolicy.backoffMillis(4));
        assertEquals(1500L, RetryPolicy.backoffMillis(9));
    }
}
