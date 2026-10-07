package fr.piika.puppyphone;

import android.content.SharedPreferences;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Tuile des réglages rapides « PupSon ». */
public class PupSonTile extends TileService {
    @Override public void onStartListening() { refresh(); }

    @Override public void onClick() {
        PupSonActivity.toggle(this);
        refresh();
    }

    void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        SharedPreferences p = getSharedPreferences(PupWallService.PREFS, MODE_PRIVATE);
        boolean video = "video".equals(p.getString("wall_type", ""));
        boolean muted = p.getBoolean("wall_muted", false);
        t.setLabel("PupSon");
        t.setState(!video ? Tile.STATE_UNAVAILABLE : (muted ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE));
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle(!video ? "Pas de fond vidéo" : (muted ? "Son coupé" : "Son en lecture"));
        t.updateTile();
    }
}
