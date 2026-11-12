/*
 * Navigateur 2.1 (PuppyOS 0.12.2) — protections.
 *
 *   explications   les textes des protections : premier lancement, fenêtre
 *                  quand on en coupe une, lexique (interface.c)
 *   permissions    caméra, micro, position, notifications : par site,
 *                  « demander », « permis » ou « bloqué pour toujours »
 *   PHP            reconnaître une page faite en PHP (blocage optionnel)
 *   pisteurs       connus (liste de blocage du moteur) ou probables
 */
#define _GNU_SOURCE
#include "nav.h"
#include "chromium.h"
#include "puppy.h"
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ----------------------------- explications ----------------------------- */
/* activee / coupee : ce qui se passe dans chaque cas (premier lancement) ;
 * risques / pourquoi / quand : la fenêtre qui s'ouvre quand on la coupe. */
const explication EXPLICATIONS[NB_EXPLICATIONS] = {
    [EX_JS] = { "JavaScript",
        "Nécessaire pour presque tous les sites modernes : vidéos, menus, messageries, cartes, paiements.",
        "JavaScript est une source majeure de pistage, de failles XSS et d'attaques. Le couper améliore beaucoup la sécurité "
        "et la vie privée, mais casse une grande partie des sites.",
        "Avec JavaScript, chaque site peut lancer son propre programme dans le Navigateur : pisteurs, empreinte de ta machine, "
        "scripts piégés (XSS), mineurs de cryptomonnaie.",
        "Le moteur reste enfermé dans le bac à sable à deux murs et Honey-Dog le surveille : même un script piégé ne sort pas.",
        "Le garder coupé a du sens pour lire des articles, de la documentation, ou visiter un site douteux. Tu peux le permettre "
        "site par site avec le cadenas de la barre d'adresse." },
    [EX_COOKIES] = { "Cookies",
        "Nécessaires pour rester connecté à tes comptes et garder tes préférences (langue, panier, thème).",
        "Les cookies servent au pistage et au profilage publicitaire. Les couper améliore la vie privée, mais casse les "
        "connexions : tu seras déconnecté partout.",
        "Les cookies permettent à un site, et à ses partenaires publicitaires, de te reconnaître d'une visite à l'autre.",
        "Les cookies tiers (ceux des pisteurs sur les autres sites) restent coupés à part, et « Tout effacer en quittant » "
        "les fait disparaître à la fermeture.",
        "Les couper est utile pour une navigation sans compte, ou sur un site que tu ne veux pas voir se souvenir de toi." },
    [EX_PHP] = { "Blocage des pages PHP",
        "Garder le blocage réduit la surface d'attaque : beaucoup de failles web passent par des applications PHP mal "
        "sécurisées.",
        "Environ 70 % des sites utilisent PHP : désactiver le blocage rouvre une très grande partie du web (WordPress, forums, "
        "boutiques…).",
        "Sans le blocage, les pages faites en PHP s'ouvrent. Le Navigateur te montre seulement l'étiquette « PHP » dans la "
        "barre d'adresse.",
        "Les applications PHP anciennes ou mal tenues (vieux WordPress, forums abandonnés) sont une porte d'entrée fréquente "
        "pour les pages piégées.",
        "Le couper est utile si tu visites beaucoup de blogs, forums ou boutiques : le web en dépend énormément. Tu peux aussi "
        "garder le blocage et permettre seulement les sites dont tu as confiance." },
    [EX_TIERS] = { "Cookies tiers",
        "Ceux des pisteurs installés sur les autres sites.",
        "Coupés, les régies publicitaires ne peuvent plus te suivre d'un site à l'autre.",
        "Un même pisteur (régie publicitaire, réseau social) te reconnaît sur tous les sites qui l'hébergent et reconstitue ton parcours.",
        "Presque aucun site n'en a vraiment besoin pour fonctionner.",
        "Rarement : certaines connexions « via Google / Facebook » ou des paiements intégrés en ont besoin." },
    [EX_HTTPS] = { "HTTPS d'abord",
        "Les adresses http sont d'abord essayées en https (chiffré).",
        "Sans lui, une adresse http s'ouvre sans chiffrement.",
        "Sans chiffrement, ce que tu lis et tapes peut être lu ou modifié par n'importe qui sur le réseau (wifi public, box piratée).",
        "Il ne coûte rien : si le site n'a pas de https, le Navigateur te propose quand même de l'ouvrir en http.",
        "Presque jamais : seulement pour un appareil de ton réseau (box, imprimante) qui se comporte mal en https." },
    [EX_LIENS] = { "Nettoyer les liens",
        "Retire les traceurs des adresses (utm_, fbclid, gclid…).",
        "Sans lui, les liens gardent leurs étiquettes de pistage.",
        "Ces étiquettes disent au site d'où tu viens (quelle pub, quel e-mail, quel réseau social) et te relient à une campagne.",
        "Le lien mène exactement à la même page, juste sans l'étiquette.",
        "Rarement : un lien de connexion ou de réduction qui ne marcherait pas sans ses paramètres." },
    [EX_GPC] = { "Ne me pistez pas (GPC)",
        "Demande aux sites de ne pas vendre tes données.",
        "Sans lui, les sites ne reçoivent pas ce signal.",
        "Le signal GPC est reconnu par la loi dans plusieurs pays : sans lui, rien n'oblige le site à ne pas revendre tes données.",
        "Il ne casse aucun site.",
        "Jamais vraiment : il ne coûte rien." },
    [EX_POPUPS] = { "Fenêtres surgissantes",
        "Coupées, seules celles ouvertes par un clic sont permises.",
        "Permises, un site peut ouvrir des onglets tout seul.",
        "Les fenêtres surgissantes servent aux pubs agressives, aux fausses alertes « virus » et aux pages de hameçonnage.",
        "Celles que tu ouvres d'un clic (paiement, connexion) passent déjà.",
        "Pour un vieux site qui ouvre ses fenêtres sans clic." },
    [EX_BANNIERES] = { "Masquer les bannières cookies",
        "Refuse et cache les bandeaux « Accepter les cookies » des sites européens.",
        "Sans lui, chaque site affiche son bandeau et attend ton choix.",
        "Les bandeaux poussent à « Tout accepter » : un clic de travers et les pisteurs sont permis.",
        "Le Navigateur clique « Tout refuser » quand le site le propose, puis cache le reste.",
        "Si un site ne marche plus sans ton accord (rare), ou pour choisir finement toi-même." },
};

