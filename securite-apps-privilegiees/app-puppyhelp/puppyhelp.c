/*
 * PuppyHelp — centre d'aide de PuppyOS, application système `.puppy` à part.
 *
 * Facultative, non monolithique, supprimable/réinstallable. Elle s'ouvre seule
 * (accueil) ou sur une page précise via « --page=categorie/fiche », transmise
 * par la passerelle d'aide du bureau (desktop/shell/aide-passerelle). Un
 * « --contexte=<clé> » permet au bouton « Où suis-je ? » de pointer la bonne
 * page selon la fenêtre d'où l'aide a été appelée.
 *
 * Trois habillages, choisis dans Paramètres et CONSERVÉS d'une ouverture à
 * l'autre (programme_donnees) : aide classique Windows 98, à icônes type XP,
 * et « Aide et support » type XP. Tous s'adaptent au thème de l'OS.
 *
 * Garde-fou NON MONOLITHIQUE : une fiche vide ou absente affiche une erreur
 * ISOLÉE dans le volet de droite — le reste de l'aide continue de fonctionner.
 *
 * La fiche « Inconnu ? » reste volontairement énigmatique : elle ne nomme rien
 * et ne confirme rien.
 */
#include "pf.h"
#include "puppy.h"
#include "theme.h"
#include "comptes.h"      /* programme_donnees : chemin des reglages par appli */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------- contenu --------------------------------- */
typedef struct { const char *id, *titre, *corps; } fiche;  /* corps NULL = page a rediger */
typedef struct { const char *id, *nom; icone_id icone; const fiche *fiches; int n; } categorie;

static const fiche F_DECOUVRIR[] = {
    { "bienvenue", "Bienvenue dans PuppyOS",
      "PuppyOS est un systeme d'exploitation fait maison, pense pour rester simple, "
      "lisible et respectueux de ta vie privee.\n"
      "\n"
      "Ce centre d'aide explique a quoi sert chaque chose, comment l'utiliser, et quoi "
      "faire quand quelque chose coince. Choisis une categorie a gauche, puis une fiche.\n"
      "\n"
      "L'aide est facultative : si tu la retires ou la desactives, le reste du systeme "
      "continue de fonctionner normalement." },
    { "bureau", "Le bureau",
      "Le bureau rassemble tes fenetres, le dock en bas et la barre en haut. La barre "
      "donne acces aux menus Fichier, Gamelles, Fenetre et Aide.\n"
      "\n"
      "Le menu Aide ouvre ce centre d'aide. S'il n'est pas disponible, une fenetre de "
      "secours t'explique quoi faire, sans jamais bloquer tes applications." },
};
static const fiche F_UTILISER[] = {
    { "applications", "Installer et retirer des applications",
      "Les applications de PuppyOS sont des fichiers `.puppy`. Le gestionnaire "
      "d'applications permet de les installer, les mettre a jour, les desactiver ou les "
      "retirer.\n"
      "\n"
      "Une application que tu retires ou desactives n'est JAMAIS reinstallee de force, "
      "meme tres ancienne : ton choix est respecte." },
    { "fichiers", "Gamelles et fichiers",
      "Tes fichiers vivent dans des gamelles. L'explorateur les ouvre, les range et les "
      "copie. Une gamelle securisee chiffre ce qu'elle contient." },
};
static const fiche F_CHIENS[] = {
    { "apercu", "Les chiens de garde",
      "La securite de PuppyOS est confiee a une petite meute, chacun avec un role precis. "
      "Leur humeur est purement decorative : un chien fache protege exactement comme un "
      "chien calme.\n"
      "\n"
      "Regle d'or : par defaut on met en quarantaine, jamais on ne supprime un fichier "
      "sans ton accord explicite — avec le chemin exact et une explication." },
    { "honey-dog", "Honey-Dog, le sas",
      "Honey-Dog examine ce qui entre et rend un verdict : autoriser, mettre en "
      "quarantaine (par defaut), ou supprimer (seulement avec ton accord). Sur un systeme "
      "voisin ouvert en lecture seule, il bloque sans jamais rien ecrire." },
    { "flair-terrier", "Flair et le terrier",
      "Flair est l'antivirus en temps reel. Le terrier est un bac a sable : il enferme un "
      "programme douteux pour qu'il ne touche a rien d'autre." },
    { "scrap-dog", "Scrap-Dog, le douanier",
      "Scrap-Dog inspecte les entrees non fiables (archives, fichiers venus d'ailleurs) "
      "en memoire, avec des limites anti-bombe, et ne transmet au reste du systeme que du "
      "contenu borne et verifie. Un type inconnu ne passe jamais." },
    { "inconnu", "Inconnu ?",
      "Il se dit qu'une presence inhabituelle sommeille dans les profondeurs de PuppyOS. "
      "On n'en connait ni le nom ni les contours.\n"
      "\n"
      "Trois composants de la meute sont officiellement recenses. Le reste appartient aux "
      "archives.\n"
      "\n"
      "(Illustration a venir.)" },
};
static const fiche F_INSTALLATION[] = {
    { "demarrer", "Demarrer et installer PuppyOS",
      "PuppyOS peut tourner depuis une cle ou etre installe a cote d'un autre systeme. "
      "L'installation ne touche jamais les donnees existantes sans te prevenir." },
};
static const fiche F_DEPANNAGE[] = {
    { "aide-indispo", "Le centre d'aide ne s'ouvre pas",
      "Si l'aide ne fonctionne plus, une fenetre de secours « Centre d'aide "
      "indisponible » apparait. Elle est independante de l'aide et marche meme si le "
      "paquet PuppyHelp est retire ou abime.\n"
      "\n"
      "Que faire : verifier l'installation du paquet PuppyHelp ; verifier son etat dans le "
      "gestionnaire d'applications ; le reinstaller depuis une source authentifiee ; "
      "consulter les journaux du systeme." },
    { "page-cassee", "Une page d'aide est vide ou illisible",
      "Si une fiche est absente ou abimee, seule cette fiche affiche une erreur : le "
      "reste de l'aide continue de fonctionner. L'aide n'est pas monolithique." },
};
static const fiche F_CONFID[] = {
    { "vie-privee", "Ta vie privee",
      "PuppyOS ne t'espionne pas : pas de telemetrie, pas de mouchard. Ce qui reste sur "
      "ta machine reste sur ta machine." },
};
static const fiche F_GLOSSAIRE[] = {
    { "termes", "Petits mots",
      ".puppy : une application. Gamelle : un espace de rangement pour tes fichiers. "
      "Quarantaine : un fichier mis de cote, ni ouvert ni supprime, en attendant ta "
      "decision." },
};

