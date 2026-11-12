/*
 * Navigateur.puppy 2.1 — tout ce qu'on voit autour de la page : onglets,
 * barre d'outils et d'adresse, favoris, menus, dialogues, bandeau du coffre,
 * recherche dans la page et pages internes (puppy://parametres…).
 *
 * Interface « immédiate » : chaque image redessine tout et note les zones
 * cliquables ; un clic cherche sa zone (de la dernière dessinée à la
 * première, donc ce qui est au-dessus gagne) et lance son action.
 */
#define _GNU_SOURCE
#include "nav.h"
#include "coffre-nav.h"
#include "puppy.h"
#include <linux/input-event-codes.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ---- ce que navigateur.c offre en plus de nav.h ---- */
int  coffre_existe(void);
int  coffre_ouvrir(const char *mdp, char *err, size_t nerr);
void coffre_verrouiller(void);
int  coffre_liste(char *dst, size_t n);
int  coffre_supprimer(int i);
int  coffre_montrer(int i, char *dst, size_t n);
int  coffre_changer(const char *mdp);
int  coffre_tout_effacer(void);
void coffre_remplir_compte(int n);
void coffre_suite(void);
extern int coffre_apres;
void permettre_http(const char *h);
void extension_confirmer(int oui);
void clic_droit_site(int x, int y);
void evaluer_public(const char *js);
void capture_publique(void);
void rendre_glisser(int x, int y, const char *u);
char *nav_demander(const char *session, const char *methode, const char *params, int delai);
void sites_appliquer(void);

/* ------------------------------ couleurs ------------------------------ */
#define C_FOND_ONGLETS1 RVB(206, 220, 240)
#define C_FOND_ONGLETS2 RVB(188, 205, 230)
#define C_BARRE         RVB(249, 250, 253)
#define C_TEXTE         RVB(30, 34, 44)
#define C_GRIS          RVB(110, 116, 130)
#define C_LIGNE         RVB(206, 212, 224)
#define C_BLEU          RVB(46, 110, 220)
#define C_ROUGE         RVB(200, 50, 40)
#define C_VERT          RVB(30, 140, 70)
#define C_PRIVE         RVB(120, 70, 190)

/* ------------------------------- zones -------------------------------- */
enum {
    A_RIEN, A_ONGLET, A_ONGLET_FERMER, A_ONGLET_PLUS, A_PRECEDENT, A_SUIVANT, A_RECHARGER, A_ACCUEIL,
    A_ADRESSE, A_SITE, A_ETOILE, A_UA, A_CLE, A_VPN, A_MENU, A_FAV_BARRE, A_FAV_PLUS, A_FAVBARRE_VIDE,
    A_INFO_OUI, A_INFO_JAMAIS, A_INFO_NON, A_INFO_FERMER,
    A_TROUVER_CHAMP, A_TROUVER_HAUT, A_TROUVER_BAS, A_TROUVER_FERMER,
    A_SUGGESTION, A_MENU_FOND, A_DLG_BOUTON, A_DLG_CHAMP, A_DLG_FOND,
    /* actions de menu */
    A_NOUVEL_ONGLET, A_ONGLET_PRIVE, A_PAGE_INTERNE, A_ZOOM, A_TROUVER, A_LECTURE, A_PDF, A_CAPTURE,
    A_EFFACER_VOIR, A_QUITTER, A_IDENTITE, A_COMPTE, A_LIEN_ONGLET, A_LIEN_PRIVE, A_LIEN_COPIER,
    A_LIEN_FAVORI, A_IMAGE_ONGLET, A_IMAGE_COPIER, A_SEL_COPIER, A_SEL_CHERCHER, A_MENU_SITE,
    A_ONGLET_RECHARGER, A_ONGLET_DUPLIQUER, A_ONGLET_FERMER_AUTRES, A_FAV_OUVRIR, A_FAV_NOUVEL,
    A_FAV_PRIVE, A_FAV_MODIFIER, A_FAV_SUPPR, A_FAV_AJOUTER_PAGE, A_FAVBARRE_MASQUER,
    A_SITE_JS, A_SITE_CK, A_SITE_EFFACER, A_SITE_OUBLIER, A_SITE_COFFRE, A_COLLER_PAGE,
    /* pages internes */
    A_REGLAGE, A_CHAMP_PAGE, A_HIST, A_HIST_NOUVEL, A_HIST_SUPPR, A_HIST_SUPPR_SITE, A_EFFACER,
    A_FAV_HAUT, A_FAV_BAS, A_COF_OUVRIR, A_COF_MONTRER, A_COF_SUPPR, A_COF_VERROU, A_COF_CHANGER,
    A_COF_TOUT, A_EXT_ACTIF, A_EXT_SUPPR, A_SITE_LIGNE_JS, A_SITE_LIGNE_CK, A_SITE_LIGNE_SUPPR,
    A_VPN_APPLIQUER, A_ERREUR_REESSAYER, A_ERREUR_HTTP, A_EXT_ALLER,
    /* 0.12.1 */
    A_EDITION, A_SOURCE, A_SOURCE_VUE,
    /* 2.1 (0.12.2) */
    A_PREMIER, A_PREMIER_CHOIX, A_DLG_CASE, A_PERM_RETIRER, A_SITE_LIGNE_PHP, A_MOT_SUPPR, A_MOT_AJOUTER,
    A_THEME_PP, A_CERT, A_SITE_OUBLIER_OUI, A_SITE_IMAGES, A_IMAGE_MODIFIER, A_IMAGE_RESTAURER, A_APPLI,
    A_AUDIT, A_PHP_UNE_FOIS, A_PHP_TOUJOURS, A_APPLI_FERMER, A_AIDE_SECTION, A_AUDIT_EFFACER,
    /* 2.2 */
    A_GOOGLE_VRAI, A_GOOGLE_DDG, A_GOOGLE_AIDE, A_GOOGLE_TESTER,
};

typedef struct { int x, y, l, h, act, arg; } zone;
#define ZONES_MAX 600
static zone zones[ZONES_MAX];
static int nb_zones, modal_debut = -1;
static rect clip_zones = { 0, 0, 0, 0 };   /* l = 0 : aucune */

static void zone_ajouter(int x, int y, int l, int h, int act, int arg)
{
    if (nb_zones >= ZONES_MAX) return;
    if (clip_zones.l > 0) {                       /* zone coupée par la page interne */
        int x2 = x + l, y2 = y + h;
        if (x < clip_zones.x) x = clip_zones.x;
        if (y < clip_zones.y) y = clip_zones.y;
        if (x2 > clip_zones.x + clip_zones.l) x2 = clip_zones.x + clip_zones.l;
        if (y2 > clip_zones.y + clip_zones.h) y2 = clip_zones.y + clip_zones.h;
        if (x2 <= x || y2 <= y) return;
        l = x2 - x; h = y2 - y;
    }
    zones[nb_zones++] = (zone){ x, y, l, h, act, arg };
}

static int sx = -1, sy = -1;                      /* la souris */
static int dedans(int x, int y, int l, int h) { return sx >= x && sy >= y && sx < x + l && sy < y + h; }

void interface_debut(void) { nb_zones = 0; modal_debut = -1; }

/* ------------------------------ outils -------------------------------- */
static int largeur(const char *t, float taille, int gras) { return gfx_largeur_vect(t, taille, gras ? POIDS_GRAS : POIDS_NORMAL); }

/* 0.12.2 : la taille de la destination est passée (avant : 1024 supposés,
 * et une suggestion d'historique longue écrasait la pile → plantage pendant
 * la saisie d'une adresse) */
static void couper_n(char *d, size_t nd, const char *s, int lmax, float taille)
{
    if (nd < 8) { if (nd) d[0] = 0; return; }
    chaine_copier(d, s, nd);
    if (largeur(d, taille, 0) <= lmax) return;
    size_t n = strlen(d);
    while (n > 0) {
        n--;
        while (n > 0 && ((unsigned char)d[n] & 0xC0) == 0x80) n--;
        d[n] = 0;
        if (n + 4 > nd) continue;                  /* place pour « … » */
        char e[1100];
        snprintf(e, sizeof e, "%s…", d);
        if (largeur(e, taille, 0) <= lmax || n == 0) { chaine_copier(d, e, nd); return; }
    }
}
#define couper(d, s, lmax, taille) couper_n((d), sizeof(d), (s), (lmax), (taille))

/* paragraphe coupé aux espaces ; renvoie la hauteur */
static int paragraphe(int x, int y, int lmax, const char *t, couleur c, float taille, int dessiner_)
{
    char ligne[600] = "", mot[300];
    int h = 0, lh = (int)(taille * 1.45f);
    const char *p = t;
    while (*p) {
        size_t n = strcspn(p, " \n");
        if (n >= sizeof mot) n = sizeof mot - 1;
        memcpy(mot, p, n); mot[n] = 0;
        char essai[900];
        snprintf(essai, sizeof essai, "%s%s%s", ligne, ligne[0] ? " " : "", mot);
        if (ligne[0] && largeur(essai, taille, 0) > lmax) {
            if (dessiner_) texte(x, y + h, ligne, c, taille, 0);
            h += lh;
            chaine_copier(ligne, mot, sizeof ligne);
        } else chaine_copier(ligne, essai, sizeof ligne);
        p += n;
        if (*p == '\n') {
            if (dessiner_) texte(x, y + h, ligne, c, taille, 0);
            h += lh; ligne[0] = 0; p++;
        } else if (*p == ' ') p++;
    }
    if (ligne[0]) { if (dessiner_) texte(x, y + h, ligne, c, taille, 0); h += lh; }
    return h;
}

static void bouton(int x, int y, int l, int h, const char *t, int style, int act, int arg)
{
    int sv = dedans(x, y, l, h);
    couleur c1, c2, ct = C_TEXTE, bord = RVB(176, 186, 204);
    if (style == 1) { c1 = RVB(80, 145, 240); c2 = RVB(40, 100, 210); ct = RVB(255, 255, 255); bord = RVB(30, 80, 180); }
    else if (style == 2) { c1 = RVB(240, 110, 95); c2 = RVB(200, 55, 40); ct = RVB(255, 255, 255); bord = RVB(160, 40, 30); }
    else { c1 = RVB(255, 255, 255); c2 = RVB(232, 236, 244); }
    if (sv) { c1 = gfx_eclaircir(c1, 0.15f); c2 = gfx_eclaircir(c2, 0.1f); }
    gfx_rect_arrondi_degrade(toile, x, y, l, h, 7, c1, c2);
    gfx_contour_arrondi(toile, x, y, l, h, 7, bord);
    int lt = largeur(t, 14, 0);
    texte(x + (l - lt) / 2, y + (h - 17) / 2, t, ct, 14, 0);
    zone_ajouter(x, y, l, h, act, arg);
}

/* bouton dont la largeur suit le texte ; renvoie la largeur */
static int bouton_auto(int x, int y, const char *t, int style, int act, int arg)
{
    int l = largeur(t, 14, 0) + 28;
    bouton(x, y, l, 30, t, style, act, arg);
    return l;
}

static void interrupteur(int x, int y, int marche, int act, int arg)
{
    gfx_rect_arrondi(toile, x, y, 42, 22, 11, marche ? RVB(52, 160, 90) : RVB(190, 196, 208));
    gfx_disque(toile, marche ? x + 31 : x + 11, y + 11, 9, RVB(255, 255, 255));
    zone_ajouter(x, y, 42, 22, act, arg);
}

/* ----------------------- presse-papiers interne ----------------------- */
static char presse[2048];
static void copier(const char *t) { chaine_copier(presse, t, sizeof presse); dire("Copié"); }

/* --------------------------------- champs ----------------------------- */
static champ *focus;
static champ adresse, trouver, dlg_champ[2], ch_accueil, ch_ua, ch_vpn_hote, ch_vpn_port, ch_hist, ch_ext, ch_mot;

void champ_vider(champ *c) { explicit_bzero(c->t, sizeof c->t); c->cur = 0; c->tout = 0; }
static void champ_mettre(champ *c, const char *t) { champ_vider(c); chaine_copier(c->t, t, sizeof c->t); c->cur = (int)strlen(c->t); }

static int precedent_car(const char *t, int i) { if (i <= 0) return 0; i--; while (i > 0 && ((unsigned char)t[i] & 0xC0) == 0x80) i--; return i; }
static int suivant_car(const char *t, int i) { int n = (int)strlen(t); if (i >= n) return n; i++; while (i < n && ((unsigned char)t[i] & 0xC0) == 0x80) i++; return i; }

static void inserer(champ *c, const char *s)
{
    size_t n = strlen(c->t), k = strlen(s);
    if (n + k + 1 > sizeof c->t) return;
    memmove(c->t + c->cur + k, c->t + c->cur, n - (size_t)c->cur + 1);
    memcpy(c->t + c->cur, s, k);
    c->cur += (int)k;
}

static char lettre(const pf_evenement *e)
{
    unsigned char c = (unsigned char)e->texte[0];
    if (c >= 'A' && c <= 'Z') c += 32;
    return (char)c;
}

int champ_touche(champ *c, const pf_evenement *e)
{
    int n = (int)strlen(c->t);
    if (c->cur > n) c->cur = n;
    switch (e->code) {
    case KEY_ENTER: case KEY_KPENTER: return 1;
    case KEY_ESC: return -1;
    case KEY_LEFT: c->tout = 0; c->cur = precedent_car(c->t, c->cur); return 0;
    case KEY_RIGHT: c->tout = 0; c->cur = suivant_car(c->t, c->cur); return 0;
    case KEY_HOME: c->tout = 0; c->cur = 0; return 0;
    case KEY_END: c->tout = 0; c->cur = n; return 0;
    case KEY_BACKSPACE:
        if (c->tout) { champ_vider(c); return 2; }
        if (c->cur > 0) { int p = precedent_car(c->t, c->cur); memmove(c->t + p, c->t + c->cur, (size_t)(n - c->cur + 1)); c->cur = p; explicit_bzero(c->t + strlen(c->t), sizeof c->t - strlen(c->t)); }
        return 2;
    case KEY_DELETE:
        if (c->tout) { champ_vider(c); return 2; }
        if (c->cur < n) { int s = suivant_car(c->t, c->cur); memmove(c->t + c->cur, c->t + s, (size_t)(n - s + 1)); explicit_bzero(c->t + strlen(c->t), sizeof c->t - strlen(c->t)); }
        return 2;
    }
    if (e->ctrl) {
        char l = lettre(e);
        if (l == 'a') { c->tout = 1; c->cur = n; return 0; }
        if ((l == 'c' || l == 'x') && !c->masque) { copier(c->t); if (l == 'x') { champ_vider(c); return 2; } return 0; }
        if (l == 'v') { if (c->tout) champ_vider(c); inserer(c, presse); return 2; }
        return 0;
    }
    if ((unsigned char)e->texte[0] >= 32) {
        if (c->tout) champ_vider(c);
        inserer(c, e->texte);
        return 2;
    }
    return 0;
}

void champ_dessiner(champ *c, int x, int y, int l, int h, int actif_, const char *indice)
{
    gfx_rect_arrondi(toile, x, y, l, h, 6, RVB(255, 255, 255));
    gfx_contour_arrondi(toile, x, y, l, h, 6, actif_ ? C_BLEU : RVB(180, 188, 204));
    char vu[1100];
    if (c->masque) {
        size_t k = 0;
        for (const char *p = c->t; *p && k + 4 < sizeof vu; p++) if (((unsigned char)*p & 0xC0) != 0x80) { memcpy(vu + k, "•", 3); k += 3; }
        vu[k] = 0;
    } else chaine_copier(vu, c->t, sizeof vu);
    float tt = 14;
    int ty = y + (h - 17) / 2;
    if (!vu[0] && indice && !actif_) { texte(x + 10, ty, indice, RVB(150, 155, 170), tt, 0); return; }
    /* curseur : position en octets dans l'affichage */
    int cur = c->cur;
    if (c->masque) { int k = 0; for (int i = 0; i < c->cur && c->t[i]; i++) if (((unsigned char)c->t[i] & 0xC0) != 0x80) k++; cur = k * 3; }
    char avant[1100];
    chaine_copier(avant, vu, sizeof avant);
    if (cur < (int)strlen(avant)) avant[cur] = 0;
    int decal = 0, la = largeur(avant, tt, 0);
    if (la > l - 24) decal = la - (l - 24);
    rect ancien = toile->clip;
    gfx_clip(toile, x + 4, y, l - 8, h);
    if (c->tout && vu[0] && actif_) gfx_rect(toile, x + 9 - decal, y + 5, largeur(vu, tt, 0) + 2, h - 10, RVB(190, 215, 250));
    texte(x + 10 - decal, ty, vu, C_TEXTE, tt, 0);
    if (actif_ && ((long)(maintenant / 530) % 2 == 0 || 1)) gfx_rect(toile, x + 10 - decal + la, y + 6, 2, h - 12, C_BLEU);
    toile->clip = ancien;
}

/* ------------------------------ menus --------------------------------- */
typedef struct { char t[200]; int act, arg, coche, gris, sep; } article;
#define ARTICLES_MAX 48
static article menu[ARTICLES_MAX];
static int nb_menu, menu_ouvert, menu_x, menu_y;
static char menu_lien[1024], menu_image[1024], menu_sel[2048];
static int menu_edition, menu_image_modifiee;
static int menu_px, menu_py;

static void menu_vider(void) { nb_menu = 0; }
static void menu_art(const char *t, int act, int arg) { if (nb_menu < ARTICLES_MAX) menu[nb_menu++] = (article){ "", act, arg, 0, 0, 0 }, chaine_copier(menu[nb_menu - 1].t, t, sizeof menu[0].t); }
static void menu_coche(const char *t, int act, int arg, int coche) { menu_art(t, act, arg); if (nb_menu) menu[nb_menu - 1].coche = coche ? 1 : 2; }
static void menu_gris(const char *t) { menu_art(t, A_RIEN, 0); if (nb_menu) menu[nb_menu - 1].gris = 1; }
static void menu_sep(void) { if (nb_menu < ARTICLES_MAX) { memset(&menu[nb_menu], 0, sizeof menu[0]); menu[nb_menu++].sep = 1; } }
static void menu_montrer(int x, int y) { menu_x = x; menu_y = y; menu_ouvert = 1; sale = 1; }

static void menu_dessiner(void)
{
    int l = 220, h = 8;
    for (int i = 0; i < nb_menu; i++) {
        int w = largeur(menu[i].t, 14, 0) + (menu[i].coche ? 64 : 40);
        if (w > l) l = w;
        h += menu[i].sep ? 9 : 30;
    }
    if (l > 520) l = 520;
    int x = menu_x, y = menu_y;
    if (x + l > EL - 4) x = EL - 4 - l;
    if (y + h > EH - 4) y = EH - 4 - h;
    if (x < 4) x = 4;
    if (y < 4) y = 4;
    modal_debut = nb_zones;
    zone_ajouter(0, 0, EL, EH, A_MENU_FOND, 0);
    gfx_ombre(toile, x, y, l, h, 10, 14, RVBA(0, 0, 0, 90));
    gfx_rect_arrondi(toile, x, y, l, h, 10, RVBA(252, 253, 255, 250));
    gfx_contour_arrondi(toile, x, y, l, h, 10, RVB(190, 198, 214));
    int yy = y + 4;
    for (int i = 0; i < nb_menu; i++) {
        if (menu[i].sep) { gfx_rect(toile, x + 10, yy + 4, l - 20, 1, C_LIGNE); yy += 9; continue; }
        int sv = dedans(x + 4, yy, l - 8, 30) && !menu[i].gris && menu[i].act;
        if (sv) gfx_rect_arrondi(toile, x + 4, yy, l - 8, 30, 6, RVB(214, 230, 252));
        int tx = x + 16;
        if (menu[i].coche) {
            if (menu[i].coche == 1) { gfx_ligne(toile, x + 14, yy + 15, x + 19, yy + 20, 2.2f, C_BLEU); gfx_ligne(toile, x + 19, yy + 20, x + 28, yy + 9, 2.2f, C_BLEU); }
            tx = x + 38;
        }
        char t[400];
        couper(t, menu[i].t, l - (tx - x) - 12, 14);
        texte(tx, yy + 6, t, menu[i].gris ? RVB(140, 146, 160) : C_TEXTE, 14, 0);
        if (!menu[i].gris && menu[i].act) zone_ajouter(x + 4, yy, l - 8, 30, A_RIEN - 1000 - i, 0);   /* -1000-i : article i */
        yy += 30;
    }
}

/* ------------------------------ dialogues ----------------------------- */
enum { DLG_AUCUN, DLG_JS, DLG_COFFRE_OUVRIR, DLG_COFFRE_CREER, DLG_COFFRE_CHANGER, DLG_FAVORI, DLG_EXTENSION,
       DLG_UA_PERSO, DLG_MONTRER, DLG_CONFIRMER,
       DLG_PROTECTION, DLG_PERMISSION, DLG_INFO };          /* 2.1 */
static int dlg, dlg_arg, dlg_nb_champs, dlg_nb_boutons, dlg_focus;
static char dlg_titre[200], dlg_texte[2600], dlg_err[240], dlg_session[64];
static int dlg_case;                       /* 2.1 : case à cocher sous le texte */
static const char *dlg_case_texte;
static const char *dlg_libelles[2];
static const char *dlg_boutons[3];
static int dlg_styles[3];
static char dlg_secret[160];
static double dlg_jusqua;

static void dlg_ouvrir(int genre, const char *titre_, const char *txt)
{
    if (dlg == DLG_JS && genre != DLG_JS) {      /* une page attend sa réponse : on refuse pour elle */
        cdp_envoyer_a(&moteur, dlg_session, "Page.handleJavaScriptDialog", "{\"accept\":false}");
    }
    if (dlg == DLG_PERMISSION) permission_repondre(0, 0);   /* idem pour une permission */
    dlg = genre;
    dlg_case = 0;
    dlg_case_texte = NULL;
    chaine_copier(dlg_titre, titre_, sizeof dlg_titre);
    chaine_copier(dlg_texte, txt ? txt : "", sizeof dlg_texte);
    dlg_err[0] = 0;
    dlg_nb_champs = dlg_nb_boutons = 0;
    champ_vider(&dlg_champ[0]); champ_vider(&dlg_champ[1]);
    dlg_champ[0].masque = dlg_champ[1].masque = 0;
    dlg_focus = 0;
    focus = NULL;
    menu_ouvert = 0;
    sale = 1;
}
static void dlg_bouton(const char *t, int style) { if (dlg_nb_boutons < 3) { dlg_styles[dlg_nb_boutons] = style; dlg_boutons[dlg_nb_boutons++] = t; } }
static void dlg_champ_ajouter(const char *lib, int masque, const char *val)
{
    if (dlg_nb_champs >= 2) return;
    dlg_libelles[dlg_nb_champs] = lib;
    dlg_champ[dlg_nb_champs].masque = masque;
    if (val) champ_mettre(&dlg_champ[dlg_nb_champs], val);
    dlg_nb_champs++;
    focus = &dlg_champ[0];
}

static void dlg_fermer(void)
{
    champ_vider(&dlg_champ[0]); champ_vider(&dlg_champ[1]);
    explicit_bzero(dlg_secret, sizeof dlg_secret);
    dlg = DLG_AUCUN;
    focus = NULL;
    sale = 1;
}

void dialogue_js(int genre, const char *message_, const char *defaut)
{
    static const char *T[] = { "Message du site", "Le site demande une confirmation", "Le site pose une question" };
    char t[300];
    snprintf(t, sizeof t, "%s · %s", T[genre < 0 || genre > 2 ? 0 : genre], ACTIF->hote[0] ? ACTIF->hote : "page");
    dlg_ouvrir(DLG_JS, t, message_);
    dlg_arg = genre;
    chaine_copier(dlg_session, ACTIF->session, sizeof dlg_session);
    if (genre == 2) dlg_champ_ajouter("Réponse", 0, defaut);
    if (genre) dlg_bouton("Annuler", 0);
    dlg_bouton("OK", 1);
}

static void js_repondre(int oui)
{
    char p[1400], e[1200];
    json_echapper(e, sizeof e, dlg_champ[0].t);
    snprintf(p, sizeof p, "{\"accept\":%s,\"promptText\":%s}", oui ? "true" : "false", e);
    cdp_envoyer_a(&moteur, dlg_session, "Page.handleJavaScriptDialog", p);
}

void dialogue_coffre(int creer)
{
    if (creer) {
        dlg_ouvrir(DLG_COFFRE_CREER, "Créer le coffre à mots de passe",
                   "Choisis un mot de passe maître (8 caractères au moins). Il chiffre le coffre en AES-256 ; "
                   "il n'est enregistré nulle part : si tu l'oublies, personne ne pourra rouvrir le coffre. "
                   "Les cartes bancaires ne sont jamais enregistrées.");
        dlg_champ_ajouter("Mot de passe maître", 1, NULL);
        dlg_champ_ajouter("Encore une fois", 1, NULL);
        dlg_bouton("Annuler", 0);
        dlg_bouton("Créer le coffre", 1);
    } else {
        dlg_ouvrir(DLG_COFFRE_OUVRIR, "Ouvrir le coffre à mots de passe",
                   "Le coffre est fermé. Tape ton mot de passe maître : il est effacé de la mémoire dès qu'il a servi.");
        dlg_champ_ajouter("Mot de passe maître", 1, NULL);
        dlg_bouton("Annuler", 0);
        dlg_bouton("Ouvrir", 1);
    }
}

