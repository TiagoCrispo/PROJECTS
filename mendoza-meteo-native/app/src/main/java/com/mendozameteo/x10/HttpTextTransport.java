package com.mendozameteo.x10;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

final class HttpTextTransport {
    private static final int CONNECT_TIMEOUT_MS = 3500;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;

    String get(String endpoint, int maxAttempts) throws WeatherException {
        int attempts = Math.max(1, maxAttempts);
        WeatherException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new WeatherException(WeatherException.Kind.INTERRUPTED, "Official alert request interrupted");
            }
            try {
                return execute(endpoint, 0);
            } catch (WeatherException error) {
                last = error;
                if (!error.retryable() || attempt >= attempts) throw error;
                sleepBackoff(attempt);
            }
        }
        throw last != null ? last : new WeatherException(WeatherException.Kind.NETWORK, "Unknown official alert failure");
    }

    private String execute(String endpoint, int redirects) throws WeatherException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(endpoint);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new WeatherException(WeatherException.Kind.HTTP_PERMANENT, "Refusing non-HTTPS official endpoint");
            }
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/xml,text/xml,application/rss+xml,text/html;q=0.8,*/*;q=0.2");
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("User-Agent", "MendozaMeteoX10/6-native-dev");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                if (redirects >= MAX_REDIRECTS) {
                    throw new WeatherException(WeatherException.Kind.HTTP_PERMANENT, "Too many official-feed redirects", status);
                }
                String location = connection.getHeaderField("Location");
                if (location == null || location.trim().isEmpty()) {
                    throw new WeatherException(WeatherException.Kind.HTTP_PERMANENT, "Redirect without Location", status);
                }
                URL target = new URL(url, location);
                if (!"https".equalsIgnoreCase(target.getProtocol())) {
                    throw new WeatherException(WeatherException.Kind.HTTP_PERMANENT, "Refusing insecure official-feed redirect", status);
                }
                return execute(target.toString(), redirects + 1);
            }
            if (status < 200 || status >= 300) {
                WeatherException.Kind kind = RetryPolicy.isRetryableHttpStatus(status)
                        ? WeatherException.Kind.HTTP_RETRYABLE
                        : WeatherException.Kind.HTTP_PERMANENT;
                throw new WeatherException(kind, "Official feed HTTP " + status, status);
            }

            InputStream raw = new BufferedInputStream(connection.getInputStream());
            String encoding = connection.getContentEncoding();
            InputStream decoded = encoding != null && "gzip".equalsIgnoreCase(encoding)
                    ? new GZIPInputStream(raw) : raw;
            byte[] bytes;
            try (InputStream in = decoded; ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new WeatherException(WeatherException.Kind.INTERRUPTED, "Official feed read interrupted");
                    }
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_RESPONSE_BYTES) {
                        throw new WeatherException(WeatherException.Kind.INVALID_DATA, "Official feed response too large");
                    }
                }
                bytes = out.toByteArray();
            }
            if (bytes.length == 0) {
                throw new WeatherException(WeatherException.Kind.INVALID_DATA, "Empty official feed response");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (SocketTimeoutException timeout) {
            throw new WeatherException(WeatherException.Kind.TIMEOUT, "Official feed request timed out", timeout);
        } catch (WeatherException known) {
            throw known;
        } catch (IOException io) {
            if (Thread.currentThread().isInterrupted()) {
                throw new WeatherException(WeatherException.Kind.INTERRUPTED, "Official feed request interrupted", io);
            }
            throw new WeatherException(WeatherException.Kind.NETWORK, "Official feed network failure", io);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void sleepBackoff(int retryNumber) throws WeatherException {
        try {
            Thread.sleep(RetryPolicy.backoffMillis(retryNumber));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new WeatherException(WeatherException.Kind.INTERRUPTED, "Official feed retry interrupted", interrupted);
        }
    }
}
