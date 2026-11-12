/*
 * PuppyOS — Navigateur : recherche Google temporairement neutralisée (2.2).
 *
 * Google Recherche ne marche pas sous PuppyOS pour l'instant (contrôles
 * anti-robots impossibles à passer). Tant que la politique du paquet le dit,
 * les recherches Google partent vers DuckDuckGo, sans qu'aucun mot ne soit
 * envoyé à Google. Rien d'autre n'est touché : l'accueil de Google reste
 * joignable (après une page locale qui explique), YouTube, Gmail et les
 * autres services de Google ne sont pas concernés, et aucune page de Google
 * n'est modifiée.
 *
 * La politique vient du fichier « politique.conf » du paquet Navigateur.puppy,
 * signé : seule une mise à jour signée du Navigateur peut la changer. Le
 * Navigateur ne se remet jamais à envoyer les recherches à Google tout seul,
 * même si un test réussit.
 *
 * Ce fichier ne fait que des calculs (adresses, politique, journal des
 * tests) ; les requêtes du moteur sont dans navigateur.c.
 */
#define _GNU_SOURCE
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "nav.h"
#include "comptes.h"
#include "puppy.h"

int google_neutralise;

/* --------------------------- politique du paquet ------------------------- */
static const char *chemin_paquet(const char *f, char *d, size_t n)
{
    /* PUPPY_NAV_PAQUET : seulement pour les essais hors de PuppyOS */
    const char *base = getenv("PUPPY_NAV_PAQUET");
    snprintf(d, n, "%s/%s", base && base[0] ? base : PUPPY_PROGRAMMES "/navigateur", f);
    return d;
}

const char *google_ressource(const char *f)
{
    static char d[600];
    return chemin_paquet(f, d, sizeof d);
}

void google_politique_charger(void)
{
    char c[600], l[200];
    google_neutralise = 0;            /* pas de fichier : fonctionnement normal */
    FILE *f = fopen(chemin_paquet("politique.conf", c, sizeof c), "r");
    if (!f) return;
    while (fgets(l, sizeof l, f)) {
        l[strcspn(l, "\r\n")] = 0;
        if (!strncmp(l, "google_recherche=", 17)) google_neutralise = !strcmp(l + 17, "neutralisee");
    }
    fclose(f);
}

int google_actif(void) { return google_neutralise && R.google_redirige; }

/* ------------------------------- adresses ------------------------------- */
/* www.google.<pays> ou google.<pays> (« com », « fr », « co.uk », « com.br »…) */
static int hote_google(const char *h)
{
    if (!strncmp(h, "www.", 4)) h += 4;
    if (strncmp(h, "google.", 7)) return 0;
    const char *t = h + 7;
    int parties = 0;
    while (*t) {
        size_t n = 0;
        while (t[n] >= 'a' && t[n] <= 'z') n++;
        if (n < 2 || n > 3) return 0;
        parties++;
        t += n;
        if (*t == '.') t++;
        else if (*t) return 0;
    }
    return parties >= 1 && parties <= 2;
}

static int hexa(int c)
{
    if (c >= '0' && c <= '9') return c - '0';
    c = tolower(c);
    return c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1;
}

/* valeur décodée du paramètre « q » d'une chaîne « a=1&q=...&b=2 » */
static int parametre_q(const char *s, const char *fin, char *q, size_t nq)
{
    while (s < fin) {
        const char *e = memchr(s, '&', (size_t)(fin - s));
        if (!e) e = fin;
        if (e - s > 2 && s[0] == 'q' && s[1] == '=') {
            size_t o = 0;
            for (const char *p = s + 2; p < e && o + 1 < nq; p++) {
                if (*p == '+') q[o++] = ' ';
                else if (*p == '%' && p + 2 < e && hexa(p[1]) >= 0 && hexa(p[2]) >= 0) {
                    q[o++] = (char)(hexa(p[1]) * 16 + hexa(p[2]));
                    p += 2;
                } else q[o++] = *p;
            }
            q[o] = 0;
            while (o && q[o - 1] == ' ') q[--o] = 0;
            return q[0] != 0;
        }
        s = e + 1;
    }
    return 0;
}

