/*
 * Passerelle d'aide + fenêtre de secours indépendante « Centre d'aide
 * indisponible ». Voir aide-passerelle.h.
 *
 * La décision « ouvrir la page / afficher le secours » vient du cœur Rust
 * testé pupdate_aide_raccourci() (pupdate/aide.rs, PR #62). Ce module ne fait
 * que : (1) détecter l'état de PuppyHelp, (2) appeler le cœur, (3) selon sa
 * réponse, lancer PuppyHelp ou dessiner la fenêtre de secours — laquelle est
 * entièrement contenue ici, dans le bureau, et ne charge AUCUN morceau de
 * PuppyHelp : elle s'affiche donc même quand le paquet est absent ou abîmé.
 */
#include "aide-passerelle.h"
#include "bureau.h"
#include "theme.h"
#include "programmes.h"
#include "puppy.h"
#include <stdio.h>
#include <string.h>

/* Cœur Rust confiné (pupdate/ffi.rs) : 1 = ouvrir la page, 0 = secours. */
extern int pupdate_aide_raccourci(int etat, int page_id_valide);

/* --------------------------------------------------------------------------
 *  Détection de l'état du centre d'aide.
 *  Honnête : on détecte ce qu'on peut constater côté bureau sans privilège —
 *  présence du paquet, marqueur de désactivation, exécutable manquant. La
 *  péremption (catalogue) et l'intégrité signée (manifeste) sont vérifiées
 *  ailleurs ; quand ces états surviennent, le cœur les traite comme « secours »
 *  de la même façon. aide_est_facultative() reste vrai : jamais de blocage.
 * ------------------------------------------------------------------------- */
aide_etat_t aide_etat(void)
{
    programme_info p;
    if (programme_trouver(AIDE_ID, &p) < 0)
        return AIDE_DESINSTALLE;               /* pas installé */

    /* Marqueur de désactivation posé par le gestionnaire d'applications
     * (choix explicite de l'utilisateur). Convention : un fichier « .desactive »
     * à la racine du dossier du programme. */
    char marqueur[512];
    snprintf(marqueur, sizeof marqueur, "%s/%s/.desactive",
             chemin_systeme(PUPPY_PROGRAMMES), AIDE_ID);
    if (fichier_existe(marqueur))
        return AIDE_DESACTIVE;

    /* Intégrité minimale : l'exécutable déclaré doit exister. La vérification
     * de signature complète est du ressort de l'audit ; ici on repère au moins
     * un paquet manifestement incomplet. */
    if (p.executable[0]) {
        char exe[768];
        snprintf(exe, sizeof exe, "%s/%s", p.dossier, p.executable);
        if (!fichier_existe(exe))
            return AIDE_CORROMPU;
    } else {
        return AIDE_CORROMPU;
    }

    return AIDE_OPERATIONNEL;
}

int aide_disponible(void)
{
    return aide_etat() == AIDE_OPERATIONNEL;
}

void aide_ouvrir_page(const char *page_id)
{
    aide_etat_t e = aide_etat();
    int page_valide = (page_id == NULL) || (page_id[0] != '\0');

    /* Le cœur tranche. Ne bloque jamais : quoi qu'il arrive, on retourne. */
    if (pupdate_aide_raccourci((int)e, page_valide)) {
        /* Ouvrir PuppyHelp sur la bonne page. L'option --page=<id> est
         * transmise au programme ; une page absente/abîmée est isolée par
         * PuppyHelp lui-même (non monolithique), jamais par nous. */
        char opt[160];
        if (page_id && page_id[0]) {
            snprintf(opt, sizeof opt, "--page=%s", page_id);
            bureau_lancer_programme_option(AIDE_ID, opt);
        } else {
            bureau_lancer_programme(AIDE_ID);
        }
        return;
    }

    /* Sinon : fenêtre de secours indépendante, avec la cause détectée. */
    aide_secours_ouvrir(e);
}

/* ==========================================================================
 *            FENÊTRE DE SECOURS « Centre d'aide indisponible »
 *   Indépendante : tout le texte et le dessin vivent ici, dans le bureau.
 * ========================================================================== */

static aide_etat_t secours_cause;          /* cause affichée (singleton) */
static rect bouton_gest, bouton_fermer;    /* zones cliquables (posées au dessin) */

static const char *cause_texte(aide_etat_t e)
{
    switch (e) {
    case AIDE_DESINSTALLE: return "Le paquet PuppyHelp n'est pas installe.";
    case AIDE_DESACTIVE:   return "PuppyHelp a ete desactive dans le gestionnaire.";
    case AIDE_OBSOLETE:    return "PuppyHelp est trop ancien pour s'ouvrir.";
    case AIDE_CORROMPU:    return "PuppyHelp semble abime (fichier manquant).";
    case AIDE_INDISPONIBLE:return "PuppyHelp ne repond pas.";
    default:               return "Le centre d'aide ne peut pas s'ouvrir.";
    }
}

