package fr.piika.puppyphone;

/** PupDown — le projet original complet (comptes à rebours + tirelire, table de mixage, radio, jeux, launchpad),
 *  embarqué TEL QUEL dans PuppyPhone (fichiers identiques à la branche pupdown). */
public class DownActivity extends PupWebActivity {
    @Override String host() { return "pupdown.local"; }
    @Override String page() { return "pupdown/index.html"; }
    @Override String uiName() { return "DownUI"; }

    @Override void onReady() {
        super.onReady();
        PupDownFit.centerSheets(web);
    }
}