void dialogue_favori(int i)
{
    if (i < 0) {
        onglet *o = ACTIF;
        if (o->interne || !o->url[0] || !strcmp(o->url, "about:blank")) { dire("Rien à mettre en favori ici"); return; }
        i = favori_trouver(o->url);
        if (i < 0) { favori_ajouter(o->url, o->titre[0] ? o->titre : o->hote); i = favori_trouver(o->url); }
        if (i < 0) { dire("Trop de favoris"); return; }
    }
    if (i >= nb_favoris) return;
    dlg_ouvrir(DLG_FAVORI, "Favori", "Le nom et l'adresse du favori.");
    dlg_arg = i;
    dlg_champ_ajouter("Nom", 0, favoris[i].titre);
    dlg_champ_ajouter("Adresse", 0, favoris[i].url);
    dlg_champ[0].tout = 1;
    dlg_bouton("Supprimer", 2);
    dlg_bouton("Annuler", 0);
    dlg_bouton("Enregistrer", 1);
}

void dialogue_extension(const char *nom, const char *motifs)
{
    char t[900];
    snprintf(t, sizeof t, "« %s » veut s'installer. Il agira sur : %s\n\nUn script utilisateur lit et modifie les pages où il agit "
             "(jamais dans les onglets privés). N'installe que des scripts dont tu as confiance.", nom, motifs[0] ? motifs : "(tous les sites)");
    dlg_ouvrir(DLG_EXTENSION, "Installer une extension (script utilisateur) ?", t);
    dlg_bouton("Annuler", 0);
    dlg_bouton("Installer", 1);
}

static void dialogue_confirmer(const char *titre_, const char *txt, int act, const char *oui)
{
    dlg_ouvrir(DLG_CONFIRMER, titre_, txt);
    dlg_arg = act;
    dlg_bouton("Annuler", 0);
    dlg_bouton(oui, 2);
}

static void agir(int act, int arg, int bouton_, int x, int y);

/* ------------------- 2.1 : protections et permissions ------------------ */
static void effet_reglage(int effet);
static int *prot_val, prot_nouveau, prot_effet;

/* le changement baisse-t-il une protection ? renvoie son explication, ou -1 */
static int protection_baissee(const int *val, int nouveau)
{
    if (val == &R.js && !nouveau) return EX_JS;
    if (val == &R.cookies && !nouveau) return EX_COOKIES;
    if (val == &R.php_bloque && !nouveau) return EX_PHP;
    if (val == &R.tiers && nouveau) return EX_TIERS;
    if (val == &R.https && !nouveau) return EX_HTTPS;
    if (val == &R.liens && !nouveau) return EX_LIENS;
    if (val == &R.gpc && !nouveau) return EX_GPC;
    if (val == &R.popups && nouveau) return EX_POPUPS;
    if (val == &R.bannieres && !nouveau) return EX_BANNIERES;
    return -1;
}

/* fenêtre d'avertissement : ce qu'on risque, pourquoi garder, quand couper */
static void dialogue_protection(int ex, int *val, int nouveau, int effet)
{
    const explication *x = &EXPLICATIONS[ex];
    char t[2600], ti[200];
    snprintf(t, sizeof t, "Ce que tu risques : %s\n\nÀ savoir : %s\n\nQuand la couper est utile : %s", x->risques, x->pourquoi, x->quand);
    snprintf(ti, sizeof ti, "Couper « %s » ?", x->nom);
    dlg_ouvrir(DLG_PROTECTION, ti, t);
    prot_val = val; prot_nouveau = nouveau; prot_effet = effet;
    dlg_case_texte = "Je sais ce que je fais, pas besoin d'explications";
    dlg_bouton("Garder la protection", 1);
    dlg_bouton("Couper quand même", 2);
}

void dialogue_permission(const char *hote, int type)
{
    static const char *const QUOI[NB_PERMS] = {
        "Le site pourra filmer avec ta caméra tant que cette page reste ouverte.",
        "Le site pourra écouter ton micro tant que cette page reste ouverte.",
        "Le site saura où tu te trouves (ta ville, parfois à quelques mètres près).",
        "Le site pourra t'envoyer des notifications, même quand tu fais autre chose." };
    if (type < 0 || type >= NB_PERMS) return;
    char t[900], ti[300];
    snprintf(ti, sizeof ti, type == PERM_NOTIFS ? "%s veut %s" : "%s veut utiliser %s", hote, PERM_NOMS[type]);
    snprintf(t, sizeof t, "%s\n\nN'accepte que si tu viens toi-même de demander cette fonction (appel vidéo, carte, "
             "messagerie). Sinon refuse : le site continue de marcher sans. « Retenir » garde ton choix ; tu peux le retirer "
             "dans Paramètres → Permissions des sites ou dans le menu du cadenas.", QUOI[type]);
    dlg_ouvrir(DLG_PERMISSION, ti, t);
    dlg_case_texte = "Retenir mon choix pour ce site";
    dlg_bouton("Refuser", 0);
    dlg_bouton("Autoriser", 1);
}

static void date_lisible(double s, char *d, size_t n)
{
    time_t t = (time_t)s;
    struct tm tm;
    localtime_r(&t, &tm);
    strftime(d, n, "%d/%m/%Y", &tm);
}

void certificat_details(void)
{
    onglet *o = nb_onglets ? ACTIF : NULL;
    if (!o || o->interne || !o->hote[0]) return;
    char t[2600], ti[300];
    snprintf(ti, sizeof ti, "Connexion avec %s", o->hote);
    if (strncmp(o->url, "https://", 8))
        snprintf(t, sizeof t, "Cette page n'est pas chiffrée (http) : il n'y a pas de certificat. Ce que tu lis et tapes ici "
                 "peut être lu ou modifié par n'importe qui sur le réseau (wifi public, box piratée). Ne tape aucun mot de passe.");
    else if (!o->cert.present)
        snprintf(t, sizeof t, "Le certificat de cette page n'a pas été reçu (page ouverte avant, ou servie depuis le cache). "
                 "Recharge la page (F5) pour le voir.");
    else {
        char d1[32], d2[32], etat[80];
        date_lisible(o->cert.debut, d1, sizeof d1);
        date_lisible(o->cert.fin, d2, sizeof d2);
        double reste = (o->cert.fin - (double)time(NULL)) / 86400.0;
        if (reste < 0) snprintf(etat, sizeof etat, "EXPIRÉ");
        else if (reste < 14) snprintf(etat, sizeof etat, "expire dans %d jour(s) !", (int)reste);
        else snprintf(etat, sizeof etat, "encore %d jours", (int)reste);
        const char *ct = !strcmp(o->cert.ct, "compliant") ? "respectée (certificat publié dans les journaux publics)"
                       : !strcmp(o->cert.ct, "not-compliant") ? "NON respectée : méfiance" : "non vérifiée";
        int vieux = !strcmp(o->cert.protocole, "TLS 1") || !strcmp(o->cert.protocole, "TLS 1.0") || !strcmp(o->cert.protocole, "TLS 1.1");
        int auto_signe = o->cert.sujet[0] && !strcmp(o->cert.sujet, o->cert.emetteur);
        snprintf(t, sizeof t, "Délivré à : %s\nPar l'autorité : %s\nValable du %s au %s (%s)\nNoms couverts : %d\n"
                 "Chiffrement : %s, %s%s%s\nTransparence : %s\n\n%s",
                 o->cert.sujet, o->cert.emetteur, d1, d2, etat, o->cert.noms, o->cert.protocole, o->cert.chiffre,
                 o->cert.echange[0] ? ", échange de clés " : "", o->cert.echange, ct,
                 vieux ? "Attention : protocole ancien (TLS 1.0 ou 1.1), plus considéré comme sûr."
                 : auto_signe ? "Attention : certificat signé par le site lui-même, personne ne garantit son identité."
                 : "Le cadenas veut dire que la connexion est chiffrée et que le certificat est valable pour ce nom. "
                   "Il ne dit pas que le site est honnête : vérifie toujours l'adresse.");
    }
    dlg_ouvrir(DLG_INFO, ti, t);
    dlg_bouton("Fermer", 1);
}

static int coffre_est_ouvert(void)
{
    uint8_t r[16];
    return coffre_disponible() && coffre_requete('T', NULL, 0, r, sizeof r, NULL) == 'K' && r[1] == '1';
}
static int cofre_sale = 1;

static void dlg_valider(int b)
{
    int dernier = b == dlg_nb_boutons - 1;
    switch (dlg) {
    case DLG_JS: js_repondre(dlg_nb_boutons == 1 || dernier); dlg_fermer(); break;
    case DLG_COFFRE_OUVRIR: case DLG_COFFRE_CREER: {
        if (!dernier) { coffre_apres = 0; dlg_fermer(); break; }
        if (dlg == DLG_COFFRE_CREER) {
            if (strlen(dlg_champ[0].t) < 8) { chaine_copier(dlg_err, "8 caractères au moins.", sizeof dlg_err); break; }
            if (strcmp(dlg_champ[0].t, dlg_champ[1].t)) { chaine_copier(dlg_err, "Les deux mots de passe ne sont pas pareils.", sizeof dlg_err); champ_vider(&dlg_champ[1]); break; }
        }
        texte(EL / 2 - 60, EH / 2 + 120, "Ouverture…", C_TEXTE, 14, 0);
        pf_afficher(fen);
        char err[200] = "";
        int r = coffre_ouvrir(dlg_champ[0].t, err, sizeof err);
        champ_vider(&dlg_champ[0]); champ_vider(&dlg_champ[1]);
        if (r < 0) { snprintf(dlg_err, sizeof dlg_err, "%s", err[0] ? err : "Mot de passe incorrect."); focus = &dlg_champ[0]; break; }
        dlg_fermer();
        cofre_sale = 1;
        dire("Coffre ouvert");
        coffre_suite();
        break;
    }
    case DLG_COFFRE_CHANGER:
        if (!dernier) { dlg_fermer(); break; }
        if (strlen(dlg_champ[0].t) < 8) { chaine_copier(dlg_err, "8 caractères au moins.", sizeof dlg_err); break; }
        if (strcmp(dlg_champ[0].t, dlg_champ[1].t)) { chaine_copier(dlg_err, "Les deux mots de passe ne sont pas pareils.", sizeof dlg_err); break; }
        if (coffre_changer(dlg_champ[0].t) == 0) dire("Mot de passe maître changé");
        else dire("Impossible : ouvre d'abord le coffre");
        dlg_fermer();
        break;
    case DLG_FAVORI:
        if (b == 0) { favori_supprimer(dlg_arg); favoris_enregistrer(); dire("Favori supprimé"); }
        else if (dernier && dlg_arg < nb_favoris) {
            if (dlg_champ[1].t[0]) chaine_copier(favoris[dlg_arg].url, dlg_champ[1].t, sizeof favoris[0].url);
            chaine_copier(favoris[dlg_arg].titre, dlg_champ[0].t[0] ? dlg_champ[0].t : favoris[dlg_arg].url, sizeof favoris[0].titre);
            favoris_enregistrer();
        }
        dlg_fermer();
        break;
    case DLG_EXTENSION: extension_confirmer(dernier); dlg_fermer(); break;
    case DLG_UA_PERSO:
        if (dernier && dlg_champ[0].t[0]) {
            chaine_copier(R.ua_perso, dlg_champ[0].t, sizeof R.ua_perso);
            R.ua = NB_IDENTITES - 1;
            reglages_enregistrer();
            appliquer_identite();
            recharger();
        }
        dlg_fermer();
        break;
    case DLG_MONTRER: case DLG_INFO: dlg_fermer(); break;
    case DLG_PROTECTION:
        if (dlg_case) R.expert = 1;
        if (dernier && prot_val) { *prot_val = prot_nouveau; dlg_fermer(); effet_reglage(prot_effet); dire("Protection coupée : tu peux la remettre à tout moment"); }
        else { dlg_fermer(); if (R.expert) reglages_enregistrer(); }
        prot_val = NULL;
        break;
    case DLG_PERMISSION: { int c = dlg_case; dlg = DLG_AUCUN; permission_repondre(dernier, c); dlg_fermer(); break; }
    case DLG_CONFIRMER: { int a = dlg_arg; dlg_fermer(); if (dernier) agir(a, 1, 0, 0, 0); break; }
    }
}

static void dlg_dessiner(void)
{
    if (dlg == DLG_MONTRER && maintenant > dlg_jusqua) { dlg_fermer(); return; }
    modal_debut = nb_zones;
    zone_ajouter(0, 0, EL, EH, A_DLG_FOND, 0);
    gfx_rect(toile, 0, 0, EL, EH, RVBA(10, 20, 40, 110));
    int l = 540, x = (EL - l) / 2;
    int ht = paragraphe(0, 0, l - 48, dlg_texte, 0, 15, 0);
    int h = 70 + ht + dlg_nb_champs * 64 + (dlg_err[0] ? 28 : 0) + 64 + (dlg == DLG_MONTRER ? 50 : 0) + (dlg_case_texte ? 38 : 0);
    int y = (EH - h) / 2;
    if (y < 8) y = 8;
    gfx_ombre(toile, x, y, l, h, 14, 24, RVBA(0, 0, 0, 120));
    gfx_rect_arrondi(toile, x, y, l, h, 14, RVB(250, 251, 254));
    gfx_degrade(toile, x + 1, y + 1, l - 2, 50, RVB(226, 236, 250), RVB(244, 247, 252));
    gfx_contour_arrondi(toile, x, y, l, h, 14, RVB(160, 176, 204));
    if (dlg >= DLG_COFFRE_OUVRIR && dlg <= DLG_COFFRE_CHANGER) {
        gfx_rect_arrondi(toile, x + 22, y + 22, 14, 11, 3, RVB(200, 150, 40));
        gfx_cercle(toile, x + 29, y + 20, 5, 2, RVB(200, 150, 40));
    }
    char t[300];
    couper(t, dlg_titre, l - 70, 18);
    texte(x + (dlg >= DLG_COFFRE_OUVRIR && dlg <= DLG_COFFRE_CHANGER ? 46 : 24), y + 16, t, C_TEXTE, 18, 1);
    int yy = y + 62;
    yy += paragraphe(x + 24, yy, l - 48, dlg_texte, RVB(50, 56, 70), 15, 1) + 8;
    if (dlg == DLG_MONTRER) {
        gfx_rect_arrondi(toile, x + 24, yy, l - 48, 40, 8, RVB(255, 248, 225));
        texte(x + 36, yy + 10, dlg_secret, C_TEXTE, 17, 1);
        yy += 50;
    }
    if (dlg_case_texte) {                        /* case à cocher */
        int cx = x + 24, cy = yy + 4;
        gfx_rect_arrondi(toile, cx, cy, 20, 20, 4, dlg_case ? C_BLEU : RVB(255, 255, 255));
        gfx_contour_arrondi(toile, cx, cy, 20, 20, 4, dlg_case ? C_BLEU : RVB(150, 160, 182));
        if (dlg_case) { gfx_ligne(toile, cx + 4, cy + 10, cx + 8, cy + 15, 2.2f, RVB(255, 255, 255)); gfx_ligne(toile, cx + 8, cy + 15, cx + 16, cy + 5, 2.2f, RVB(255, 255, 255)); }
        texte(cx + 30, cy + 1, dlg_case_texte, C_TEXTE, 14, 0);
        zone_ajouter(cx - 4, cy - 4, largeur(dlg_case_texte, 14, 0) + 40, 28, A_DLG_CASE, 0);
        yy += 38;
    }
    for (int i = 0; i < dlg_nb_champs; i++) {
        texte(x + 24, yy, dlg_libelles[i], C_GRIS, 13, 0);
        champ_dessiner(&dlg_champ[i], x + 24, yy + 20, l - 48, 34, focus == &dlg_champ[i], NULL);
        zone_ajouter(x + 24, yy + 20, l - 48, 34, A_DLG_CHAMP, i);
        yy += 64;
    }
    if (dlg_err[0]) { texte(x + 24, yy, dlg_err, C_ROUGE, 14, 0); yy += 28; }
    int bx = x + l - 24;
    for (int i = dlg_nb_boutons - 1; i >= 0; i--) {
        int bl = largeur(dlg_boutons[i], 14, 0) + 36;
        if (bl < 96) bl = 96;
        bx -= bl;
        bouton(bx, y + h - 50, bl, 32, dlg_boutons[i], dlg_styles[i], A_DLG_BOUTON, i);
        bx -= 10;
    }
}

/* ------------------------------ bandeau ------------------------------- */
static int info_genre = -1;              /* 0 info, 1 coffre, 2 Google Recherche redirigée (2.2) */
static char info_texte[400];
void interface_info(int genre, const char *t) { info_genre = genre; chaine_copier(info_texte, t, sizeof info_texte); sale = 1; }
void interface_fermer_info(void) { info_genre = -1; sale = 1; }

/* ------------------------- icônes de la barre ------------------------- */
void dessiner_icone_page(int x, int y, const char *hote, int prive)
{
    if (prive) {
        gfx_disque(toile, x + 8, y + 8, 8, C_PRIVE);
        gfx_ellipse(toile, x + 5, y + 8, 2.5f, 2, RVB(255, 255, 255));
        gfx_ellipse(toile, x + 11, y + 8, 2.5f, 2, RVB(255, 255, 255));
        return;
    }
    unsigned hsh = 5381;
    const char *h = hote && hote[0] ? hote : "?";
    if (!strncmp(h, "www.", 4)) h += 4;
    for (const char *p = h; *p; p++) hsh = hsh * 33 + (unsigned char)*p;
    couleur c = RVB(60 + hsh % 140, 60 + (hsh >> 8) % 140, 80 + (hsh >> 16) % 140);
    gfx_disque(toile, x + 8, y + 8, 8, c);
    char l[2] = { (char)(h[0] >= 'a' && h[0] <= 'z' ? h[0] - 32 : h[0]), 0 };
    if ((unsigned char)l[0] < 32 || (unsigned char)l[0] > 126) l[0] = '?';
    texte(x + 8 - largeur(l, 11, 1) / 2, y + 1, l, RVB(255, 255, 255), 11, 1);
}

static void icone_fleche(int cx, int cy, int sens, couleur c)
{
    gfx_ligne(toile, cx - 7 * sens, cy, cx + 7 * sens, cy, 2.2f, c);
    gfx_ligne(toile, cx - 7 * sens, cy, cx - 1 * sens, cy - 6, 2.2f, c);
    gfx_ligne(toile, cx - 7 * sens, cy, cx - 1 * sens, cy + 6, 2.2f, c);
}

static void icone_etoile(int cx, int cy, float r, int pleine, couleur c)
{
    float px[10], py[10];
    for (int i = 0; i < 10; i++) {
        float a = (float)(-M_PI / 2 + i * M_PI / 5), rr = i % 2 ? r * 0.45f : r;
        px[i] = cx + rr * cosf(a); py[i] = cy + rr * sinf(a);
    }
    if (pleine) for (int i = 0; i < 10; i++) gfx_triangle(toile, (float)cx, (float)cy, px[i], py[i], px[(i + 1) % 10], py[(i + 1) % 10], c);
    for (int i = 0; i < 10; i++) gfx_ligne(toile, px[i], py[i], px[(i + 1) % 10], py[(i + 1) % 10], 1.6f, c);
}

static void icone_cle(int cx, int cy, couleur c)
{
    gfx_cercle(toile, cx - 4, cy, 4, 2, c);
    gfx_ligne(toile, cx, cy, cx + 8, cy, 2, c);
    gfx_ligne(toile, cx + 6, cy, cx + 6, cy + 4, 2, c);
    gfx_ligne(toile, cx + 3, cy, cx + 3, cy + 3, 2, c);
}

static void icone_cadenas(int cx, int cy, couleur c)
{
    gfx_rect_arrondi(toile, cx - 5, cy - 1, 11, 9, 2, c);
    gfx_cercle(toile, cx + 0.5f, cy - 3, 3.5f, 1.8f, c);
    gfx_rect(toile, cx - 5, cy - 1, 11, 2, c);
}

static void bouton_icone(int x, int y, int l, int h, int act, int actif_)
{
    if (actif_ && dedans(x, y, l, h)) gfx_rect_arrondi(toile, x, y, l, h, 8, RVB(222, 230, 244));
    zone_ajouter(x, y, l, h, act, 0);
}

/* --------------------------- suggestions ------------------------------ */
#define SUG_MAX 7
static int nb_sug, sug_sel = -1;
static char sug_url[SUG_MAX][512], sug_titre[SUG_MAX][160];

static int contient(const char *a, const char *b)
{
    if (!b[0]) return 0;
    size_t lb = strlen(b);
    for (const char *p = a; *p; p++) if (!strncasecmp(p, b, lb)) return 1;
    return 0;
}

static void suggestions_calculer(void)
{
    nb_sug = 0;
    sug_sel = -1;
    const char *q = adresse.t;
    if (!q[0] || adresse.tout) return;
    for (int i = 0; i < nb_favoris && nb_sug < 3; i++)
        if (contient(favoris[i].url, q) || contient(favoris[i].titre, q)) {
            chaine_copier(sug_url[nb_sug], favoris[i].url, 512);
            chaine_copier(sug_titre[nb_sug], favoris[i].titre, 160);
            nb_sug++;
        }
    for (int i = nb_historique - 1; i >= 0 && nb_sug < SUG_MAX; i--) {
        if (!contient(historique[i].url, q) && !contient(historique[i].titre, q)) continue;
        int deja = 0;
        for (int k = 0; k < nb_sug; k++) if (!strcmp(sug_url[k], historique[i].url)) deja = 1;
        if (deja) continue;
        chaine_copier(sug_url[nb_sug], historique[i].url, 512);
        chaine_copier(sug_titre[nb_sug], historique[i].titre, 160);
        nb_sug++;
    }
}

void barre_saisie_activer(void)
{
    onglet *o = nb_onglets ? ACTIF : NULL;
    champ_mettre(&adresse, o && o->url[0] && strcmp(o->url, "about:blank") ? o->url : "");
    adresse.tout = 1;
    focus = &adresse;
    nb_sug = 0;
    sale = 1;
}
int saisie_active(void) { return focus == &adresse; }

/* --------------------------- glisser-déposer -------------------------- */
static int glisse;                 /* 1 onglet, 2 favori, 3 lien venu de la page */
static int glisse_i, glisse_x0, glisse_y0, glisse_parti;
static char glisse_url[1024], glisse_titre[200];

void glisser_lien(const char *url, const char *t)
{
    glisse = 3;
    glisse_parti = 1;
    chaine_copier(glisse_url, url, sizeof glisse_url);
    chaine_copier(glisse_titre, t && t[0] ? t : url, sizeof glisse_titre);
    sale = 1;
}

/* ------------------------------ onglets ------------------------------- */
static int largeur_onglet(void)
{
    int l = nb_onglets ? (EL - 60) / nb_onglets : 200;
    return l > 230 ? 230 : l < 70 ? 70 : l;
}

static void dessiner_onglets(void)
{
    gfx_degrade(toile, 0, 0, EL, H_ONGLETS, C_FOND_ONGLETS1, C_FOND_ONGLETS2);
    int lo = largeur_onglet(), x = 6;
    for (int i = 0; i < nb_onglets; i++, x += lo) {
        onglet *o = &onglets[i];
        int a = i == actif, sv = dedans(x, 5, lo - 2, H_ONGLETS - 5);
        couleur fond = a ? C_BARRE : sv ? RVB(222, 232, 246) : RVB(200, 214, 236);
        if (o->prive) fond = a ? RVB(244, 238, 252) : RVB(214, 200, 236);
        gfx_rect_arrondi(toile, x, 5, lo - 2, H_ONGLETS + 4, 9, fond);
        if (!a && i + 1 != actif) gfx_rect(toile, x + lo - 2, 12, 1, 16, RVB(160, 176, 204));
        if (o->interne) {
            gfx_disque(toile, x + 18, 19, 8, C_GRIS);
            gfx_cercle(toile, x + 18, 19, 3.5f, 1.8f, RVB(255, 255, 255));
        } else if (o->chargement) {
            float t = (float)fmod(maintenant / 160.0, 12.0);
            gfx_cercle(toile, x + 18, 19, 7, 2, RVB(200, 210, 228));
            gfx_disque(toile, x + 18 + 7 * cosf(t * 0.5236f), 19 + 7 * sinf(t * 0.5236f), 2.4f, C_BLEU);
        } else dessiner_icone_page(x + 10, 11, o->hote, o->prive);
        char t[300];
        couper(t, o->titre[0] ? o->titre : o->url[0] && strcmp(o->url, "about:blank") ? o->url : "Nouvel onglet", lo - 66, 13);
        texte(x + 32, 11, t, a ? C_TEXTE : RVB(50, 58, 74), 13, 0);
        zone_ajouter(x, 5, lo - 2, H_ONGLETS - 5, A_ONGLET, i);
        int fx = x + lo - 26;
        if (dedans(fx, 10, 18, 18)) gfx_disque(toile, fx + 9, 19, 9, RVB(206, 214, 230));
        gfx_ligne(toile, fx + 5, 15, fx + 13, 23, 1.6f, C_GRIS);
        gfx_ligne(toile, fx + 13, 15, fx + 5, 23, 1.6f, C_GRIS);
        zone_ajouter(fx, 10, 18, 18, A_ONGLET_FERMER, i);
    }
    int px = x + 6;
    if (dedans(px, 7, 26, 24)) gfx_rect_arrondi(toile, px, 7, 26, 24, 7, RVB(222, 232, 246));
    gfx_ligne(toile, px + 13, 13, px + 13, 25, 2, RVB(60, 70, 90));
    gfx_ligne(toile, px + 7, 19, px + 19, 19, 2, RVB(60, 70, 90));
    zone_ajouter(px, 7, 26, 24, A_ONGLET_PLUS, 0);
}

