/*
 * Données du Navigateur (0.12) : réglages, favoris, historique, exceptions
 * par site et extensions. Tout est rangé dans le dossier de données du
 * programme (sur la gamelle principale, donc chiffré avec elle), en fichiers
 * texte simples. Les onglets privés n'écrivent jamais rien.
 */
#define _GNU_SOURCE
#include "nav.h"
#include "comptes.h"
#include "puppy.h"
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

reglages R;
favori favoris[FAVORIS_MAX];
int nb_favoris;
passage historique[HISTORIQUE_MAX];
int nb_historique;
exception_site sites[SITES_MAX];
int nb_sites;
extension extensions[EXT_MAX];
int nb_extensions;

const moteur_recherche MOTEURS_RECHERCHE[] = {
    { "DuckDuckGo", "https://duckduckgo.com/?q=" },
    { "Qwant",      "https://www.qwant.com/?q=" },
    { "Startpage",  "https://www.startpage.com/do/search?q=" },
    { "Ecosia",     "https://www.ecosia.org/search?q=" },
    { "Brave",      "https://search.brave.com/search?q=" },
    { "Google",     "https://www.google.com/search?q=" },
    { "Bing",       "https://www.bing.com/search?q=" },
};
const int NB_MOTEURS_RECHERCHE = (int)(sizeof MOTEURS_RECHERCHE / sizeof MOTEURS_RECHERCHE[0]);

const char *chemin_donnees(const char *f)
{
    static char c[4][600];
    static int k;
    k = (k + 1) & 3;
    chaine_copier(c[k], programme_donnees(f), sizeof c[k]);
    return c[k];
}

static double ms_maintenant(void)
{
    struct timespec t;
    clock_gettime(CLOCK_REALTIME, &t);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}

/* une valeur ne contient jamais de | ni de fin de ligne (format des fichiers) */
static void propre(char *s)
{
    for (; *s; s++) if (*s == '|' || *s == '\n' || *s == '\r' || *s == '\t') *s = ' ';
}

/* ------------------------------- réglages ------------------------------ */
static void reglages_defaut(void)
{
    memset(&R, 0, sizeof R);
    chaine_copier(R.accueil, "https://duckduckgo.com/", sizeof R.accueil);
    R.zoom = 100;
    R.cookies = 1; R.tiers = 0; R.js = 1;
    R.https = 1; R.bannieres = 1; R.liens = 1; R.gpc = 1;
    R.coffre = 1; R.verrou_min = 10; R.barre_favoris = 1;
    R.lecture_auto = 1; R.vpn_coupe_circuit = 1; R.vpn_port = 1080;
    R.google_redirige = 1;
}

#define ENTIER(nom, champ) if (config_valeur(t, nom, v, sizeof v) == 0) R.champ = atoi(v)
#define TEXTE(nom, champ)  if (config_valeur(t, nom, v, sizeof v) == 0) chaine_copier(R.champ, v, sizeof R.champ)

static void reglages_charger(void)
{
    reglages_defaut();
    char *t = lire_fichier(chemin_donnees("navigateur.conf"), NULL), v[600];
    if (!t) return;
    TEXTE("accueil", accueil);
    ENTIER("recherche", recherche); ENTIER("zoom", zoom);
    ENTIER("cookies", cookies); ENTIER("cookies_tiers", tiers); ENTIER("javascript", js);
    ENTIER("https_dabord", https); ENTIER("bannieres", bannieres); ENTIER("sombre", sombre);
    ENTIER("nettoyer_liens", liens); ENTIER("gpc", gpc); ENTIER("popups", popups);
    ENTIER("coffre", coffre); ENTIER("coffre_verrou_min", verrou_min); ENTIER("barre_favoris", barre_favoris);
    ENTIER("lecture_auto", lecture_auto); ENTIER("identite", ua); TEXTE("identite_perso", ua_perso);
    ENTIER("vpn", vpn); TEXTE("vpn_hote", vpn_hote); ENTIER("vpn_port", vpn_port);
    ENTIER("vpn_coupe_circuit", vpn_coupe_circuit); ENTIER("effacer_en_quittant", effacer_en_quittant);
    ENTIER("premier_lancement_fait", premier_fait); ENTIER("blocage_php", php_bloque);
    ENTIER("sans_explications", expert); ENTIER("langage_puppyplay", langage);
    ENTIER("theme_puppyplay", theme_puppyplay); ENTIER("google_redirige", google_redirige);
    free(t);
    if (R.recherche < 0 || R.recherche >= NB_MOTEURS_RECHERCHE) R.recherche = 0;
    if (R.zoom < 30 || R.zoom > 300) R.zoom = 100;
    if (R.ua < 0 || R.ua >= NB_IDENTITES) R.ua = 0;
    if (R.verrou_min < 1 || R.verrou_min > 240) R.verrou_min = 10;
    if (R.vpn < 0 || R.vpn > 3) R.vpn = 0;
    if (strncmp(R.accueil, "https://", 8) && strncmp(R.accueil, "http://", 7))
        chaine_copier(R.accueil, "https://duckduckgo.com/", sizeof R.accueil);
}

