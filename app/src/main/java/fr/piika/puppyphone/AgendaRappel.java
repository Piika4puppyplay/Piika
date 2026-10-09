package fr.piika.puppyphone;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

/** Rappels de PupAgenda : une alarme exacte pour le prochain rappel, puis notification « 📅 … ». */
public class AgendaRappel extends BroadcastReceiver {
    static final String CH = "pupagenda";
    static SharedPreferences sp(Context c) { return c.getSharedPreferences("pupagenda", Context.MODE_PRIVATE); }

    static void set(Context c, String json) { sp(c).edit().putString("rem", json).apply(); schedule(c); }

    static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 4400, new Intent(c, AgendaRappel.class).setAction("fr.piika.puppyphone.AGENDA"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        am.cancel(pi(c));
        long now = System.currentTimeMillis(), best = Long.MAX_VALUE;
        String done = sp(c).getString("done", "");
        try {
            JSONArray a = new JSONArray(sp(c).getString("rem", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i); long at = o.optLong("at");
                if (at > now - 60_000 && at < best && !done.contains("|" + o.optString("id") + "@" + at + "|")) best = at;
            }
        } catch (Exception ignored) { }
        if (best == Long.MAX_VALUE) return;
        try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Math.max(best, now + 1000), pi(c)); }
        catch (SecurityException e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, Math.max(best, now + 1000), pi(c)); }
    }

    @Override public void onReceive(Context c, Intent i) {
        long now = System.currentTimeMillis();
        StringBuilder done = new StringBuilder(sp(c).getString("done", ""));
        if (done.length() > 6000) done = new StringBuilder(done.substring(done.length() - 3000));
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "PupAgenda — rappels", NotificationManager.IMPORTANCE_HIGH));
        try {
            JSONArray a = new JSONArray(sp(c).getString("rem", "[]"));
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k); long at = o.optLong("at");
                String key = "|" + o.optString("id") + "@" + at + "|";
                if (at > now + 30_000 || at < now - 6 * 3600_000L || done.indexOf(key) >= 0) continue;
                done.append(key);
                PendingIntent open = PendingIntent.getActivity(c, 4401, new Intent(c, AgendaActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Notification n = new Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_paw)
                        .setContentTitle("📅 " + o.optString("title", "Événement"))
                        .setContentText(o.optString("when", "C'est bientôt 🐾"))
                        .setColor(0xFFFF3FA4).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).setContentIntent(open).build();
                try { nm.notify(4500 + (int) (Math.abs((key).hashCode()) % 400), n); } catch (Exception ignored) { }
                PupSons.play(c, "grelot");
            }
        } catch (Exception ignored) { }
        sp(c).edit().putString("done", done.toString()).apply();
        schedule(c);
    }
}