/* --------------------------- barre d'outils --------------------------- */
static int adr_x, adr_l;

/* petite étiquette cliquable dans la barre d'adresse (menu du site) */
static int pastille(int x, int ay, int xmax, const char *m, couleur fond, couleur c)
{
    int ml = largeur(m, 11, 0) + 14;
    if (x + ml > xmax) return 0;
    gfx_rect_arrondi(toile, x, ay + 6, ml, 16, 8, fond);
    texte(x + 7, ay + 7, m, c, 11, 0);
    zone_ajouter(x, ay + 2, ml, 24, A_SITE, 0);
    return ml + 6;
}

static void dessiner_outils(void)
{
    int y0 = H_ONGLETS;
    gfx_rect(toile, 0, y0, EL, H_OUTILS, C_BARRE);
    onglet *o = nb_onglets ? ACTIF : NULL;
    couleur ci = RVB(60, 68, 86);
    int y = y0 + 6;
    bouton_icone(8, y, 32, 32, A_PRECEDENT, 1);  icone_fleche(24, y + 16, 1, ci);
    bouton_icone(42, y, 32, 32, A_SUIVANT, 1);   icone_fleche(58, y + 16, -1, ci);
    bouton_icone(76, y, 32, 32, A_RECHARGER, 1);
    if (o && o->chargement) { gfx_ligne(toile, 86, y + 10, 98, y + 22, 2.2f, ci); gfx_ligne(toile, 98, y + 10, 86, y + 22, 2.2f, ci); }
    else {
        gfx_cercle(toile, 92, y + 16, 7, 2.2f, ci);
        gfx_rect(toile, 93, y + 6, 9, 8, dedans(76, y, 32, 32) ? RVB(222, 230, 244) : C_BARRE);
        gfx_triangle(toile, 93, y + 5, 93, y + 13, 99, y + 9, ci);
    }
    bouton_icone(110, y, 32, 32, A_ACCUEIL, 1);
    gfx_triangle(toile, 117, y + 16, 135, y + 16, 126, y + 7, ci);
    gfx_rect(toile, 119, y + 16, 14, 10, ci);
    gfx_rect(toile, 124, y + 19, 4, 7, C_BARRE);

    /* à droite : identité, coffre, VPN, menu */
    int dx = EL - 8;
    dx -= 34; bouton_icone(dx, y, 32, 32, A_MENU, 1);
    for (int k = 0; k < 3; k++) gfx_rect_arrondi(toile, dx + 9, y + 10 + k * 6, 15, 2, 1, ci);
    if (R.vpn && R.vpn_hote[0]) {
        dx -= 50;
        gfx_rect_arrondi(toile, dx, y + 7, 44, 20, 10, RVB(40, 150, 80));
        texte(dx + 9, y + 9, "VPN", RVB(255, 255, 255), 12, 1);
        zone_ajouter(dx, y + 4, 44, 26, A_VPN, 0);
    }
    dx -= 34; bouton_icone(dx, y, 32, 32, A_CLE, 1);
    icone_cle(dx + 14, y + 16, o && o->identifiants ? RVB(200, 140, 20) : ci);
    if (o && o->identifiants) gfx_disque(toile, dx + 25, y + 8, 4, RVB(220, 150, 20));

    /* barre d'adresse */
    adr_x = 150; adr_l = dx - 8 - adr_x;
    int ay = y + 2, ah = 28, edit = focus == &adresse;
    gfx_rect_arrondi(toile, adr_x, ay, adr_l, ah, 14, edit ? RVB(255, 255, 255) : RVB(240, 243, 248));
    gfx_contour_arrondi(toile, adr_x, ay, adr_l, ah, 14, edit ? C_BLEU : RVB(196, 204, 218));
    /* pastille d'identité (User-Agent) dans la barre */
    const char *idc = identite_court(R.ua);
    int pl = largeur(idc, 12, 0) + 22, px = adr_x + adr_l - pl - 34;
    int svp = dedans(px, ay + 4, pl, 20);
    gfx_rect_arrondi(toile, px, ay + 4, pl, 20, 10, R.ua ? (svp ? RVB(255, 226, 170) : RVB(255, 236, 196)) : (svp ? RVB(214, 226, 246) : RVB(226, 234, 248)));
    texte(px + 8, ay + 6, idc, R.ua ? RVB(130, 80, 0) : RVB(50, 70, 110), 12, 0);
    gfx_triangle(toile, px + pl - 12, ay + 12, px + pl - 6, ay + 12, px + pl - 9, ay + 16, C_GRIS);
    zone_ajouter(px, ay + 4, pl, 20, A_UA, 0);
    /* étoile */
    int fav = o && !o->interne && favori_trouver(o->url) >= 0;
    icone_etoile(adr_x + adr_l - 18, ay + 14, 8, fav, fav ? RVB(230, 160, 20) : C_GRIS);
    zone_ajouter(adr_x + adr_l - 30, ay, 28, ah, A_ETOILE, 0);
    /* sécurité du site */
    int sx0 = adr_x + 6, tx;
    if (o && !o->interne && !edit) {
        int https = !strncmp(o->url, "https://", 8), http = !strncmp(o->url, "http://", 7);
        int puny = strstr(o->hote, "xn--") != NULL;
        if (http || puny) {
            const char *m = puny ? "Adresse piégée ?" : "Non sécurisé";
            int ml = largeur(m, 12, 1) + 30;
            gfx_rect_arrondi(toile, sx0, ay + 4, ml, 20, 10, RVB(253, 226, 222));
            texte(sx0 + 22, ay + 6, m, C_ROUGE, 12, 1);
            gfx_triangle(toile, sx0 + 8, ay + 19, sx0 + 18, ay + 19, sx0 + 13, ay + 8, C_ROUGE);
            zone_ajouter(sx0, ay + 2, ml, 24, A_SITE, 0);
            tx = sx0 + ml + 6;
        } else {
            if (https) icone_cadenas(sx0 + 12, ay + 15, RVB(70, 80, 100));
            else { gfx_cercle(toile, sx0 + 12, ay + 14, 7, 1.6f, C_GRIS); texte(sx0 + 10, ay + 6, "i", C_GRIS, 12, 1); }
            zone_ajouter(sx0, ay + 2, 26, 24, A_SITE, 0);
            tx = sx0 + 28;
        }
        if (o->js_coupe || o->cookies_coupes)
            tx += pastille(tx, ay, px - 160, o->js_coupe && o->cookies_coupes ? "JS + cookies coupés" : o->js_coupe ? "JS coupé" : "cookies coupés",
                           RVB(232, 236, 244), C_GRIS);
        /* 2.1 : permissions en cours, PHP, pisteurs */
        if (o->perms) {
            char m[80] = "Actif : ";
            for (int t = 0, n = 0; t < NB_PERMS; t++)
                if (o->perms & (1 << t)) { size_t k = strlen(m); snprintf(m + k, sizeof m - k, "%s%s", n++ ? " + " : "", PERM_TITRES[t]); }
            tx += pastille(tx, ay, px - 160, m, RVB(253, 226, 222), C_ROUGE);
        }
        if (o->php) tx += pastille(tx, ay, px - 160, "PHP", RVB(255, 236, 206), RVB(160, 90, 0));
        int np = o->pisteurs_connus + o->pisteurs_inconnus;
        if (np) {
            char m[40];
            snprintf(m, sizeof m, "%d pisteur%s", np, np > 1 ? "s" : "");
            tx += pastille(tx, ay, px - 160, m, RVB(236, 228, 250), RVB(110, 60, 170));
        }
    } else tx = sx0 + 12;
    int zl = px - 8 - tx;
    zone_ajouter(tx, ay, zl, ah, A_ADRESSE, 0);
    if (edit) champ_dessiner(&adresse, tx - 8, ay + 1, zl + 8, ah - 2, 1, NULL);
    else if (o) {
        const char *u = o->url;
        if (!u[0] || !strcmp(u, "about:blank")) texte(tx, ay + 6, "Rechercher ou taper une adresse", RVB(140, 145, 160), 14, 0);
        else {
            /* le nom du site en foncé, le reste en gris : on voit à qui on parle */
            const char *h = strstr(u, "://");
            char debut[1100], reste[1100], c1[1100];
            if (h && !o->interne) {
                h += 3;
                size_t lh = strcspn(h, "/?#");
                snprintf(debut, sizeof debut, "%.*s", (int)lh, h);
                chaine_copier(reste, h + lh, sizeof reste);
            } else { chaine_copier(debut, u, sizeof debut); reste[0] = 0; }
            couper(c1, debut, zl, 14);
            int l1 = largeur(c1, 14, 0);
            texte(tx, ay + 6, c1, C_TEXTE, 14, 0);
            if (reste[0] && l1 < zl - 20) { couper(c1, reste, zl - l1, 14); texte(tx + l1, ay + 6, c1, RVB(130, 136, 150), 14, 0); }
        }
    }
    if (o && o->zoom != 100) {
        char z[16];
        snprintf(z, sizeof z, "%d %%", o->zoom);
        texte(px - largeur(z, 12, 0) - 8, ay + 7, z, C_GRIS, 12, 0);
    }
    if (o && o->chargement && !o->interne)
        gfx_rect(toile, 0, y0 + H_OUTILS - 3, (int)(EL * (0.15 + 0.8 * fmod((maintenant - o->depuis) / 2500.0, 1.0))), 3, C_BLEU);
    gfx_rect(toile, 0, y0 + H_OUTILS - 1, EL, 1, C_LIGNE);
}

/* ------------------------------ favoris ------------------------------- */
static int fav_x[FAVORIS_MAX], fav_l[FAVORIS_MAX], nb_fav_vus;

static void dessiner_favoris(void)
{
    int y0 = H_ONGLETS + H_OUTILS;
    gfx_rect(toile, 0, y0, EL, H_FAVORIS, RVB(244, 246, 251));
    zone_ajouter(0, y0, EL, H_FAVORIS, A_FAVBARRE_VIDE, 0);
    int x = 8;
    nb_fav_vus = 0;
    for (int i = 0; i < nb_favoris; i++) {
        char t[300];
        couper(t, favoris[i].titre[0] ? favoris[i].titre : favoris[i].url, 150, 13);
        int l = largeur(t, 13, 0) + 34;
        if (x + l > EL - 40) break;
        int sv = dedans(x, y0 + 3, l, H_FAVORIS - 6);
        if (sv || (glisse == 2 && glisse_i == i)) gfx_rect_arrondi(toile, x, y0 + 3, l, H_FAVORIS - 6, 6, RVB(220, 230, 246));
        char h[256] = "";
        hote_de(favoris[i].url, h, sizeof h);
        dessiner_icone_page(x + 6, y0 + 6, h, 0);
        texte(x + 27, y0 + 6, t, C_TEXTE, 13, 0);
        fav_x[i] = x; fav_l[i] = l;
        zone_ajouter(x, y0 + 3, l, H_FAVORIS - 6, A_FAV_BARRE, i);
        x += l + 4;
        nb_fav_vus++;
    }
    if (nb_fav_vus < nb_favoris) {
        texte(EL - 30, y0 + 5, "»", C_GRIS, 16, 1);
        zone_ajouter(EL - 36, y0 + 2, 30, H_FAVORIS - 4, A_FAV_PLUS, 0);
    }
    if (!nb_favoris) texte(10, y0 + 6, "Glisse un lien ici, ou clique sur l'étoile, pour garder un favori.", RVB(140, 146, 160), 13, 0);
    gfx_rect(toile, 0, y0 + H_FAVORIS - 1, EL, 1, C_LIGNE);
}

/* -------------------------- recherche (Ctrl+F) ------------------------ */
static int trouver_ouvert;

static void dessiner_trouver(void)
{
    int l = 420, x = EL - l - 16, y = PY + 8;
    gfx_ombre(toile, x, y, l, 40, 10, 10, RVBA(0, 0, 0, 80));
    gfx_rect_arrondi(toile, x, y, l, 40, 10, C_BARRE);
    gfx_contour_arrondi(toile, x, y, l, 40, 10, RVB(190, 198, 214));
    champ_dessiner(&trouver, x + 8, y + 5, l - 118, 30, focus == &trouver, "Rechercher dans la page");
    zone_ajouter(x + 8, y + 5, l - 118, 30, A_TROUVER_CHAMP, 0);
    int bx = x + l - 104;
    bouton_icone(bx, y + 5, 30, 30, A_TROUVER_HAUT, 1);
    gfx_ligne(toile, bx + 9, y + 23, bx + 15, y + 15, 2, C_GRIS); gfx_ligne(toile, bx + 15, y + 15, bx + 21, y + 23, 2, C_GRIS);
    bouton_icone(bx + 32, y + 5, 30, 30, A_TROUVER_BAS, 1);
    gfx_ligne(toile, bx + 41, y + 17, bx + 47, y + 25, 2, C_GRIS); gfx_ligne(toile, bx + 47, y + 25, bx + 53, y + 17, 2, C_GRIS);
    bouton_icone(bx + 66, y + 5, 30, 30, A_TROUVER_FERMER, 1);
    gfx_ligne(toile, bx + 75, y + 14, bx + 87, y + 26, 2, C_GRIS); gfx_ligne(toile, bx + 87, y + 14, bx + 75, y + 26, 2, C_GRIS);
}

/* ---------------------------- bandeau info ---------------------------- */
static void dessiner_info(void)
{
    int y = PY;
    gfx_rect(toile, 0, y, EL, H_INFO, info_genre == 1 ? RVB(255, 247, 222) : RVB(232, 240, 252));
    gfx_rect(toile, 0, y + H_INFO - 1, EL, 1, RVB(220, 200, 150));
    if (info_genre == 1) icone_cle(20, y + 18, RVB(190, 130, 10));
    char t[500];
    int bx = EL - 12;
    if (info_genre == 1) {
        bx -= 110; bouton(bx, y + 4, 106, 28, "Pas maintenant", 0, A_INFO_NON, 0);
        bx -= 150; bouton(bx, y + 4, 146, 28, "Jamais pour ce site", 0, A_INFO_JAMAIS, 0);
        bx -= 110; bouton(bx, y + 4, 106, 28, "Enregistrer", 1, A_INFO_OUI, 0);
    } else if (info_genre == 2) {               /* 2.2 : discret, sur deux lignes si besoin */
        bx -= 30; bouton_icone(bx, y + 4, 28, 28, A_INFO_FERMER, 1);
        gfx_ligne(toile, bx + 8, y + 12, bx + 20, y + 24, 2, C_GRIS); gfx_ligne(toile, bx + 20, y + 12, bx + 8, y + 24, 2, C_GRIS);
        bx -= 104; bouton(bx, y + 4, 98, 28, "Pourquoi ?", 0, A_GOOGLE_AIDE, 0);
        gfx_disque(toile, 20, y + 18, 9, RVB(70, 120, 210));
        texte(17, y + 9, "i", RVB(255, 255, 255), 14, 1);
        int lt = bx - 50;
        if (paragraphe(0, 0, lt, info_texte, 0, 12, 0) <= 34) paragraphe(38, y + 1, lt, info_texte, C_TEXTE, 12, 1);
        else { couper(t, info_texte, lt, 13); texte(38, y + 10, t, C_TEXTE, 13, 0); }
        return;
    } else {
        bx -= 30; bouton_icone(bx, y + 4, 28, 28, A_INFO_FERMER, 1);
        gfx_ligne(toile, bx + 8, y + 12, bx + 20, y + 24, 2, C_GRIS); gfx_ligne(toile, bx + 20, y + 12, bx + 8, y + 24, 2, C_GRIS);
    }
    couper(t, info_texte, bx - 50, 14);
    texte(38, y + 9, t, C_TEXTE, 14, 0);
}

static void dessiner_suggestions(void)
{
    int x = adr_x, y = H_ONGLETS + 38, l = adr_l, h = nb_sug * 34 + 8;
    gfx_ombre(toile, x, y, l, h, 10, 12, RVBA(0, 0, 0, 80));
    gfx_rect_arrondi(toile, x, y, l, h, 10, RVB(255, 255, 255));
    gfx_contour_arrondi(toile, x, y, l, h, 10, RVB(196, 204, 218));
    for (int i = 0; i < nb_sug; i++) {
        int yy = y + 4 + i * 34;
        if (i == sug_sel || dedans(x + 4, yy, l - 8, 34)) gfx_rect_arrondi(toile, x + 4, yy, l - 8, 34, 6, RVB(222, 234, 252));
        char h_[256] = "", t[400];
        hote_de(sug_url[i], h_, sizeof h_);
        dessiner_icone_page(x + 14, yy + 9, h_, 0);
        couper(t, sug_titre[i][0] ? sug_titre[i] : sug_url[i], l / 2 - 40, 14);
        texte(x + 40, yy + 8, t, C_TEXTE, 14, 0);
        int lt = largeur(t, 14, 0);
        couper(t, sug_url[i], l - lt - 80, 13);
        texte(x + 56 + lt, yy + 9, t, C_BLEU, 13, 0);
        zone_ajouter(x + 4, yy, l - 8, 34, A_SUGGESTION, i);
    }
}

static void dessiner_glisse(void)
{
    if (glisse != 3 || sx < 0) return;
    char t[300];
    couper(t, glisse_titre, 260, 13);
    int l = largeur(t, 13, 0) + 40;
    gfx_ombre(toile, sx + 12, sy + 12, l, 28, 8, 8, RVBA(0, 0, 0, 80));
    gfx_rect_arrondi(toile, sx + 12, sy + 12, l, 28, 8, RVBA(255, 255, 255, 235));
    char h[256] = "";
    hote_de(glisse_url, h, sizeof h);
    dessiner_icone_page(sx + 18, sy + 18, h, 0);
    texte(sx + 40, sy + 17, t, C_TEXTE, 13, 0);
}

/* ============================ pages internes ========================== */
static const char *NOMS_INTERNES[] = { "", "Paramètres", "Historique", "Favoris", "Mots de passe", "Extensions", "Aide et lexique" };
static int contenu_h;                  /* hauteur du contenu de la page interne */
#define LARGEUR_SOURCE 200
static int aller_effacer;              /* Paramètres : faire défiler jusqu'à « Effacer » */
static int effacer_y;

/* une ligne de réglage */
enum { L_TITRE, L_BASCULE, L_CYCLE, L_CHAMP, L_TEXTE };
typedef struct { int genre; const char *libelle, *aide; int *val; const char **choix; int nb; int effet; champ *ch; } reglage_ligne;
enum { E_RIEN, E_APPLIQUER, E_IDENTITE, E_SITES, E_SCRIPTS, E_VPN, E_EXPERT, E_PERSO, E_GOOGLE };

static const char *CH_ZOOM[] = { "50 %", "67 %", "75 %", "80 %", "90 %", "100 %", "110 %", "125 %", "150 %", "175 %", "200 %" };
static const int ZOOMS[] = { 50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200 };
static const char *CH_VERROU[] = { "1 minute", "5 minutes", "10 minutes", "15 minutes", "30 minutes", "1 heure" };
static const int VERROUS[] = { 1, 5, 10, 15, 30, 60 };
static const char *CH_VPN[] = { "Aucun", "SOCKS5", "HTTP", "HTTPS" };
static const char *CH_MOTEURS[16];
static const char *CH_IDENT[32];
static int zoom_i, verrou_i, avertir;

static reglage_ligne REGLAGES[64];
static int nb_reglages;

static void reglages_table(void)
{
    for (int i = 0; i < NB_MOTEURS_RECHERCHE && i < 16; i++) CH_MOTEURS[i] = MOTEURS_RECHERCHE[i].nom;
    for (int i = 0; i < NB_IDENTITES && i < 32; i++) CH_IDENT[i] = IDENTITES[i].nom;
    zoom_i = 5; for (int i = 0; i < 11; i++) if (ZOOMS[i] == R.zoom) zoom_i = i;
    verrou_i = 2; for (int i = 0; i < 6; i++) if (VERROUS[i] == R.verrou_min) verrou_i = i;
    avertir = !R.expert;
    int n = 0;
#define T(l) REGLAGES[n++] = (reglage_ligne){ L_TITRE, l, NULL, NULL, NULL, 0, 0, NULL }
#define B(l, a, v, e) REGLAGES[n++] = (reglage_ligne){ L_BASCULE, l, a, v, NULL, 0, e, NULL }
#define C(l, a, v, ch, nb, e) REGLAGES[n++] = (reglage_ligne){ L_CYCLE, l, a, v, ch, nb, e, NULL }
#define F(l, a, c) REGLAGES[n++] = (reglage_ligne){ L_CHAMP, l, a, NULL, NULL, 0, 0, c }
#define X(l, a) REGLAGES[n++] = (reglage_ligne){ L_TEXTE, l, a, NULL, NULL, 0, 0, NULL }
    T("Démarrage et recherche");
    F("Page d'accueil", "Ouverte au démarrage et par le bouton maison.", &ch_accueil);
    C("Moteur de recherche", "Utilisé quand tu tapes des mots dans la barre d'adresse.", &R.recherche, CH_MOTEURS, NB_MOTEURS_RECHERCHE, E_RIEN);
    if (google_neutralise)                       /* 2.2 : seulement tant que Google Recherche est indisponible */
        B("Recherches Google vers DuckDuckGo", "Google Recherche est indisponible sous PuppyOS pour l'instant : tes recherches "
          "Google partent vers DuckDuckGo et aucun mot n'est envoyé à Google. Coupé : elles vont à Google, mais risquent de "
          "ne pas aboutir. Dans les deux cas, tu peux ouvrir google.com directement.", &R.google_redirige, E_GOOGLE);
    C("Zoom par défaut", "Ctrl + et Ctrl − changent le zoom de l'onglet.", &zoom_i, CH_ZOOM, 11, E_RIEN);
    B("Barre de favoris", "Sous la barre d'adresse.", &R.barre_favoris, E_APPLIQUER);
    B("Mode sombre forcé", "Assombrit les sites qui n'ont pas de thème sombre.", &R.sombre, E_APPLIQUER);
    T("Confidentialité");
    B("JavaScript", "Coupé : les sites ne peuvent plus lancer de programmes (beaucoup ne marchent plus). Réglable par site avec le cadenas.", &R.js, E_SITES);
    B("Cookies", "Coupés : les sites ne se souviennent plus de toi (connexions perdues). Réglable par site.", &R.cookies, E_SITES);
    B("Blocage des pages PHP", "Les pages faites en PHP (WordPress, forums…) ne s'ouvrent qu'avec ton accord. Réduit la surface "
      "d'attaque, mais bloque une grande partie du web. Réglable par site.", &R.php_bloque, E_SITES);
    B("Cookies tiers", "Ceux des pisteurs publicitaires sur les autres sites. Coupés conseillé.", &R.tiers, E_APPLIQUER);
    B("HTTPS d'abord", "Les adresses http sont d'abord essayées en https (chiffré).", &R.https, E_RIEN);
    B("Nettoyer les liens", "Retire les traceurs des adresses (utm_, fbclid, gclid…).", &R.liens, E_RIEN);
    B("Ne me pistez pas (GPC)", "Demande aux sites de ne pas vendre tes données. Nouveaux onglets.", &R.gpc, E_SCRIPTS);
    B("Masquer les bannières cookies", "Refuse et cache les bandeaux « Accepter les cookies ». Nouveaux onglets.", &R.bannieres, E_SCRIPTS);
    B("Fenêtres surgissantes", "Coupées : seules celles ouvertes par un clic sont permises.", &R.popups, E_RIEN);
    B("Vidéos qui démarrent seules", "Coupées : rien ne joue avant un clic. Nouveaux onglets.", &R.lecture_auto, E_SCRIPTS);
    B("Tout effacer en quittant", "Historique, cookies et cache disparaissent à la fermeture.", &R.effacer_en_quittant, E_RIEN);
    T("Avertissements");
    B("Expliquer les risques quand je coupe une protection", "Une fenêtre dit ce que tu risques, pourquoi garder la protection "
      "et quand la couper est utile. Coupé : « Je sais ce que je fais ».", &avertir, E_EXPERT);
    T("Personnalisation (affichage seulement)");
    X("Rien ne change sur les sites", "Ces réglages ne changent que ce que tu vois, sur cette machine : jamais le site lui-même, "
      "ses fonctions payantes, ni rien côté serveur. Clic droit sur une image : « Modifier l'image… » la remplace par une des tiennes.");
    B("Langage Puppyplay", "Remplace des mots dans les pages selon ton dictionnaire (plus bas). Compatible mode lecture et mode sombre.", &R.langage, E_PERSO);
    B("Thème Puppyplay", "L'extension intégrée : couleurs et coins arrondis façon PuppyOS sur tous les sites.", &R.theme_puppyplay, E_PERSO);
    T("Identité (User-Agent)");
    C("Le navigateur se présente comme", "Aussi dans la barre d'adresse. Les sites voient ce système et ce navigateur.", &R.ua, CH_IDENT, NB_IDENTITES, E_IDENTITE);
    F("Identité personnalisée", "Le texte User-Agent complet, utilisé avec « Personnalisée… ».", &ch_ua);
    T("Mots de passe");
    B("Proposer d'enregistrer les mots de passe", "Dans le coffre chiffré (AES-256, enclave isolée). Jamais les cartes bancaires.", &R.coffre, E_SCRIPTS);
    C("Le coffre se referme après", "Sans usage. Il se referme aussi quand l'écran se verrouille.", &verrou_i, CH_VERROU, 6, E_RIEN);
    T("VPN");
    X("Ton propre serveur VPN ou proxy", "Les onglets passent par ton serveur (SOCKS5 : WireGuard ou OpenVPN avec un relais SOCKS, Tor, un serveur SSH -D…). "
      "Si le serveur ne répond pas, rien ne sort : jamais de fuite par la connexion normale. WebRTC ne passe que par le VPN.");
    C("Type", NULL, &R.vpn, CH_VPN, 4, E_VPN);
    F("Serveur", "Nom ou adresse IP du serveur.", &ch_vpn_hote);
    F("Port", NULL, &ch_vpn_port);
    nb_reglages = n;
#undef T
#undef B
#undef C
#undef F
#undef X
}

