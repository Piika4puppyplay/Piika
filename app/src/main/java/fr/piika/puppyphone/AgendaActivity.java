package fr.piika.puppyphone;

import android.webkit.JavascriptInterface;

/** PupAgenda — l'agenda de la niche (événements dans la mémoire de la page, rappels par notification). */
public class AgendaActivity extends PupWebActivity {
    @Override String host() { return "pupagenda.local"; }
    @Override String page() { return "agenda.html"; }
    @Override String uiName() { return "AgendaUI"; }
    @Override Object bridge() { return new Bridge(); }

    class Bridge extends Common {
        /** Liste des rappels à venir [{id, at, title, when}] calculée par la page. */
        @JavascriptInterface public void setReminders(String json) { AgendaRappel.set(AgendaActivity.this, json); }
    }
}