#define CAT(i, n, ic, t) { i, n, ic, t, (int)(sizeof t / sizeof *t) }
static const categorie CATEGORIES[] = {
    CAT("decouvrir",      "Decouvrir",             ICONE_A_PROPOS,          F_DECOUVRIR),
    CAT("utiliser",       "Utiliser",              ICONE_PROGRAMMES,        F_UTILISER),
    CAT("securite",       "Securite - les chiens", ICONE_CADENAS,           F_CHIENS),
    CAT("installation",   "Installation",          ICONE_INSTALLATEUR,      F_INSTALLATION),
    CAT("depannage",      "Depannage",             ICONE_MISES_A_JOUR,      F_DEPANNAGE),
    CAT("confidentialite","Confidentialite",       ICONE_GAMELLE_SECURISEE, F_CONFID),
    CAT("glossaire",      "Glossaire",             ICONE_TEXTE,             F_GLOSSAIRE),
};
#define NB_CAT ((int)(sizeof CATEGORIES / sizeof *CATEGORIES))

/* bouton « Ou suis-je ? » : associe un contexte (fenetre/reglage ouvert) a une page. */
static const struct { const char *ctx, *page; } OU_SUIS_JE[] = {
    { "gestionnaire",    "utiliser/applications" },
    { "explorateur",     "utiliser/fichiers" },
    { "securite",        "securite/apercu" },
    { "installation",    "installation/demarrer" },
    { "confidentialite", "confidentialite/vie-privee" },
};
#define NB_OSJ ((int)(sizeof OU_SUIS_JE / sizeof *OU_SUIS_JE))

/* ------------------------------- habillages ------------------------------ */
typedef enum { SKIN_WIN98, SKIN_XP_ICONES, SKIN_XP_HELP } skin;
static skin habillage = SKIN_WIN98;
#define NB_SKIN 3
static const char *SKIN_ID[NB_SKIN]  = { "win98", "xp-icones", "xp-help" };
static const char *SKIN_NOM[NB_SKIN] = {
    "Aide classique (Windows 98)", "A icones (type XP)", "Aide et support (XP)"
};
#define CONF "puppyhelp.conf"