void reglages_enregistrer(void)
{
    char b[4096];
    propre(R.accueil); propre(R.ua_perso); propre(R.vpn_hote);
    int n = snprintf(b, sizeof b,
        "# Navigateur PuppyOS : réglages\naccueil=%s\nrecherche=%d\nzoom=%d\ncookies=%d\ncookies_tiers=%d\njavascript=%d\n"
        "https_dabord=%d\nbannieres=%d\nsombre=%d\nnettoyer_liens=%d\ngpc=%d\npopups=%d\ncoffre=%d\ncoffre_verrou_min=%d\n"
        "barre_favoris=%d\nlecture_auto=%d\nidentite=%d\nidentite_perso=%s\nvpn=%d\nvpn_hote=%s\nvpn_port=%d\n"
        "vpn_coupe_circuit=%d\neffacer_en_quittant=%d\npremier_lancement_fait=%d\nblocage_php=%d\n"
        "sans_explications=%d\nlangage_puppyplay=%d\ntheme_puppyplay=%d\ngoogle_redirige=%d\n",
        R.accueil, R.recherche, R.zoom, R.cookies, R.tiers, R.js, R.https, R.bannieres, R.sombre, R.liens, R.gpc,
        R.popups, R.coffre, R.verrou_min, R.barre_favoris, R.lecture_auto, R.ua, R.ua_perso, R.vpn, R.vpn_hote,
        R.vpn_port, R.vpn_coupe_circuit, R.effacer_en_quittant, R.premier_fait, R.php_bloque, R.expert,
        R.langage, R.theme_puppyplay, R.google_redirige);
    if (n > 0) ecrire_fichier_atomique(chemin_donnees("navigateur.conf"), b, (size_t)n);
}

/* -------------------------------- favoris ------------------------------ */
static void favoris_charger(void)
{
    nb_favoris = 0;
    char *t = lire_fichier(chemin_donnees("navigateur-favoris.txt"), NULL);
    if (!t) {
        /* quelques favoris pour commencer */
        favori_ajouter("https://duckduckgo.com/", "DuckDuckGo");
        favori_ajouter("https://fr.wikipedia.org/", "Wikipédia");
        favori_ajouter("https://github.com/Piika4puppyplay/Piika", "PuppyOS sur GitHub");
        return;
    }
    for (char *l = strtok(t, "\n"); l && nb_favoris < FAVORIS_MAX; l = strtok(NULL, "\n")) {
        if (l[0] == '#') continue;
        char *s = strchr(l, '|');
        if (!s) continue;
        *s = 0;
        chaine_copier(favoris[nb_favoris].url, l, sizeof favoris[0].url);
        chaine_copier(favoris[nb_favoris].titre, s + 1, sizeof favoris[0].titre);
        nb_favoris++;
    }
    free(t);
}

void favoris_enregistrer(void)
{
    size_t cap = (size_t)nb_favoris * 1300 + 64, o = 0;
    char *b = malloc(cap);
    if (!b) return;
    o += (size_t)snprintf(b, cap, "# url|titre\n");
    for (int i = 0; i < nb_favoris; i++) {
        propre(favoris[i].url); propre(favoris[i].titre);
        o += (size_t)snprintf(b + o, cap - o, "%s|%s\n", favoris[i].url, favoris[i].titre);
    }
    ecrire_fichier_atomique(chemin_donnees("navigateur-favoris.txt"), b, o);
    free(b);
}

int favori_trouver(const char *url)
{
    for (int i = 0; i < nb_favoris; i++) if (!strcmp(favoris[i].url, url)) return i;
    return -1;
}

void favori_ajouter(const char *url, const char *titre)
{
    if (!url[0] || favori_trouver(url) >= 0 || nb_favoris >= FAVORIS_MAX) return;
    chaine_copier(favoris[nb_favoris].url, url, sizeof favoris[0].url);
    char h[256] = "";
    hote_de(url, h, sizeof h);
    chaine_copier(favoris[nb_favoris].titre, titre && titre[0] ? titre : h, sizeof favoris[0].titre);
    propre(favoris[nb_favoris].titre);
    nb_favoris++;
    favoris_enregistrer();
}

