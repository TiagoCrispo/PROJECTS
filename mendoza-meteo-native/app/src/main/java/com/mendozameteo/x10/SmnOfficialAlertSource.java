package com.mendozameteo.x10;

import java.util.List;

final class SmnOfficialAlertSource {
    private final SmnCapAlertSource cap;
    private final SmnApiAlertSource api;

    SmnOfficialAlertSource(HttpTextTransport transport) {
        cap = new SmnCapAlertSource(transport);
        api = new SmnApiAlertSource(transport);
    }

    List<OfficialAlert> load(double latitude, double longitude, long nowMillis) throws Exception {
        Exception capFailure;
        try {
            return cap.load(latitude, longitude, nowMillis);
        } catch (Exception error) {
            capFailure = error;
        }
        try {
            return api.load(latitude, longitude, nowMillis);
        } catch (Exception apiFailure) {
            apiFailure.addSuppressed(capFailure);
            throw apiFailure;
        }
    }
}
