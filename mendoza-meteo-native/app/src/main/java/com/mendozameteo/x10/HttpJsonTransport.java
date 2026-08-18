package com.mendozameteo.x10;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

final class HttpJsonTransport {
    private static final int CONNECT_TIMEOUT_MS = 3500;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    JSONObject get(String endpoint, int maxAttempts) throws WeatherException {
        int attempts = Math.max(1, maxAttempts);
        WeatherException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new WeatherException(WeatherException.Kind.INTERRUPTED, "Network request interrupted");
            }
            try {
                return execute(endpoint);
            } catch (WeatherException error) {
                last = error;
                if (!error.retryable() || attempt >= attempts) throw error;
                sleepBackoff(attempt);
            }
        }
        throw last != null ? last : new WeatherException(WeatherException.Kind.NETWORK, "Unknown network failure");
    }

    private JSONObject execute(String endpoint) throws WeatherException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new WeatherException(WeatherException.Kind.HTTP_PERMANENT, "Refusing non-HTTPS endpoint");
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("User-Agent", "MendozaMeteoX10/6-native-dev");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                WeatherException.Kind kind = RetryPolicy.isRetryableHttpStatus(status)
                        ? WeatherException.Kind.HTTP_RETRYABLE
                        : WeatherException.Kind.HTTP_PERMANENT;
                throw new WeatherException(kind, "HTTP " + status, status);
            }
            String contentType = connection.getContentType();
            if (contentType != null && !contentType.toLowerCase(Locale.US).contains("json")) {
                throw new WeatherException(WeatherException.Kind.INVALID_DATA, "Unexpected content type: " + contentType);
            }
            InputStream raw = new BufferedInputStream(connection.getInputStream());
            String encoding = connection.getContentEncoding();
            InputStream decoded = encoding != null && "gzip".equalsIgnoreCase(encoding)
                    ? new GZIPInputStream(raw)
                    : raw;
            byte[] bytes;
            try (InputStream in = decoded; ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new WeatherException(WeatherException.Kind.INTERRUPTED, "Network read interrupted");
                    }
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_RESPONSE_BYTES) {
                        throw new WeatherException(WeatherException.Kind.INVALID_DATA, "Weather response too large");
                    }
                }
                bytes = out.toByteArray();
            }
            if (bytes.length == 0) {
                throw new WeatherException(WeatherException.Kind.INVALID_DATA, "Empty weather response");
            }
            try {
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (JSONException malformed) {
                throw new WeatherException(WeatherException.Kind.INVALID_DATA, "Malformed weather JSON", malformed);
            }
        } catch (SocketTimeoutException timeout) {
            throw new WeatherException(WeatherException.Kind.TIMEOUT, "Weather request timed out", timeout);
        } catch (WeatherException known) {
            throw known;
        } catch (IOException io) {
            if (Thread.currentThread().isInterrupted()) {
                throw new WeatherException(WeatherException.Kind.INTERRUPTED, "Network request interrupted", io);
            }
            throw new WeatherException(WeatherException.Kind.NETWORK, "Weather network failure", io);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void sleepBackoff(int retryNumber) throws WeatherException {
        try {
            Thread.sleep(RetryPolicy.backoffMillis(retryNumber));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WeatherException(WeatherException.Kind.INTERRUPTED, "Retry backoff interrupted", interrupted);
        }
    }
}
