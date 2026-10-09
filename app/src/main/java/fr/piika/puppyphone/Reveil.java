package fr.piika.puppyphone;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * PupRéveil : stockage et planification des réveils.
 * Un réveil = {id, h, m, days:[lun..dim booléens], label, on, sound, vib, snooze (min), douceur (crescendo)}.
 * Seul le prochain réveil est confié à Android (setAlarmClock : exact, même en économie d'énergie, et visible par One UI).
 */
final class Reveil {
    private Reveil() { }
    static final String ACT_FIRE = "fr.piika.puppyphone.REVEIL_FIRE";
    static final String[][] SONS = {
            {"wouf", "Wouf wouf du matin"}, {"jappe", "Chiot impatient"}, {"grelot", "Grelot de la médaille"},
            {"couine", "Câlin qui couine"}, {"systeme", "Sonnerie du téléphone"}};

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("pupreveil", Context.MODE_PRIVATE); }
    static JSONArray list(Context c) { try { return new JSONArray(sp(c).getString("alarms", "[]")); } catch (Exception e) { return new JSONArray(); } }
    static void save(Context c, JSONArray a) { sp(c).edit().putString("alarms", a.toString()).commit(); }
    static JSONObject find(Context c, int id) { JSONArray a = list(c); for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i).optInt("id") == id) return a.optJSONObject(i); return null; }

    /** Prochaine sonnerie (ms) d'un réveil après « after ». */
    static long next(JSONObject a, long after) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(after);
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        c.set(Calendar.HOUR_OF_DAY, a.optInt("h")); c.set(Calendar.MINUTE, a.optInt("m"));
        JSONArray d = a.optJSONArray("days");
        boolean any = false;
        if (d != null) for (int i = 0; i < d.length(); i++) any |= d.optBoolean(i);
        for (int k = 0; k < 8; k++) {
            if (c.getTimeInMillis() > after) {
                if (!any) return c.getTimeInMillis();
                int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7; // lundi = 0
                if (d.optBoolean(dow)) return c.getTimeInMillis();
            }
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return Long.MAX_VALUE;
    }

    /** {at, id, snooze} du prochain réveil (ou at = 0). */
    static long[] upcoming(Context c) {
        long now = System.currentTimeMillis(), best = Long.MAX_VALUE; long id = -1, snooze = 0;
        JSONArray a = list(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (!o.optBoolean("on", true)) continue;
            long t = next(o, now + 1000);
            if (t < best) { best = t; id = o.optInt("id"); }
        }
        long sz = sp(c).getLong("snoozeAt", 0);
        if (sz > now && sz < best) { best = sz; id = sp(c).getInt("snoozeId", -1); snooze = 1; }
        return best == Long.MAX_VALUE ? new long[]{0, -1, 0} : new long[]{best, id, snooze};
    }

    static PendingIntent firePI(Context c, int id, boolean snooze) {
        Intent i = new Intent(c, ReveilReceiver.class).setAction(ACT_FIRE).putExtra("id", id).putExtra("snooze", snooze);
        return PendingIntent.getBroadcast(c, 4200, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Confie le prochain réveil à Android. */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        long[] u = upcoming(c);
        am.cancel(firePI(c, 0, false));
        sp(c).edit().putLong("nextAt", u[0]).putInt("nextId", (int) u[1]).apply();
        if (u[0] <= 0) return;
        PendingIntent show = PendingIntent.getActivity(c, 4201, new Intent(c, ReveilActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        try { am.setAlarmClock(new AlarmManager.AlarmClockInfo(u[0], show), firePI(c, (int) u[1], u[2] == 1)); }
        catch (SecurityException e) { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, u[0], firePI(c, (int) u[1], u[2] == 1)); }
    }

    static void snooze(Context c, int id) {
        JSONObject a = find(c, id);
        int min = a == null ? 5 : a.optInt("snooze", 5);
        sp(c).edit().putLong("snoozeAt", System.currentTimeMillis() + min * 60000L).putInt("snoozeId", id).commit();
        schedule(c);
    }

    /** « 07:30 » du prochain réveil, pour la Veille / la Sieste ("" si aucun). */
    static String nextText(Context c) {
        long at = sp(c).getLong("nextAt", 0);
        if (at <= System.currentTimeMillis()) return "";
        Calendar k = Calendar.getInstance(); k.setTimeInMillis(at);
        return String.format(java.util.Locale.FRANCE, "%02d:%02d", k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE));
    }
}
