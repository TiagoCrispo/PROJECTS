package com.mendozameteo.x10;

final class WeatherException extends Exception {
    private static final long serialVersionUID = 1L;

    enum Kind {
        OFFLINE,
        TIMEOUT,
        NETWORK,
        HTTP_RETRYABLE,
        HTTP_PERMANENT,
        INVALID_DATA,
        INTERRUPTED,
        CACHE
    }

    final Kind kind;
    final int httpStatus;

    WeatherException(Kind kind, String message) {
        this(kind, message, -1, null);
    }

    WeatherException(Kind kind, String message, Throwable cause) {
        this(kind, message, -1, cause);
    }

    WeatherException(Kind kind, String message, int httpStatus) {
        this(kind, message, httpStatus, null);
    }

    WeatherException(Kind kind, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    boolean retryable() {
        return kind == Kind.TIMEOUT || kind == Kind.NETWORK || kind == Kind.HTTP_RETRYABLE;
    }
}
