package fr.piika.puppyphone;

/** PupRadio — la radio PupDown (mix génératif en direct + lecteur « Ma musique » + mode Reprog),
 *  extraite telle quelle du moteur original (script radio isolé, son propre contexte audio). */
public class RadioActivity extends PupWebActivity {
    @Override String host() { return "pupradio.local"; }
    @Override String page() { return "pd/radio/index.html"; }
    @Override String uiName() { return "RadioUI"; }

    @Override void onReady() {
        super.onReady();
        // Dans PuppyPhone, le Reprog a son icône d'accueil dédiée (PupReprog) → on retire le bouton de la radio.
        PupRadioFit.hideReprogButton(web);
    }
}