void favori_supprimer(int i)
{
    if (i < 0 || i >= nb_favoris) return;
    memmove(&favoris[i], &favoris[i + 1], sizeof(favori) * (size_t)(nb_favoris - i - 1));
    nb_favoris--;
    favoris_enregistrer();
}

void favori_deplacer(int de, int vers)
{
    if (de < 0 || de >= nb_favoris || vers < 0 || vers >= nb_favoris || de == vers) return;
    favori f = favoris[de];
    if (de < vers) memmove(&favoris[de], &favoris[de + 1], sizeof(favori) * (size_t)(vers - de));
    else memmove(&favoris[vers + 1], &favoris[vers], sizeof(favori) * (size_t)(de - vers));
    favoris[vers] = f;
    favoris_enregistrer();
}

/* ------------------------------ historique ----------------------------- */
static int hist_sale;
static double hist_ecrit;

static void historique_charger(void)
{
    nb_historique = 0;
    char *t = lire_fichier(chemin_donnees("navigateur-historique.txt"), NULL);
    if (!t) return;
    for (char *l = strtok(t, "\n"); l; l = strtok(NULL, "\n")) {
        char *a = strchr(l, '|'), *b = a ? strchr(a + 1, '|') : NULL;
        if (!a || !b) continue;
        *a = *b = 0;
        if (nb_historique >= HISTORIQUE_MAX) {      /* les plus anciennes partent */
            memmove(&historique[0], &historique[1], sizeof(passage) * (HISTORIQUE_MAX - 1));
            nb_historique--;
        }
        passage *p = &historique[nb_historique++];
        p->t = atof(l);
        chaine_copier(p->url, a + 1, sizeof p->url);
        chaine_copier(p->titre, b + 1, sizeof p->titre);
    }
    free(t);
}

void historique_sauver_maintenant(void)
{
    size_t cap = (size_t)nb_historique * 700 + 16, o = 0;
    char *b = malloc(cap);
    if (!b) return;
    for (int i = 0; i < nb_historique; i++)
        o += (size_t)snprintf(b + o, cap - o, "%.0f|%s|%s\n", historique[i].t, historique[i].url, historique[i].titre);
    ecrire_fichier_atomique(chemin_donnees("navigateur-historique.txt"), b, o);
    free(b);
    hist_sale = 0;
    hist_ecrit = maintenant;
}

void historique_enregistrer(void)
{
    if (hist_sale && maintenant - hist_ecrit > 3000) historique_sauver_maintenant();
}

void historique_ajouter(const char *url, const char *titre)
{
    if (strncmp(url, "http://", 7) && strncmp(url, "https://", 8)) return;
    /* la même page revisitée tout de suite : on met juste l'heure à jour */
    if (nb_historique && !strncmp(historique[nb_historique - 1].url, url, sizeof historique[0].url - 1)) {
        historique[nb_historique - 1].t = ms_maintenant();
        hist_sale = 1;
        return;
    }
    if (nb_historique >= HISTORIQUE_MAX) {
        memmove(&historique[0], &historique[1], sizeof(passage) * (HISTORIQUE_MAX - 1));
        nb_historique--;
    }
    passage *p = &historique[nb_historique++];
    p->t = ms_maintenant();
    chaine_copier(p->url, url, sizeof p->url);
    chaine_copier(p->titre, titre ? titre : "", sizeof p->titre);
    propre(p->url); propre(p->titre);
    hist_sale = 1;
}

void historique_titre(const char *url, const char *titre)
{
    for (int i = nb_historique - 1; i >= 0 && i >= nb_historique - 20; i--)
        if (!strncmp(historique[i].url, url, sizeof historique[0].url - 1)) {
            if (strcmp(historique[i].titre, titre)) {
                chaine_copier(historique[i].titre, titre, sizeof historique[i].titre);
                propre(historique[i].titre);
                hist_sale = 1;
            }
            return;
        }
}

void historique_supprimer(int i)
{
    if (i < 0 || i >= nb_historique) return;
    memmove(&historique[i], &historique[i + 1], sizeof(passage) * (size_t)(nb_historique - i - 1));
    nb_historique--;
    historique_sauver_maintenant();
}

void historique_supprimer_site(const char *hote)
{
    int o = 0;
    for (int i = 0; i < nb_historique; i++) {
        char h[256] = "";
        hote_de(historique[i].url, h, sizeof h);
        if (strcmp(h, hote)) historique[o++] = historique[i];
    }
    nb_historique = o;
    historique_sauver_maintenant();
}

