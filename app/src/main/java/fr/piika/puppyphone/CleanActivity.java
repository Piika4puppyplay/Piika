package fr.piika.puppyphone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** PupClean : raccourci qui ouvre l'accueil directement sur le grand nettoyage (le tri vit dans le lanceur). */
public class CleanActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            startActivity(new Intent(this, MainActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra("pup_open", "clean"));
        } catch (Exception ignored) { }
        finish();
    }
}