static void champs_page_sync(void)
{
    if (focus != &ch_accueil) champ_mettre(&ch_accueil, R.accueil);
    if (focus != &ch_ua) champ_mettre(&ch_ua, R.ua_perso);
    if (focus != &ch_vpn_hote) champ_mettre(&ch_vpn_hote, R.vpn_hote);
    if (focus != &ch_vpn_port) { char p[16]; snprintf(p, sizeof p, "%d", R.vpn_port); champ_mettre(&ch_vpn_port, p); }
}

/* un champ de page quitté ou validé : on garde sa valeur */
static void champ_page_garder(champ *c)
{
    if (c == &ch_accueil) {
        if (c->t[0] && strncmp(c->t, "https://", 8) && strncmp(c->t, "http://", 7) && strncmp(c->t, "puppy://", 8)) {
            char u[1100]; snprintf(u, sizeof u, "https://%s", c->t); chaine_copier(R.accueil, u, sizeof R.accueil);
        } else chaine_copier(R.accueil, c->t, sizeof R.accueil);
    } else if (c == &ch_ua) {
        chaine_copier(R.ua_perso, c->t, sizeof R.ua_perso);
        if (R.ua == NB_IDENTITES - 1) appliquer_identite();
    } else if (c == &ch_vpn_hote) chaine_copier(R.vpn_hote, c->t, sizeof R.vpn_hote);
    else if (c == &ch_vpn_port) { int p = atoi(c->t); if (p > 0 && p < 65536) R.vpn_port = p; }
    else return;
    reglages_enregistrer();
}

static void focus_changer(champ *c)
{
    if (focus && focus != c) champ_page_garder(focus);
    if (focus == &adresse && c != &adresse) nb_sug = 0;
    focus = c;
    sale = 1;
}

static void effet_reglage(int effet)
{
    R.zoom = ZOOMS[zoom_i];
    R.verrou_min = VERROUS[verrou_i];
    reglages_enregistrer();
    switch (effet) {
    case E_APPLIQUER: appliquer_reglages(); break;
    case E_IDENTITE: appliquer_identite(); dire("Identité changée : recharge la page pour qu'un site la voie"); break;
    case E_SITES: sites_appliquer(); break;
    case E_SCRIPTS: dire("S'applique aux nouveaux onglets"); break;
    case E_EXPERT: R.expert = !avertir; reglages_enregistrer(); break;
    case E_PERSO: perso_appliquer(); break;
    case E_GOOGLE:
        if (!R.google_redirige)
            dlg_ouvrir(DLG_INFO, "Redirection vers DuckDuckGo coupée",
                       "Tes recherches Google partiront maintenant vers Google. Pour l'instant, elles risquent de ne pas "
                       "aboutir sous PuppyOS : contrôles anti-robots impossibles à passer, pages vides ou bloquées.\n\n"
                       "Tu peux remettre la redirection quand tu veux, ici même. L'accès direct à google.com reste "
                       "possible dans tous les cas.");
        if (!R.google_redirige) dlg_bouton("J'ai compris", 1);
        else dire("Recherches Google redirigées vers DuckDuckGo");
        break;
    }
}

static int titre_page(int x, int y, const char *t)
{
    texte(x, y, t, C_TEXTE, 26, 1);
    return 52;
}

static int carte_debut(int x, int y, int l, int h)
{
    gfx_rect_arrondi(toile, x, y, l, h, 12, RVB(255, 255, 255));
    gfx_contour_arrondi(toile, x, y, l, h, 12, RVB(218, 224, 236));
    return 0;
}

static int page_parametres(int x, int y, int l)
{
    reglages_table();
    champs_page_sync();
    int y0 = y;
    y += titre_page(x, y, "Paramètres");
    for (int i = 0; i < nb_reglages; i++) {
        reglage_ligne *g = &REGLAGES[i];
        if (g->genre == L_TITRE) { y += 14; texte(x, y, g->libelle, C_BLEU, 17, 1); y += 32; continue; }
        int ha = g->aide ? paragraphe(0, 0, l - 330, g->aide, 0, 13, 0) : 0;
        int h = 22 + ha + 16;
        if (g->genre == L_TEXTE) h = 22 + paragraphe(0, 0, l - 30, g->aide, 0, 13, 0) + 16;
        if (h < 52) h = 52;
        carte_debut(x, y, l, h - 6);
        texte(x + 16, y + 10, g->libelle, C_TEXTE, 15, 0);
        if (g->genre == L_TEXTE) { paragraphe(x + 16, y + 32, l - 30, g->aide, C_GRIS, 13, 1); y += h; continue; }
        if (g->aide) paragraphe(x + 16, y + 32, l - 330, g->aide, C_GRIS, 13, 1);
        int cx = x + l - 300;
        if (g->genre == L_BASCULE) interrupteur(x + l - 60, y + 12, *g->val, A_REGLAGE, i);
        else if (g->genre == L_CYCLE) {
            int v = *g->val;
            if (v < 0 || v >= g->nb) v = 0;
            int sv = dedans(cx, y + 8, 284, 30);
            gfx_rect_arrondi(toile, cx, y + 8, 284, 30, 7, sv ? RVB(236, 242, 252) : RVB(246, 248, 252));
            gfx_contour_arrondi(toile, cx, y + 8, 284, 30, 7, RVB(190, 198, 214));
            char t[300];
            couper(t, g->choix[v], 240, 14);
            texte(cx + 12, y + 14, t, C_TEXTE, 14, 0);
            gfx_triangle(toile, cx + 262, y + 20, cx + 272, y + 20, cx + 267, y + 26, C_GRIS);
            zone_ajouter(cx, y + 8, 284, 30, A_REGLAGE, i);
        } else if (g->genre == L_CHAMP) {
            champ_dessiner(g->ch, cx, y + 8, 284, 30, focus == g->ch, g->ch == &ch_ua ? "Mozilla/5.0 (…)" : "");
            zone_ajouter(cx, y + 8, 284, 30, A_CHAMP_PAGE, i);
        }
        y += h;
    }
    bouton_auto(x, y + 2, R.vpn ? "Appliquer le VPN (les onglets se rouvrent)" : "Appliquer (VPN coupé)", 1, A_VPN_APPLIQUER, 0);
    y += 48;

    /* 2.1 : dictionnaire du langage Puppyplay */
    y += 14; texte(x, y, "Dictionnaire du langage Puppyplay", C_BLEU, 17, 1); y += 32;
    champ_dessiner(&ch_mot, x, y, l - 130, 32, focus == &ch_mot, "Mot --> remplacement");
    zone_ajouter(x, y, l - 130, 32, A_CHAMP_PAGE, 102);
    bouton(x + l - 120, y + 1, 120, 30, "Ajouter", 1, A_MOT_AJOUTER, 0);
    y += 44;
    for (int i = 0; i < nb_mots; i++) {
        if (y > PY - 40 && y < PY + PH + 40) {
            carte_debut(x, y, l, 38);
            char t[300];
            snprintf(t, sizeof t, "%s  →  %s", mots[i].de, mots[i].vers);
            texte(x + 16, y + 9, t, C_TEXTE, 14, 0);
            bouton(x + l - 92, y + 4, 84, 30, "Retirer", 2, A_MOT_SUPPR, i);
        }
        y += 44;
    }
    if (!nb_mots) { texte(x + 4, y, "Le dictionnaire est vide. Exemple : Maison --> niche", C_GRIS, 13, 0); y += 30; }

    /* 2.1 : permissions des sites */
    y += 14; texte(x, y, "Permissions des sites", C_BLEU, 17, 1); y += 32;
    int np = 0;
    for (int i = 0; i < nb_perms; i++)
        for (int t = 0; t < NB_PERMS; t++) {
            if (perms[i].p[t] < 0) continue;
            carte_debut(x, y, l, 40);
            dessiner_icone_page(x + 12, y + 12, perms[i].hote, 0);
            texte(x + 38, y + 10, perms[i].hote, C_TEXTE, 14, 0);
            char m[120];
            snprintf(m, sizeof m, "%s : %s", PERM_TITRES[t], perms[i].p[t] ? "permis" : "bloqué pour toujours");
            texte(x + l / 2, y + 10, m, perms[i].p[t] ? RVB(200, 120, 0) : C_VERT, 14, 0);
            bouton(x + l - 92, y + 5, 84, 30, "Retirer", 0, A_PERM_RETIRER, i * NB_PERMS + t);
            y += 46;
            np++;
        }
    if (!np) { texte(x + 4, y, "Aucune. Caméra, micro, position et notifications sont demandés à chaque fois.", C_GRIS, 13, 0); y += 30; }

    /* exceptions par site */
    y += 14; texte(x, y, "Réglages par site", C_BLEU, 17, 1); y += 32;
    int n = 0;
    for (int i = 0; i < nb_sites; i++) {
        exception_site *s = &sites[i];
        if (s->js < 0 && s->cookies < 0 && s->php < 0) continue;
        carte_debut(x, y, l, 44);
        char hc[200];
        couper(hc, s->hote, l - 560, 14);
        texte(x + 16, y + 12, hc, C_TEXTE, 14, 0);
        int bx = x + l - 550;
        texte(bx, y + 13, "JavaScript", C_GRIS, 13, 0);
        interrupteur(bx + 80, y + 11, s->js < 0 ? R.js : s->js, A_SITE_LIGNE_JS, i);
        texte(bx + 140, y + 13, "Cookies", C_GRIS, 13, 0);
        interrupteur(bx + 200, y + 11, s->cookies < 0 ? R.cookies : s->cookies, A_SITE_LIGNE_CK, i);
        texte(bx + 258, y + 13, "PHP", C_GRIS, 13, 0);
        interrupteur(bx + 292, y + 11, site_php_permis(s->hote), A_SITE_LIGNE_PHP, i);
        bouton(bx + 390, y + 7, 150, 30, "Revenir au général", 0, A_SITE_LIGNE_SUPPR, i);
        y += 50;
        n++;
    }
    if (!n) { texte(x + 4, y, "Aucun. Le cadenas de la barre d'adresse règle JavaScript, cookies et PHP pour un site.", C_GRIS, 13, 0); y += 30; }

    /* effacer */
    y += 14;
    effacer_y = y - y0;
    texte(x, y, "Effacer les données de navigation", C_BLEU, 17, 1); y += 36;
    int bx = x;
    bx += bouton_auto(bx, y, "Historique : dernière heure", 0, A_EFFACER, 1) + 8;
    bx += bouton_auto(bx, y, "Dernières 24 h", 0, A_EFFACER, 2) + 8;
    bx += bouton_auto(bx, y, "Tout l'historique", 0, A_EFFACER, 3) + 8;
    y += 40; bx = x;
    bx += bouton_auto(bx, y, "Cookies et connexions", 0, A_EFFACER, 4) + 8;
    bx += bouton_auto(bx, y, "Cache", 0, A_EFFACER, 5) + 8;
    bx += bouton_auto(bx, y, "Tout effacer", 2, A_EFFACER, 6) + 8;
    y += 50;
    texte(x, y, "Navigateur.puppy 2.2 · moteur Chromium 141 dans le bac à sable de PuppyOS (Honey-Dog veille). Aide : F1.", C_GRIS, 12, 0);
    y += 30;
    return y - y0;
}

static char *quand(double t, char *d, size_t n)
{
    time_t s = (time_t)(t / 1000);
    struct tm tm;
    localtime_r(&s, &tm);
    strftime(d, n, "%d/%m %H:%M", &tm);
    return d;
}

static int hist_filtre[HISTORIQUE_MAX], nb_hist_filtre;

static int page_historique(int x, int y, int l)
{
    int y0 = y;
    y += titre_page(x, y, "Historique");
    champ_dessiner(&ch_hist, x, y, 360, 32, focus == &ch_hist, "Chercher dans l'historique");
    zone_ajouter(x, y, 360, 32, A_CHAMP_PAGE, 100);
    int bx = x + 380;
    bx += bouton_auto(bx, y + 1, "Effacer la dernière heure", 0, A_EFFACER, 1) + 8;
    bx += bouton_auto(bx, y + 1, "24 h", 0, A_EFFACER, 2) + 8;
    bouton_auto(bx, y + 1, "Tout", 2, A_EFFACER, 3);
    y += 48;
    texte(x, y, "Clic : ouvrir · clic droit : supprimer, ouvrir dans un nouvel onglet…", C_GRIS, 12, 0);
    y += 26;
    nb_hist_filtre = 0;
    for (int i = nb_historique - 1; i >= 0; i--)
        if (!ch_hist.t[0] || contient(historique[i].url, ch_hist.t) || contient(historique[i].titre, ch_hist.t)) hist_filtre[nb_hist_filtre++] = i;
    char jour_avant[16] = "";
    for (int k = 0; k < nb_hist_filtre; k++) {
        int i = hist_filtre[k];
        passage *p = &historique[i];
        char q[32], j[16];
        quand(p->t, q, sizeof q);
        snprintf(j, sizeof j, "%.5s", q);
        if (strcmp(j, jour_avant)) {
            /* un titre par jour */
            time_t s = (time_t)(p->t / 1000);
            struct tm tm;
            localtime_r(&s, &tm);
            char jt[64];
            strftime(jt, sizeof jt, "%A %d/%m/%Y", &tm);
            y += 8;
            if (y > PY - 40 && y < PY + PH + 40) texte(x, y, jt, C_BLEU, 14, 1);
            y += 26;
            chaine_copier(jour_avant, j, sizeof jour_avant);
        }
        if (y > PY - 40 && y < PY + PH + 40) {
            if (dedans(x, y, l, 30)) gfx_rect_arrondi(toile, x, y, l, 30, 6, RVB(230, 238, 250));
            texte(x + 8, y + 7, q + 6, C_GRIS, 13, 0);
            char h[256] = "", t[400];
            hote_de(p->url, h, sizeof h);
            dessiner_icone_page(x + 60, y + 7, h, 0);
            couper(t, p->titre[0] ? p->titre : p->url, l / 2 - 90, 14);
            texte(x + 84, y + 6, t, C_TEXTE, 14, 0);
            couper(t, h, l / 2 - 30, 13);
            texte(x + l / 2 + 10, y + 7, t, C_GRIS, 13, 0);
            zone_ajouter(x, y, l, 30, A_HIST, i);
        }
        y += 32;
    }
    if (!nb_hist_filtre) { texte(x, y, nb_historique ? "Rien ne correspond." : "L'historique est vide.", C_GRIS, 14, 0); y += 30; }
    texte(x, y + 10, "Les onglets privés ne laissent aucune trace ici.", C_GRIS, 12, 0);
    return y + 40 - y0;
}

static int page_favoris(int x, int y, int l)
{
    int y0 = y;
    y += titre_page(x, y, "Favoris");
    texte(x, y, "Clic : ouvrir · clic droit : modifier, supprimer · glisser dans la barre de favoris pour les ranger.", C_GRIS, 12, 0);
    y += 28;
    for (int i = 0; i < nb_favoris; i++) {
        if (y > PY - 40 && y < PY + PH + 40) {
            carte_debut(x, y, l, 40);
            if (dedans(x, y, l - 220, 40)) gfx_rect_arrondi(toile, x + 1, y + 1, l - 2, 38, 12, RVB(236, 242, 252));
            char h[256] = "", t[400];
            hote_de(favoris[i].url, h, sizeof h);
            dessiner_icone_page(x + 12, y + 12, h, 0);
            couper(t, favoris[i].titre, l / 2 - 60, 14);
            texte(x + 38, y + 10, t, C_TEXTE, 14, 0);
            couper(t, favoris[i].url, l / 2 - 200, 13);
            texte(x + l / 2 - 10, y + 11, t, C_GRIS, 13, 0);
            zone_ajouter(x, y, l - 220, 40, A_FAV_OUVRIR, i);
            int bx = x + l - 214;
            bouton(bx, y + 5, 34, 30, "↑", 0, A_FAV_HAUT, i);
            bouton(bx + 38, y + 5, 34, 30, "↓", 0, A_FAV_BAS, i);
            bouton(bx + 76, y + 5, 70, 30, "Modifier", 0, A_FAV_MODIFIER, i);
            bouton(bx + 150, y + 5, 58, 30, "Retirer", 2, A_FAV_SUPPR, i);
        }
        y += 46;
    }
    if (!nb_favoris) { texte(x, y, "Aucun favori. L'étoile de la barre d'adresse (ou Ctrl+D) en ajoute un.", C_GRIS, 14, 0); y += 30; }
    return y + 20 - y0;
}

static char liste_coffre[COFFRE_MESSAGE_MAX];
static int coffre_vu_ouvert;

static int page_coffre(int x, int y, int l)
{
    int y0 = y;
    y += titre_page(x, y, "Mots de passe");
    int h = paragraphe(x, y, l, "Le coffre est chiffré en AES-256-GCM avec une clé tirée de ton mot de passe maître (Argon2id). "
                       "Il vit dans une enclave à part : un petit processus isolé, sans réseau ni fichiers, dont la mémoire "
                       "est verrouillée et effacée. Les mots de passe ne vont qu'au site exact où ils ont été enregistrés. "
                       "Les numéros de carte bancaire sont toujours refusés.", C_GRIS, 13, 1);
    y += h + 16;
    if (!coffre_disponible()) { texte(x, y, "Le coffre n'a pas pu démarrer.", C_ROUGE, 15, 0); return y + 40 - y0; }
    if (cofre_sale) { coffre_vu_ouvert = coffre_est_ouvert(); cofre_sale = 0; if (coffre_vu_ouvert) coffre_liste(liste_coffre, sizeof liste_coffre); else explicit_bzero(liste_coffre, sizeof liste_coffre); }
    if (!coffre_vu_ouvert) {
        int ex = coffre_existe();
        texte(x, y, ex ? "Le coffre est fermé." : "Tu n'as pas encore de coffre.", C_TEXTE, 16, 0);
        y += 34;
        bouton_auto(x, y, ex ? "Ouvrir le coffre" : "Créer le coffre", 1, A_COF_OUVRIR, 0);
        return y + 60 - y0;
    }
    int bx = x;
    bx += bouton_auto(bx, y, "Refermer le coffre", 0, A_COF_VERROU, 0) + 8;
    bx += bouton_auto(bx, y, "Changer le mot de passe maître", 0, A_COF_CHANGER, 0) + 8;
    bouton_auto(bx, y, "Tout effacer", 2, A_COF_TOUT, 0);
    y += 48;
    char copie[COFFRE_MESSAGE_MAX];
    memcpy(copie, liste_coffre, sizeof copie);
    int n = 0;
    char *sv = NULL;
    for (char *li = strtok_r(copie, "\n", &sv); li; li = strtok_r(NULL, "\n", &sv)) {
        char *t1 = strchr(li, '\t'), *t2 = t1 ? strchr(t1 + 1, '\t') : NULL;
        if (!t1 || !t2) continue;
        *t1 = *t2 = 0;
        int id = atoi(li);
        if (y > PY - 40 && y < PY + PH + 40) {
            carte_debut(x, y, l, 40);
            dessiner_icone_page(x + 12, y + 12, t1 + 1, 0);
            char t[300];
            couper(t, t1 + 1, l / 2 - 60, 14);
            texte(x + 38, y + 10, t, C_TEXTE, 14, 0);
            couper(t, t2 + 1, l / 2 - 190, 14);
            texte(x + l / 2 - 20, y + 10, t, C_GRIS, 14, 0);
            texte(x + l / 2 + l / 2 - 330, y + 10, "••••••••", C_GRIS, 14, 0);
            bouton(x + l - 170, y + 5, 76, 30, "Montrer", 0, A_COF_MONTRER, id);
            bouton(x + l - 88, y + 5, 80, 30, "Supprimer", 2, A_COF_SUPPR, id);
        }
        y += 46;
        n++;
    }
    explicit_bzero(copie, sizeof copie);
    if (!n) { texte(x, y, "Le coffre est vide. Connecte-toi à un site : le Navigateur proposera d'y ranger le mot de passe.", C_GRIS, 14, 0); y += 30; }
    return y + 20 - y0;
}

static int page_extensions(int x, int y, int l)
{
    int y0 = y;
    y += titre_page(x, y, "Extensions");
    y += paragraphe(x, y, l, "Le moteur du Navigateur ne peut pas charger les extensions du Chrome Web Store. Il accepte les "
                    "scripts utilisateur (format Greasemonkey / Tampermonkey, fichiers « .user.js ») : ouvre l'adresse d'un "
                    ".user.js (par exemple sur greasyfork.org) et le Navigateur propose de l'installer. Ils n'agissent que sur "
                    "les sites de leurs lignes @match, jamais dans les onglets privés.", C_GRIS, 13, 1) + 14;
    /* 2.1 : l'extension intégrée */
    carte_debut(x, y, l, 56);
    texte(x + 16, y + 8, "Thème Puppyplay (intégré)", C_TEXTE, 15, 0);
    texte(x + 16, y + 30, "L'allure de PuppyOS sur tous les sites. Affichage seulement, sur cette machine.", C_GRIS, 12, 0);
    interrupteur(x + l - 60, y + 17, R.theme_puppyplay, A_THEME_PP, 0);
    y += 70;
    champ_dessiner(&ch_ext, x, y, l - 130, 32, focus == &ch_ext, "Adresse d'un script .user.js");
    zone_ajouter(x, y, l - 130, 32, A_CHAMP_PAGE, 101);
    bouton(x + l - 120, y + 1, 120, 30, "Ouvrir", 1, A_EXT_ALLER, 0);
    y += 50;
    for (int i = 0; i < nb_extensions; i++) {
        carte_debut(x, y, l, 56);
        char t[300];
        snprintf(t, sizeof t, "%s %s", extensions[i].nom, extensions[i].version);
        texte(x + 16, y + 8, t, C_TEXTE, 15, 0);
        char m[600] = "";
        for (int k = 0; k < extensions[i].nb && k < 3; k++) { size_t o = strlen(m); snprintf(m + o, sizeof m - o, "%s%s", k ? ", " : "Agit sur : ", extensions[i].motifs[k]); }
        couper(t, m, l - 220, 12);
        texte(x + 16, y + 30, t, C_GRIS, 12, 0);
        interrupteur(x + l - 160, y + 17, extensions[i].actif, A_EXT_ACTIF, i);
        bouton(x + l - 100, y + 13, 88, 30, "Supprimer", 2, A_EXT_SUPPR, i);
        y += 62;
    }
    if (!nb_extensions) { texte(x, y, "Aucune extension installée.", C_GRIS, 14, 0); y += 30; }
    return y + 20 - y0;
}

/* 2.2 : illustration de « Google Recherche indisponible » : un fichier du
 * paquet, remplaçable sans recompiler ; la version téléphone et tablette
 * seulement sur ces appareils. Renvoie la hauteur dessinée (0 : rien). */
static surface *illu_google;
static int illu_google_lue;

static int illustration_google(int x, int y, int lmax, int hmax, int cliquable)
{
    if (!illu_google_lue) {
        illu_google_lue = 1;
        illu_google = surface_charger_image(google_ressource(appareil_mobile() ? "illustrations/google-indisponible-mobile.png"
                                                                               : "illustrations/google-indisponible.png"));
    }
    if (!illu_google || hmax < 80 || lmax < 80) return 0;
    int h = illu_google->h, l = illu_google->l;
    if (h > hmax) { l = l * hmax / h; h = hmax; }
    if (l > lmax) { h = h * lmax / l; l = lmax; }
    gfx_composer_taille(toile, illu_google, x, y, l, h, 255);
    if (cliquable) zone_ajouter(x, y, l, h, A_GOOGLE_AIDE, 0);
    return h;
}