static void habillage_lire(void)
{
    char *t = lire_fichier(programme_donnees(CONF), NULL), v[64];
    if (!t) return;
    if (config_valeur(t, "habillage", v, sizeof v) == 0)
        for (int i = 0; i < NB_SKIN; i++)
            if (strcmp(v, SKIN_ID[i]) == 0) habillage = (skin)i;
    free(t);
}
static void habillage_ecrire(void)
{
    config_ecrire(programme_donnees(CONF), "habillage", SKIN_ID[habillage]);
}

/* ------------------------------- etat ------------------------------------ */
#define HAUT_DEF 520
#define VOLET_L  212
#define ENTETE_H 44
#define LIGNE_H  22
#define SEUIL_ETROIT 560        /* en dessous : mise en page compacte (tablette/tel) */

static int cat_choisie, fiche_choisie;      /* selection courante */
static int defile, defile_max;
static int accueil = 1;                      /* 1 = page d'accueil de l'habillage */
static int vue_params;                       /* 1 = volet droit = parametres */
static int etroit, vue_liste = 1;            /* compact : liste OU document */
static char recherche[64]; static int recherche_actif;
static const char *contexte;                 /* --contexte= pour « Ou suis-je ? » */

/* zones cliquables (coordonnees surface, 0-based comme pf) */
static rect z_param, z_skin[NB_SKIN], z_rech, z_osj, z_retour;
static rect z_cat[NB_CAT];
static rect z_fiche[40]; static int z_fiche_cat[40], z_fiche_fi[40], z_fiche_n;
static rect z_tuile[NB_CAT];
static rect z_res[40]; static int z_res_cat[40], z_res_fi[40], z_res_n;

/* --------------------------- outils texte -------------------------------- */
static int dans(rect r, int x, int y){ return x>=r.x && x<r.x+r.l && y>=r.y && y<r.y+r.h; }

static char bas(char c){ return (c>='A'&&c<='Z') ? (char)(c+32) : c; }
/* sous-chaine insensible a la casse (ASCII). */
static int contient(const char *h, const char *a)
{
    if (!*a) return 1;
    for (; *h; h++) {
        const char *x=h,*y=a;
        while (*x && *y && bas(*x)==bas(*y)) { x++; y++; }
        if (!*y) return 1;
    }
    return 0;
}

/* Corps avec retour a la ligne aux mots. Renvoie la hauteur totale. */
static int dessiner_corps(surface *s, int x, int y0, int larg, int haut, const char *corps, couleur c)
{
    gfx_clip(s, x, y0, larg, haut);
    int y = y0 - defile; char mot[128]; const char *p = corps;
    while (*p) {
        int lx = x, quelque_chose = 0;
        for (;;) {
            while (*p == ' ') p++;
            if (*p == '\n' || *p == '\0') break;
            int k = 0;
            while (*p && *p != ' ' && *p != '\n' && k < (int)sizeof mot - 1) mot[k++] = *p++;
            mot[k] = 0;
            int w = gfx_largeur_texte(mot, 1);
            if (quelque_chose && lx + w > x + larg) { p -= k; break; }
            if (y + LIGNE_H > y0 && y < y0 + haut) lx += gfx_texte(s, lx, y, mot, c, 1);
            else lx += w;
            lx += gfx_largeur_texte(" ", 1);
            quelque_chose = 1;
        }
        y += LIGNE_H;
        if (*p == '\n') { p++; if (!quelque_chose) y += LIGNE_H / 2; }
    }
    gfx_clip_tout(s);
    return (y + defile) - y0;
}

static const fiche *fiche_courante(void)
{
    if (accueil || cat_choisie < 0 || cat_choisie >= NB_CAT) return NULL;
    const categorie *ca = &CATEGORIES[cat_choisie];
    if (fiche_choisie < 0 || fiche_choisie >= ca->n) return NULL;
    return &ca->fiches[fiche_choisie];
}

