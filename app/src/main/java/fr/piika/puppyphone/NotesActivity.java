package fr.piika.puppyphone;

/** PupNotes — le carnet de croquettes (notes et listes, rangées dans la mémoire de la page, sauvegardées par « Enterrer un os »). */
public class NotesActivity extends PupWebActivity {
    @Override String host() { return "pupnotes.local"; }
    @Override String page() { return "notes.html"; }
    @Override String uiName() { return "NotesUI"; }
}