void historique_effacer(double depuis_ms)
{
    if (depuis_ms <= 0) nb_historique = 0;
    else {
        double limite = ms_maintenant() - depuis_ms;
        int o = 0;
        for (int i = 0; i < nb_historique; i++) if (historique[i].t < limite) historique[o++] = historique[i];
        nb_historique = o;
    }
    historique_sauver_maintenant();
}

/* --------------------------- exceptions par site ----------------------- */
static void sites_charger(void)
{
    nb_sites = 0;
    char *t = lire_fichier(chemin_donnees("navigateur-sites.txt"), NULL);
    if (!t) return;
    for (char *l = strtok(t, "\n"); l && nb_sites < SITES_MAX; l = strtok(NULL, "\n")) {
        if (l[0] == '#') continue;
        char h[128];
        int js, ck, php = -1;
        if (sscanf(l, "%127[^|]|%d|%d|%d", h, &js, &ck, &php) < 3) continue;
        chaine_copier(sites[nb_sites].hote, h, sizeof sites[0].hote);
        sites[nb_sites].js = js < -1 || js > 1 ? -1 : js;
        sites[nb_sites].cookies = ck < -1 || ck > 1 ? -1 : ck;
        sites[nb_sites].php = php < -1 || php > 1 ? -1 : php;
        nb_sites++;
    }
    free(t);
}

void sites_enregistrer(void)
{
    char b[SITES_MAX * 150 + 64];
    size_t o = (size_t)snprintf(b, sizeof b, "# site|javascript|cookies|php   (1 permis, 0 coupé, -1 réglage général)\n");
    for (int i = 0; i < nb_sites; i++) {
        if (sites[i].js < 0 && sites[i].cookies < 0 && sites[i].php < 0) continue;
        o += (size_t)snprintf(b + o, sizeof b - o, "%s|%d|%d|%d\n", sites[i].hote, sites[i].js, sites[i].cookies, sites[i].php);
    }
    ecrire_fichier_atomique(chemin_donnees("navigateur-sites.txt"), b, o);
}

exception_site *site_exception(const char *hote, int creer)
{
    if (!hote || !hote[0]) return NULL;
    for (int i = 0; i < nb_sites; i++) if (!strcmp(sites[i].hote, hote)) return &sites[i];
    if (!creer || nb_sites >= SITES_MAX) return NULL;
    exception_site *s = &sites[nb_sites++];
    chaine_copier(s->hote, hote, sizeof s->hote);
    s->js = s->cookies = s->php = -1;
    return s;
}

int site_js_permis(const char *hote)
{
    exception_site *s = site_exception(hote, 0);
    return s && s->js >= 0 ? s->js : R.js;
}

int site_cookies_permis(const char *hote)
{
    exception_site *s = site_exception(hote, 0);
    return s && s->cookies >= 0 ? s->cookies : R.cookies;
}

/* 2.1 : PHP permis sur ce site (exception du site, sinon le réglage général) */
int site_php_permis(const char *hote)
{
    exception_site *s = site_exception(hote, 0);
    return s && s->php >= 0 ? s->php : !R.php_bloque;
}

/* ------------------------------- extensions ----------------------------
 * Chromium « headless » ne charge pas les extensions du Chrome Web Store.
 * Le Navigateur accepte donc les SCRIPTS UTILISATEUR (format Greasemonkey /
 * Tampermonkey, fichiers « .user.js ») : ouvrir l'adresse d'un .user.js
 * propose de l'installer. Ils ne tournent que sur les sites de leurs
 * lignes @match / @include, jamais dans les onglets privés.
 * ----------------------------------------------------------------------- */
int hote_correspond(const char *motif, const char *url)
{
    /* motif « glob » : * = n'importe quoi */
    const char *m = motif, *u = url, *em = NULL, *eu = NULL;
    while (*u) {
        if (*m == '*') { em = m++; eu = u; continue; }
        if (*m == *u) { m++; u++; continue; }
        if (em) { m = em + 1; u = ++eu; continue; }
        return 0;
    }
    while (*m == '*') m++;
    return *m == 0;
}

