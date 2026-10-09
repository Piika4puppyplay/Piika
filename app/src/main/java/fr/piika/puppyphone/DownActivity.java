package fr.piika.puppyphone;

/** PupDown — comptes à rebours (festival, vacances…) avec tirelire. Tout reste sur le téléphone. */
public class DownActivity extends PupWebActivity {
    @Override String host() { return "pupdown.local"; }
    @Override String page() { return "down.html"; }
    @Override String uiName() { return "DownUI"; }
}