/* ------------------------------- en-tete --------------------------------- */
/* Chrome different selon l'habillage ; pose les zones recherche / ou-suis-je. */
static void entete(surface *s, const theme *t, int L)
{
    couleur ban = t->accent_fonce;
    if (habillage == SKIN_XP_HELP) {
        /* banniere coloree facon « Centre d'aide et de support » */
        gfx_rect(s, 0, 0, L, ENTETE_H, ban);
        gfx_texte_gras(s, 16, 13, "Centre d'aide et de support", 0xFFFFFFFF, 1);
    } else {
        theme_barre_outils(s, 0, 0, L, ENTETE_H);
        gfx_icone(s, ICONE_A_PROPOS, 12, 7, 30);
        gfx_texte_gras(s, 50, 13, "Centre d'aide PuppyOS", t->texte, 1);
    }

    /* bouton « Ou suis-je ? » */
    int bw = gfx_largeur_texte("Ou suis-je ?", 1) + 24;
    z_osj = (rect){ L - bw - 12, 7, bw, 30 };
    theme_bouton(s, z_osj.x, z_osj.y, z_osj.l, z_osj.h, "Ou suis-je ?", BOUTON_NORMAL, 0, 1);

    /* champ de recherche */
    int rw = etroit ? 120 : 200;
    z_rech = (rect){ z_osj.x - rw - 10, 8, rw, 28 };
    theme_champ(s, z_rech.x, z_rech.y, z_rech.l, z_rech.h, recherche_actif);
    const char *aff = recherche[0] ? recherche : "Rechercher...";
    gfx_texte(s, z_rech.x + 8, z_rech.y + 6, aff,
              recherche[0] ? t->texte : t->texte_doux, 1);

    /* bouton accueil (icones/help) ou retour (compact en mode document) */
    if (etroit && !vue_liste && !vue_params) {
        z_retour = (rect){ 8, 7, gfx_largeur_texte("< Categories", 1) + 20, 30 };
        theme_bouton(s, z_retour.x, z_retour.y, z_retour.l, z_retour.h, "< Categories", BOUTON_NORMAL, 0, 1);
    } else z_retour = (rect){0,0,0,0};
}

/* ------------------------------ volet gauche ----------------------------- */
static void volet_gauche(surface *s, const theme *t, int H)
{
    theme_lateral(s, 0, ENTETE_H, VOLET_L, H - ENTETE_H);
    int y = ENTETE_H + 10; z_fiche_n = 0;
    for (int c = 0; c < NB_CAT; c++) {
        const categorie *ca = &CATEGORIES[c];
        int sel = (!accueil && c == cat_choisie && !vue_params);
        z_cat[c] = (rect){ 6, y - 3, VOLET_L - 12, LIGNE_H };
        if (sel) theme_menu_selection(s, 6, y - 3, VOLET_L - 12, LIGNE_H);
        gfx_icone(s, ca->icone, 12, y - 2, 18);
        gfx_texte_gras(s, 38, y, ca->nom, t->texte, 1);
        y += LIGNE_H + 2;
        if (sel) {
            for (int fi = 0; fi < ca->n && z_fiche_n < 40; fi++) {
                int sf = (fi == fiche_choisie);
                if (sf) theme_menu_selection(s, 16, y - 2, VOLET_L - 22, LIGNE_H - 2);
                gfx_texte(s, 44, y, ca->fiches[fi].titre, sf ? t->texte : t->texte_doux, 1);
                z_fiche[z_fiche_n] = (rect){ 16, y - 2, VOLET_L - 22, LIGNE_H - 2 };
                z_fiche_cat[z_fiche_n] = c; z_fiche_fi[z_fiche_n] = fi; z_fiche_n++;
                y += LIGNE_H - 1;
            }
            y += 4;
        }
    }
    y += 6;
    gfx_ligne(s, 10, y - 4, VOLET_L - 10, y - 4, 1, t->fenetre_bord);
    if (vue_params) theme_menu_selection(s, 6, y - 1, VOLET_L - 12, LIGNE_H);
    gfx_icone(s, ICONE_PERSONNALISER, 12, y, 18);
    gfx_texte_gras(s, 38, y + 2, "Parametres", vue_params ? t->texte : t->texte_doux, 1);
    z_param = (rect){ 6, y - 1, VOLET_L - 12, LIGNE_H };
}

