package com.mendozameteo.x10;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.EnumSet;

public final class NotificationUpdateWorker extends Worker {
    public NotificationUpdateWorker(Context appContext, WorkerParameters params) {
        super(appContext, params);
    }

    @Override public Result doWork() {
        Context app = getApplicationContext();
        if (!WeatherNotifier.canPost(app)) return Result.success();

        long now = System.currentTimeMillis();
        NotificationLocation.Point point = NotificationLocation.load(app, now);
        NotificationStateStore state = new NotificationStateStore(app);

        // Official state is processed before the general forecast so a slow/failing model fetch
        // can never delay an SMN/Mendoza notification that was already retrieved successfully.
        OfficialAlertRepository.Result official = new OfficialAlertRepository(app).load(point.lat, point.lon);
        for (NotificationPolicy.PreviousOfficial removed : state.pruneOfficial(official, now)) {
            WeatherNotifier.cancel(app, removed.notificationId);
        }

        for (OfficialAlert alert : official.alerts) {
            NotificationPolicy.PreviousOfficial previous = state.findMatchingOfficial(alert);
            NotificationPolicy.OfficialChange change = NotificationPolicy.officialChange(previous, alert, now);
            if (change == NotificationPolicy.OfficialChange.NONE) continue;
            int existingId = previous == null ? 0 : previous.notificationId;
            int notificationId = WeatherNotifier.notifyOfficial(app, alert, change, point.label(), now, existingId);
            if (notificationId == 0) {
                // If the destination channel was disabled, never leave an older lower/higher
                // severity card visible as if it were still current.
                if (previous != null) {
                    WeatherNotifier.cancel(app, previous.notificationId);
                    state.removeOfficial(previous);
                }
                continue;
            }
            if (previous != null) {
                if (previous.notificationId != notificationId) WeatherNotifier.cancel(app, previous.notificationId);
                state.removeOfficial(previous);
            }
            state.markOfficial(alert, notificationId, now);
        }

        String forecastCacheKey = point.personalized ? "local" : "utn";
        WeatherRepository.Result weather = new WeatherRepository(app).load(forecastCacheKey, point.lat, point.lon);
        if (weather.isSuccess() && weather.safeForAlerts()) {
            AlertEngine.Report report = AlertEngine.analyze(weather.forecast);
            boolean hasThunderstorm = false;
            for (AlertEngine.Event event : report.events) {
                if (event.kind == AlertEngine.Kind.THUNDERSTORM) {
                    hasThunderstorm = true;
                    break;
                }
            }

            ArrayList<AlertEngine.Event> retained = new ArrayList<>();
            EnumSet<AlertEngine.Kind> retainedKinds = EnumSet.noneOf(AlertEngine.Kind.class);
            for (AlertEngine.Event event : report.events) {
                if (retained.size() >= 2) break;
                if (event.kind == AlertEngine.Kind.RAIN && hasThunderstorm) continue;
                if (event.severity.rank < AlertEngine.Severity.IMPORTANT.rank) continue;
                if (NotificationPolicy.officialCoversX10(official.alerts, event)) continue;
                if (retainedKinds.add(event.kind)) retained.add(event);
            }

            // A fresh evaluation is authoritative for X10 cards. Remove both the Android card
            // and its cooldown state when the event resolved, became low severity, or is now
            // covered by an overlapping official alert. Stale/failed weather never clears them.
            for (AlertEngine.Kind kind : AlertEngine.Kind.values()) {
                if (!retainedKinds.contains(kind)) {
                    WeatherNotifier.cancelX10(app, kind);
                    state.clearX10(kind);
                }
            }

            for (AlertEngine.Event event : retained) {
                AlertCooldownPolicy.Previous previous = state.loadX10(event.kind);
                if (!NotificationPolicy.shouldNotifyX10(previous, event, now)) continue;
                int notificationId = WeatherNotifier.notifyX10(app, event, point.label(), now);
                if (notificationId != 0) state.markX10(event, now);
            }
        }

        if (official.shouldRetryBackground() && getRunAttemptCount() < 1) return Result.retry();
        return Result.success();
    }
}