/* captures d'écran des erreurs observées (fichiers du paquet, facultatifs) */
#define CAPTURES_GOOGLE 6
static surface *captures_google[CAPTURES_GOOGLE];
static int captures_google_lues, nb_captures_google;

static void captures_google_charger(void)
{
    if (captures_google_lues) return;
    captures_google_lues = 1;
    for (int i = 0; i < CAPTURES_GOOGLE; i++) {
        char f[80];
        snprintf(f, sizeof f, "illustrations/captures/google-%d.png", i + 1);
        surface *s = surface_charger_image(google_ressource(f));
        if (s) captures_google[nb_captures_google++] = s;
    }
}

/* 2.1 : aide et lexique (puppy://aide, F1) */
typedef struct { int genre; const char *a, *b; } bloc_aide;   /* 0 titre, 1 paragraphe, 2 terme + définition,
                                                               * 3 illustration Google, 4 capture (a = numéro) (2.2) */
static const bloc_aide AIDE_DEBUT[] = {
    { 0, "Comment marche le Navigateur", NULL },
    { 1, "Le Navigateur affiche les sites avec le moteur Chromium, mais il ne lui fait pas confiance. Le moteur tourne "
         "derrière deux murs : son propre bac à sable, puis celui de PuppyOS autour (un compte à lui, des appels système "
         "filtrés, aucun accès à tes fichiers à part le dossier « Envoi » et les téléchargements en quarantaine). Honey-Dog, "
         "le gardien, le surveille : s'il touche à un piège ou tente une action interdite, il est arrêté et tu choisis quoi "
         "faire. Le Navigateur dessine lui-même les onglets, la barre d'adresse et les menus ; tes favoris, ton historique et "
         "ton coffre restent sur cette machine. Rien n'est envoyé à PuppyOS ni à personne.", NULL },
    { 0, "Zero-Trust", NULL },
    { 1, "Aucune pièce ne fait confiance à une autre sans preuve. Le moteur ne voit jamais tes mots de passe : ils vivent "
         "dans une enclave chiffrée (AES-256) à part, et ne vont qu'au site exact où ils ont été enregistrés. Les sites ne "
         "reçoivent que ce que tu permets : caméra, micro, position et notifications sont demandés, et ton accord se retire "
         "à tout moment. Les cartes bancaires ne sont jamais enregistrées.", NULL },
    { 0, "Les mises à jour", NULL },
    { 1, "PuppyOS, le Navigateur et le moteur Chromium arrivent en paquets signés. La signature est vérifiée avant toute "
         "installation : un paquet modifié, même d'un octet, est refusé. Après une mise à jour du Navigateur, réinstalle "
         "Navigateur.puppy depuis le Bureau. Le bouton « Revenir à » des mises à jour remet la version précédente.", NULL },
    { 0, "Téléchargements et quarantaine", NULL },
    { 1, "Tout fichier téléchargé arrive dans un volume où rien ne peut s'exécuter, puis Flair (l'antivirus de PuppyOS) "
         "l'analyse. C'est vrai même pour une image .png ou une vidéo .avi : l'extension d'un fichier ne prouve rien, et des "
         "failles existent dans les lecteurs d'images et de vidéos. Un fichier suspect reste en quarantaine ; tu décides "
         "de le garder ou de le détruire.", NULL },
    { 0, "La barre d'adresse", NULL },
    { 2, "Cadenas", "La connexion est chiffrée (https) et le certificat est valable pour ce nom. Clique dessus : menu du site, certificat, permissions." },
    { 2, "« Non sécurisé »", "Page en http : ce que tu lis et tapes peut être lu ou modifié sur le réseau." },
    { 2, "« Adresse piégée ? »", "Le nom du site contient des lettres étrangères qui peuvent imiter un autre site (un « a » cyrillique à la place d'un « a » latin : invisible à l'œil)." },
    { 2, "« JS coupé », « cookies coupés »", "Ces protections sont actives pour ce site." },
    { 2, "« PHP »", "La page est faite en PHP (vu dans l'adresse ou la réponse du serveur)." },
    { 2, "« N pisteurs »", "Des domaines de pistage ont été vus sur la page : les connus sont bloqués par le moteur, les probables sont signalés." },
    { 2, "Pastille rouge (caméra, micro…)", "Le site utilise en ce moment une permission que tu as accordée." },
    { 2, "Pastille d'identité", "Le système et le navigateur que les sites croient voir (User-Agent). Clique pour en changer." },
    { 0, "Les fonctions des sites et leurs dangers", NULL },
    { 2, "JavaScript", "Le programme que chaque site lance chez toi. Indispensable à la plupart des sites, mais principale source de pistage, d'empreinte et de pages piégées." },
    { 2, "Cookies", "Petits fichiers qu'un site garde chez toi pour te reconnaître (connexion, panier). Servent aussi au profilage publicitaire." },
    { 2, "Cookies tiers", "Cookies d'un autre site que celui que tu visites, en général une régie publicitaire qui te suit de site en site." },
    { 2, "Stockage local", "Comme des cookies, en plus gros : le site peut y garder des données. « Oublier ce site » l'efface aussi." },
    { 2, "PHP", "Langage qui fabrique les pages côté serveur (WordPress, forums, boutiques). Les applications PHP anciennes ou mal tenues sont une porte d'entrée fréquente pour les pages piégées." },
    { 2, "WebSocket", "Une connexion qui reste ouverte entre la page et un serveur (messageries, jeux). Peut aussi servir à envoyer en continu ce que tu fais." },
    { 2, "Cadre (iframe)", "Une page dans la page. Un cadre invisible peut cacher une pub, un pisteur ou un piège (clic détourné)." },
    { 2, "Redirection", "Le site t'envoie vers une autre adresse. Une longue chaîne de redirections entre sites différents est typique des pubs et des arnaques." },
    { 2, "Contenu mixte", "Une page chiffrée (https) qui charge des morceaux non chiffrés (http) : ces morceaux peuvent être modifiés en route." },
    { 2, "Certificat", "La carte d'identité du site, signée par une autorité. Expiré, mal nommé ou signé par le site lui-même : quelqu'un peut se faire passer pour lui." },
    { 2, "Empreinte (fingerprinting)", "Le site mesure ta machine (dessin canvas, carte graphique, audio, polices, écran) pour te reconnaître sans cookies." },
    { 2, "WebAssembly et workers", "Du code rapide et des calculs en arrière-plan. Normaux pour les jeux et l'édition, mais aussi utilisés par les mineurs." },
    { 2, "Mineur de cryptomonnaie", "Un script qui utilise ton processeur pour gagner de l'argent à ton insu. La machine chauffe et ralentit." },
    { 2, "Code dissimulé (obfuscation)", "Du code volontairement illisible. Souvent pour protéger un produit, parfois pour cacher un piège aux antivirus." },
    { 2, "Évasion", "Le script cherche à savoir s'il est observé (outils de développement, robot, navigateur sans écran) pour changer de comportement." },
    { 2, "Fuite de mot de passe", "Le mot de passe tapé est envoyé ailleurs que là où le formulaire l'annonce, ou sans chiffrement." },
    { 2, "Caméra, micro, position", "Le site voit, entend ou localise. À n'accorder que pour un appel, une carte, une messagerie que tu utilises." },
    { 2, "Notifications", "Le site peut t'écrire même quand tu fais autre chose. Très utilisé par les arnaques (« Votre PC est infecté »)." },
    { 2, "Fenêtres surgissantes", "Onglets ouverts sans ton clic : pubs agressives, fausses alertes, hameçonnage." },
    { 2, "Pisteur connu, pisteur probable", "Connu : dans la liste de blocage du moteur, bloqué. Probable : pas dans la liste, mais son adresse en a l'allure (/collect, /pixel, /beacon…)." },
    { 0, "Chaque protection et son effet", NULL },
};
static const bloc_aide AIDE_FIN[] = {
    { 0, "Autres réglages et fonctions", NULL },
    { 2, "Mode sombre forcé", "Assombrit les sites qui n'ont pas de thème sombre. Affichage seulement." },
    { 2, "Mode lecture (F9)", "Garde le texte principal, sans pubs ni menus. Suit le mode sombre et le langage Puppyplay." },
    { 2, "Vidéos qui démarrent seules", "Coupées : rien ne joue avant ton clic." },
    { 2, "Tout effacer en quittant", "Historique, cookies et cache disparaissent à la fermeture." },
    { 2, "Identité (User-Agent)", "Ce que les sites croient voir. Changer d'identité peut débloquer un site, mais ne te rend pas anonyme." },
    { 2, "VPN", "Les onglets passent par ton serveur. S'il ne répond pas, rien ne sort : jamais de fuite par la connexion normale." },
    { 2, "Coffre à mots de passe", "Chiffré en AES-256 avec ton mot de passe maître (Argon2id), dans une enclave isolée. Il se referme seul." },
    { 2, "Permissions des sites", "Demander, permettre ou bloquer pour toujours, site par site. Liste et retrait dans Paramètres." },
    { 2, "Oublier ce site", "Efface d'un coup cookies, stockage, permissions, historique, réglages et images modifiées d'un site." },
    { 2, "Langage Puppyplay", "Remplace des mots à l'écran selon ton dictionnaire « Mot --> remplacement ». Le site n'en sait rien." },
    { 2, "Modifier une image", "Clic droit sur une image : remplace-la par une des tiennes, pour ce site et sur cette machine. « Restaurer l'original » la remet." },
    { 2, "Thème Puppyplay", "Extension intégrée (coupée au départ) : l'allure de PuppyOS sur tous les sites. Affichage seulement." },
    { 2, "Application web", "Menu → « Installer comme application » : un raccourci sur le Bureau ouvre le site en plein écran, dans son propre bac à sable ; ses téléchargements vont aussi en quarantaine." },
    { 2, "Audit de sécurité (F10)", "Analyse la page sans rien envoyer : pisteurs, scripts dissimulés, mineurs, empreinte, cadres invisibles, fuites de mot de passe, redirections, certificat. Chaque audit est gardé dans l'historique des audits." },
    { 2, "Personnalisations", "Langage, images, thème et mode sombre ne changent que ton affichage, sur cette machine : jamais le site, ses fonctions payantes, ni rien côté serveur." },
    { 0, "Raccourcis clavier", NULL },
    { 2, "F1 · F5 · F9 · F10", "Cette aide · recharger · mode lecture · audit de sécurité (F12 reste la capture d'écran de PuppyOS)." },
    { 2, "Ctrl+T · Ctrl+W · Ctrl+Maj+N", "Nouvel onglet · fermer l'onglet · onglet privé." },
    { 2, "Ctrl+L · Ctrl+F · Ctrl+D · Ctrl+H", "Barre d'adresse · chercher dans la page · favori · historique." },
    { 2, "Ctrl+U · Ctrl+Maj+U", "Code source · audit de sécurité." },
    { 2, "Ctrl + / Ctrl − / Ctrl+0", "Zoom." },
};

/* 2.2 : rubrique temporaire, montrée seulement tant que la politique du
 * paquet neutralise Google Recherche (elle disparaît avec la mise à jour
 * qui la lève) */
static const bloc_aide AIDE_GOOGLE[] = {
    { 0, "Pourquoi Google Recherche est-il indisponible sous PuppyOS ?", NULL },
    { 3, NULL, NULL },
    { 2, "Ce qui se passe", "Sous PuppyOS, Google Recherche ne donne pas de résultats : à la place, il affiche des contrôles "
         "anti-robots (CAPTCHA) qu'on ne peut pas passer, ou des pages qui restent bloquées. L'origine exacte n'est pas "
         "connue : Google ne l'explique pas, et un système ou un navigateur peu répandu peut suffire à déclencher ces contrôles." },
    { 2, "Seule la recherche est concernée", "YouTube, Gmail, Maps, Drive et les autres services de Google marchent comme "
         "d'habitude et ne sont pas touchés par ce qui suit. Google n'est pas bloqué." },
    { 2, "Ce que fait le Navigateur", "Il arrête seulement les recherches Google avant qu'elles partent : celles tapées dans "
         "la barre d'adresse (si ton moteur est Google), celles d'un lien ou d'un formulaire de recherche Google, et les "
         "suggestions que la page de Google envoie pendant que tu tapes. Aucun mot n'est envoyé à Google. Les pages et les "
         "scripts de Google ne sont jamais modifiés." },
    { 2, "La redirection vers DuckDuckGo", "La recherche part vers DuckDuckGo, avec les mêmes mots. Un bandeau te le dit une "
         "fois par lancement. DuckDuckGo n'est pas cru sur parole pour autant : ses résultats, liens et téléchargements "
         "passent par les mêmes protections que le reste du web (pisteurs bloqués, bac à sable, quarantaine)." },
    { 2, "Ouvrir quand même Google", "Ouvrir google.com affiche d'abord une page locale de PuppyOS qui explique tout ça. Son "
         "bouton « Ouvrir quand même google.com » ouvre le vrai site. Paramètres → « Recherches Google vers DuckDuckGo » "
         "coupe la redirection : tes recherches iront à Google, sans garantie qu'elles aboutissent." },
    { 2, "Le retour de Google Recherche", "Quand Google Recherche remarchera vraiment (des recherches complètes, plusieurs jours "
         "de suite, pas un seul essai réussi), une mise à jour signée du Navigateur retirera la redirection, la page locale, "
         "cette rubrique et le bandeau. Le Navigateur ne le décide jamais tout seul. Si le problème revient, une autre mise à "
         "jour signée remet tout en place." },
    { 2, "Tester Google Recherche", NULL },          /* texte posé à la construction (résumé des tests) */
    { 0, "Captures des erreurs observées", NULL },
};

#define AIDE_MAX 180
static bloc_aide aide[AIDE_MAX];
static char aide_textes[NB_EXPLICATIONS][1400];
static char aide_test_google[600];
static int nb_aide, aide_h[AIDE_MAX], aide_l = -1, aide_google = -1, aller_aide_google;

void aide_invalider(void) { nb_aide = 0; aide_l = -1; }

void google_aide(void)
{
    aller_aide_google = 1;
    onglet_interne(INT_AIDE, 1);
}

static void aide_construire(void)
{
    if (nb_aide) return;
    aide_google = -1;
    if (google_neutralise) {
        char resume[300];
        google_tests_resume(resume, sizeof resume);
        snprintf(aide_test_google, sizeof aide_test_google, "Le bouton « Tester Google Recherche » de la page locale envoie "
                 "seulement le mot « puppyos » à Google, sur ton clic, et note le résultat sur cette machine. Un test réussi "
                 "ne réactive rien : seule une mise à jour signée le fait. %s", resume);
        aide_google = nb_aide;
        for (size_t i = 0; i < sizeof AIDE_GOOGLE / sizeof AIDE_GOOGLE[0]; i++) {
            aide[nb_aide] = AIDE_GOOGLE[i];
            if (aide[nb_aide].genre == 2 && !aide[nb_aide].b) aide[nb_aide].b = aide_test_google;
            nb_aide++;
        }
        captures_google_charger();
        static const char *num[CAPTURES_GOOGLE] = { "0", "1", "2", "3", "4", "5" };
        for (int i = 0; i < nb_captures_google; i++) aide[nb_aide++] = (bloc_aide){ 4, num[i], NULL };
        if (!nb_captures_google)
            aide[nb_aide++] = (bloc_aide){ 1, "Les captures des erreurs vraiment observées seront ajoutées ici, sans aucune "
                                              "information personnelle.", NULL };
    }
    for (size_t i = 0; i < sizeof AIDE_DEBUT / sizeof AIDE_DEBUT[0]; i++) aide[nb_aide++] = AIDE_DEBUT[i];
    for (int k = 0; k < NB_EXPLICATIONS; k++) {
        const explication *x = &EXPLICATIONS[k];
        snprintf(aide_textes[k], sizeof aide_textes[k], "Activée : %s\nCoupée : %s\nCe que tu risques sans elle : %s",
                 x->activee, x->coupee, x->risques);
        aide[nb_aide++] = (bloc_aide){ 2, x->nom, aide_textes[k] };
    }
    for (size_t i = 0; i < sizeof AIDE_FIN / sizeof AIDE_FIN[0] && nb_aide < AIDE_MAX; i++) aide[nb_aide++] = AIDE_FIN[i];
}

static int page_aide(int x, int y, int l)
{
    aide_construire();
    int y0 = y;
    y += titre_page(x, y, "Aide et lexique");
    if (aide_l != l) {                              /* hauteurs mesurées une fois par largeur */
        for (int i = 0; i < nb_aide; i++) {
            const bloc_aide *b = &aide[i];
            if (b->genre == 3) {
                illustration_google(0, 0, 0, 0, 0);  /* charge l'image */
                int hl = illu_google ? illu_google->h * (l < 420 ? l : 420) / (illu_google->l ? illu_google->l : 1) : 0;
                aide_h[i] = illu_google ? (hl > 300 ? 300 : hl) + 16 : 0;
                continue;
            }
            if (b->genre == 4) {
                surface *c = captures_google[atoi(b->a)];
                int hc = c->h * l / (c->l ? c->l : 1);
                aide_h[i] = (hc > 420 ? 420 : hc) + 52;
                continue;
            }
            aide_h[i] = b->genre == 0 ? 46 : b->genre == 1 ? paragraphe(0, 0, l, b->a, 0, 14, 0) + 12
                                                         : paragraphe(0, 0, l - 30, b->b, 0, 13, 0) + 34;
        }
        aide_l = l;
    }
    if (aller_aide_google) {                        /* ouverte depuis la page locale ou le bandeau */
        aller_aide_google = 0;
        int dy = 52;
        for (int i = 0; i < aide_google && i < nb_aide; i++) dy += aide_h[i];
        if (aide_google >= 0 && nb_aide && ACTIF->interne == INT_AIDE) { ACTIF->defile = dy; sale = 1; }
    }
    for (int i = 0; i < nb_aide; i++) {
        const bloc_aide *b = &aide[i];
        if (y + aide_h[i] > PY - 20 && y < PY + PH + 20) {
            if (b->genre == 3) illustration_google(x, y, l < 420 ? l : 420, 300, 0);
            else if (b->genre == 4) {
                surface *c = captures_google[atoi(b->a)];
                int hc = aide_h[i] - 52, lc = c->l * hc / (c->h ? c->h : 1);
                gfx_composer_taille(toile, c, x, y, lc, hc, 255);
                paragraphe(x, y + hc + 6, l, "Observation faite dans l'environnement testé : elle montre ce qui s'est passé, "
                           "pas la cause technique.", C_GRIS, 12, 1);
            } else if (b->genre == 0) texte(x, y + 14, b->a, C_BLEU, 18, 1);
            else if (b->genre == 1) paragraphe(x, y, l, b->a, RVB(50, 56, 70), 14, 1);
            else {
                texte(x + 10, y, b->a, C_TEXTE, 14, 1);
                paragraphe(x + 30, y + 22, l - 30, b->b, RVB(70, 76, 90), 13, 1);
            }
        }
        y += aide_h[i];
    }
    return y + 30 - y0;
}

static const char *erreur_lisible(const char *e)
{
    static const struct { const char *code, *texte; } T[] = {
        { "NAME_NOT_RESOLVED", "Adresse introuvable : le nom du site n'existe pas, ou le réseau ne trouve pas les noms (DNS)." },
        { "INTERNET_DISCONNECTED", "Pas de connexion internet." },
        { "CONNECTION_REFUSED", "Le site a refusé la connexion." },
        { "CONNECTION_TIMED_OUT", "Le site ne répond pas." },
        { "TIMED_OUT", "Le site ne répond pas." },
        { "CONNECTION_RESET", "La connexion a été coupée." },
        { "CONNECTION_CLOSED", "La connexion a été coupée." },
        { "ADDRESS_UNREACHABLE", "Le site est injoignable depuis ce réseau." },
        { "NETWORK_CHANGED", "Le réseau a changé pendant le chargement." },
        { "EMPTY_RESPONSE", "Le site n'a rien envoyé." },
        { "TOO_MANY_REDIRECTS", "Le site renvoie sans fin vers lui-même." },
        { "PROXY_CONNECTION_FAILED", "Le VPN (proxy) ne répond pas : rien ne sort sans lui. Vérifie Paramètres → VPN." },
        { "SOCKS_CONNECTION_FAILED", "Le VPN (SOCKS) ne répond pas : rien ne sort sans lui. Vérifie Paramètres → VPN." },
        { "TUNNEL_CONNECTION_FAILED", "Le VPN n'a pas pu joindre ce site." },
        { "CERT_DATE_INVALID", "Le certificat du site a expiré (ou n'est pas encore valable). Vérifie aussi la date et l'heure de PuppyOS." },
        { "CERT_AUTHORITY_INVALID", "Le certificat n'est signé par aucune autorité reconnue : certificat fait maison, ou quelqu'un intercepte la connexion." },
        { "CERT_COMMON_NAME_INVALID", "Le certificat est celui d'un autre nom de site : quelqu'un pourrait se faire passer pour celui-ci." },
        { "CERT_REVOKED", "Le certificat a été révoqué par son autorité : il a peut-être été volé." },
        { "CERT_WEAK", "Le certificat utilise une clé ou une signature trop faible." },
        { "CERT_KNOWN_INTERCEPTION", "Un logiciel ou un appareil intercepte la connexion chiffrée." },
        { "CERTIFICATE_TRANSPARENCY", "Le certificat n'a pas été publié dans les journaux publics comme il le devrait." },
        { "CERT_", "Le certificat du site n'est pas valable : quelqu'un pourrait se faire passer pour lui." },
        { "SSL_VERSION_OR_CIPHER", "Le site n'accepte qu'un chiffrement trop ancien, refusé parce qu'il n'est plus sûr." },
        { "SSL_", "La connexion chiffrée a échoué." },
        { "BLOCKED_BY_CLIENT", "Bloqué par le Navigateur." },
        { "BLOCKED_BY_RESPONSE", "Le site refuse d'être affiché ainsi." },
        { "FILE_NOT_FOUND", "Fichier introuvable." },
        { "FAILED", "Le chargement a échoué." },
    };
    for (size_t i = 0; i < sizeof T / sizeof T[0]; i++) if (strstr(e, T[i].code)) return T[i].texte;
    return "Le chargement a échoué.";
}