/* ------------------------------ volet droit ------------------------------ */
static void pane_params(surface *s, const theme *t, int dx, int dw)
{
    gfx_texte_gras(s, dx, ENTETE_H + 14, "Parametres de l'aide", t->texte, 2);
    gfx_ligne(s, dx, ENTETE_H + 46, dx + dw, ENTETE_H + 46, 1, t->fenetre_bord);
    gfx_texte(s, dx, ENTETE_H + 58, "Habillage du centre d'aide", t->texte_doux, 1);
    int ry = ENTETE_H + 86;
    for (int i = 0; i < NB_SKIN; i++) {
        int sel = (i == (int)habillage);
        z_skin[i] = (rect){ dx, ry, dw, 34 };
        if (sel) theme_menu_selection(s, dx, ry, dw, 34);
        else     theme_panneau(s, dx, ry, dw, 34, 6);
        gfx_texte(s, dx + 14, ry + 9, sel ? "◉" : "○", sel ? t->accent : t->texte_doux, 1);
        gfx_texte(s, dx + 38, ry + 9, SKIN_NOM[i], t->texte, 1);
        ry += 42;
    }
    gfx_texte(s, dx, ry + 6, "Enregistre tout de suite. L'aide rouvre toujours sur ce choix.", t->texte_doux, 1);
    defile_max = 0;
}

static void pane_resultats(surface *s, const theme *t, int dx, int dw)
{
    char titre[96];
    snprintf(titre, sizeof titre, "Recherche : %s", recherche);
    gfx_texte_gras(s, dx, ENTETE_H + 14, titre, t->texte, 2);
    gfx_ligne(s, dx, ENTETE_H + 46, dx + dw, ENTETE_H + 46, 1, t->fenetre_bord);
    int ry = ENTETE_H + 58; z_res_n = 0;
    for (int c = 0; c < NB_CAT; c++)
        for (int fi = 0; fi < CATEGORIES[c].n && z_res_n < 40; fi++) {
            const fiche *f = &CATEGORIES[c].fiches[fi];
            if (!contient(f->titre, recherche) && !(f->corps && contient(f->corps, recherche))) continue;
            z_res[z_res_n] = (rect){ dx, ry, dw, 30 };
            z_res_cat[z_res_n] = c; z_res_fi[z_res_n] = fi; z_res_n++;
            theme_panneau(s, dx, ry, dw, 30, 6);
            gfx_icone(s, CATEGORIES[c].icone, dx + 8, ry + 6, 18);
            gfx_texte(s, dx + 34, ry + 8, f->titre, t->texte, 1);
            char sous[64]; snprintf(sous, sizeof sous, "  (%s)", CATEGORIES[c].nom);
            gfx_texte(s, dx + 34 + gfx_largeur_texte(f->titre, 1), ry + 8, sous, t->texte_doux, 1);
            ry += 36;
        }
    if (!z_res_n) gfx_texte(s, dx, ry, "Aucun resultat.", t->texte_doux, 1);
    defile_max = 0;
}

/* accueil : home different selon l'habillage (grille d'icones / grands liens). */
static void pane_accueil(surface *s, const theme *t, int dx, int dw, int H)
{
    if (habillage == SKIN_XP_ICONES) {
        gfx_texte_gras(s, dx, ENTETE_H + 14, "Par ou commencer ?", t->texte, 2);
        int cols = (dw > 460) ? 3 : 2, tw = (dw - (cols - 1) * 12) / cols, th = 78;
        int ry = ENTETE_H + 54, col = 0, cx = dx;
        for (int c = 0; c < NB_CAT; c++) {
            z_tuile[c] = (rect){ cx, ry, tw, th };
            theme_panneau(s, cx, ry, tw, th, 8);
            gfx_icone(s, CATEGORIES[c].icone, cx + tw / 2 - 16, ry + 10, 32);
            int lw = gfx_largeur_texte(CATEGORIES[c].nom, 1);
            gfx_texte(s, cx + (tw - lw) / 2, ry + th - 20, CATEGORIES[c].nom, t->texte, 1);
            if (++col >= cols) { col = 0; cx = dx; ry += th + 12; } else cx += tw + 12;
        }
        defile_max = 0;
    } else if (habillage == SKIN_XP_HELP) {
        gfx_texte_gras(s, dx, ENTETE_H + 14, "Choisis un sujet d'aide", t->texte, 2);
        gfx_ligne(s, dx, ENTETE_H + 46, dx + dw, ENTETE_H + 46, 1, t->fenetre_bord);
        int cols = (dw > 460) ? 2 : 1, cw = (dw - (cols - 1) * 16) / cols;
        int ry = ENTETE_H + 60, col = 0, cx = dx;
        for (int c = 0; c < NB_CAT; c++) {
            z_tuile[c] = (rect){ cx, ry, cw, 34 };
            gfx_icone(s, CATEGORIES[c].icone, cx, ry + 4, 22);
            gfx_texte(s, cx + 30, ry + 7, CATEGORIES[c].nom, t->accent, 1);
            if (++col >= cols) { col = 0; cx = dx; ry += 40; } else cx += cw + 16;
        }
        (void)H; defile_max = 0;
    } else {
        /* Win98 : pas de home separee, on ouvre la 1re fiche. */
        accueil = 0;
    }
}

