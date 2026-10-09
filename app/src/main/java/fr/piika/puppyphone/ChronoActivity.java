package fr.piika.puppyphone;

/** PupDown (version propre, migrée) : comptes à rebours + tirelire uniquement.
 *  Extrait tel quel du moteur original (script chrono isolé), sans la radio/mixage/jeux/launchpad. */
public class ChronoActivity extends PupWebActivity {
    @Override String host() { return "pupchrono.local"; }
    @Override String page() { return "pd/chrono/index.html"; }
    @Override String uiName() { return "DownUI"; }

    @Override void onReady() {
        super.onReady();
        PupDownFit.centerSheets(web);
    }
}