static void page_erreur(onglet *o)
{
    gfx_rect(toile, PX, PY, PL, PH, RVB(246, 248, 252));
    int l = 640, x = PX + (PL - l) / 2, y = PY + 70;
    if (o->erreur == 2) {
        gfx_disque(toile, x + 30, y + 30, 30, RVB(230, 120, 60));
        texte(x + 23, y + 12, "!", RVB(255, 255, 255), 32, 1);
        texte(x + 80, y + 10, "Cette page a planté", C_TEXTE, 24, 1);
        int hp = paragraphe(x + 80, y + 50, l - 80, o->erreur_texte[0] ? o->erreur_texte : "Le moteur a arrêté cette page.",
                            RVB(60, 66, 80), 15, 1);
        hp += paragraphe(x + 80, y + 58 + hp, l - 80, "Les autres onglets continuent. La cause est notée dans le journal.", C_GRIS, 14, 1);
        if (o->erreur_detail[0]) texte(x + 80, y + 66 + hp, o->erreur_detail, RVB(150, 156, 170), 12, 0);
        bouton(x + 80, y + 96 + hp, 140, 34, "Recharger", 1, A_ERREUR_REESSAYER, 0);
        return;
    }
    if (o->erreur == 4) {                      /* 2.2 : Google Recherche indisponible (page locale) */
        char h[256] = "", t[300];
        hote_de(o->erreur_url, h, sizeof h);
        /* fenêtre large : l'illustration (cliquable) à droite du texte ; sinon dessous */
        int wi = PL - 740 - 60, cote = 0;
        if (wi > 260) wi = 260;
        if (wi >= 160) {
            x = PX + (PL - 740 - wi) / 2;
            int hi = illustration_google(x + 740, y, wi, PH - 160, 1);
            if (hi > 0) {
                cote = 1;
                paragraphe(x + 740, y + hi + 8, wi, "Pourquoi ? Clique sur l'image pour lire l'aide.", C_BLEU, 13, 1);
            }
        }
        gfx_disque(toile, x + 30, y + 30, 30, RVB(110, 120, 140));
        gfx_cercle(toile, x + 26, y + 26, 11, 3, RVB(255, 255, 255));
        gfx_ligne(toile, x + 34, y + 34, x + 43, y + 43, 4, RVB(255, 255, 255));
        int ht = paragraphe(x + 80, y + 4, l - 80, "Google Recherche est indisponible sous PuppyOS", C_TEXTE, 22, 1);
        texte(x + 80, y + 10 + ht, h, C_BLEU, 15, 0);
        int yy = y + 40 + ht;
        yy += paragraphe(x + 80, yy, l - 80, "Pour l'instant, les recherches Google n'aboutissent pas dans PuppyOS : des contrôles "
                         "anti-robots impossibles à passer s'affichent à la place des résultats. La cause exacte n'est pas connue.",
                         RVB(60, 66, 80), 15, 1) + 8;
        yy += paragraphe(x + 80, yy, l - 80, "Tant que c'est le cas, aucune recherche n'est envoyée à Google : elles partent vers "
                         "DuckDuckGo. YouTube, Gmail et les autres services de Google ne sont pas concernés.",
                         RVB(60, 66, 80), 15, 1) + 8;
        yy += paragraphe(x + 80, yy, l - 80, "Cette page est une page de PuppyOS, pas une page de Google. DuckDuckGo n'est pas cru "
                         "sur parole non plus : ses résultats, liens et téléchargements passent par les mêmes protections que le reste du web.",
                         C_GRIS, 13, 1) + 14;
        int bx = x + 80;
        bx += bouton_auto(bx, yy, "Chercher avec DuckDuckGo", 1, A_GOOGLE_DDG, 0) + 10;
        bx += bouton_auto(bx, yy, "Ouvrir quand même google.com", 0, A_GOOGLE_VRAI, 0) + 10;
        bouton_auto(bx, yy, "Tester Google Recherche", 0, A_GOOGLE_TESTER, 0);
        yy += 40;
        char resume[300];
        google_tests_resume(resume, sizeof resume);
        snprintf(t, sizeof t, "Le test envoie seulement le mot « puppyos » à Google, sur ton clic. %s", resume);
        yy += paragraphe(x + 80, yy, l - 80, t, C_GRIS, 12, 1) + 14;
        if (cote) return;
        int hi = illustration_google(x + 80, yy, l - 80, PY + PH - yy - 44, 1);
        if (hi > 0) texte(x + 80, yy + hi + 6, "Pourquoi ? Clique sur l'image pour lire l'aide.", C_BLEU, 13, 0);
        else bouton_auto(x + 80, yy, "Pourquoi ?", 0, A_GOOGLE_AIDE, 0);
        return;
    }
    if (o->erreur == 3) {                      /* 2.1 : page PHP bloquée */
        char h[256] = "";
        hote_de(o->erreur_url, h, sizeof h);
        gfx_disque(toile, x + 30, y + 30, 30, RVB(230, 150, 40));
        texte(x + 13, y + 18, "PHP", RVB(255, 255, 255), 16, 1);
        texte(x + 80, y + 6, "Page PHP bloquée", C_TEXTE, 24, 1);
        texte(x + 80, y + 42, h, C_BLEU, 16, 0);
        int hp = paragraphe(x + 80, y + 74, l - 80, "Cette page est faite en PHP et le blocage des pages PHP est actif. "
                            "Les applications PHP anciennes ou mal tenues sont une porte d'entrée fréquente pour les pages piégées.",
                            RVB(60, 66, 80), 15, 1);
        hp += paragraphe(x + 80, y + 82 + hp, l - 80, "Le site sera ouvert dans le bac à sable comme les autres. Ouvre-le si tu lui fais confiance.",
                         C_GRIS, 14, 1);
        int by = y + 100 + hp, bx = x + 80;
        bx += bouton_auto(bx, by, "Ouvrir cette fois", 1, A_PHP_UNE_FOIS, 0) + 10;
        bx += bouton_auto(bx, by, "Toujours permettre ce site", 0, A_PHP_TOUJOURS, 0) + 10;
        bouton_auto(bx, by, "Retour", 0, A_PRECEDENT, 0);
        return;
    }
    int cert = strstr(o->erreur_texte, "CERT") || strstr(o->erreur_texte, "SSL_");
    gfx_disque(toile, x + 30, y + 30, 30, cert ? C_ROUGE : RVB(150, 160, 180));
    if (cert) texte(x + 24, y + 12, "!", RVB(255, 255, 255), 32, 1);
    else gfx_ligne(toile, x + 16, y + 30, x + 44, y + 30, 3, RVB(255, 255, 255));
    texte(x + 80, y + 6, cert ? "Connexion non sûre" : "Ce site est injoignable", C_TEXTE, 24, 1);
    char h[256] = "";
    hote_de(o->erreur_url, h, sizeof h);
    texte(x + 80, y + 42, h[0] ? h : o->erreur_url, C_BLEU, 16, 0);
    int hp = paragraphe(x + 80, y + 74, l - 80, erreur_lisible(o->erreur_texte), RVB(60, 66, 80), 15, 1);
    texte(x + 80, y + 82 + hp, o->erreur_texte, RVB(150, 156, 170), 12, 0);
    int by = y + 110 + hp;
    int bx = x + 80;
    bx += bouton_auto(bx, by, "Réessayer", 1, A_ERREUR_REESSAYER, 0) + 10;
    /* un certificat refusé : pas de raccourci vers http, c'est justement là
     * qu'un intercepteur attend */
    if (cert) paragraphe(x + 80, by + 44, l - 80, "Le Navigateur ne propose pas de passer outre : si quelqu'un intercepte la "
                         "connexion, tout ce que tu enverrais serait lu. Réessaie plus tard ou depuis un autre réseau.", C_GRIS, 13, 1);
    else if (!strncmp(o->erreur_url, "https://", 8) && !strstr(o->erreur_texte, "NAME_NOT_RESOLVED") && !strstr(o->erreur_texte, "INTERNET"))
        bouton_auto(bx, by, "Ouvrir en http (non chiffré)", 0, A_ERREUR_HTTP, 0);
}

/* 0.12.1 : code source coloré (balises en bleu, attributs, textes entre
 * guillemets en vert, commentaires en gris) et audit de sécurité */
static int source_ligne(int x, int y, int i)
{
    const char *d = SRC.texte + SRC.lignes[i];
    int fin = i + 1 < SRC.nb ? SRC.lignes[i + 1] : (int)SRC.n;
    int n = fin - SRC.lignes[i];
    while (n > 0 && (d[n - 1] == '\n')) n--;
    int etat = SRC.etat[i];
    char q = 0;                                    /* guillemet ouvert dans une balise */
    char morceau[LARGEUR_SOURCE + 8];
    int k = 0;
    while (k < n) {
        int debut = k, e0 = etat;
        couleur c;
        if (etat == 2) {                           /* commentaire jusqu'à --> */
            while (k < n && !(k >= 2 && d[k] == '>' && d[k - 1] == '-' && d[k - 2] == '-')) k++;
            if (k < n) { k++; etat = 0; }
            c = RVB(140, 150, 140);
        } else if (etat == 1) {                    /* dans une balise */
            if (q || d[k] == '"' || d[k] == '\'') {
                if (!q) q = d[k++];
                while (k < n && d[k] != q) k++;
                if (k < n) { k++; q = 0; }
                c = RVB(30, 130, 60);
            } else {
                while (k < n && d[k] != '"' && d[k] != '\'' && d[k] != '>') k++;
                if (k < n && d[k] == '>') { k++; etat = 0; }
                c = RVB(40, 90, 200);
            }
        } else {                                   /* texte (ou script) */
            while (k < n && d[k] != '<') k++;
            if (k == debut) { etat = (k + 3 < n && !strncmp(d + k, "<!--", 4)) ? 2 : 1; continue; }
            c = RVB(40, 44, 52);
        }
        (void)e0;
        int l = k - debut;
        if (l <= 0) continue;
        if (l > LARGEUR_SOURCE) l = LARGEUR_SOURCE;
        memcpy(morceau, d + debut, (size_t)l);
        morceau[l] = 0;
        texte(x, y, morceau, c, 13, 0);
        x += largeur(morceau, 13, 0);
        if (x > PX + PL) break;
    }
    return 0;
}

static void page_source(onglet *o)
{
    (void)o;
    gfx_rect(toile, PX, PY, PL, PH, RVB(250, 251, 253));
    rect ancien = toile->clip;
    gfx_clip(toile, PX, PY, PL, PH);
    clip_zones = (rect){ PX, PY, PL, PH };
    /* en-tête : adresse et onglets Code / Audit */
    int hy = PY, hh = 52;
    int y0 = PY + hh - o->defile;
    int x = PX + 20, l = PL - 40;
    if (SRC.vue == 0 && SRC.texte) {
        int lh = 18, premier = o->defile / lh;
        if (premier < 0) premier = 0;
        int ng = 0;
        for (int v = SRC.nb; v; v /= 10) ng++;
        int gx = x + ng * 9 + 14;
        for (int i = premier; i < SRC.nb; i++) {
            int y = y0 + 10 + i * lh;
            if (y > PY + PH) break;
            char num[16];
            snprintf(num, sizeof num, "%d", i + 1);
            texte(x, y, num, RVB(170, 175, 186), 12, 0);
            source_ligne(gx, y, i);
        }
        contenu_h = SRC.nb * lh + hh + 20;
        if (SRC.total > (long)SRC.n) {
            char t[120];
            snprintf(t, sizeof t, "… code tronqué : %ld Ko sur %ld Ko affichés", (long)(SRC.n / 1024), SRC.total / 1024);
            texte(x, y0 + 14 + SRC.nb * lh, t, C_GRIS, 13, 1);
            contenu_h += 30;
        }
    } else if (SRC.vue == 2) {                 /* 2.1 : historique des audits */
        int y = y0 + 20;
        y += titre_page(x, y, "Historique des audits");
        y += paragraphe(x, y, l, "Les audits faits sur cette machine (jamais ceux des onglets privés), du plus récent au plus ancien.",
                        C_GRIS, 13, 1) + 10;
        if (nb_audits) { bouton_auto(x, y, "Vider l'historique des audits", 2, A_AUDIT_EFFACER, 0); y += 44; }
        for (int i = nb_audits - 1; i >= 0; i--) {
            audit_passe *a = &audits[i];
            if (y > PY - 80 && y < PY + PH + 20) {
                carte_debut(x, y, l, 62);
                couleur cc = a->pire == 2 ? C_ROUGE : a->pire == 1 ? RVB(220, 140, 20) : RVB(60, 150, 90);
                gfx_disque(toile, x + 20, y + 31, 7, cc);
                char q[32], t[400];
                quand(a->t, q, sizeof q);
                texte(x + 40, y + 9, q, C_GRIS, 13, 0);
                couper(t, a->url, l - 260, 14);
                texte(x + 140, y + 8, t, C_TEXTE, 14, 0);
                snprintf(t, sizeof t, "%d danger(s), %d point(s) d'attention", a->dangers, a->attentions);
                texte(x + l - largeur(t, 12, 0) - 14, y + 10, t, cc, 12, 0);
                couper(t, a->resume, l - 60, 13);
                texte(x + 40, y + 34, t, RVB(70, 76, 90), 13, 0);
            }
            y += 70;
        }
        if (!nb_audits) { texte(x, y, "Aucun audit pour l'instant. F10 sur un site en lance un.", C_GRIS, 14, 0); y += 30; }
        contenu_h = y - y0 + hh + 20;
    } else {
        int y = y0 + 20;
        y += titre_page(x, y, "Audit de sécurité");
        const char *bilan = SRC.pire == 2 ? "Attention : ce site présente un vrai risque (voir en rouge)."
                          : SRC.pire == 1 ? "Rien de grave, mais quelques points à connaître (en orange)."
                                          : "Rien d'inquiétant repéré sur cette page.";
        couleur cb = SRC.pire == 2 ? C_ROUGE : SRC.pire == 1 ? RVB(200, 120, 0) : RVB(30, 140, 70);
        y += paragraphe(x, y, l, bilan, cb, 16, 1) + 6;
        y += paragraphe(x, y, l, "L'audit lit la page telle qu'elle est chargée, sans rien envoyer. Il repère les signes connus, "
                        "il ne remplace pas Flair ni Honey-Dog qui surveillent le moteur en permanence.", C_GRIS, 13, 1) + 14;
        for (int g = 2; g >= 0; g--)
            for (int i = 0; i < SRC.nb_constats; i++) {
                constat *c = &SRC.constats[i];
                if (c->g != g) continue;
                int th = paragraphe(x + 40, 0, l - 56, c->t, C_TEXTE, 14, 0);
                carte_debut(x, y, l, th + 20);
                couleur cc = g == 2 ? C_ROUGE : g == 1 ? RVB(220, 140, 20) : RVB(60, 150, 90);
                gfx_disque(toile, x + 20, y + 10 + th / 2, 7, cc);
                paragraphe(x + 40, y + 10, l - 56, c->t, C_TEXTE, 14, 1);
                y += th + 26;
            }
        contenu_h = y - y0 + hh + 20;
    }
    /* en-tête par-dessus */
    gfx_rect(toile, PX, hy, PL, hh, RVB(238, 242, 248));
    gfx_rect(toile, PX, hy + hh - 1, PL, 1, C_LIGNE);
    int bx = PX + 16;
    bx += bouton_auto(bx, hy + 10, "Code source", SRC.vue == 0, A_SOURCE_VUE, 0) + 8;
    bx += bouton_auto(bx, hy + 10, SRC.pire == 2 ? "Audit (danger)" : SRC.pire == 1 ? "Audit (attention)" : "Audit", SRC.vue == 1, A_SOURCE_VUE, 1) + 8;
    bx += bouton_auto(bx, hy + 10, "Historique des audits", SRC.vue == 2, A_SOURCE_VUE, 2) + 16;
    char t[1100];
    couper(t, SRC.url, PX + PL - bx - 20, 13);
    texte(bx, hy + 17, t, C_GRIS, 13, 0);
    int max = contenu_h - PH + 60;
    if (max < 0) max = 0;
    if (o->defile > max) { o->defile = max; sale = 1; }
    if (max > 0) {
        int bh = PH * PH / (contenu_h + 60);
        if (bh < 30) bh = 30;
        int by = PY + (PH - bh) * o->defile / max;
        gfx_rect_arrondi(toile, PX + PL - 9, by, 6, bh, 3, RVBA(100, 110, 130, 120));
    }
    clip_zones.l = 0;
    toile->clip = ancien;
}

void page_interne_dessiner(onglet *o)
{
    if (o->interne == INT_SOURCE) { page_source(o); return; }
    if (o->erreur && !o->interne) { page_erreur(o); return; }
    gfx_rect(toile, PX, PY, PL, PH, RVB(243, 245, 250));
    rect ancien = toile->clip;
    gfx_clip(toile, PX, PY, PL, PH);
    clip_zones = (rect){ PX, PY, PL, PH };
    /* menu de gauche */
    int ml = 210;
    gfx_rect(toile, PX, PY, ml, PH, RVB(234, 238, 246));
    gfx_rect(toile, PX + ml, PY, 1, PH, C_LIGNE);
    for (int k = 1; k <= NB_PAGES_MENU; k++) {
        int yy = PY + 20 + (k - 1) * 42;
        if (o->interne == k) gfx_rect_arrondi(toile, PX + 10, yy, ml - 20, 36, 8, RVB(210, 224, 248));
        else if (dedans(PX + 10, yy, ml - 20, 36)) gfx_rect_arrondi(toile, PX + 10, yy, ml - 20, 36, 8, RVB(224, 232, 246));
        texte(PX + 26, yy + 9, NOMS_INTERNES[k], o->interne == k ? C_BLEU : C_TEXTE, 15, o->interne == k);
        zone_ajouter(PX + 10, yy, ml - 20, 36, A_PAGE_INTERNE, k);
    }
    int l = PL - ml - 80;
    if (l > 860) l = 860;
    int x = PX + ml + 40, y = PY + 28 - o->defile;
    if (o->interne == INT_PARAMETRES && aller_effacer) { aller_effacer = 0; o->defile = effacer_y; y = PY + 28 - o->defile; }
    switch (o->interne) {
    case INT_PARAMETRES: contenu_h = page_parametres(x, y, l); break;
    case INT_HISTORIQUE: contenu_h = page_historique(x, y, l); break;
    case INT_FAVORIS:    contenu_h = page_favoris(x, y, l); break;
    case INT_COFFRE:     contenu_h = page_coffre(x, y, l); break;
    case INT_EXTENSIONS: contenu_h = page_extensions(x, y, l); break;
    case INT_AIDE:       contenu_h = page_aide(x, y, l); break;
    }
    int max = contenu_h - PH + 60;
    if (max < 0) max = 0;
    if (o->defile > max) { o->defile = max; sale = 1; }
    if (max > 0) {                               /* ascenseur */
        int bh = PH * PH / (contenu_h + 60);
        if (bh < 30) bh = 30;
        int by = PY + (PH - bh) * o->defile / max;
        gfx_rect_arrondi(toile, PX + PL - 9, by, 6, bh, 3, RVBA(100, 110, 130, 120));
    }
    clip_zones.l = 0;
    toile->clip = ancien;
}

/* ===================== 2.1 : premier lancement ========================= */
/* Obligatoire au premier démarrage : quelques écrans de présentation (le
 * bouton « Suivant » attend un peu, pour qu'on ne les saute pas sans les
 * voir), puis le réglage de la sécurité : JavaScript, cookies et blocage
 * PHP, rien de choisi d'avance. Aucun site ne s'ouvre avant la fin. */
typedef struct { const char *titre, *texte; } ecran_presentation;
static const ecran_presentation PRESENTATION[] = {
    { "Bienvenue dans le Navigateur",
      "Le Navigateur affiche les sites avec le moteur Chromium, mais ce moteur ne touche jamais directement à PuppyOS : "
      "il tourne enfermé dans un bac à sable à deux murs, sans accès à tes fichiers, et Honey-Dog le surveille en "
      "permanence. S'il fait quelque chose d'anormal, il est arrêté et tu es prévenu.\n\n"
      "Le Navigateur dessine lui-même les onglets, la barre d'adresse et les menus, et garde tes données (favoris, "
      "historique, coffre à mots de passe) sur cette machine seulement. Pas de compte, pas de synchronisation, pas de "
      "statistiques envoyées : rien ne part vers PuppyOS ni vers personne." },
    { "Pourquoi autant de protections ?",
      "Un site web, c'est du code écrit par des inconnus qui s'exécute chez toi. La plupart sont honnêtes, mais une seule "
      "publicité piégée ou un site piraté suffit. Le Navigateur empile donc plusieurs barrières : les pisteurs connus sont "
      "bloqués, les bandeaux cookies refusés, les liens nettoyés, les adresses http essayées d'abord en https.\n\n"
      "Chaque téléchargement va en quarantaine et Flair l'analyse avant que tu puisses l'ouvrir, même une simple image .png "
      "ou une vidéo .avi : un fichier peut mentir sur ce qu'il est. Si une barrière tombe, les autres tiennent encore." },
    { "Les mises à jour",
      "Les mises à jour de PuppyOS et du Navigateur arrivent en paquets signés. Avant d'installer quoi que ce soit, PuppyOS "
      "vérifie la signature : un paquet modifié, même d'un seul octet, est refusé. Le moteur Chromium est lui aussi un "
      "paquet signé, vérifié de la même façon.\n\n"
      "Après une mise à jour du Navigateur, réinstalle Navigateur.puppy depuis le Bureau. Si une mise à jour pose problème, "
      "le bouton « Revenir à » des mises à jour remet la version d'avant." },
    { "Zero-Trust : ne rien croire sur parole",
      "Zero-Trust veut dire qu'aucune pièce ne fait confiance à une autre sans preuve. Le moteur ne voit pas tes mots de "
      "passe : ils vivent dans une enclave chiffrée à part. Les sites ne reçoivent que ce que tu permets : caméra, micro, "
      "position et notifications sont demandés, et tu peux retirer ton accord à tout moment.\n\n"
      "Le Navigateur te montre aussi ce qu'il voit : pisteurs, pages PHP, certificat du site, permissions actives, et un "
      "audit de sécurité complet d'une touche (F10). À toi de juger, en connaissance de cause." },
};
#define NB_PRESENTATION ((int)(sizeof PRESENTATION / sizeof PRESENTATION[0]))
#define PREMIER_ATTENTE 1200.0           /* ms avant que « Suivant » réponde */

static int premier_ecran, premier_choix[3] = { -1, -1, -1 }, premier_defile;
static double premier_depuis = -1;

static int premier_actif(void) { return !R.premier_fait && !streaming; }

static void premier_aller(int e)
{
    if (e < 0 || e > NB_PRESENTATION) return;
    premier_ecran = e;
    premier_depuis = maintenant;
    premier_defile = 0;
    anime_jusqua = maintenant + PREMIER_ATTENTE + 100;
    sale = 1;
}

static int premier_pret(void) { return maintenant - premier_depuis >= PREMIER_ATTENTE; }
static int premier_complet(void) { return premier_choix[0] >= 0 && premier_choix[1] >= 0 && premier_choix[2] >= 0; }

static void premier_suivant(void)
{
    if (!premier_pret()) return;
    if (premier_ecran < NB_PRESENTATION) { premier_aller(premier_ecran + 1); return; }
    if (!premier_complet()) { dire("Choisis Activer ou Désactiver pour chacune des trois protections"); return; }
    R.js = premier_choix[0];
    R.cookies = premier_choix[1];
    R.php_bloque = premier_choix[2];
    premier_terminer();
}

static void premier_option(int x, int y, int l, int h, const char *titre_, const char *txt, int choisi, int arg)
{
    int sv = dedans(x, y, l, h);
    gfx_rect_arrondi(toile, x, y, l, h, 10, choisi ? RVB(222, 236, 255) : sv ? RVB(245, 248, 255) : RVB(255, 255, 255));
    gfx_contour_arrondi(toile, x, y, l, h, 10, choisi ? C_BLEU : RVB(200, 208, 222));
    gfx_cercle(toile, x + 20, y + 19, 8, 2, choisi ? C_BLEU : C_GRIS);
    if (choisi) gfx_disque(toile, x + 20, y + 19, 4, C_BLEU);
    texte(x + 36, y + 10, titre_, choisi ? C_BLEU : C_TEXTE, 15, 1);
    paragraphe(x + 14, y + 38, l - 28, txt, RVB(60, 66, 80), 13, 1);
    zone_ajouter(x, y, l, h, A_PREMIER_CHOIX, arg);
}

static void premier_dessiner(void)
{
    if (premier_depuis < 0) premier_aller(0);
    modal_debut = nb_zones;
    zone_ajouter(0, 0, EL, EH, A_DLG_FOND, 0);
    gfx_degrade(toile, 0, 0, EL, EH, RVB(214, 228, 248), RVB(240, 244, 252));
    int l = EL - 60 < 820 ? EL - 60 : 820, x = (EL - l) / 2;
    int y = 28 - premier_defile;
    /* les étapes */
    int nb = NB_PRESENTATION + 1;
    for (int i = 0; i < nb; i++) {
        int cx = EL / 2 - (nb - 1) * 14 + i * 28;
        if (i == premier_ecran) gfx_disque(toile, cx, y + 6, 6, C_BLEU);
        else gfx_disque(toile, cx, y + 6, 4, i < premier_ecran ? RVB(120, 160, 230) : RVB(180, 190, 210));
    }
    y += 26;
    int carte_y = y, carte_h;
    const char *bouton_t;
    if (premier_ecran < NB_PRESENTATION) {
        const ecran_presentation *e = &PRESENTATION[premier_ecran];
        int ht = paragraphe(0, 0, l - 80, e->texte, 0, 16, 0);
        carte_h = ht + 120;
        gfx_ombre(toile, x, carte_y, l, carte_h, 16, 20, RVBA(0, 0, 0, 60));
        gfx_rect_arrondi(toile, x, carte_y, l, carte_h, 16, RVB(255, 255, 255));
        texte(x + 40, carte_y + 30, e->titre, C_TEXTE, 26, 1);
        paragraphe(x + 40, carte_y + 84, l - 80, e->texte, RVB(50, 56, 70), 16, 1);
        bouton_t = "Suivant";
    } else {
        static const int EX[3] = { EX_JS, EX_COOKIES, EX_PHP };
        int hc[3], total = 0, lc = (l - 108) / 2;
        const char *intro = "Trois réglages décident de ce que les sites peuvent faire. Rien n'est choisi d'avance : lis les "
                            "conséquences et choisis. Tu pourras tout changer plus tard dans Paramètres.";
        int hi = paragraphe(0, 0, l - 80, intro, 0, 14, 0);
        for (int k = 0; k < 3; k++) {
            int a = paragraphe(0, 0, lc - 28, EXPLICATIONS[EX[k]].activee, 0, 13, 0);
            int b = paragraphe(0, 0, lc - 28, EXPLICATIONS[EX[k]].coupee, 0, 13, 0);
            hc[k] = (a > b ? a : b) + 50;
            total += hc[k] + 46;
        }
        carte_h = 100 + hi + total;
        gfx_ombre(toile, x, carte_y, l, carte_h, 16, 20, RVBA(0, 0, 0, 60));
        gfx_rect_arrondi(toile, x, carte_y, l, carte_h, 16, RVB(255, 255, 255));
        texte(x + 40, carte_y + 26, "Veuillez paramétrer votre sécurité internet s'il vous plaît", C_TEXTE, 21, 1);
        int yy = carte_y + 66;
        yy += paragraphe(x + 40, yy, l - 80, intro, C_GRIS, 14, 1) + 14;
        for (int k = 0; k < 3; k++) {
            texte(x + 40, yy, EXPLICATIONS[EX[k]].nom, C_BLEU, 17, 1);
            yy += 30;
            premier_option(x + 40, yy, lc, hc[k], k == 2 ? "Activer le blocage" : "Activer", EXPLICATIONS[EX[k]].activee,
                           premier_choix[k] == 1, k * 2 + 1);
            premier_option(x + 68 + lc, yy, lc, hc[k], k == 2 ? "Désactiver le blocage" : "Désactiver", EXPLICATIONS[EX[k]].coupee,
                           premier_choix[k] == 0, k * 2);
            yy += hc[k] + 16;
        }
        bouton_t = "Terminer";
    }
    /* boutons sous la carte */
    int by = carte_y + carte_h + 18;
    if (premier_ecran > 0) bouton_auto(x, by, "Précédent", 0, A_PREMIER, -1);
    int pret = premier_pret() && (premier_ecran < NB_PRESENTATION || premier_complet());
    int bl = 160, bx = x + l - bl;
    if (pret) bouton(bx, by, bl, 34, bouton_t, 1, A_PREMIER, 1);
    else {
        gfx_rect_arrondi(toile, bx, by, bl, 34, 7, RVB(220, 226, 236));
        double f = (maintenant - premier_depuis) / PREMIER_ATTENTE;
        if (f > 1) f = 1;
        if (premier_ecran < NB_PRESENTATION || f < 1) gfx_rect_arrondi(toile, bx, by + 30, (int)(bl * f), 4, 2, RVB(120, 160, 230));
        texte(bx + (bl - largeur(bouton_t, 14, 0)) / 2, by + 8, bouton_t, RVB(140, 148, 165), 14, 0);
        if (premier_ecran == NB_PRESENTATION && f >= 1) {
            const char *m = "Choisis les trois réglages pour continuer";
            texte(bx - largeur(m, 13, 0) - 14, by + 9, m, C_GRIS, 13, 0);
        }
    }
    /* défilement si l'écran est petit */
    int bas = by + 34 + 20 + premier_defile;
    int max = bas - EH;
    if (max < 0) max = 0;
    if (premier_defile > max) { premier_defile = max; sale = 1; }
}

