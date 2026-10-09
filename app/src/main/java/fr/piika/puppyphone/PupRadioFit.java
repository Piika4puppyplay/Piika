package fr.piika.puppyphone;

import android.webkit.WebView;

/** Petits ajustements injectés dans PupRadio (sans modifier les fichiers du moteur). */
final class PupRadioFit {
    private PupRadioFit() { }

    /** Dans PuppyPhone, le Mode Reprog a sa propre icône d'accueil → on masque le bouton dans la radio. */
    static void hideReprogButton(WebView web) {
        if (web == null) return;
        web.evaluateJavascript("(function(){var s=document.getElementById('pupreprogfix');if(!s){s=document.createElement('style');s.id='pupreprogfix';document.head.appendChild(s);}s.textContent='#rpOpen{display:none!important}';})()", null);
    }

    /** Ouvre directement le Mode Reprog (pour le raccourci d'accueil PupReprog). */
    static void openReprog(WebView web) {
        if (web == null) return;
        web.evaluateJavascript("setTimeout(function(){var r=document.getElementById('rpOpen');if(r)r.click();},500);", null);
    }
}