/* ------------------------------ permissions ----------------------------- */
const char *const PERM_NOMS[NB_PERMS] = { "ta caméra", "ton micro", "ta position", "t'envoyer des notifications" };
const char *const PERM_TITRES[NB_PERMS] = { "Caméra", "Micro", "Position", "Notifications" };

perm_site perms[PERMS_SITES_MAX];
int nb_perms;

void permissions_charger(void)
{
    nb_perms = 0;
    char *t = lire_fichier(chemin_donnees("navigateur-permissions.txt"), NULL);
    if (!t) return;
    for (char *l = strtok(t, "\n"); l && nb_perms < PERMS_SITES_MAX; l = strtok(NULL, "\n")) {
        if (l[0] == '#') continue;
        char h[128];
        int v[NB_PERMS] = { -1, -1, -1, -1 };
        if (sscanf(l, "%127[^|]|%d|%d|%d|%d", h, &v[0], &v[1], &v[2], &v[3]) < 2) continue;
        perm_site *p = &perms[nb_perms++];
        chaine_copier(p->hote, h, sizeof p->hote);
        for (int k = 0; k < NB_PERMS; k++) p->p[k] = (signed char)(v[k] < -1 || v[k] > 1 ? -1 : v[k]);
    }
    free(t);
}

void permissions_enregistrer(void)
{
    char b[PERMS_SITES_MAX * 160 + 128];
    size_t o = (size_t)snprintf(b, sizeof b, "# site|caméra|micro|position|notifications   (1 permis, 0 bloqué, -1 demander)\n");
    for (int i = 0; i < nb_perms; i++) {
        perm_site *p = &perms[i];
        int utile = 0;
        for (int k = 0; k < NB_PERMS; k++) if (p->p[k] >= 0) utile = 1;
        if (utile) o += (size_t)snprintf(b + o, sizeof b - o, "%s|%d|%d|%d|%d\n", p->hote, p->p[0], p->p[1], p->p[2], p->p[3]);
    }
    ecrire_fichier_atomique(chemin_donnees("navigateur-permissions.txt"), b, o);
}

static perm_site *perm_trouver(const char *hote, int creer)
{
    for (int i = 0; i < nb_perms; i++) if (!strcmp(perms[i].hote, hote)) return &perms[i];
    if (!creer || nb_perms >= PERMS_SITES_MAX || !hote[0]) return NULL;
    perm_site *p = &perms[nb_perms++];
    chaine_copier(p->hote, hote, sizeof p->hote);
    for (int k = 0; k < NB_PERMS; k++) p->p[k] = -1;
    return p;
}

int permission_etat(const char *hote, int type)
{
    perm_site *p = perm_trouver(hote, 0);
    return p && type >= 0 && type < NB_PERMS ? p->p[type] : -1;
}

void permission_retenir(const char *hote, int type, int etat)
{
    if (type < 0 || type >= NB_PERMS) return;
    perm_site *p = perm_trouver(hote, etat >= 0);
    if (!p) return;
    p->p[type] = (signed char)etat;
    permissions_enregistrer();
}

