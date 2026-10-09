package fr.piika.puppyphone;

import android.webkit.WebView;

import org.json.JSONObject;

/** Petits correctifs d'affichage injectés dans le moteur PupDown (sans modifier ses fichiers). */
final class PupDownFit {
    private PupDownFit() { }

    /** Centre les feuilles (sheets) au lieu de les coller en bas, pour que le clavier ne cache jamais le champ (ex. dépôt tirelire). */
    static void centerSheets(WebView web) {
        if (web == null) return;
        String css = ".scrim{align-items:center!important;padding:14px!important}"
                + ".sheet{border-radius:22px!important;max-height:78vh!important;border-bottom:1px solid var(--line)!important}";
        web.evaluateJavascript("(function(){var s=document.getElementById('pupsheetfix');if(!s){s=document.createElement('style');s.id='pupsheetfix';document.head.appendChild(s);}s.textContent=" + JSONObject.quote(css) + ";})()", null);
    }
}