static void pane_doc(surface *s, const theme *t, int dx, int dw, int H)
{
    const fiche *f = fiche_courante();
    gfx_texte_gras(s, dx, ENTETE_H + 14, f ? f->titre : "Aide", t->texte, 2);
    gfx_ligne(s, dx, ENTETE_H + 46, dx + dw, ENTETE_H + 46, 1, t->fenetre_bord);
    int by = ENTETE_H + 58, bh = H - by - 14;
    if (f && f->corps) {
        int ht = dessiner_corps(s, dx, by, dw, bh, f->corps, t->texte);
        defile_max = ht > bh ? ht - bh : 0;
        if (defile > defile_max) defile = defile_max;
        if (defile_max > 0) {
            int sbh = bh * bh / ht, sby = by + (bh - sbh) * defile / defile_max;
            gfx_rect_arrondi(s, dx + dw + 4, sby, 4, sbh, 2, t->texte_doux);
        }
    } else {
        theme_panneau(s, dx, by, dw, 72, 8);
        gfx_texte_gras(s, dx + 14, by + 14, "Page indisponible", t->texte, 1);
        gfx_texte(s, dx + 14, by + 36,
                  "Cette fiche est absente ou abimee. Le reste de l'aide fonctionne.", t->texte_doux, 1);
    }
}

static void dessiner(surface *s)
{
    const theme *t = theme_actuel();
    int L = s->l, H = s->h;
    etroit = (L < SEUIL_ETROIT);

    /* remise a zero des zones cliquables non dessinees a chaque trame */
    z_param = z_retour = (rect){0,0,0,0};
    z_fiche_n = z_res_n = 0;
    for (int i = 0; i < NB_CAT; i++) z_cat[i] = z_tuile[i] = (rect){0,0,0,0};
    for (int i = 0; i < NB_SKIN; i++) z_skin[i] = (rect){0,0,0,0};

    theme_fond_fenetre(s, 0, 0, L, H);
    entete(s, t, L);

    int dx, dw;
    if (!etroit) {
        volet_gauche(s, t, H);
        dx = VOLET_L + 18; dw = L - VOLET_L - 36;
    } else {
        /* compact : la liste sert d'accueil ; sinon le contenu plein cadre */
        if (vue_liste && !vue_params && !recherche[0]) { volet_gauche(s, t, H); return; }
        dx = 18; dw = L - 36;
    }

    if (vue_params)                               pane_params(s, t, dx, dw);
    else if (recherche[0])                        pane_resultats(s, t, dx, dw);
    else if (accueil && habillage != SKIN_WIN98)  pane_accueil(s, t, dx, dw, H);
    else { accueil = 0; pane_doc(s, t, dx, dw, H); }
}

/* ------------------------------ navigation ------------------------------- */
static int aller_page(const char *page)
{
    if (!page || !*page) return -1;
    char cat[64]; const char *slash = strchr(page, '/');
    size_t nc = slash ? (size_t)(slash - page) : strlen(page);
    if (nc >= sizeof cat) nc = sizeof cat - 1;
    memcpy(cat, page, nc); cat[nc] = 0;
    for (int c = 0; c < NB_CAT; c++) {
        if (strcmp(CATEGORIES[c].id, cat) != 0) continue;
        cat_choisie = c; fiche_choisie = 0; defile = 0; accueil = 0; vue_params = 0;
        recherche[0] = 0; recherche_actif = 0; vue_liste = 0;
        if (slash && slash[1])
            for (int fi = 0; fi < CATEGORIES[c].n; fi++)
                if (strcmp(CATEGORIES[c].fiches[fi].id, slash + 1) == 0) { fiche_choisie = fi; break; }
        return 0;
    }
    return -1;
}

static void ouvrir_fiche(int c, int fi)
{
    cat_choisie = c; fiche_choisie = fi; defile = 0;
    accueil = 0; vue_params = 0; recherche[0] = 0; recherche_actif = 0; vue_liste = 0;
}

