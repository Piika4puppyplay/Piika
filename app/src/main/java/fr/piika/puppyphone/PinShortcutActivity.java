package fr.piika.puppyphone;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.os.Bundle;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** Accepte les raccourcis qu'une appli veut épingler (ex. « Ajouter à l'écran d'accueil » d'un navigateur). */
public class PinShortcutActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            LauncherApps la = (LauncherApps) getSystemService(Context.LAUNCHER_APPS_SERVICE);
            LauncherApps.PinItemRequest r = la.getPinItemRequest(getIntent());
            if (r != null && r.isValid() && r.getRequestType() == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
                ShortcutInfo si = r.getShortcutInfo();
                if (si != null && r.accept()) {
                    SharedPreferences p = getSharedPreferences("pup_pins", MODE_PRIVATE);
                    JSONArray arr = new JSONArray(p.getString("pins", "[]"));
                    JSONObject o = new JSONObject();
                    o.put("pkg", si.getPackage());
                    o.put("sid", si.getId());
                    CharSequence l = si.getShortLabel();
                    o.put("label", l == null ? si.getId() : l.toString());
                    arr.put(o);
                    p.edit().putString("pins", arr.toString()).commit();
                    Toast.makeText(this, "🐾 Raccourci ajouté à ton PuppyPhone", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception ignored) { }
        finish();
    }
}
