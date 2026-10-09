package fr.piika.puppyphone;

/** PupReprog — raccourci d'accueil qui ouvre PupRadio directement sur le Mode Reprog. */
public class ReprogActivity extends RadioActivity {
    @Override void onReady() {
        super.onReady();          // charge la radio + masque le bouton reprog in-app
        PupRadioFit.openReprog(web); // puis ouvre le panneau Reprog
    }
}