static void ou_suis_je(void)
{
    recherche[0] = 0; recherche_actif = 0;
    if (contexte)
        for (int i = 0; i < NB_OSJ; i++)
            if (strcmp(OU_SUIS_JE[i].ctx, contexte) == 0) { aller_page(OU_SUIS_JE[i].page); return; }
    /* pas de contexte connu : page d'explication de secours */
    aller_page("decouvrir/bureau");
}

static void clic(int x, int y)
{
    if (dans(z_osj, x, y))   { ou_suis_je(); return; }
    if (dans(z_rech, x, y))  { recherche_actif = 1; return; }
    if (z_retour.l && dans(z_retour, x, y)) { vue_liste = 1; return; }
    recherche_actif = 0;

    if (dans(z_param, x, y)) { vue_params = 1; accueil = 0; defile = 0; if (etroit) vue_liste = 0; return; }

    if (vue_params) {
        for (int i = 0; i < NB_SKIN; i++)
            if (dans(z_skin[i], x, y)) { habillage = (skin)i; habillage_ecrire(); return; }
        return;
    }
    if (recherche[0]) {
        for (int i = 0; i < z_res_n; i++)
            if (dans(z_res[i], x, y)) { ouvrir_fiche(z_res_cat[i], z_res_fi[i]); return; }
    }
    if (accueil && (habillage == SKIN_XP_ICONES || habillage == SKIN_XP_HELP)) {
        for (int c = 0; c < NB_CAT; c++)
            if (dans(z_tuile[c], x, y)) { ouvrir_fiche(c, 0); return; }
    }
    /* volet gauche : categories / fiches */
    for (int i = 0; i < z_fiche_n; i++)
        if (dans(z_fiche[i], x, y)) { ouvrir_fiche(z_fiche_cat[i], z_fiche_fi[i]); return; }
    for (int c = 0; c < NB_CAT; c++)
        if (dans(z_cat[c], x, y)) {
            cat_choisie = c; fiche_choisie = 0; defile = 0; accueil = 0; vue_params = 0;
            recherche[0] = 0; if (etroit) vue_liste = 1;   /* montre les fiches de la categorie */
            return;
        }
}

static void touche_recherche(const pf_evenement *e)
{
    size_t n = strlen(recherche);
    if (e->code == 14) { if (n) recherche[n - 1] = 0; }        /* backspace */
    else if (e->code == 1) { recherche[0] = 0; recherche_actif = 0; }  /* echap */
    else if ((unsigned char)e->texte[0] >= 32 && n + 1 < sizeof recherche)
        strcat(recherche, e->texte);
}

int main(int argc, char **argv)
{
    const char *page = NULL;
    for (int i = 1; i < argc; i++) {
        if (strncmp(argv[i], "--page=", 7) == 0) page = argv[i] + 7;
        else if (strncmp(argv[i], "--contexte=", 11) == 0) contexte = argv[i] + 11;
    }

    habillage_lire();
    cat_choisie = 0; fiche_choisie = 0;
    if (page) aller_page(page);   /* ouverture directe depuis la passerelle */

    theme_initialiser();
    pf_fenetre *f = pf_ouvrir("Centre d'aide", 720, HAUT_DEF);
    if (!f) { fprintf(stderr, "puppyhelp : pas de bureau PuppyOS\n"); return 1; }
    surface *s = pf_surface(f);
    dessiner(s); pf_afficher(f);

    pf_evenement e; int r;
    while ((r = pf_attendre(f, &e, -1)) >= 0) {
        if (r == 0) continue;
        if (e.type == PF_EV_FERMETURE) break;
        if (e.type == PF_EV_SOURIS) {
            if (e.bouton == 0 && e.appuye) clic(e.x, e.y);
            else continue;
        } else if (e.type == PF_EV_MOLETTE) {
            defile -= e.delta * 40;
            if (defile < 0) defile = 0;
            if (defile > defile_max) defile = defile_max;
        } else if (e.type == PF_EV_TOUCHE) {
            if (recherche_actif) touche_recherche(&e);
            else switch (e.code) {
            case 103: defile -= 40; if (defile < 0) defile = 0; break;
            case 108: defile += 40; if (defile > defile_max) defile = defile_max; break;
            default: break;
            }
        } else if (e.type == PF_EV_TAILLE) {
            s = pf_surface(f);
        } else continue;
        dessiner(s); pf_afficher(f);
    }
    pf_fermer(f);
    return 0;
}