void permission_oublier_site(const char *hote)
{
    for (int i = 0; i < nb_perms; i++)
        if (!strcmp(perms[i].hote, hote)) { perms[i] = perms[--nb_perms]; permissions_enregistrer(); return; }
}

/* ---------------------------------- PHP ---------------------------------- */
static int contient_i(const char *s, const char *m)
{
    size_t n = strlen(m);
    for (; *s; s++) if (!strncasecmp(s, m, n)) return 1;
    return 0;
}

/* L'adresse (…/page.php, ….php?x=) et les en-têtes de la réponse (X-Powered-By:
 * PHP/8.2, cookie PHPSESSID…) trahissent une page PHP. Un site peut le
 * cacher : c'est une détection honnête, pas une garantie. */
int page_php(const char *url, const char *entetes)
{
    const char *q = url + strcspn(url, "?#");
    const char *p = strstr(url, ".php");
    if (p && p < q && (p[4] == 0 || p[4] == '?' || p[4] == '#' || p[4] == '/' || isdigit((unsigned char)p[4]))) return 1;
    if (!entetes) return 0;
    return contient_i(entetes, "\"x-powered-by\",\"value\":\"php") || contient_i(entetes, "phpsessid") ||
           contient_i(entetes, "\"value\":\"php/") || contient_i(entetes, "x-powered-by\":\"php");
}

/* -------------------------------- pisteurs ------------------------------- */
/* La liste de blocage du moteur (fichier hosts, des dizaines de milliers de
 * domaines) : chargée une fois, triée, cherchée par dichotomie. */
static char **liste;
static int nb_liste, liste_lue;

static int cmp(const void *a, const void *b) { return strcmp(*(char *const *)a, *(char *const *)b); }

static void liste_charger(void)
{
    liste_lue = 1;
    char *t = lire_fichier(CHROMIUM_MOTEUR "/blocage/hosts", NULL);
    if (!t) return;
    int cap = 0;
    for (char *p = t; *p; p++) if (*p == '\n') cap++;
    liste = malloc((size_t)(cap + 1) * sizeof *liste);
    if (!liste) { free(t); return; }
    for (char *l = strtok(t, "\n"); l; l = strtok(NULL, "\n")) {
        if (strncmp(l, "0.0.0.0 ", 8)) continue;
        char *h = l + 8;
        h[strcspn(h, " \t\r#")] = 0;
        if (h[0] && nb_liste < cap) liste[nb_liste++] = h;   /* t reste alloué : les noms y vivent */
    }
    qsort(liste, (size_t)nb_liste, sizeof *liste, cmp);
}

int pisteur_connu(const char *hote)
{
    if (!liste_lue) liste_charger();
    if (!nb_liste || !hote[0]) return 0;
    /* le domaine et ses parents : « a.b.pisteur.com » est couvert par « pisteur.com » */
    for (const char *h = hote; h; h = strchr(h, '.') ? strchr(h, '.') + 1 : NULL) {
        if (!strchr(h, '.')) break;
        const char *k = h;
        if (bsearch(&k, liste, (size_t)nb_liste, sizeof *liste, cmp)) return 1;
    }
    return 0;
}

int pisteur_probable(const char *url)
{
    static const char *MOTS[] = { "/collect", "/pixel", "/track", "/beacon", "/analytics", "/telemetry", "/metrics",
                                  "/log?", "/event?", "/events?", "/hit?", "/stats", "/fingerprint", "/1x1", "/p.gif",
                                  "/pageview", "/impression", "utm_", "/rum?", "/ping?", NULL };
    for (int i = 0; MOTS[i]; i++) if (contient_i(url, MOTS[i])) return 1;
    return 0;
}

/* même site : les deux derniers éléments du nom (approximation sans la
 * liste des suffixes publics ; « .co.uk » et semblables sont traités) */
static const char *racine(const char *h)
{
    const char *fin = h + strlen(h), *p = fin;
    int points = 0;
    while (p > h) {
        p--;
        if (*p == '.' && ++points == 2) {
            /* .co.uk, .com.au, .gouv.fr : un niveau de plus */
            if (fin - p <= 7 && (!strncmp(p + 1, "co.", 3) || !strncmp(p + 1, "com.", 4) || !strncmp(p + 1, "gouv.", 5) ||
                                 !strncmp(p + 1, "org.", 4) || !strncmp(p + 1, "ac.", 3) || !strncmp(p + 1, "net.", 4))) {
                points--;
                continue;
            }
            return p + 1;
        }
    }
    return h;
}

int meme_site(const char *a, const char *b) { return !strcmp(racine(a), racine(b)); }