static void secours_dessiner(fenetre *f, surface *s, rect z)
{
    (void)f;
    const theme *t = theme_actuel();
    theme_fond_fenetre(s, z.x, z.y, z.l, z.h);

    /* Mise en page en coordonnées RELATIVES à la zone (0,0 = origine du
     * contenu) ; on dessine à z.x+rel / z.y+rel et on stocke les zones
     * cliquables en relatif, car les clics arrivent en coordonnées de zone. */
    int cx = z.l / 2;

    /* Icône (atténuée : l'aide n'est pas là). Icône dédiée à venir avec le
     * .puppy ; remplaçable. */
    gfx_icone(s, ICONE_A_PROPOS, z.x + cx - 28, z.y + 22, 56);

    const char *titre = "Centre d'aide indisponible";
    gfx_texte_gras(s, z.x + cx - gfx_largeur_texte_gras(titre, 2) / 2, z.y + 92, titre, t->texte, 2);

    const char *cause = cause_texte(secours_cause);
    gfx_texte(s, z.x + cx - gfx_largeur_texte(cause, 1) / 2, z.y + 126, cause, t->texte_doux, 1);

    const char *rassure = "Les applications continuent de fonctionner normalement.";
    gfx_texte(s, z.x + cx - gfx_largeur_texte(rassure, 1) / 2, z.y + 146, rassure, t->accent_fonce, 1);

    /* Panneau « Que faire » avec la marche à suivre (texte Piika). */
    int px = 28, pl = z.l - 56, py = 172, ph = 112;
    theme_panneau(s, z.x + px, z.y + py, pl, ph, 8);
    gfx_texte_gras(s, z.x + px + 16, z.y + py + 12, "Que faire :", t->texte, 1);
    const char *etapes[] = {
        "Verifier l'installation du paquet PuppyHelp.",
        "Verifier son etat dans le gestionnaire d'applications.",
        "Le reinstaller depuis une source authentifiee.",
        "Consulter les journaux du systeme.",
    };
    for (int i = 0; i < 4; i++) {
        int y = py + 36 + i * 18;
        gfx_texte(s, z.x + px + 16, z.y + y, "•", t->accent, 1);
        gfx_texte(s, z.x + px + 30, z.y + y, etapes[i], t->texte_doux, 1);
    }

    /* Boutons (zones stockées en relatif). */
    int bh = 30, by = z.h - bh - 18;
    int lg = gfx_largeur_texte("Gestionnaire d'applications", 1) + 28;
    int lf = gfx_largeur_texte("Fermer", 1) + 40;
    bouton_fermer = (rect){ z.l - 18 - lf, by, lf, bh };
    bouton_gest   = (rect){ bouton_fermer.x - 12 - lg, by, lg, bh };
    theme_bouton(s, z.x + bouton_gest.x, z.y + bouton_gest.y, bouton_gest.l, bouton_gest.h,
                 "Gestionnaire d'applications", BOUTON_ACCENT, 0, 1);
    theme_bouton(s, z.x + bouton_fermer.x, z.y + bouton_fermer.y, bouton_fermer.l, bouton_fermer.h,
                 "Fermer", BOUTON_NORMAL, 0, 1);
}

static int dans(rect r, int x, int y)
{
    return x >= r.x && x < r.x + r.l && y >= r.y && y < r.y + r.h;
}

static void secours_clic(fenetre *f, int x, int y, int appuye)
{
    if (!appuye) return;
    /* x,y en coordonnées de zone ; nos rects sont stockés en relatif. */
    if (dans(bouton_gest, x, y)) {
        bureau_ouvrir_app("programmes", NULL);
        wm_fermer(f);
    } else if (dans(bouton_fermer, x, y)) {
        wm_fermer(f);
    }
}

static const fenetre_ops SECOURS_OPS = {
    secours_dessiner, secours_clic, NULL, NULL, NULL, NULL, NULL, NULL
};

void aide_secours_ouvrir(aide_etat_t cause)
{
    secours_cause = cause;
    fenetre *f = wm_chercher_app("aide-secours");
    if (f) { fenetre_salir(f); wm_restaurer(f); return; }
    rect o = { bureau_largeur() / 2, bureau_hauteur() / 2, 0, 0 };
    wm_creer("aide-secours", "Centre d'aide indisponible", 480, 320, &SECOURS_OPS, NULL, o);
}
