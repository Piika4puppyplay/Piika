package fr.piika.puppyphone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

/** PupRéveil : l'heure est venue (ou le téléphone a redémarré / changé d'heure → on replanifie). */
public class ReveilReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (Reveil.ACT_FIRE.equals(i.getAction())) {
            int id = i.getIntExtra("id", -1);
            boolean snooze = i.getBooleanExtra("snooze", false);
            if (snooze) Reveil.sp(c).edit().remove("snoozeAt").remove("snoozeId").commit();
            JSONArray a = Reveil.list(c);
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.optJSONObject(k);
                if (o.optInt("id") != id) continue;
                JSONArray d = o.optJSONArray("days"); boolean any = false;
                if (d != null) for (int j = 0; j < d.length(); j++) any |= d.optBoolean(j);
                if (!any && !snooze) { try { o.put("on", false); } catch (Exception ignored) { } } // réveil unique : on le range
            }
            Reveil.save(c, a);
            Intent s = new Intent(c, ReveilService.class).putExtra("id", id);
            try { c.startForegroundService(s); } catch (Exception e) { try { c.startService(s); } catch (Exception ignored) { } }
        }
        Reveil.schedule(c);
        AgendaRappel.schedule(c);
    }
}