int google_classer(const char *u, char *q, size_t nq)
{
    if (q && nq) q[0] = 0;
    const char *p;
    if (!strncmp(u, "https://", 8)) p = u + 8;
    else if (!strncmp(u, "http://", 7)) p = u + 7;
    else return GOOGLE_AUTRE;
    char h[128];
    size_t i = 0;
    while (p[i] && p[i] != '/' && p[i] != ':' && p[i] != '?' && p[i] != '#' && i + 1 < sizeof h) {
        h[i] = (char)tolower((unsigned char)p[i]);
        i++;
    }
    h[i] = 0;
    if (!hote_google(h)) return GOOGLE_AUTRE;
    p += i;
    if (*p == ':') {                                   /* seulement les ports normaux */
        if (strncmp(p, ":443", 4) && strncmp(p, ":80", 3)) return GOOGLE_AUTRE;
        while (*p && *p != '/' && *p != '?' && *p != '#') p++;
    }
    const char *chemin = p;
    size_t lc = strcspn(chemin, "?#");
    const char *requete = chemin[lc] == '?' ? chemin + lc + 1 : NULL;
    const char *ancre = strchr(chemin, '#');
    const char *fin_req = requete ? (ancre && ancre > requete ? ancre : requete + strlen(requete)) : NULL;
    char tq[600] = "";
    int a_q = (requete && parametre_q(requete, fin_req, tq, sizeof tq)) ||
              (ancre && parametre_q(ancre + 1, ancre + strlen(ancre), tq, sizeof tq));
    if (q && nq) chaine_copier(q, tq, nq);
#define CHEMIN(c) (lc == strlen(c) && !strncmp(chemin, c, lc))
    if (CHEMIN("/complete/search") || CHEMIN("/complete/search/")) return GOOGLE_SUGGESTION;
    if (CHEMIN("/search")) return a_q ? GOOGLE_RECHERCHE : GOOGLE_ACCUEIL;
    if (lc == 0 || CHEMIN("/") || CHEMIN("/webhp") || CHEMIN("/imghp"))
        return a_q ? GOOGLE_RECHERCHE : GOOGLE_ACCUEIL;
#undef CHEMIN
    return GOOGLE_AUTRE;                              /* /maps, /url, /sorry, /intl… : pas la recherche */
}

/* des mots → morceau d'adresse (lettres, chiffres, - et . gardés ; espace → +) */
void encoder_recherche(const char *s, char *q, size_t nq)
{
    size_t o = 0;
    for (const unsigned char *p = (const unsigned char *)s; *p && o + 4 < nq; p++) {
        if ((*p >= 'a' && *p <= 'z') || (*p >= 'A' && *p <= 'Z') || (*p >= '0' && *p <= '9') || *p == '-' || *p == '.')
            q[o++] = (char)*p;
        else if (*p == ' ') q[o++] = '+';
        else o += (size_t)snprintf(q + o, nq - o, "%%%02X", *p);
    }
    q[o] = 0;
}

void adresse_duckduckgo(const char *mots, char *u, size_t n)
{
    char q[1024];
    encoder_recherche(mots, q, sizeof q);
    snprintf(u, n, "https://duckduckgo.com/?q=%s", q);
}

int moteur_est_google(void)
{
    return !strcmp(MOTEURS_RECHERCHE[R.recherche].nom, "Google");
}

/* ------------------------------ messages -------------------------------- */
const char *GOOGLE_NOTIFICATION =
    "Google Recherche est actuellement indisponible sous PuppyOS. Votre recherche a été redirigée vers DuckDuckGo. "
    "Les données reçues restent considérées comme non fiables et soumises aux protections du navigateur.";

/* une fois par lancement : pas à chaque recherche */
static int notifie, averti;

void google_signaler_redirection(void)
{
    if (notifie) return;
    notifie = 1;
    interface_info(2, GOOGLE_NOTIFICATION);
}

void google_avertir_sans_redirection(void)
{
    if (averti || !google_neutralise || R.google_redirige) return;
    averti = 1;
    dire("Redirection coupée : les recherches Google risquent de ne pas aboutir sous PuppyOS");
}

/* ------------------------- tests faits par l'utilisateur ---------------------
 * Un test envoie seulement le mot « puppyos » à Google, sur ton clic. Il est
 * noté ici, sur cette machine ; il ne change jamais la politique. */
