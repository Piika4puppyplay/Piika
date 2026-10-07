package fr.piika.puppyphone;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Tuile des réglages rapides : ouvre PupTasks. */
public class PupTasksTile extends TileService {
    @Override public void onStartListening() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(Tile.STATE_ACTIVE);
        t.setLabel("PupTasks");
        if (Build.VERSION.SDK_INT >= 29) t.setSubtitle("Dernières balades");
        t.updateTile();
    }

    @Override public void onClick() {
        Intent i = new Intent(this, TasksActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
        else startActivityAndCollapse(i);
    }
}
