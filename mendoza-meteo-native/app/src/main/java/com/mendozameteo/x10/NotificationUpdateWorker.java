package com.mendozameteo.x10;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

public final class NotificationUpdateWorker extends Worker {
    public NotificationUpdateWorker(Context appContext, WorkerParameters params) {
        super(appContext, params);
    }

    @Override public Result doWork() {
        Context app = getApplicationContext();
        if (!WeatherNotifier.canPost(app)) return Result.success();

        long now = System.currentTimeMillis();
        NotificationLocation.Point point = NotificationLocation.load(app, now);
        OfficialAlertRepository.Result official = new OfficialAlertRepository(app).load(point.lat, point.lon);
        WeatherRepository.Result weather = new WeatherRepository(app).load("local", point.lat, point.lon);
        NotificationStateStore state = new NotificationStateStore(app);

        for (NotificationPolicy.PreviousOfficial removed : state.pruneOfficial(official, now)) {
            WeatherNotifier.cancel(app, removed.notificationId);
        }

        for (OfficialAlert alert : official.alerts) {
            NotificationPolicy.PreviousOfficial previous = state.findMatchingOfficial(alert);
            NotificationPolicy.OfficialChange change = NotificationPolicy.officialChange(previous, alert, now);
            if (change == NotificationPolicy.OfficialChange.NONE) continue;
            int notificationId = WeatherNotifier.notifyOfficial(app, alert, change, point.label(), now);
            if (notificationId == 0) continue;
            if (previous != null) {
                if (previous.notificationId != notificationId) WeatherNotifier.cancel(app, previous.notificationId);
                state.removeOfficial(previous);
            }
            state.markOfficial(alert, notificationId, now);
        }

        if (weather.isSuccess() && weather.safeForAlerts()) {
            AlertEngine.Report report = AlertEngine.analyze(weather.forecast);
            boolean hasThunderstorm = false;
            for (AlertEngine.Event event : report.events) {
                if (event.kind == AlertEngine.Kind.THUNDERSTORM) {
                    hasThunderstorm = true;
                    break;
                }
            }
            int shown = 0;
            for (AlertEngine.Event event : report.events) {
                if (shown >= 2) break;
                if (event.kind == AlertEngine.Kind.RAIN && hasThunderstorm) continue;
                if (NotificationPolicy.officialCoversX10(official.alerts, event)) continue;
                AlertCooldownPolicy.Previous previous = state.loadX10(event.kind);
                if (!NotificationPolicy.shouldNotifyX10(previous, event, now)) continue;
                int notificationId = WeatherNotifier.notifyX10(app, event, point.label(), now);
                if (notificationId != 0) {
                    state.markX10(event, now);
                    shown++;
                }
            }
        }

        boolean officialUnavailable = !official.anyOfficialSourceAvailable() && !official.usedCache;
        if (officialUnavailable && !weather.isSuccess() && weather.shouldRetryBackground() && getRunAttemptCount() < 1) {
            return Result.retry();
        }
        return Result.success();
    }
}