#define TESTS_MAX 200
static struct { long t; int ok, resultats; char detail[80]; } tests[TESTS_MAX];
static int nb_tests, tests_lus;

static void tests_charger(void)
{
    if (tests_lus) return;
    tests_lus = 1;
    FILE *f = fopen(programme_donnees("navigateur-google.txt"), "r");
    if (!f) return;
    char l[300];
    while (fgets(l, sizeof l, f) && nb_tests < TESTS_MAX) {
        if (l[0] == '#') continue;
        l[strcspn(l, "\r\n")] = 0;
        long t; int ok, r; char d[80] = "";
        if (sscanf(l, "%ld|%d|%d|%79[^\n]", &t, &ok, &r, d) >= 3) {
            tests[nb_tests].t = t; tests[nb_tests].ok = ok; tests[nb_tests].resultats = r;
            chaine_copier(tests[nb_tests].detail, d, sizeof tests[nb_tests].detail);
            nb_tests++;
        }
    }
    fclose(f);
}

void google_test_noter(int ok, int resultats, const char *detail)
{
    tests_charger();
    if (nb_tests == TESTS_MAX) { memmove(tests, tests + 1, sizeof tests[0] * (TESTS_MAX - 1)); nb_tests--; }
    tests[nb_tests].t = (long)time(NULL);
    tests[nb_tests].ok = ok;
    tests[nb_tests].resultats = resultats;
    chaine_copier(tests[nb_tests].detail, detail, sizeof tests[nb_tests].detail);
    nb_tests++;
    FILE *f = fopen(programme_donnees("navigateur-google.txt"), "w");
    if (!f) return;
    fprintf(f, "# tests de Google Recherche faits sur cette machine : heure|réussi|résultats|détail\n");
    for (int i = 0; i < nb_tests; i++)
        fprintf(f, "%ld|%d|%d|%s\n", tests[i].t, tests[i].ok, tests[i].resultats, tests[i].detail);
    fclose(f);
}

void google_tests_effacer(void)
{
    nb_tests = 0;
    tests_lus = 1;
    remove(programme_donnees("navigateur-google.txt"));
}

void google_tests_resume(char *d, size_t n)
{
    tests_charger();
    if (!nb_tests) { snprintf(d, n, "Aucun test fait sur cette machine pour l'instant."); return; }
    int ok = 0;
    for (int i = 0; i < nb_tests; i++) ok += tests[i].ok;
    char quand[40];
    time_t t = tests[nb_tests - 1].t;
    struct tm tm;
    localtime_r(&t, &tm);
    strftime(quand, sizeof quand, "%d/%m/%Y %H:%M", &tm);
    snprintf(d, n, "Tests faits sur cette machine : %d, dont %d réussi%s. Dernier : %s, %s.",
             nb_tests, ok, ok > 1 ? "s" : "", quand, tests[nb_tests - 1].ok ? "réussi" : tests[nb_tests - 1].detail);
}

/* verdict d'un test à partir de ce que la page a montré (voir JS_GOOGLE_TEST) */
int google_test_juger(int sorry, int captcha, int resultats, const char *hote, char *detail, size_t n)
{
    if (!hote_google(hote)) { snprintf(detail, n, "échec : renvoyé vers %s", hote[0] ? hote : "une autre page"); return 0; }
    if (sorry || captcha) { snprintf(detail, n, "échec : contrôle anti-robots"); return 0; }
    if (resultats < 3) { snprintf(detail, n, "échec : pas de résultats (%d)", resultats); return 0; }
    snprintf(detail, n, "réussi (%d résultats)", resultats);
    return 1;
}

/* l'appareil : seulement pour choisir l'illustration (PuppyOS n'existe pas
 * encore sur téléphone et tablette ; /system/etc/appareil le dira) */
int appareil_mobile(void)
{
    FILE *f = fopen("/system/etc/appareil", "r");
    if (!f) return 0;
    char l[40] = "";
    if (!fgets(l, sizeof l, f)) l[0] = 0;
    fclose(f);
    return !strncmp(l, "telephone", 9) || !strncmp(l, "tablette", 8);
}
