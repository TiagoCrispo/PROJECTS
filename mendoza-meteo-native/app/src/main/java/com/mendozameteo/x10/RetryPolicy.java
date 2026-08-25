package com.mendozameteo.x10;

final class RetryPolicy {
    private RetryPolicy() { }

    static boolean isRetryableHttpStatus(int status) {
        return status == 408 || status == 425 || status == 429 || (status >= 500 && status <= 599);
    }

    static long backoffMillis(int retryNumber) {
        if (retryNumber <= 0) return 0L;
        long value = 250L << Math.min(3, retryNumber - 1);
        return Math.min(1500L, value);
    }
}