static void lire_entete(const char *src, extension *e)
{
    e->nb = 0;
    e->nom[0] = e->version[0] = 0;
    const char *d = strstr(src, "==UserScript==");
    const char *f = d ? strstr(d, "==/UserScript==") : NULL;
    if (!d || !f) return;
    for (const char *p = d; p < f; ) {
        const char *nl = memchr(p, '\n', (size_t)(f - p));
        if (!nl) nl = f;
        char l[400];
        size_t n = (size_t)(nl - p) < sizeof l - 1 ? (size_t)(nl - p) : sizeof l - 1;
        memcpy(l, p, n);
        l[n] = 0;
        char *a = strchr(l, '@');
        if (a) {
            char cle[32] = "", val[300] = "";
            if (sscanf(a + 1, "%31s %299[^\r\n]", cle, val) >= 1) {
                if (!strcmp(cle, "name") && !e->nom[0]) chaine_copier(e->nom, val, sizeof e->nom);
                else if (!strcmp(cle, "version")) chaine_copier(e->version, val, sizeof e->version);
                else if ((!strcmp(cle, "match") || !strcmp(cle, "include")) && e->nb < 8 && val[0])
                    chaine_copier(e->motifs[e->nb++], val, sizeof e->motifs[0]);
            }
        }
        p = nl + 1;
    }
    propre(e->nom);
}

static void extensions_charger(void)
{
    nb_extensions = 0;
    char *t = lire_fichier(chemin_donnees("navigateur-extensions.txt"), NULL);
    if (!t) return;
    for (char *l = strtok(t, "\n"); l && nb_extensions < EXT_MAX; l = strtok(NULL, "\n")) {
        char f[80];
        int a;
        if (sscanf(l, "%79[^|]|%d", f, &a) != 2 || strchr(f, '/') || f[0] == '.') continue;
        extension *e = &extensions[nb_extensions];
        chaine_copier(e->fichier, f, sizeof e->fichier);
        e->actif = a != 0;
        char *src = extension_source(nb_extensions);
        if (!src) continue;
        lire_entete(src, e);
        free(src);
        if (!e->nom[0]) chaine_copier(e->nom, f, sizeof e->nom);
        nb_extensions++;
    }
    free(t);
}

void extensions_enregistrer(void)
{
    char b[EXT_MAX * 100 + 8];
    size_t o = 0;
    for (int i = 0; i < nb_extensions; i++) o += (size_t)snprintf(b + o, sizeof b - o, "%s|%d\n", extensions[i].fichier, extensions[i].actif);
    ecrire_fichier_atomique(chemin_donnees("navigateur-extensions.txt"), b, o);
}

char *extension_source(int i)
{
    char c[200];
    snprintf(c, sizeof c, "navigateur-ext-%s", extensions[i].fichier);
    return lire_fichier(chemin_donnees(c), NULL);
}

int extension_installer(const char *source, size_t n, char *err, size_t nerr)
{
    if (n > 512 * 1024) { snprintf(err, nerr, "script trop gros (512 Kio au plus)"); return -1; }
    if (memchr(source, 0, n)) { snprintf(err, nerr, "ce n'est pas un script texte"); return -1; }
    extension e;
    memset(&e, 0, sizeof e);
    lire_entete(source, &e);
    if (!e.nom[0] || !e.nb) { snprintf(err, nerr, "en-tête ==UserScript== avec @name et @match introuvable"); return -1; }
    int i;
    for (i = 0; i < nb_extensions; i++) if (!strcmp(extensions[i].nom, e.nom)) break;
    if (i == nb_extensions) {
        if (nb_extensions >= EXT_MAX) { snprintf(err, nerr, "trop d'extensions"); return -1; }
        snprintf(e.fichier, sizeof e.fichier, "%ld-%d.user.js", (long)time(NULL), nb_extensions);
        nb_extensions++;
    } else chaine_copier(e.fichier, extensions[i].fichier, sizeof e.fichier);
    e.actif = 1;
    char c[200];
    snprintf(c, sizeof c, "navigateur-ext-%s", e.fichier);
    if (ecrire_fichier_atomique(chemin_donnees(c), source, n) < 0) { snprintf(err, nerr, "écriture impossible"); return -1; }
    extensions[i] = e;
    extensions_enregistrer();
    return i;
}

void extension_supprimer(int i)
{
    if (i < 0 || i >= nb_extensions) return;
    char c[200];
    snprintf(c, sizeof c, "navigateur-ext-%s", extensions[i].fichier);
    unlink(chemin_donnees(c));
    memmove(&extensions[i], &extensions[i + 1], sizeof(extension) * (size_t)(nb_extensions - i - 1));
    nb_extensions--;
    extensions_enregistrer();
}

/* ------------------------------------------------------------------------ */
void donnees_charger(void)
{
    /* le dossier de données existe (puppy-autorite le prépare à l'installation) */
    reglages_charger();
    favoris_charger();
    historique_charger();
    sites_charger();
    extensions_charger();
    permissions_charger();
    langage_charger();
    audits_charger();
}
