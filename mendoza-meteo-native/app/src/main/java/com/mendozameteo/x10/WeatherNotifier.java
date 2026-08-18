package com.mendozameteo.x10;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class WeatherNotifier {
    static final String CHANNEL_OFFICIAL_URGENT = "official_urgent_v1";
    static final String CHANNEL_OFFICIAL = "official_alerts_v1";
    static final String CHANNEL_X10 = "x10_signals_v1";
    private static final String GROUP_OFFICIAL = "mendoza_meteo_official";
    private static final String GROUP_X10 = "mendoza_meteo_x10";
    private static final long NO_EXPIRY_STALE_TIMEOUT_MILLIS = 36L * 60L * 60L * 1000L;

    private WeatherNotifier() { }

    static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        NotificationChannel urgent = new NotificationChannel(CHANNEL_OFFICIAL_URGENT,
                "Alertas oficiales urgentes", NotificationManager.IMPORTANCE_HIGH);
        urgent.setDescription("Alertas oficiales meteorológicas naranjas y rojas.");
        urgent.enableVibration(true);

        NotificationChannel official = new NotificationChannel(CHANNEL_OFFICIAL,
                "Alertas oficiales", NotificationManager.IMPORTANCE_DEFAULT);
        official.setDescription("Avisos oficiales del SMN y Contingencias Climáticas de Mendoza.");

        NotificationChannel x10 = new NotificationChannel(CHANNEL_X10,
                "Señales meteorológicas X10", NotificationManager.IMPORTANCE_DEFAULT);
        x10.setDescription("Señales heurísticas importantes de lluvia, tormenta y posible Zonda. No son alertas oficiales.");

        manager.createNotificationChannel(urgent);
        manager.createNotificationChannel(official);
        manager.createNotificationChannel(x10);
    }

    static boolean canPost(Context context) {
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && manager.areNotificationsEnabled();
    }

    static int notifyOfficial(Context context, OfficialAlert alert,
                              NotificationPolicy.OfficialChange change, String locationLabel,
                              long nowMillis, int existingNotificationId) {
        if (alert == null || change == NotificationPolicy.OfficialChange.NONE || !canPost(context)) return 0;
        createChannels(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return 0;

        boolean urgent = alert.level == OfficialAlert.Level.ORANGE || alert.level == OfficialAlert.Level.RED;
        String channel = urgent ? CHANNEL_OFFICIAL_URGENT : CHANNEL_OFFICIAL;
        if (!channelCanPost(manager, channel)) return 0;
        String level = alert.level == OfficialAlert.Level.UNKNOWN ? "" : alert.level.label;
        String source = alert.sourceLabel();
        String event = alert.event.isEmpty() ? alert.title() : alert.event;
        String title = source + (level.isEmpty() ? "" : " · " + level) + " · " + clip(event, 80);
        String prefix;
        if (change == NotificationPolicy.OfficialChange.ESCALATION) prefix = "Escalada oficial. ";
        else if (change == NotificationPolicy.OfficialChange.DEESCALATION) prefix = "Nivel oficial reducido. ";
        else if (change == NotificationPolicy.OfficialChange.IMPORTANT_UPDATE) prefix = "Actualización oficial. ";
        else prefix = "";
        String body = prefix + buildOfficialBody(alert, locationLabel, nowMillis);

        Notification.Builder builder = builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(clip(body, 150))
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openAppIntent(context))
                .setCategory(Notification.CATEGORY_EVENT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(change == NotificationPolicy.OfficialChange.DEESCALATION)
                .setGroup(GROUP_OFFICIAL)
                .setWhen(nowMillis)
                .setShowWhen(true)
                .setPriority(urgent ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);
        if (Build.VERSION.SDK_INT >= 26) {
            long timeout;
            if (alert.expiresMillis > nowMillis) {
                timeout = alert.expiresMillis - nowMillis;
            } else {
                long base = alert.sentMillis > 0L ? alert.sentMillis : nowMillis;
                long staleAt = base + NO_EXPIRY_STALE_TIMEOUT_MILLIS;
                if (staleAt <= nowMillis) return 0;
                timeout = staleAt - nowMillis;
            }
            builder.setTimeoutAfter(timeout);
        }

        int id = existingNotificationId != 0
                ? existingNotificationId
                : notificationId("official|" + alert.source.name() + "|" + alert.id + "|" + alert.event + "|" + alert.startIso);
        manager.notify(id, builder.build());
        return id;
    }

    static int notifyX10(Context context, AlertEngine.Event event, String locationLabel, long nowMillis) {
        if (event == null || !canPost(context)) return 0;
        createChannels(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !channelCanPost(manager, CHANNEL_X10)) return 0;
        String title = "X10 · " + event.severity.label + " · " + event.title();
        String body = event.detailText() + " · " + locationLabel
                + ". Heurística X10: no es una alerta oficial del SMN.";
        Notification.Builder builder = builder(context, CHANNEL_X10)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(clip(body, 150))
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openAppIntent(context))
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setGroup(GROUP_X10)
                .setWhen(nowMillis)
                .setShowWhen(true)
                .setPriority(Notification.PRIORITY_DEFAULT);
        if (Build.VERSION.SDK_INT >= 26) {
            long end = eventEndMillis(event.endExclusiveIso);
            if (end > nowMillis) builder.setTimeoutAfter(end - nowMillis);
        }
        int id = x10NotificationId(event.kind);
        manager.notify(id, builder.build());
        return id;
    }

    static void cancelX10(Context context, AlertEngine.Kind kind) {
        if (kind == null) return;
        cancel(context, x10NotificationId(kind));
    }

    static void cancel(Context context, int notificationId) {
        if (notificationId == 0) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(notificationId);
    }

    private static boolean channelCanPost(NotificationManager manager, String channelId) {
        if (Build.VERSION.SDK_INT < 26) return true;
        NotificationChannel channel = manager.getNotificationChannel(channelId);
        return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static Notification.Builder builder(Context context, String channel) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(context, channel);
        return new Notification.Builder(context);
    }

    private static PendingIntent openAppIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 71, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String buildOfficialBody(OfficialAlert alert, String locationLabel, long nowMillis) {
        StringBuilder body = new StringBuilder();
        String timing = alert.timingText(nowMillis);
        if (!timing.isEmpty()) body.append(timing);
        if (!alert.area.isEmpty()) append(body, alert.area);
        if (!alert.description.isEmpty()) append(body, clip(alert.description, 650));
        if (!alert.instruction.isEmpty()) append(body, clip(alert.instruction, 450));
        if (locationLabel != null && !locationLabel.isEmpty()) append(body, locationLabel);
        if (body.length() == 0) body.append(alert.title());
        return body.toString();
    }

    private static long eventEndMillis(String iso) {
        if (iso == null || iso.length() < 16) return -1L;
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        format.setLenient(false);
        format.setTimeZone(WeatherClient.MENDOZA_TZ);
        try {
            Date date = format.parse(iso.substring(0, 16));
            return date == null ? -1L : date.getTime();
        } catch (ParseException ignored) {
            return -1L;
        }
    }

    private static int x10NotificationId(AlertEngine.Kind kind) {
        return 700_000 + kind.ordinal();
    }

    private static void append(StringBuilder target, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (target.length() > 0) target.append(" · ");
        target.append(value.trim());
    }

    private static int notificationId(String stable) {
        int value = stable == null ? 1 : stable.hashCode() & 0x7fffffff;
        return value == 0 ? 1 : value;
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("\\s+", " ");
        return clean.length() <= max ? clean : clean.substring(0, Math.max(0, max - 1)) + "…";
    }
}