static int premier_touche(const pf_evenement *e)
{
    switch (e->code) {
    case KEY_ENTER: case KEY_KPENTER: case KEY_SPACE: premier_suivant(); break;
    case KEY_BACKSPACE: if (premier_ecran > 0) premier_aller(premier_ecran - 1); break;
    case KEY_DOWN: premier_defile += 50; break;
    case KEY_UP: premier_defile = premier_defile > 50 ? premier_defile - 50 : 0; break;
    }
    if (e->ctrl && lettre(e) == 'q') quitter_navigateur();
    sale = 1;
    return 1;
}

/* =============================== dessin =============================== */
/* 2.1 : application web (plein écran) : une barre n'apparaît que quand la
 * souris monte tout en haut */
#define H_BARRE_APPLI 40
static void dessiner_appli(void)
{
    if (sy < 0 || sy > H_BARRE_APPLI) return;
    gfx_rect(toile, 0, 0, EL, H_BARRE_APPLI, RVBA(28, 34, 50, 235));
    onglet *o = nb_onglets ? ACTIF : NULL;
    if (o) dessiner_icone_page(12, 12, o->hote, 0);
    char t[400];
    couper(t, o && o->titre[0] ? o->titre : o ? o->hote : "", EL - 420, 14);
    texte(36, 11, t, RVB(240, 244, 252), 14, 0);
    int bx = EL - 10;
    bx -= 96;  bouton(bx, 5, 90, 30, "Fermer", 2, A_APPLI_FERMER, 0);
    bx -= 100; bouton(bx, 5, 94, 30, "Recharger", 0, A_RECHARGER, 0);
    bx -= 76;  bouton(bx, 5, 70, 30, "Menu", 0, A_MENU, 0);
}

void interface_dessiner(void)
{
    if (streaming) return;
    if (appli) {
        if (info_genre >= 0) dessiner_info();
        if (!menu_ouvert && !dlg) dessiner_appli();
    } else {
        if (nb_onglets && !ACTIF->interne && !ACTIF->erreur) {
            if (info_genre >= 0) dessiner_info();
            if (trouver_ouvert) dessiner_trouver();
        } else if (info_genre >= 0) dessiner_info();
        dessiner_onglets();
        dessiner_outils();
        if (R.barre_favoris) dessiner_favoris();
        if (focus == &adresse && nb_sug) dessiner_suggestions();
        dessiner_glisse();
    }
    if (menu_ouvert) menu_dessiner();
    if (premier_actif()) premier_dessiner();
    if (dlg) dlg_dessiner();
}

/* =============================== actions ============================== */
static void ouvrir_menu_identites(int x, int y)
{
    menu_vider();
    for (int i = 0; i < NB_IDENTITES; i++) menu_coche(IDENTITES[i].nom, A_IDENTITE, i, R.ua == i);
    menu_sep();
    menu_gris("Les sites voient ce système et ce navigateur");
    menu_montrer(x, y);
}

static void ouvrir_menu_site(int x, int y)
{
    onglet *o = ACTIF;
    if (o->interne || !o->hote[0]) return;
    menu_vider();
    char t[400];
    int https = !strncmp(o->url, "https://", 8);
    snprintf(t, sizeof t, "%s · %s", o->hote, https ? "connexion chiffrée (HTTPS)" : "connexion NON chiffrée");
    menu_gris(t);
    if (strstr(o->hote, "xn--")) menu_gris("Attention : nom en caractères étrangers (imitation possible)");
    menu_art(https ? "Certificat et chiffrement…" : "Pourquoi « non chiffré » ?", A_CERT, 0);
    if (https && o->cert.present && o->cert.fin > 0 && o->cert.fin - (double)time(NULL) < 14 * 86400.0)
        menu_gris("Attention : le certificat expire bientôt");
    if (o->mixte) { snprintf(t, sizeof t, "%d élément(s) chargé(s) sans chiffrement", o->mixte); menu_gris(t); }
    if (o->php) menu_gris("Page faite en PHP");
    if (o->nb_pisteurs) {
        snprintf(t, sizeof t, "Pisteurs : %d connu(s), bloqué(s) · %d probable(s)", o->pisteurs_connus, o->pisteurs_inconnus);
        menu_gris(t);
        for (int i = 0; i < o->nb_pisteurs && i < 4; i++) { snprintf(t, sizeof t, "    %s", o->pisteurs[i]); menu_gris(t); }
        if (o->nb_pisteurs > 4) menu_gris("    …");
    } else menu_gris("Aucun pisteur repéré sur cette page");
    menu_sep();
    menu_coche("JavaScript permis sur ce site", A_SITE_JS, 0, site_js_permis(o->hote));
    menu_coche("Cookies permis sur ce site", A_SITE_CK, 0, site_cookies_permis(o->hote));
    menu_coche("Pages PHP permises sur ce site", A_SITE_JS, 2, site_php_permis(o->hote));
    /* permissions retenues ou accordées pour cette page */
    int ip = -1;
    for (int i = 0; i < nb_perms; i++) if (!strcmp(perms[i].hote, o->hote)) ip = i;
    for (int k = 0; k < NB_PERMS; k++) {
        int e = ip >= 0 ? perms[ip].p[k] : -1;
        if (e >= 0) { snprintf(t, sizeof t, "%s : %s · retirer", PERM_TITRES[k], e ? "permis" : "bloqué"); menu_art(t, A_PERM_RETIRER, ip * NB_PERMS + k); }
        else if (o->perms & (1 << k)) { snprintf(t, sizeof t, "%s : permis pour cette page", PERM_TITRES[k]); menu_gris(t); }
    }
    menu_sep();
    int ni = images_nombre(o->hote);
    if (ni) { snprintf(t, sizeof t, "Restaurer les images d'origine (%d)", ni); menu_art(t, A_SITE_IMAGES, 0); }
    menu_art("Effacer cookies et stockage de ce site", A_SITE_EFFACER, 0);
    menu_art("Oublier ce site…", A_SITE_OUBLIER, 0);
    menu_sep();
    menu_art("Audit de sécurité du site               F10", A_AUDIT, 0);
    menu_art("Mots de passe de ce site…", A_SITE_COFFRE, 0);
    menu_montrer(x, y);
}

void menu_principal(int x, int y)
{
    menu_vider();
    if (appli) {                                 /* application web : l'essentiel */
        menu_art("Mode lecture                                F9", A_LECTURE, 0);
        menu_art("Enregistrer en PDF", A_PDF, 0);
        menu_art("Capture de la page", A_CAPTURE, 0);
        menu_art("Audit de sécurité                         F10", A_AUDIT, 0);
        menu_art("Menu du site (certificat, permissions…)", A_SITE, 0);
        menu_sep();
        menu_art("Fermer l'application              Ctrl+W", A_APPLI_FERMER, 0);
        menu_montrer(x, y);
        return;
    }
    menu_art("Nouvel onglet                       Ctrl+T", A_NOUVEL_ONGLET, 0);
    menu_art("Nouvel onglet privé          Ctrl+Maj+N", A_ONGLET_PRIVE, 0);
    menu_sep();
    menu_art("Historique                           Ctrl+H", A_PAGE_INTERNE, INT_HISTORIQUE);
    menu_art("Favoris", A_PAGE_INTERNE, INT_FAVORIS);
    menu_art("Mots de passe", A_PAGE_INTERNE, INT_COFFRE);
    menu_art("Extensions", A_PAGE_INTERNE, INT_EXTENSIONS);
    menu_sep();
    char z[64];
    snprintf(z, sizeof z, "Zoom : %d %%   (Ctrl + / Ctrl −)", nb_onglets ? ACTIF->zoom : 100);
    menu_gris(z);
    menu_art("Zoom avant", A_ZOOM, 2);
    menu_art("Zoom arrière", A_ZOOM, 0);
    menu_art("Zoom 100 %", A_ZOOM, 1);
    menu_sep();
    menu_art("Rechercher dans la page          Ctrl+F", A_TROUVER, 0);
    menu_art("Mode lecture                                F9", A_LECTURE, 0);
    menu_art("Enregistrer en PDF                  Ctrl+P", A_PDF, 0);
    menu_art("Capture de la page", A_CAPTURE, 0);
    menu_art("Audit de sécurité                         F10", A_AUDIT, 0);
    menu_art("Installer comme application", A_APPLI, 0);
    menu_sep();
    menu_art("Effacer les données de navigation…", A_EFFACER_VOIR, 0);
    menu_art("Paramètres", A_PAGE_INTERNE, INT_PARAMETRES);
    menu_art("Aide et lexique                            F1", A_PAGE_INTERNE, INT_AIDE);
    menu_sep();
    menu_art("Quitter", A_QUITTER, 0);
    menu_montrer(x, y);
}

void menu_comptes(int *ids, char noms[][128], int n)
{
    menu_vider();
    menu_gris("Remplir avec le compte :");
    for (int i = 0; i < n && i < 30; i++) menu_art(noms[i], A_COMPTE, ids[i]);
    menu_montrer(EL - 300, H_ONGLETS + H_OUTILS - 4);
}

void dialogue_lien(const char *v, int x, int y)
{
    menu_lien[0] = menu_image[0] = menu_sel[0] = 0;
    menu_edition = 0;
    if (v && v[0] && json_valide(v)) {
        json_chaine(v, "l", menu_lien, sizeof menu_lien);
        json_chaine(v, "i", menu_image, sizeof menu_image);
        json_chaine(v, "s", menu_sel, sizeof menu_sel);
        double ed = 0;
        if (json_nombre(v, "e", &ed)) menu_edition = ed > 0;
        double rm = 0;
        menu_image_modifiee = json_nombre(v, "r", &rm) && rm > 0;
    }
    if (strncmp(menu_lien, "http", 4)) menu_lien[0] = 0;
    if (strncmp(menu_image, "http", 4)) menu_image[0] = 0;
    menu_px = x; menu_py = y;
    menu_vider();
    if (menu_lien[0]) {
        menu_art("Ouvrir le lien dans un nouvel onglet", A_LIEN_ONGLET, 0);
        menu_art("Ouvrir le lien dans un onglet privé", A_LIEN_PRIVE, 0);
        menu_art("Copier l'adresse du lien", A_LIEN_COPIER, 0);
        menu_art("Ajouter le lien aux favoris", A_LIEN_FAVORI, 0);
        menu_sep();
    }
    if (menu_image[0]) {
        menu_art("Ouvrir l'image dans un nouvel onglet", A_IMAGE_ONGLET, 0);
        menu_art("Copier l'adresse de l'image", A_IMAGE_COPIER, 0);
        if (nb_onglets && !ACTIF->prive) {
            menu_art("Modifier l'image… (sur cette machine)", A_IMAGE_MODIFIER, 0);
            if (menu_image_modifiee) menu_art("Restaurer l'original", A_IMAGE_RESTAURER, 0);
        }
        menu_sep();
    }
    if (menu_edition) {
        /* champ de saisie : le menu d'édition complet */
        menu_art("Annuler                              Ctrl+Z", A_EDITION, 0);
        menu_art("Rétablir                             Ctrl+Y", A_EDITION, 1);
        menu_sep();
        if (menu_sel[0]) menu_art("Couper", A_EDITION, 2); else menu_gris("Couper");
        if (menu_sel[0]) menu_art("Copier                                Ctrl+C", A_SEL_COPIER, 0); else menu_gris("Copier");
        if (presse[0]) menu_art("Coller                                 Ctrl+V", A_COLLER_PAGE, 0); else menu_gris("Coller");
        if (menu_sel[0]) menu_art("Supprimer", A_EDITION, 3); else menu_gris("Supprimer");
        menu_art("Tout sélectionner              Ctrl+A", A_EDITION, 4);
        menu_sep();
    } else {
        if (menu_sel[0]) {
            char t[260];
            snprintf(t, sizeof t, "Rechercher « %.40s%s »", menu_sel, strlen(menu_sel) > 40 ? "…" : "");
            menu_art(t, A_SEL_CHERCHER, 0);
            menu_art("Copier                                Ctrl+C", A_SEL_COPIER, 0);
        }
        menu_art("Tout sélectionner              Ctrl+A", A_EDITION, 4);
        menu_sep();
    }
    menu_art("Précédent", A_PRECEDENT, 0);
    menu_art("Suivant", A_SUIVANT, 0);
    menu_art("Recharger", A_RECHARGER, 0);
    menu_sep();
    menu_art("Ajouter la page aux favoris", A_ETOILE, 0);
    menu_art("Enregistrer en PDF", A_PDF, 0);
    menu_art("Capture de la page", A_CAPTURE, 0);
    menu_art("Mode lecture", A_LECTURE, 0);
    menu_sep();
    if (!appli) menu_art("Code source de la page             Ctrl+U", A_SOURCE, 0);
    menu_art("Audit de sécurité du site               F10", A_AUDIT, 0);
    menu_art("Menu du site (clic droit du site)", A_MENU_SITE, 0);
    menu_montrer(x, y);
}

static void effacer(int k)
{
    switch (k) {
    case 1: effacer_donnees(1, 0, 0, 3600e3); break;
    case 2: effacer_donnees(1, 0, 0, 86400e3); break;
    case 3: effacer_donnees(1, 0, 0, 0); break;
    case 4: effacer_donnees(0, 1, 0, 0); break;
    case 5: effacer_donnees(0, 0, 1, 0); break;
    case 6: effacer_donnees(1, 1, 1, 0); break;
    }
}

