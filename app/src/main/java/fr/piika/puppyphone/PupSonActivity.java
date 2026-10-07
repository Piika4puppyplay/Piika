package fr.piika.puppyphone;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.quicksettings.TileService;
import android.content.ComponentName;
import android.widget.Toast;

/** PupSon : l'appli dans l'appli qui coupe / remet le son du fond vidéo. */
public class PupSonActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        boolean nowMuted = toggle(this);
        SharedPreferences p = getSharedPreferences(PupWallService.PREFS, MODE_PRIVATE);
        if (!"video".equals(p.getString("wall_type", ""))) {
            Toast.makeText(this, "🐾 Pas de fond vidéo actif — choisis-en un dans PupRéglages", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, nowMuted ? "🔇 PupSon : son du fond coupé" : "🔊 PupSon : son du fond remis", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    /** Inverse le muet. Renvoie le nouvel état (true = coupé). */
    static boolean toggle(Context c) {
        SharedPreferences p = c.getSharedPreferences(PupWallService.PREFS, Context.MODE_PRIVATE);
        boolean m = !p.getBoolean("wall_muted", false);
        p.edit().putBoolean("wall_muted", m).commit();
        apply(c);
        return m;
    }

    static void apply(Context c) {
        SharedPreferences p = c.getSharedPreferences(PupWallService.PREFS, Context.MODE_PRIVATE);
        if (PupWallService.running != null) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (PupWallService.running != null) PupWallService.running.applyState();
            });
        } else if ("video".equals(p.getString("wall_type", ""))) {
            try { c.startForegroundService(new Intent(c, PupWallService.class).setAction(PupWallService.ACT_START)); }
            catch (Exception ignored) { }
        }
        try { TileService.requestListeningState(c, new ComponentName(c, PupSonTile.class)); } catch (Exception ignored) { }
    }
}