static void agir(int act, int arg, int bouton_, int x, int y)
{
    onglet *o = nb_onglets ? ACTIF : NULL;
    int droit = bouton_ == 1;
    if (act <= A_RIEN - 1000) {                    /* article de menu */
        int i = A_RIEN - 1000 - act;
        menu_ouvert = 0;
        sale = 1;
        if (i >= 0 && i < nb_menu) agir(menu[i].act, menu[i].arg, 0, menu_px, menu_py);
        return;
    }
    switch (act) {
    case A_ONGLET:
        if (droit) {
            menu_vider();
            menu_art("Nouvel onglet", A_NOUVEL_ONGLET, 0);
            menu_art("Recharger", A_ONGLET_RECHARGER, arg);
            menu_art("Dupliquer", A_ONGLET_DUPLIQUER, arg);
            menu_sep();
            menu_art("Fermer", A_ONGLET_FERMER, arg);
            menu_art("Fermer les autres onglets", A_ONGLET_FERMER_AUTRES, arg);
            menu_montrer(x, y);
        } else onglet_activer(arg);
        break;
    case A_ONGLET_FERMER: onglet_fermer(arg); break;
    case A_ONGLET_PLUS: case A_NOUVEL_ONGLET: onglet_nouveau("", 1, 0); break;
    case A_ONGLET_PRIVE: if (onglet_nouveau("", 1, 1) >= 0) dire("Onglet privé : aucune trace, cookies jetés à la fermeture"); break;
    case A_ONGLET_RECHARGER: onglet_activer(arg); recharger(); break;
    case A_ONGLET_DUPLIQUER: if (arg < nb_onglets && !onglets[arg].interne) { char u[1024]; chaine_copier(u, onglets[arg].url, sizeof u); onglet_nouveau(u, 1, onglets[arg].prive); } break;
    case A_ONGLET_FERMER_AUTRES:
        for (int i = nb_onglets - 1; i >= 0; i--) if (i != arg) { onglet_fermer(i); if (i < arg) arg--; }
        break;
    case A_PRECEDENT: if (droit) break; precedent(); break;
    case A_SUIVANT: if (droit) break; suivant(); break;
    case A_RECHARGER:
        if (o && o->chargement && !o->interne) { cdp_envoyer_a(&moteur, o->session, "Page.stopLoading", "{}"); o->chargement = 0; }
        else recharger();
        break;
    case A_ACCUEIL: aller(R.accueil[0] ? R.accueil : "https://duckduckgo.com/"); break;
    case A_ADRESSE:
        if (focus != &adresse) barre_saisie_activer();
        else adresse.tout = 0;
        break;
    case A_SITE: ouvrir_menu_site(adr_x, H_ONGLETS + H_OUTILS - 6); break;
    case A_ETOILE: dialogue_favori(-1); break;
    case A_UA: ouvrir_menu_identites(adr_x + adr_l - 300, H_ONGLETS + H_OUTILS - 6); break;
    case A_CLE:
        if (o && !o->interne && o->hote[0] && strncmp(o->url, "puppy://", 8)) coffre_remplir();
        else onglet_interne(INT_COFFRE, 1);
        break;
    case A_VPN: onglet_interne(INT_PARAMETRES, 1); break;
    case A_MENU: menu_principal(EL - 300, H_ONGLETS + H_OUTILS - 6); break;
    case A_FAV_BARRE:
        if (droit) {
            menu_vider();
            menu_art("Ouvrir", A_FAV_OUVRIR, arg);
            menu_art("Ouvrir dans un nouvel onglet", A_FAV_NOUVEL, arg);
            menu_art("Ouvrir dans un onglet privé", A_FAV_PRIVE, arg);
            menu_sep();
            menu_art("Modifier…", A_FAV_MODIFIER, arg);
            menu_art("Supprimer", A_FAV_SUPPR, arg);
            menu_montrer(x, y);
        }
        break;
    case A_FAV_PLUS:
        menu_vider();
        for (int i = nb_fav_vus; i < nb_favoris && nb_menu < ARTICLES_MAX; i++) menu_art(favoris[i].titre[0] ? favoris[i].titre : favoris[i].url, A_FAV_OUVRIR, i);
        menu_montrer(EL - 300, H_ONGLETS + H_OUTILS + H_FAVORIS);
        break;
    case A_FAVBARRE_VIDE:
        if (droit) {
            menu_vider();
            menu_art("Ajouter la page aux favoris", A_FAV_AJOUTER_PAGE, 0);
            menu_art("Gérer les favoris", A_PAGE_INTERNE, INT_FAVORIS);
            menu_art("Masquer la barre de favoris", A_FAVBARRE_MASQUER, 0);
            menu_montrer(x, y);
        }
        break;
    case A_FAV_OUVRIR: if (arg < nb_favoris) { if (o && o->interne) onglet_nouveau(favoris[arg].url, 1, 0); else aller(favoris[arg].url); } break;
    case A_FAV_NOUVEL: if (arg < nb_favoris) onglet_nouveau(favoris[arg].url, 1, 0); break;
    case A_FAV_PRIVE: if (arg < nb_favoris) onglet_nouveau(favoris[arg].url, 1, 1); break;
    case A_FAV_MODIFIER: dialogue_favori(arg); break;
    case A_FAV_SUPPR: if (arg < nb_favoris) { favori_supprimer(arg); favoris_enregistrer(); dire("Favori supprimé"); } break;
    case A_FAV_HAUT: if (arg > 0) { favori_deplacer(arg, arg - 1); favoris_enregistrer(); } break;
    case A_FAV_BAS: if (arg + 1 < nb_favoris) { favori_deplacer(arg, arg + 1); favoris_enregistrer(); } break;
    case A_FAV_AJOUTER_PAGE: dialogue_favori(-1); break;
    case A_FAVBARRE_MASQUER: R.barre_favoris = 0; reglages_enregistrer(); appliquer_reglages(); break;
    case A_INFO_OUI: coffre_enregistrer_proposition(1, 0); break;
    case A_INFO_JAMAIS: coffre_enregistrer_proposition(0, 1); break;
    case A_INFO_NON: coffre_enregistrer_proposition(0, 0); break;
    case A_INFO_FERMER: interface_fermer_info(); break;
    case A_TROUVER_CHAMP: focus_changer(&trouver); break;
    case A_TROUVER_HAUT: if (trouver.t[0]) rechercher_page(trouver.t, 1); break;
    case A_TROUVER_BAS: if (trouver.t[0]) rechercher_page(trouver.t, 0); break;
    case A_TROUVER_FERMER: trouver_ouvert = 0; if (focus == &trouver) focus = NULL; break;
    case A_TROUVER: trouver_ouvert = 1; focus_changer(&trouver); trouver.tout = 1; break;
    case A_SUGGESTION: if (arg < nb_sug) { char u[512]; chaine_copier(u, sug_url[arg], sizeof u); focus = NULL; nb_sug = 0; aller(u); } break;
    case A_DLG_BOUTON: dlg_valider(arg); break;
    case A_DLG_CHAMP: focus = &dlg_champ[arg]; dlg_focus = arg; break;
    case A_PAGE_INTERNE: onglet_interne(arg, 1); break;
    case A_ZOOM: zoom_changer(arg - 1); break;
    case A_LECTURE: mode_lecture(); break;
    case A_PDF: imprimer_pdf(); break;
    case A_CAPTURE: capture_publique(); break;
    case A_EFFACER_VOIR: aller_effacer = 1; onglet_interne(INT_PARAMETRES, 1); break;
    case A_QUITTER: quitter_navigateur(); break;
    case A_IDENTITE:
        if (arg == NB_IDENTITES - 1) {
            dlg_ouvrir(DLG_UA_PERSO, "Identité personnalisée", "Colle ou écris le texte User-Agent que les sites verront.");
            dlg_champ_ajouter("User-Agent", 0, R.ua_perso);
            dlg_bouton("Annuler", 0);
            dlg_bouton("Utiliser", 1);
        } else {
            R.ua = arg;
            reglages_enregistrer();
            appliquer_identite();
            recharger();
            char t[200];
            snprintf(t, sizeof t, "Les sites te voient maintenant comme : %s", IDENTITES[arg].nom);
            dire(t);
        }
        break;
    case A_COMPTE: coffre_remplir_compte(arg); break;
    case A_LIEN_ONGLET: onglet_nouveau(menu_lien, 0, o && o->prive); dire("Ouvert dans un nouvel onglet"); break;
    case A_LIEN_PRIVE: onglet_nouveau(menu_lien, 1, 1); break;
    case A_LIEN_COPIER: copier(menu_lien); break;
    case A_LIEN_FAVORI: favori_ajouter(menu_lien, menu_lien); favoris_enregistrer(); dire("Ajouté aux favoris"); break;
    case A_IMAGE_ONGLET: onglet_nouveau(menu_image, 1, o && o->prive); break;
    case A_IMAGE_COPIER: copier(menu_image); break;
    case A_SEL_COPIER: copier(menu_sel); break;
    case A_EDITION: {
        static const char *const CMD[] = { "undo", "redo", "delete", "delete", "selectAll" };
        if (arg == 2) copier(menu_sel);               /* couper = copier puis effacer */
        if (arg >= 0 && arg <= 4) edition_page(CMD[arg]);
        break;
    }
    case A_SOURCE: source_ouvrir(arg); break;
    case A_SOURCE_VUE: SRC.vue = arg; if (o) o->defile = 0; break;
    case A_SEL_CHERCHER: { char s[320]; chaine_copier(s, menu_sel, sizeof s); int i = onglet_nouveau("", 1, o && o->prive); if (i >= 0) valider_saisie(s); break; }
    case A_COLLER_PAGE: if (o && presse[0]) { char e[2200], p[2300]; json_echapper(e, sizeof e, presse); snprintf(p, sizeof p, "{\"text\":%s}", e); cdp_envoyer_a(&moteur, o->session, "Input.insertText", p); } break;
    case A_MENU_SITE: clic_droit_site(x, y); break;
    case A_SITE_JS: case A_SITE_CK: {
        exception_site *s = site_exception(o->hote, 1);
        if (!s) break;
        if (act == A_SITE_JS && arg == 2) {
            s->php = !site_php_permis(o->hote);
            if (s->php == !R.php_bloque) s->php = -1;
            sites_enregistrer();
            dire("S'applique au prochain chargement de ce site");
            break;
        }
        if (act == A_SITE_JS) s->js = !site_js_permis(o->hote);
        else s->cookies = !site_cookies_permis(o->hote);
        if (s->js == R.js) s->js = -1;
        if (s->cookies == R.cookies) s->cookies = -1;
        sites_enregistrer();
        sites_appliquer();
        break;
    }
    case A_SITE_EFFACER: if (o && o->hote[0]) { site_effacer_stockage(o->hote); dire("Cookies et stockage de ce site effacés"); } break;
    case A_SITE_OUBLIER:
        if (o && o->hote[0]) {
            char t[600];
            snprintf(t, sizeof t, "Cookies, stockage, permissions, historique, réglages et images modifiées de %s seront effacés. "
                     "Tu seras déconnecté de ce site.", o->hote);
            dialogue_confirmer("Oublier ce site ?", t, A_SITE_OUBLIER_OUI, "Oublier");
        }
        break;
    case A_SITE_OUBLIER_OUI: if (o && o->hote[0]) { char h[256]; chaine_copier(h, o->hote, sizeof h); site_oublier(h); } break;
    case A_SITE_IMAGES: image_restaurer(NULL); break;
    case A_IMAGE_MODIFIER: if (menu_image[0]) image_modifier(menu_image); break;
    case A_IMAGE_RESTAURER: if (menu_image[0]) image_restaurer(menu_image); break;
    case A_CERT: certificat_details(); break;
    case A_AUDIT: audit_rapide(); break;
    case A_APPLI: appli_installer(); break;
    case A_APPLI_FERMER: quitter_navigateur(); break;
    case A_PHP_UNE_FOIS: php_ouvrir_cette_fois(); break;
    case A_GOOGLE_VRAI: google_ouvrir_vrai(); break;
    case A_GOOGLE_DDG: aller("https://duckduckgo.com/"); break;
    case A_GOOGLE_AIDE: interface_fermer_info(); google_aide(); break;
    case A_GOOGLE_TESTER: google_tester(); break;
    case A_PHP_TOUJOURS: php_toujours_permettre(); break;
    case A_PREMIER: if (arg < 0) premier_aller(premier_ecran - 1); else premier_suivant(); break;
    case A_PREMIER_CHOIX: if (arg >= 0 && arg < 6) premier_choix[arg / 2] = arg % 2; break;
    case A_DLG_CASE: dlg_case = !dlg_case; break;
    case A_AUDIT_EFFACER: audits_effacer(); break;
    case A_SITE_COFFRE: coffre_remplir(); break;
    case A_REGLAGE: {
        if (arg < 0 || arg >= nb_reglages) break;
        reglage_ligne *g = &REGLAGES[arg];
        if (g->genre == L_BASCULE) {
            int ex = protection_baissee(g->val, !*g->val);
            if (ex >= 0 && !R.expert) { dialogue_protection(ex, g->val, !*g->val, g->effet); break; }
            *g->val = !*g->val;
        }
        else if (g->genre == L_CYCLE) {
            if (droit) *g->val = (*g->val + g->nb - 1) % g->nb;
            else *g->val = (*g->val + 1) % g->nb;
            if (g->val == &R.ua && R.ua == NB_IDENTITES - 1 && !R.ua_perso[0]) R.ua = 0;
        }
        effet_reglage(g->effet);
        break;
    }
    case A_CHAMP_PAGE:
        if (arg == 100) focus_changer(&ch_hist);
        else if (arg == 101) focus_changer(&ch_ext);
        else if (arg == 102) focus_changer(&ch_mot);
        else if (arg >= 0 && arg < nb_reglages && REGLAGES[arg].ch) focus_changer(REGLAGES[arg].ch);
        break;
    case A_VPN_APPLIQUER:
        if (focus) champ_page_garder(focus);
        if (R.vpn && !R.vpn_hote[0]) { dire("Indique le serveur du VPN"); break; }
        vpn_appliquer();
        break;
    case A_HIST:
        if (arg >= nb_historique) break;
        if (droit) {
            menu_vider();
            menu_art("Ouvrir", A_HIST_NOUVEL, arg + 100000);
            menu_art("Ouvrir dans un nouvel onglet", A_HIST_NOUVEL, arg);
            menu_art("Copier l'adresse", A_LIEN_COPIER, 0);
            chaine_copier(menu_lien, historique[arg].url, sizeof menu_lien);
            menu_sep();
            menu_art("Supprimer de l'historique", A_HIST_SUPPR, arg);
            menu_art("Supprimer tout ce site", A_HIST_SUPPR_SITE, arg);
            menu_montrer(x, y);
        } else { char u[512]; chaine_copier(u, historique[arg].url, sizeof u); onglet_nouveau(u, 1, 0); }
        break;
    case A_HIST_NOUVEL: {
        int i = arg % 100000;
        if (i < nb_historique) { char u[512]; chaine_copier(u, historique[i].url, sizeof u); onglet_nouveau(u, arg < 100000 ? 0 : 1, 0); }
        break;
    }
    case A_HIST_SUPPR: if (arg < nb_historique) { historique_supprimer(arg); historique_sauver_maintenant(); } break;
    case A_HIST_SUPPR_SITE: if (arg < nb_historique) { char h[256] = ""; hote_de(historique[arg].url, h, sizeof h); historique_supprimer_site(h); historique_sauver_maintenant(); } break;
    case A_EFFACER:
        if (arg == 6 || arg == 3) {
            dialogue_confirmer(arg == 6 ? "Tout effacer ?" : "Effacer tout l'historique ?",
                                    arg == 6 ? "L'historique, les cookies (tu seras déconnecté des sites) et le cache. Les favoris et le coffre restent."
                                             : "Toutes les pages visitées disparaissent.", arg == 6 ? -6 : -3, "Effacer");
        } else effacer(arg);
        break;
    case -6: effacer(6); break;
    case -3: effacer(3); break;
    case A_COF_OUVRIR: coffre_apres = 0; dialogue_coffre(!coffre_existe()); break;
    case A_COF_VERROU: coffre_verrouiller(); cofre_sale = 1; dire("Coffre refermé"); break;
    case A_COF_CHANGER:
        dlg_ouvrir(DLG_COFFRE_CHANGER, "Nouveau mot de passe maître", "Le coffre est rechiffré avec le nouveau mot de passe (8 caractères au moins).");
        dlg_champ_ajouter("Nouveau mot de passe maître", 1, NULL);
        dlg_champ_ajouter("Encore une fois", 1, NULL);
        dlg_bouton("Annuler", 0);
        dlg_bouton("Changer", 1);
        break;
    case A_COF_TOUT:
        dialogue_confirmer("Vider le coffre ?", "Tous les mots de passe enregistrés seront effacés pour de bon.", -100, "Tout effacer");
        break;
    case -100: if (coffre_tout_effacer() == 0) dire("Coffre vidé"); cofre_sale = 1; break;
    case A_COF_MONTRER: {
        char s[160];
        if (coffre_montrer(arg, s, sizeof s) < 0) { dire("Le coffre est fermé"); cofre_sale = 1; break; }
        dlg_ouvrir(DLG_MONTRER, "Mot de passe", "Il disparaît de l'écran et de la mémoire dans 10 secondes.");
        chaine_copier(dlg_secret, s, sizeof dlg_secret);
        explicit_bzero(s, sizeof s);
        dlg_jusqua = maintenant + 10000;
        dlg_bouton("Fermer", 1);
        break;
    }
    case A_COF_SUPPR:
        dialogue_confirmer("Supprimer ce mot de passe ?", "Il sera effacé du coffre.", -200 - arg, "Supprimer");
        break;
    case A_THEME_PP: R.theme_puppyplay = !R.theme_puppyplay; reglages_enregistrer(); perso_appliquer(); break;
    case A_EXT_ACTIF: if (arg < nb_extensions) { extensions[arg].actif = !extensions[arg].actif; extensions_enregistrer(); dire("S'applique aux nouvelles pages"); } break;
    case A_EXT_SUPPR: if (arg < nb_extensions) { extension_supprimer(arg); dire("Extension supprimée"); } break;
    case A_EXT_ALLER: if (ch_ext.t[0]) { char u[1024]; chaine_copier(u, ch_ext.t, sizeof u); champ_vider(&ch_ext); focus = NULL; int i = onglet_nouveau("", 1, 0); if (i >= 0) valider_saisie(u); } break;
    case A_SITE_LIGNE_JS: if (arg < nb_sites) { sites[arg].js = !(sites[arg].js < 0 ? R.js : sites[arg].js); sites_enregistrer(); sites_appliquer(); } break;
    case A_SITE_LIGNE_CK: if (arg < nb_sites) { sites[arg].cookies = !(sites[arg].cookies < 0 ? R.cookies : sites[arg].cookies); sites_enregistrer(); sites_appliquer(); } break;
    case A_SITE_LIGNE_SUPPR: if (arg < nb_sites) { sites[arg].js = sites[arg].cookies = sites[arg].php = -1; sites_enregistrer(); sites_appliquer(); } break;
    case A_SITE_LIGNE_PHP: if (arg < nb_sites) { sites[arg].php = !site_php_permis(sites[arg].hote); if (sites[arg].php == !R.php_bloque) sites[arg].php = -1; sites_enregistrer(); } break;
    case A_MOT_AJOUTER:
        if (!strstr(ch_mot.t, "-->")) { dire("Écris : Mot --> remplacement"); break; }
        if (langage_ajouter(ch_mot.t) < 0) { dire("Dictionnaire plein, ou mot vide"); break; }
        langage_enregistrer();
        champ_vider(&ch_mot);
        if (R.langage) perso_appliquer(); else dire("Ajouté. Active « Langage Puppyplay » pour le voir dans les pages");
        break;
    case A_MOT_SUPPR: if (arg < nb_mots) { langage_supprimer(arg); langage_enregistrer(); if (R.langage) perso_appliquer(); } break;
    case A_PERM_RETIRER: {
        int i = arg / NB_PERMS, t = arg % NB_PERMS;
        if (i < nb_perms) { char h[128]; chaine_copier(h, perms[i].hote, sizeof h); permission_retirer(h, t); dire("Le site devra de nouveau demander"); }
        break;
    }
    case A_ERREUR_REESSAYER: if (o) { char u[1024]; chaine_copier(u, o->erreur_url[0] ? o->erreur_url : o->url, sizeof u); o->erreur = 0; aller(u); } break;
    case A_ERREUR_HTTP:
        if (o) {
            char h[256] = "", u[1100];
            hote_de(o->erreur_url, h, sizeof h);
            permettre_http(h);
            snprintf(u, sizeof u, "http://%s", o->erreur_url + 8);
            o->erreur = 0;
            aller(u);
        }
        break;
    default:
        if (act <= -200 && act > -100000) { if (coffre_supprimer(-200 - act) == 0) dire("Mot de passe supprimé"); cofre_sale = 1; }
        break;
    }
    sale = 1;
}

/* ============================== souris =============================== */
static zone *zone_sous(int x, int y)
{
    int debut = modal_debut >= 0 ? modal_debut : 0;
    for (int i = nb_zones - 1; i >= debut; i--) {
        zone *z = &zones[i];
        if (x >= z->x && y >= z->y && x < z->x + z->l && y < z->y + z->h) return z;
    }
    return NULL;
}

static void lacher_lien(int x, int y)
{
    if (y < H_ONGLETS) onglet_nouveau(glisse_url, 1, ACTIF->prive);
    else if (y < H_ONGLETS + H_OUTILS) { if (x >= adr_x && x < adr_x + adr_l) aller(glisse_url); }
    else if (y < PY && R.barre_favoris) {
        favori_ajouter(glisse_url, glisse_titre);
        /* rangé là où on l'a lâché */
        int de = favori_trouver(glisse_url), vers = nb_fav_vus;
        for (int i = 0; i < nb_fav_vus; i++) if (x < fav_x[i] + fav_l[i] / 2) { vers = i; break; }
        if (de >= 0 && vers < nb_favoris && vers != de) favori_deplacer(de, vers);
        favoris_enregistrer();
        dire("Ajouté aux favoris");
    } else if (y >= PY && !ACTIF->interne) { rendre_glisser(x, y, glisse_url); return; }
    if (!ACTIF->interne) cdp_envoyer_a(&moteur, ACTIF->session, "Input.cancelDragging", "{}");
}

int interface_souris(const pf_evenement *e)
{
    if (streaming) return 0;
    int bouge = e->x != sx || e->y != sy, sy_avant = sy;
    sx = e->x; sy = e->y;
    onglet *o = nb_onglets ? ACTIF : NULL;
    int page_libre = o && !o->interne && !o->erreur;
    /* glisser en cours */
    if (glisse) {
        if (e->bouton < 0) {
            if (!glisse_parti && (abs(e->x - glisse_x0) > 6 || abs(e->y - glisse_y0) > 6)) glisse_parti = 1;
            if (glisse == 1 && glisse_parti) {
                int lo = largeur_onglet(), t = (e->x - 6) / lo;
                if (t >= 0 && t < nb_onglets && t != glisse_i) { onglet_deplacer(glisse_i, t); glisse_i = t; }
            } else if (glisse == 2 && glisse_parti) {
                for (int i = 0; i < nb_fav_vus; i++)
                    if (e->x >= fav_x[i] && e->x < fav_x[i] + fav_l[i] && i != glisse_i) { favori_deplacer(glisse_i, i); glisse_i = i; favoris_enregistrer(); break; }
            }
            sale = 1;
            return 1;
        }
        if (e->bouton == 0 && !e->appuye) {
            int g = glisse, parti = glisse_parti, i = glisse_i;
            glisse = 0;
            if (g == 3) lacher_lien(e->x, e->y);
            else if (g == 2 && !parti && i < nb_favoris) { if (o && o->interne) onglet_nouveau(favoris[i].url, 1, 0); else aller(favoris[i].url); }
            sale = 1;
            return 1;
        }
        return 1;
    }
    int modal = menu_ouvert || dlg || premier_actif();
    if (appli && bouge && !modal && ((sy <= H_BARRE_APPLI) != (sy_avant <= H_BARRE_APPLI))) sale = 1;
    if (e->bouton < 0) {
        if (modal || e->y < PY + (info_genre >= 0 ? H_INFO : 0) || !page_libre || (focus == &adresse && nb_sug) || trouver_ouvert) {
            if (bouge) sale = 1;
        }
        if (modal) return 1;
        if (!page_libre && e->y >= PY) return 1;
        if (focus == &adresse && nb_sug && e->y < H_ONGLETS + 44 + nb_sug * 34 + 8 && e->x >= adr_x && e->x < adr_x + adr_l) return 1;
        return e->y < PY;
    }
    if (!e->appuye) {                         /* relâchés : ceux de la page vont à la page */
        if (modal) return 1;
        if (e->y >= PY && page_libre) return e->bouton == 1;
        return 1;
    }
    zone *z = zone_sous(e->x, e->y);
    if (modal) {
        if (!z) return 1;
        if (z->act == A_MENU_FOND) { menu_ouvert = 0; sale = 1; return 1; }
        if (z->act == A_DLG_FOND) return 1;
        agir(z->act, z->arg, e->bouton, e->x, e->y);
        return 1;
    }
    if (z) {
        /* un champ quitté garde sa valeur */
        if (focus && z->act != A_CHAMP_PAGE && z->act != A_ADRESSE && z->act != A_TROUVER_CHAMP && z->act != A_SUGGESTION) focus_changer(NULL);
        if (z->act == A_ONGLET && e->bouton == 0) { onglet_activer(z->arg); glisse = 1; glisse_i = z->arg; glisse_parti = 0; glisse_x0 = e->x; glisse_y0 = e->y; sale = 1; return 1; }
        if (z->act == A_FAV_BARRE && e->bouton == 0) { glisse = 2; glisse_i = z->arg; glisse_parti = 0; glisse_x0 = e->x; glisse_y0 = e->y; sale = 1; return 1; }
        agir(z->act, z->arg, e->bouton, e->x, e->y);
        return 1;
    }
    if (focus) focus_changer(NULL);
    if (e->y < PY) return 1;
    if (!page_libre) return 1;
    if (e->bouton == 1) { lien_contexte(e->x, e->y); return 1; }
    return 0;
}

int interface_molette(const pf_evenement *e)
{
    if (streaming) return 0;
    if (premier_actif() && !dlg) { premier_defile -= e->delta * 60; if (premier_defile < 0) premier_defile = 0; sale = 1; return 1; }
    if (menu_ouvert || dlg) return 1;
    if (appli) return 0;
    if (e->y < PY) {
        if (e->y < H_ONGLETS && nb_onglets > 1) onglet_activer((actif + (e->delta > 0 ? nb_onglets - 1 : 1)) % nb_onglets);
        return 1;
    }
    onglet *o = ACTIF;
    if (o->interne) { o->defile -= e->delta * 60; if (o->defile < 0) o->defile = 0; sale = 1; return 1; }
    if (o->erreur) return 1;
    return 0;
}

/* ============================== clavier ============================== */
static void champ_page_entree(champ *c)
{
    champ_page_garder(c);
    if (c == &ch_ext) agir(A_EXT_ALLER, 0, 0, 0, 0);
    if (c == &ch_mot) { agir(A_MOT_AJOUTER, 0, 0, 0, 0); return; }
    if (c == &ch_vpn_hote || c == &ch_vpn_port) dire("Enregistré : « Appliquer » pour utiliser le VPN");
    if (c != &ch_hist) focus = NULL;
}

int interface_touche(const pf_evenement *e)
{
    if (streaming) return 0;
    sale = 1;
    char l = lettre(e);
    /* dialogue : il garde la main */
    if (dlg) {
        if (e->code == KEY_TAB && dlg_nb_champs > 1) { dlg_focus = (dlg_focus + 1) % dlg_nb_champs; focus = &dlg_champ[dlg_focus]; return 1; }
        if (focus && focus >= &dlg_champ[0] && focus <= &dlg_champ[1]) {
            int r = champ_touche(focus, e);
            if (r == 1) { if (dlg_nb_champs == 2 && focus == &dlg_champ[0] && !dlg_champ[1].t[0] && dlg != DLG_FAVORI) { focus = &dlg_champ[1]; dlg_focus = 1; } else dlg_valider(dlg_nb_boutons - 1); }
            else if (r == -1) dlg_valider(0);
            return 1;
        }
        /* Entrée ne baisse jamais une protection et n'accorde rien : premier bouton */
        if (e->code == KEY_ENTER || e->code == KEY_KPENTER) dlg_valider(dlg == DLG_PROTECTION || dlg == DLG_PERMISSION ? 0 : dlg_nb_boutons - 1);
        else if (e->code == KEY_ESC) dlg_valider(0);
        else if (e->code == KEY_SPACE && dlg_case_texte) dlg_case = !dlg_case;
        return 1;
    }
    if (premier_actif()) return premier_touche(e);
    if (menu_ouvert) { if (e->code == KEY_ESC) menu_ouvert = 0; return 1; }
    /* 2.1 : application web : seulement quelques touches */
    if (appli) {
        if (e->ctrl && (l == 'w' || l == 'q')) { quitter_navigateur(); return 1; }
        if (e->code == KEY_F5 || (e->ctrl && l == 'r')) { recharger(); return 1; }
        if (e->code == KEY_F10) { audit_rapide(); return 1; }
        if (e->ctrl && l == 'c') goto copier_page;
        if (e->ctrl && l == 'v' && presse[0]) { agir(A_COLLER_PAGE, 0, 0, 0, 0); return 1; }
        return 0;
    }
    if (glisse && e->code == KEY_ESC) { glisse = 0; return 1; }

    /* raccourcis du navigateur */
    if (e->ctrl) {
        if (e->code == KEY_TAB) { if (nb_onglets > 1) onglet_activer((actif + (e->maj ? nb_onglets - 1 : 1)) % nb_onglets); return 1; }
        if (e->code == KEY_PAGEDOWN) { onglet_activer((actif + 1) % nb_onglets); return 1; }
        if (e->code == KEY_PAGEUP) { onglet_activer((actif + nb_onglets - 1) % nb_onglets); return 1; }
        if (e->code == KEY_F4) return 0;
        if (e->maj && e->code == KEY_DELETE) { agir(A_EFFACER_VOIR, 0, 0, 0, 0); return 1; }
        switch (l) {
        case 't': agir(A_NOUVEL_ONGLET, 0, 0, 0, 0); return 1;
        case 'n': if (e->maj) agir(A_ONGLET_PRIVE, 0, 0, 0, 0); else agir(A_NOUVEL_ONGLET, 0, 0, 0, 0); return 1;
        case 'w': onglet_fermer(actif); return 1;
        case 'l': barre_saisie_activer(); return 1;
        case 'd': dialogue_favori(-1); return 1;
        case 'u': if (e->maj) audit_rapide(); else source_ouvrir(0); return 1;
        case 'h': onglet_interne(INT_HISTORIQUE, 1); return 1;
        case 'f': if (!ACTIF->interne) agir(A_TROUVER, 0, 0, 0, 0); return 1;
        case 'p': imprimer_pdf(); return 1;
        case 'r': recharger(); return 1;
        case '+': case '=': zoom_changer(1); return 1;
        case '-': case '6': zoom_changer(-1); return 1;
        case '0': zoom_changer(0); return 1;
        case 'q': quitter_navigateur(); return 1;
        }
        if (!strcmp(e->texte, "à")) { zoom_changer(0); return 1; }
    }
    if (e->code == KEY_F5) { recharger(); return 1; }
    if (e->code == KEY_F6) { barre_saisie_activer(); return 1; }
    if (e->code == KEY_F1) { onglet_interne(INT_AIDE, 1); return 1; }
    if (e->code == KEY_F9) { mode_lecture(); return 1; }
    if (e->code == KEY_F10) { audit_rapide(); return 1; }

    /* champ actif */
    if (focus == &adresse) {
        if (e->code == KEY_DOWN && nb_sug) { sug_sel = (sug_sel + 1) % nb_sug; return 1; }
        if (e->code == KEY_UP && nb_sug) { sug_sel = sug_sel <= 0 ? nb_sug - 1 : sug_sel - 1; return 1; }
        int r = champ_touche(&adresse, e);
        if (r == 1) {
            char s[1100];
            chaine_copier(s, sug_sel >= 0 && sug_sel < nb_sug ? sug_url[sug_sel] : adresse.t, sizeof s);
            focus = NULL;
            nb_sug = 0;
            if (nb_onglets && ACTIF->interne && strncmp(s, "puppy://", 8)) { int i = onglet_nouveau("", 1, 0); if (i < 0) return 1; }
            valider_saisie(s);
        } else if (r == -1) { focus = NULL; nb_sug = 0; }
        else if (r == 2) suggestions_calculer();
        return 1;
    }
    if (focus == &trouver) {
        int r = champ_touche(&trouver, e);
        if (r == 1 && trouver.t[0]) rechercher_page(trouver.t, e->maj);
        else if (r == -1) { trouver_ouvert = 0; focus = NULL; }
        return 1;
    }
    if (focus) {
        int r = champ_touche(focus, e);
        if (r == 1) champ_page_entree(focus);
        else if (r == -1) { champs_page_sync(); focus = NULL; }
        return 1;
    }
    if (e->code == KEY_ESC && trouver_ouvert) { trouver_ouvert = 0; return 1; }
    onglet *o = ACTIF;
    if (o->interne || o->erreur) {
        int pas = 0;
        switch (e->code) {
        case KEY_DOWN: pas = 50; break;
        case KEY_UP: pas = -50; break;
        case KEY_PAGEDOWN: case KEY_SPACE: pas = PH - 60; break;
        case KEY_PAGEUP: pas = -(PH - 60); break;
        case KEY_HOME: o->defile = 0; break;
        case KEY_END: o->defile = 1 << 20; break;
        }
        o->defile += pas;
        if (o->defile < 0) o->defile = 0;
        if (o->erreur && (e->code == KEY_ENTER || e->code == KEY_KPENTER)) agir(A_ERREUR_REESSAYER, 0, 0, 0, 0);
        return 1;
    }
    /* Ctrl+C dans la page : on garde aussi la sélection pour nos champs */
    if (e->ctrl && l == 'c') {
copier_page:;
        char *r = nav_demander(ACTIF->session, "Runtime.evaluate", "{\"expression\":\"String(getSelection()).slice(0,2000)\",\"returnByValue\":true}", 1500);
        char v[2048] = "";
        if (r && json_chaine(r, "result.result.value", v, sizeof v) && v[0]) chaine_copier(presse, v, sizeof presse);
        free(r);
        return 0;
    }
    if (e->ctrl && l == 'v' && presse[0]) { agir(A_COLLER_PAGE, 0, 0, 0, 0); return 1; }
    return 0;
}
