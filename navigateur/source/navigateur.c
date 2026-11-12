/*
 * Navigateur.puppy 2.0 (PuppyOS 0.12) — deux navigateurs sur un même moteur
 * Chromium, chacun dans son bac à sable (puppy-bac), sans aucun droit sur
 * PuppyOS.
 *
 *   navigateur              le vrai navigateur : onglets, favoris, historique,
 *                           navigation privée, identités (User-Agent), cookies
 *                           et JavaScript réglables par site, HTTPS d'abord,
 *                           coffre à mots de passe chiffré (enclave), VPN,
 *                           extensions (scripts utilisateur), mode lecture,
 *                           recherche dans la page, PDF, glisser-déposer.
 *   navigateur --streaming  le lecteur du MediaCenter : plein écran, aucune
 *                           barre, aucun favori, impossible de quitter les
 *                           sites de streaming.
 *
 * Chromium tourne « headless » : il nous envoie des images JPEG de la page
 * (Page.screencastFrame) et nous lui renvoyons la souris et le clavier
 * (Input.dispatch*). Il ne voit ni l'écran, ni le bureau, ni les fichiers.
 *
 * Jamais de contournement : pas de DRM (Netflix, Prime Video, Disney+
 * demandent Widevine), pas de bloqueur de pub dans le lecteur streaming.
 */
#define _GNU_SOURCE
#include "nav.h"
#include "bac.h"
#include "coffre-nav.h"
#include "puppy.h"
#include "son.h"
#include <linux/input-event-codes.h>
#include <math.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <signal.h>
#ifdef NAV_DEBUG
#define TRACE(...) (fprintf(stderr, "%.0f ", horloge()), fprintf(stderr, __VA_ARGS__), fputc(10, stderr), fsync(2))
#else
#define TRACE(...) ((void)0)
#endif

pf_fenetre *fen;
surface *toile;
int EL, EH, PX, PY, PL, PH;
double maintenant, anime_jusqua;
int sale = 1;
cdp moteur;
int moteur_pret, streaming;
char erreur_moteur[200];
onglet onglets[ONGLETS_MAX];
int nb_onglets, actif;

static char message[200];
static double message_jusqua;
static double dernier_geste;          /* dernier clic ou touche dans la page (fenêtres surgissantes) */
static char contexte_prive[64];       /* contexte de navigation privée (jetable) */
static char contexte_vpn[64];         /* contexte qui passe par le VPN */
static int quitter;

/* lecteur (mode streaming) */
enum { VUE_ACCUEIL, VUE_PAGE };
static int vue = VUE_ACCUEIL, choix;
static int video_plein, video_pause, id_etat = -1;
static double video_t, video_d, osd_jusqua, prochain_etat, prochain_titre;


static double horloge(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}

void dire(const char *m)
{
    chaine_copier(message, m, sizeof message);
    message_jusqua = maintenant + 5000;
    sale = 1;
}

void texte(int x, int y, const char *t, couleur c, float taille, int gras)
{
    gfx_texte_vect(toile, x, y, t, c, taille, gras ? POIDS_GRAS : POIDS_NORMAL);
}


/* ------------------------------ services --------------------------------- */
typedef struct {
    const char *nom, *adresse, *detail;
    couleur c1, c2;
    int disponible;
} service;

static const service SERVICES[] = {
    { "YouTube",     "https://www.youtube.com/tv#/",       "Vidéos, chaînes, abonnements",  RVB(230, 33, 23),  RVB(140, 10, 10), 1 },
    { "Twitch",      "https://www.twitch.tv/",            "Directs et rediffusions",       RVB(145, 70, 255), RVB(80, 30, 160), 1 },
    { "Dailymotion", "https://www.dailymotion.com/fr",    "Vidéos et actualités",          RVB(40, 120, 255), RVB(10, 50, 140), 1 },
    { "ARTE",        "https://www.arte.tv/fr/",           "Films, documentaires, séries",  RVB(250, 90, 30),  RVB(150, 40, 10), 1 },
    { "Netflix",     NULL, "Protégé par Widevine : indisponible", RVB(90, 90, 90), RVB(50, 50, 50), 0 },
    { "Prime Video", NULL, "Protégé par Widevine : indisponible", RVB(90, 90, 90), RVB(50, 50, 50), 0 },
    { "Disney+",     NULL, "Protégé par Widevine : indisponible", RVB(90, 90, 90), RVB(50, 50, 50), 0 },
};
#define NB_SERVICES (int)(sizeof SERVICES / sizeof SERVICES[0])

/* Sites où le lecteur peut aller (page principale seulement : les cadres
 * intérieurs des sites restent libres, sinon ils cassent). La connexion
 * aux comptes passe par les pages de compte de chaque service. */
static const char *AUTORISES[] = {
    "youtube.com", "youtu.be", "youtube-nocookie.com",
    "accounts.google.com", "accounts.youtube.com", "consent.youtube.com", "consent.google.com",
    "twitch.tv", "dailymotion.com", "arte.tv", NULL };

int hote_de(const char *u, char *h, size_t n)
{
    const char *p = strstr(u, "://");
    if (!p) return 0;
    p += 3;
    size_t i = 0;
    while (p[i] && p[i] != '/' && p[i] != ':' && p[i] != '?' && p[i] != '#' && i + 1 < n) { h[i] = p[i]; i++; }
    h[i] = 0;
    char *at = strchr(h, '@');                  /* jamais d'identifiants dans l'adresse */
    if (at) memmove(h, at + 1, strlen(at));
    for (char *q = h; *q; q++) if (*q >= 'A' && *q <= 'Z') *q += 32;
    return i > 0;
}

static int autorise(const char *u)
{
    if (!strncmp(u, "about:blank", 11)) return 1;
    if (strncmp(u, "https://", 8)) return 0;            /* https seulement */
    char h[256];
    if (!hote_de(u, h, sizeof h)) return 0;
    size_t lh = strlen(h);
    for (int i = 0; AUTORISES[i]; i++) {
        size_t la = strlen(AUTORISES[i]);
        if (lh == la && !strcmp(h, AUTORISES[i])) return 1;
        if (lh > la && !strcmp(h + lh - la, AUTORISES[i]) && h[lh - la - 1] == '.') return 1;
    }
    return 0;
}


/* ----------------------- Honey-Dog (0.11) --------------------------------
 * Sites bloqués : après une attaque, le gardien des bacs (puppy-bac) ajoute
 * le site à /system/etc/sites-bloques.txt ; on refuse de l'ouvrir (et ses
 * sous-domaines) avec une page claire. La personne peut le débloquer dans
 * Paramètres → Sécurité → Bacs à sable.
 * ------------------------------------------------------------------------- */
static int site_bloque(const char *h)
{
    if (!h || !h[0]) return 0;
    char *t = lire_fichier(BAC_SITES_BLOQUES, NULL);
    if (!t) return 0;
    int oui = 0;
    size_t lh = strlen(h);
    for (char *l = strtok(t, "\n"); l && !oui; l = strtok(NULL, "\n")) {
        size_t k = strcspn(l, "|");
        if (!k || l[0] == '#') continue;
        if (k == lh && !strncmp(l, h, k)) oui = 1;
        else if (lh > k && h[lh - k - 1] == '.' && !strncmp(h + lh - k, l, k)) oui = 1;
    }
    free(t);
    return oui;
}

static void base64_ecrire(const unsigned char *s, size_t n, char *d)
{
    static const char a[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t o = 0;
    for (size_t i = 0; i < n; i += 3) {
        unsigned v = (unsigned)s[i] << 16 | (i + 1 < n ? (unsigned)s[i + 1] << 8 : 0) | (i + 2 < n ? s[i + 2] : 0);
        d[o++] = a[v >> 18 & 63];
        d[o++] = a[v >> 12 & 63];
        d[o++] = i + 1 < n ? a[v >> 6 & 63] : '=';
        d[o++] = i + 2 < n ? a[v & 63] : '=';
    }
    d[o] = 0;
}

/* répond nous-mêmes à la place du site : une page qui explique */
static void page_bloquee(const char *session, const char *erid, const char *hote)
{
    char h[256];
    size_t o = 0;
    for (const char *c = hote; *c && o + 1 < sizeof h; c++)       /* rien d'autre qu'un nom de site */
        if ((*c >= 'a' && *c <= 'z') || (*c >= '0' && *c <= '9') || *c == '.' || *c == '-') h[o++] = *c;
    h[o] = 0;
    char html[3000];
    int n = snprintf(html, sizeof html,
        "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\"><title>Site bloqué par Honey-Dog</title>"
        "<style>body{margin:0;font-family:sans-serif;background:linear-gradient(#fdf1e6,#fbe2cc);color:#3a2a1a}"
        ".b{max-width:640px;margin:70px auto;background:rgba(255,255,255,.85);border:1px solid #e0b48a;border-radius:18px;"
        "padding:34px 40px;box-shadow:0 10px 40px rgba(120,60,0,.18)}h1{color:#b3470b;font-size:30px;margin:0 0 14px}"
        "p{font-size:18px;line-height:1.5}code{background:#f6e3d2;padding:2px 8px;border-radius:6px}.e{font-size:64px}</style>"
        "</head><body><div class=\"b\"><div class=\"e\">&#128737;&#65039;</div><h1>Site bloqué par Honey-Dog</h1>"
        "<p>Le site <code>%s</code> a attaqué le bac à sable du Navigateur. Honey-Dog l'a arrêté et a remis le "
        "navigateur dans son état d'avant : rien n'a touché ton système.</p>"
        "<p>Pour ta sécurité, ce site reste bloqué. Si tu es sûr de toi, tu peux le débloquer dans "
        "<b>Paramètres &rarr; Sécurité &rarr; Bacs à sable</b>.</p></div></body></html>", h);
    if (n < 0 || n >= (int)sizeof html) return;
    char *b64 = malloc((size_t)n * 4 / 3 + 8), *p = malloc((size_t)n * 4 / 3 + 512);
    if (b64 && p) {
        base64_ecrire((const unsigned char *)html, (size_t)n, b64);
        snprintf(p, (size_t)n * 4 / 3 + 512,
                 "{\"requestId\":%s,\"responseCode\":200,\"responseHeaders\":[{\"name\":\"Content-Type\","
                 "\"value\":\"text/html; charset=utf-8\"}],\"body\":\"%s\"}", erid, b64);
        cdp_envoyer_a(&moteur, session, "Fetch.fulfillRequest", p);
    }
    free(b64);
    free(p);
}

/* dit au gardien quelle adresse est ouverte (pour l'incident et le blocage) */
static void dire_honeydog(const char *u)
{
    if (moteur.canal < 0 || !u[0]) return;
    char l[1100];
    int n = snprintf(l, sizeof l, "URL %s\n", u);
    if (n <= 0 || n >= (int)sizeof l) return;
    for (int i = 4; i < n - 1; i++) if ((unsigned char)l[i] < 32) l[i] = ' ';
    send(moteur.canal, l, (size_t)n, MSG_NOSIGNAL | MSG_DONTWAIT);
}

/* état des fenêtres de Honey-Dog et du choix de fichiers */
static int alerte, alerte_score, alerte_continues, alerte_confirmer;
static char alerte_pourquoi[600], alerte_quoi[1400];
static int arret, arret_force;
static char arret_message[300], arret_site[256];
static int choix_ouvert, choix_noeud, choix_multiple, choix_nb, choix_defile;
static char choix_noms[64][128];
static int choix_pris[64];
static int choix_image;                 /* 2.1 : le choix sert à « Modifier » une image du site */
static char image_originale[1024];
static void image_choisie(void);
/* 2.1 : définis plus bas (bloc « 2.1 ») */
static void permission_demande(onglet *o, const char *pl);
static void reseau_requete(onglet *o, const char *m);
static void reseau_reponse(onglet *o, const char *m);
void sites_appliquer(void);

typedef struct { int x, y, l, h; } zone;
static zone z_detruire, z_continuer, z_relancer, z_envoyer, z_annuler, z_liste;
static int dans(zone z, int x, int y) { return z.l > 0 && x >= z.x && y >= z.y && x < z.x + z.l && y < z.y + z.h; }

static void ouvrir_choix(int noeud, int multiple)
{
    choix_ouvert = 1;
    choix_noeud = noeud;
    choix_multiple = multiple;
    choix_nb = choix_defile = 0;
    memset(choix_pris, 0, sizeof choix_pris);
    DIR *d = opendir(BAC_ENVOI);
    struct dirent *e;
    while (d && (e = readdir(d)) && choix_nb < 64) {
        if (e->d_name[0] == '.' || strlen(e->d_name) >= sizeof choix_noms[0] || !strcmp(e->d_name, "Lisez-moi.txt")) continue;
        char c[400];
        struct stat st;
        snprintf(c, sizeof c, "%s/%s", BAC_ENVOI, e->d_name);
        if (lstat(c, &st) < 0 || !S_ISREG(st.st_mode)) continue;
        chaine_copier(choix_noms[choix_nb++], e->d_name, sizeof choix_noms[0]);
    }
    if (d) closedir(d);
    sale = 1;
}

/* envoie la décision au gardien (détruire / continuer) */
static void decision_honeydog(const char *d)
{
    if (moteur.canal >= 0) {
        char l[32];
        int n = snprintf(l, sizeof l, "DECISION %s\n", d);
        send(moteur.canal, l, (size_t)n, MSG_NOSIGNAL | MSG_DONTWAIT);
    }
    alerte = alerte_confirmer = 0;
    sale = 1;
}

/* une ligne du canal d'alerte de Honey-Dog */
static void ligne_honeydog(char *l)
{
    TRACE("honeydog : %.200s", l);
    if (!strncmp(l, "ALERTE\t", 7)) {
        /* ALERTE<tab>score<tab>continues<tab>pourquoi<tab>quoi */
        char *champ[5] = { l + 7 };
        int n = 1;
        for (char *p = l + 7; *p && n < 5; p++) if (*p == '\t') { *p = 0; champ[n++] = p + 1; }
        alerte = 1;
        arret = choix_ouvert = 0;
        alerte_score = atoi(champ[0]);
        alerte_continues = n > 1 ? atoi(champ[1]) : 0;
        alerte_confirmer = 0;
        chaine_copier(alerte_pourquoi, n > 2 ? champ[2] : "", sizeof alerte_pourquoi);
        chaine_copier(alerte_quoi, n > 3 ? champ[3] : "", sizeof alerte_quoi);
        sale = 1;
    } else if (!strncmp(l, "STOP\t", 5)) {
        /* STOP<tab>force|demande<tab>message<tab>site */
        char *champ[4] = { l + 5 };
        int n = 1;
        for (char *p = l + 5; *p && n < 4; p++) if (*p == '\t') { *p = 0; champ[n++] = p + 1; }
        arret = 1;
        arret_force = !strcmp(champ[0], "force");
        alerte = choix_ouvert = 0;
        chaine_copier(arret_message, n > 1 ? champ[1] : "L'attaque a été arrêtée.", sizeof arret_message);
        chaine_copier(arret_site, n > 2 ? champ[2] : "", sizeof arret_site);
        moteur_pret = 0;
        if (nb_onglets && ACTIF->image) { surface_detruire(ACTIF->image); ACTIF->image = NULL; }
        sale = 1;
    }
}

static char canal_tampon[4096];
static size_t canal_lu;
static void lire_honeydog(void)
{
    ssize_t r = recv(moteur.canal, canal_tampon + canal_lu, sizeof canal_tampon - 1 - canal_lu, MSG_DONTWAIT);
    if (r <= 0) { if (r == 0) { close(moteur.canal); moteur.canal = -1; } return; }
    canal_lu += (size_t)r;
    canal_tampon[canal_lu] = 0;
    char *nl;
    while ((nl = memchr(canal_tampon, '\n', canal_lu))) {
        *nl = 0;
        ligne_honeydog(canal_tampon);
        size_t reste = canal_lu - (size_t)(nl + 1 - canal_tampon);
        memmove(canal_tampon, nl + 1, reste);
        canal_lu = reste;
        canal_tampon[canal_lu] = 0;
    }
    if (canal_lu == sizeof canal_tampon - 1) canal_lu = 0;
}

/* envoie au moteur les fichiers choisis dans le dossier d'envoi */
static void envoyer_choix(void)
{
    char liste[4096] = "[", *p = liste + 1;
    size_t reste = sizeof liste - 2;
    int premier = 1;
    for (int i = 0; i < choix_nb; i++) {
        if (!choix_pris[i]) continue;
        char chemin[300], e[600];
        snprintf(chemin, sizeof chemin, "/tmp/envoi/%s", choix_noms[i]);   /* vu ainsi dans la boîte */
        json_echapper(e, sizeof e, chemin);
        int k = snprintf(p, reste, "%s%s", premier ? "" : ",", e);
        if (k < 0 || (size_t)k >= reste) break;
        p += k;
        reste -= (size_t)k;
        premier = 0;
    }
    *p++ = ']';
    *p = 0;
    char params[4400];
    snprintf(params, sizeof params, "{\"backendNodeId\":%d,\"files\":%s}", choix_noeud, liste);
    cdp_envoyer(&moteur, "DOM.setFileInputFiles", params, 1);
    choix_ouvert = 0;
    dire(premier ? "Aucun fichier envoyé" : "Fichier(s) envoyé(s) au site");
    sale = 1;
}

/* clics dans les fenêtres de Honey-Dog ; 1 = le clic est pris */
static int clic_honeydog(int x, int y, int appuye)
{
    if (!appuye) return alerte || arret || choix_ouvert;
    if (arret) { if (dans(z_relancer, x, y)) { arret = 0; sale = 1; } return 1; }
    if (alerte) {
        if (dans(z_detruire, x, y)) decision_honeydog("detruire");
        else if (dans(z_continuer, x, y)) {
            if (alerte_confirmer || alerte_score < 70) decision_honeydog("continuer");
            else { alerte_confirmer = 1; sale = 1; }   /* on fait confirmer quand c'est grave */
        }
        return 1;
    }
    if (choix_ouvert) {
        if (dans(z_annuler, x, y)) {
            if (!choix_image) cdp_envoyer(&moteur, "DOM.setFileInputFiles", "{\"backendNodeId\":0,\"files\":[]}", 1);
            choix_ouvert = choix_image = 0;
            sale = 1;
        }
        else if (dans(z_envoyer, x, y)) { if (choix_image) image_choisie(); else envoyer_choix(); }
        else if (dans(z_liste, x, y)) {
            int i = choix_defile + (y - z_liste.y) / 34;
            if (i < choix_nb) { if (!choix_multiple) memset(choix_pris, 0, sizeof choix_pris); choix_pris[i] = !choix_pris[i]; sale = 1; }
        }
        return 1;
    }
    return 0;
}

/* ------------------------- scripts injectés ------------------------------- */
/* Bannières cookies (mode navigateur), 0.12.2 : on clique d'abord sur
 * « Tout refuser » / « Continuer sans accepter » quand le site le propose
 * (le site retient le refus), puis on masque ce qui reste. Cherche aussi
 * dans les composants fermés (shadow DOM) et dans les cadres. */
static const char *SCRIPT_COOKIES =
    "(()=>{if(window.__puppyCk)return;window.__puppyCk=1;"
    "const css=`#onetrust-consent-sdk,#onetrust-banner-sdk,.onetrust-pc-dark-filter,.fc-consent-root,#didomi-host,#didomi-notice,"
    "#CybotCookiebotDialog,#CybotCookiebotDialogBodyUnderlay,#cookiebanner,#cookie-banner,.cookie-banner,#cookie-notice,.cookie-notice,"
    "#cmpbox,#cmpbox2,.cmpboxBG,#qc-cmp2-container,.qc-cmp2-container,[id^=sp_message_container],#usercentrics-root,"
    ".cc-window,.cc-banner,#truste-consent-track,#gdpr-consent-tool-wrapper,#axeptio_overlay,#tarteaucitronRoot,"
    "#tarteaucitronAlertBig,.osano-cm-window,#consent_blackbar,.qc-cmp-ui-container,#cookie-law-info-bar,"
    ".cky-consent-container,.cky-overlay,.cmplz-cookiebanner,#BorlabsCookieBox,.iubenda-cs-container,#cookie-consent,.cookie-consent,"
    "#cookieConsent,.cookieConsent,#cookies-banner,#cookie-bar,.cookie-bar,#CookieBanner,#gdpr-cookie-message,#sd-cmp,"
    "#pmConsentWall,#cmp-app-container,.sp_veil,#consent-banner,.consent-banner,#cookiescript_injected,.evidon-banner,"
    "#hs-eu-cookie-confirmation,.termly-styles-root,#ccc,#ccc-overlay,.js-consent-banner,#cookie_banner,#privacy-consent"
    "{display:none!important}html,body{overflow:auto!important}`;"
    "const NON=/^(tout refuser|refuser tout|tout rejeter|rejeter tout|refuser|je refuse|refuser et fermer|continuer sans accepter|"
    "reject all|reject|reject all cookies|decline|decline all|deny|deny all|only necessary|necessary only|only essential|"
    "use necessary cookies only|accept only essential cookies|accept necessary|uniquement les cookies (n[ée]cessaires|essentiels)|"
    "cookies n[ée]cessaires uniquement|n[ée]cessaires uniquement|accepter (uniquement )?les cookies (n[ée]cessaires|essentiels)|"
    "non merci|no thanks|ablehnen|alle ablehnen|rechazar( todo)?|rifiuta( tutto)?|weigeren|alles weigeren)$/i;"
    "const ZONE='[id*=cookie i],[class*=cookie i],[id*=consent i],[class*=consent i],[id*=cmp i],[class*=cmp i],"
    "[id*=gdpr i],[class*=gdpr i],[id*=privacy i],[class*=privacy i],[aria-modal=true],dialog,[role=dialog],[role=alertdialog]';"
    "const pageConsent=/consent|cookie|privacy|gdpr|cmp|didomi|onetrust|sourcepoint|usercentrics/i.test(location.hostname+location.pathname);"
    "const cherche=(r,p)=>{if(p>4)return false;for(const b of r.querySelectorAll('button,a,[role=button],input[type=button],input[type=submit]')){"
    "const t=(b.innerText||b.value||b.getAttribute('aria-label')||'').trim().replace(/\\s+/g,' ');"
    "if(t&&t.length<70&&NON.test(t)&&(pageConsent||(b.closest&&b.closest(ZONE)))){b.click();return true}}"
    "for(const h of r.querySelectorAll('*'))if(h.shadowRoot&&cherche(h.shadowRoot,p+1))return true;return false};"
    "const f=()=>{if(document.getElementById('puppy-cookies'))return;const s=document.createElement('style');"
    "s.id='puppy-cookies';s.textContent=css;(document.head||document.documentElement).appendChild(s)};"
    "let n=0,fait=false;const essai=()=>{if(fait||n++>30)return;try{if(document.body&&cherche(document,0)){fait=true;return}}catch(e){}"
    "setTimeout(essai,n<8?400:1500)};"
    "const go=()=>{f();essai()};"
    "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',go);else go();"
    "if(document.documentElement)new MutationObserver(f).observe(document.documentElement,{childList:true});})()";

/* Lecteur : la vidéo principale passe en plein écran dès qu'elle joue. */
static const char *SCRIPT_LECTEUR =
    "(()=>{window.__puppyVideo=()=>{let b=null,s=0;for(const v of document.querySelectorAll('video')){"
    "const a=v.clientWidth*v.clientHeight;if(a>s){s=a;b=v}}return b};"
    "document.addEventListener('play',e=>{const v=e.target;if(!(v instanceof HTMLVideoElement))return;"
    "if(v.duration&&v.duration<20)return;if(!document.fullscreenElement&&v.requestFullscreen)"
    "v.requestFullscreen().catch(()=>{})},true);})()";

static const char *JS_ETAT =
    "(()=>{const v=window.__puppyVideo&&window.__puppyVideo();if(!v)return'';"
    "return JSON.stringify({t:v.currentTime||0,d:isFinite(v.duration)?v.duration:0,p:v.paused?1:0,"
    "f:document.fullscreenElement?1:0})})()";

static int evaluer(const char *js, int geste)
{
    size_t n = strlen(js) * 2 + 64;
    char *p = malloc(n + 128), *e = malloc(n);
    if (!p || !e) { free(p); free(e); return -1; }
    json_echapper(e, n, js);
    snprintf(p, n + 128, "{\"expression\":%s,\"returnByValue\":true,\"userGesture\":%s}", e, geste ? "true" : "false");
    int id = cdp_envoyer(&moteur, "Runtime.evaluate", p, 1);
    free(p);
    free(e);
    return id;
}

/* 2.1 : la même chose sur un onglet ou un cadre donné, sans attendre */
static void evaluer_session(const char *session, const char *js)
{
    size_t n = strlen(js) * 2 + 64;
    char *p = malloc(n + 128), *e = malloc(n);
    if (p && e) {
        json_echapper(e, n, js);
        snprintf(p, n + 128, "{\"expression\":%s,\"returnByValue\":true}", e);
        cdp_envoyer_a(&moteur, session, "Runtime.evaluate", p);
    }
    free(p);
    free(e);
}

typedef struct { int code; const char *cle, *nom; int vk; const char *texte; } touche_speciale;
static const touche_speciale SPECIALES[] = {
    { KEY_ENTER, "Enter", "Enter", 13, "\\r" }, { KEY_KPENTER, "Enter", "NumpadEnter", 13, "\\r" },
    { KEY_BACKSPACE, "Backspace", "Backspace", 8, NULL }, { KEY_TAB, "Tab", "Tab", 9, NULL },
    { KEY_ESC, "Escape", "Escape", 27, NULL }, { KEY_DELETE, "Delete", "Delete", 46, NULL },
    { KEY_LEFT, "ArrowLeft", "ArrowLeft", 37, NULL }, { KEY_UP, "ArrowUp", "ArrowUp", 38, NULL },
    { KEY_RIGHT, "ArrowRight", "ArrowRight", 39, NULL }, { KEY_DOWN, "ArrowDown", "ArrowDown", 40, NULL },
    { KEY_HOME, "Home", "Home", 36, NULL }, { KEY_END, "End", "End", 35, NULL },
    { KEY_PAGEUP, "PageUp", "PageUp", 33, NULL }, { KEY_PAGEDOWN, "PageDown", "PageDown", 34, NULL },
    { KEY_F5, "F5", "F5", 116, NULL }, { KEY_F11, "F11", "F11", 122, NULL },
};

static void envoyer_touche(const pf_evenement *e)
{
    int mod = (e->ctrl ? 2 : 0) | (e->maj ? 8 : 0);
    char p[512];
    for (size_t i = 0; i < sizeof SPECIALES / sizeof SPECIALES[0]; i++) {
        const touche_speciale *t = &SPECIALES[i];
        if (t->code != e->code) continue;
        const char *texte = t->texte && !mod ? t->texte : NULL;
        snprintf(p, sizeof p, "{\"type\":\"%s\",\"key\":\"%s\",\"code\":\"%s\",\"windowsVirtualKeyCode\":%d,\"modifiers\":%d%s%s%s}",
                 texte ? "keyDown" : "rawKeyDown", t->cle, t->nom, t->vk, mod,
                 texte ? ",\"text\":\"" : "", texte ? texte : "", texte ? "\"" : "");
        cdp_envoyer(&moteur, "Input.dispatchKeyEvent", p, 1);
        snprintf(p, sizeof p, "{\"type\":\"keyUp\",\"key\":\"%s\",\"code\":\"%s\",\"windowsVirtualKeyCode\":%d,\"modifiers\":%d}",
                 t->cle, t->nom, t->vk, mod);
        cdp_envoyer(&moteur, "Input.dispatchKeyEvent", p, 1);
        return;
    }
    if (!e->texte[0] || (unsigned char)e->texte[0] < 32) return;
    char t[32];
    json_echapper(t, sizeof t, e->texte);
    int vk = 0;
    unsigned char c0 = (unsigned char)e->texte[0];
    if (c0 >= 'a' && c0 <= 'z') vk = c0 - 32;
    else if (c0 >= 'A' && c0 <= 'Z') vk = c0;
    else if (c0 >= '0' && c0 <= '9') vk = c0;
    else if (c0 == ' ') vk = 32;
    if (e->ctrl) {                                   /* Ctrl+C, Ctrl+V… : sans texte */
        /* 0.12.1 : sans bureau Linux, le moteur ne relie pas Ctrl+Z, Ctrl+Y
         * et Ctrl+A à l'édition : on lui nomme la commande */
        const char *cmd = c0 == 'z' || c0 == 'Z' ? (e->maj ? "redo" : "undo") : c0 == 'y' || c0 == 'Y' ? "redo"
                        : c0 == 'a' || c0 == 'A' ? "selectAll" : NULL;
        snprintf(p, sizeof p, "{\"type\":\"rawKeyDown\",\"key\":%s,\"windowsVirtualKeyCode\":%d,\"modifiers\":%d%s%s%s}", t, vk, mod,
                 cmd ? ",\"commands\":[\"" : "", cmd ? cmd : "", cmd ? "\"]" : "");
        cdp_envoyer(&moteur, "Input.dispatchKeyEvent", p, 1);
    } else {
        snprintf(p, sizeof p, "{\"type\":\"keyDown\",\"key\":%s,\"text\":%s,\"unmodifiedText\":%s,\"windowsVirtualKeyCode\":%d,\"modifiers\":%d}",
                 t, t, t, vk, mod);
        cdp_envoyer(&moteur, "Input.dispatchKeyEvent", p, 1);
    }
    snprintf(p, sizeof p, "{\"type\":\"keyUp\",\"key\":%s,\"windowsVirtualKeyCode\":%d,\"modifiers\":%d}", t, vk, mod);
    cdp_envoyer(&moteur, "Input.dispatchKeyEvent", p, 1);
}

static int bouton_souris;
static void envoyer_souris(int x, int y, int bouton, int appuye)
{
    const char *type = bouton < 0 ? "mouseMoved" : appuye ? "mousePressed" : "mouseReleased";
    const char *b = bouton == 0 ? "left" : bouton == 1 ? "right" : "none";
    if (bouton >= 0) bouton_souris = appuye ? (bouton == 0 ? 1 : 2) : 0;
    char p[200];
    snprintf(p, sizeof p, "{\"type\":\"%s\",\"x\":%d,\"y\":%d,\"button\":\"%s\",\"buttons\":%d,\"clickCount\":1}",
             type, x, y, bouton < 0 ? (bouton_souris == 1 ? "left" : "none") : b, bouton_souris);
    cdp_envoyer(&moteur, "Input.dispatchMouseEvent", p, 1);
}


static void verre(int x, int y, int l, int h, couleur c1, couleur c2, int actif, int alpha)
{
    if (actif) gfx_ombre(toile, x - 6, y - 6, l + 12, h + 12, 18, 14, RVBA(120, 180, 255, alpha * 160 / 255));
    gfx_rect_arrondi_degrade(toile, x, y, l, h, 14, AVEC_ALPHA(c1, alpha), AVEC_ALPHA(c2, alpha));
    gfx_degrade(toile, x + 4, y + 3, l - 8, h / 2, RVBA(255, 255, 255, 70 * alpha / 255), RVBA(255, 255, 255, 8 * alpha / 255));
    gfx_contour_arrondi(toile, x, y, l, h, 14, actif ? RVBA(255, 255, 255, 230) : RVBA(255, 255, 255, 70));
}

static int tuile_x(int i, int *y, int *l, int *h)
{
    int col = 4, tl = (EL - 160 - (col - 1) * 30) / col;
    if (tl > 280) tl = 280;
    int th = tl * 9 / 16;
    int total = col * tl + (col - 1) * 30, x0 = (EL - total) / 2;
    *l = tl; *h = th;
    *y = EH / 2 - th - 10 + (i / col) * (th + 60);
    return x0 + (i % col) * (tl + 30);
}

static void dessiner_accueil(void)
{
    gfx_degrade(toile, 0, 0, EL, EH, RVB(10, 30, 70), RVB(4, 10, 28));
    for (int k = 0; k < 3; k++) {                   /* traînées lumineuses façon Media Center */
        float ph = (float)(maintenant / 4000.0) + k * 2.1f;
        for (int x = 0; x < EL; x += 3) {
            float y = EH * 0.78f + sinf(x / 260.0f + ph) * 40 + k * 18;
            gfx_rect(toile, x, (int)y, 3, 2, RVBA(120, 180, 255, 60 - k * 15));
        }
    }
    texte(80, 60, "Streaming", RVB(255, 255, 255), 54, 0);
    texte(84, 130, "Choisissez un service. Connexion avec votre compte si le site la demande.",
          RVBA(210, 225, 255, 200), 18, 0);
    for (int i = 0; i < NB_SERVICES; i++) {
        int y, l, h, x = tuile_x(i, &y, &l, &h);
        const service *s = &SERVICES[i];
        verre(x, y, l, h, s->c1, s->c2, i == choix, s->disponible ? 255 : 150);
        int tl = gfx_largeur_vect(s->nom, 30, POIDS_GRAS);
        texte(x + (l - tl) / 2, y + h / 2 - 22, s->nom, RVBA(255, 255, 255, s->disponible ? 255 : 150), 30, 1);
        int dl = gfx_largeur_vect(s->detail, 14, POIDS_NORMAL);
        texte(x + (l - dl) / 2, y + h + 10, s->detail, RVBA(210, 225, 255, s->disponible ? 210 : 140), 14, 0);
    }
    const char *aide = "← → ↑ ↓ : choisir    Entrée : ouvrir    Échap : retour au MediaCenter";
    int l = gfx_largeur_vect(aide, 15, POIDS_NORMAL);
    texte((EL - l) / 2, EH - 40, aide, RVBA(210, 225, 255, 170), 15, 0);
}

static void dessiner_osd(void)
{
    if (!video_plein || maintenant > osd_jusqua) return;
    gfx_degrade(toile, 0, EH - 140, EL, 140, RVBA(0, 0, 0, 0), RVBA(0, 0, 0, 200));
    int bx = 60, by = EH - 70, bl = EL - 120;
    gfx_rect_arrondi(toile, bx, by, bl, 8, 4, RVBA(255, 255, 255, 60));
    if (video_d > 0) {
        int r = (int)(video_t / video_d * bl);
        if (r > 4) gfx_rect_arrondi_degrade(toile, bx, by, r, 8, 4, RVB(120, 190, 255), RVB(40, 110, 230));
    }
    char l[160];
    snprintf(l, sizeof l, "%d:%02d / %d:%02d%s", (int)video_t / 60, (int)video_t % 60, (int)video_d / 60,
             (int)video_d % 60, video_pause ? "   ·   en pause" : "");
    texte(bx, by + 18, l, RVB(255, 255, 255), 16, 0);
    const char *a = "Espace : pause   ← → : 10 s   ↑ ↓ : volume   Échap : quitter le plein écran";
    int al = gfx_largeur_vect(a, 15, POIDS_NORMAL);
    texte(EL - 60 - al, by + 18, a, RVBA(255, 255, 255, 170), 15, 0);
    if (nb_onglets && ACTIF->titre[0]) texte(bx, EH - 130, ACTIF->titre, RVB(255, 255, 255), 22, 1);
}


/* ------------------- fenêtres de Honey-Dog (0.11) ------------------------ */
static void bouton_hd(zone *z, const char *t, couleur c1, couleur c2, int taille)
{
    gfx_ombre(toile, z->x, z->y + 2, z->l, z->h, 10, 6, RVBA(0, 0, 0, 90));
    gfx_rect_arrondi_degrade(toile, z->x, z->y, z->l, z->h, 10, c1, c2);
    gfx_degrade(toile, z->x + 3, z->y + 2, z->l - 6, z->h / 2, RVBA(255, 255, 255, 90), RVBA(255, 255, 255, 10));
    gfx_contour_arrondi(toile, z->x, z->y, z->l, z->h, 10, RVBA(255, 255, 255, 160));
    int l = gfx_largeur_vect(t, (float)taille, POIDS_GRAS);
    texte(z->x + (z->l - l) / 2, z->y + (z->h - taille) / 2 - 2, t, RVB(255, 255, 255), (float)taille, 1);
}

/* un paragraphe coupé aux espaces ; renvoie la hauteur occupée */
static int para(int x, int y, int lmax, const char *t, couleur c, float taille)
{
    char mot[256], ligne[1024] = "";
    int ly = y;
    const char *p = t;
    while (*p) {
        size_t k = 0;
        while (p[k] && p[k] != ' ' && k + 1 < sizeof mot) k++;
        chaine_copier(mot, p, k + 1);
        char essai[1100];
        snprintf(essai, sizeof essai, "%s%s%s", ligne, ligne[0] ? " " : "", mot);
        if (ligne[0] && gfx_largeur_vect(essai, taille, POIDS_NORMAL) > lmax) {
            texte(x, ly, ligne, c, taille, 0);
            ly += (int)taille + 7;
            chaine_copier(ligne, mot, sizeof ligne);
        } else chaine_copier(ligne, essai, sizeof ligne);
        p += k;
        while (*p == ' ') p++;
    }
    if (ligne[0]) { texte(x, ly, ligne, c, taille, 0); ly += (int)taille + 7; }
    return ly - y;
}

static void voile(void)
{
    for (int y = 0; y < EH; y++)
        for (int x = 0; x < EL; x++) {
            couleur *px = &toile->px[y * toile->l + x];
            unsigned r = (*px >> 16) & 255, g = (*px >> 8) & 255, b = *px & 255;
            *px = RVB(r * 2 / 5, g * 2 / 5, b * 2 / 5 + 10);
        }
}

static void dessiner_alerte(void)
{
    voile();
    int l = EL > 720 ? 620 : EL - 80, h = 360, x = (EL - l) / 2, y = (EH - h) / 2;
    gfx_ombre(toile, x, y, l, h, 40, 24, RVBA(0, 0, 0, 170));
    gfx_rect_arrondi_degrade(toile, x, y, l, h, 18, RVB(252, 246, 238), RVB(246, 231, 214));
    gfx_contour_arrondi(toile, x, y, l, h, 18, RVB(210, 150, 90));
    gfx_disque_degrade(toile, x + 52, y + 56, 26, RVB(240, 170, 60), RVB(200, 110, 20));
    texte(x + 44, y + 40, "!", RVB(255, 255, 255), 34, 1);
    const char *titre_a = alerte_score >= 70 ? "Attaque très dangereuse en cours"
                        : alerte_continues ? "Honey-Dog insiste : c'est dangereux" : "Honey-Dog a repéré un comportement suspect";
    texte(x + 92, y + 32, titre_a, RVB(150, 60, 10), 24, 1);
    int yy = y + 86;
    yy += para(x + 36, yy, l - 72,
               "Une page web essaie de faire des choses qu'un site normal ne fait jamais, dans la boîte du Navigateur.",
               RVB(70, 50, 35), 17) + 6;
    if (alerte_pourquoi[0]) yy += para(x + 36, yy, l - 72, alerte_pourquoi, RVB(110, 80, 55), 15) + 4;
    if (alerte_quoi[0]) {
        gfx_rect_arrondi(toile, x + 36, yy, l - 72, 46, 8, RVBA(0, 0, 0, 16));
        char c[400];
        gfx_texte_coupe(c, alerte_quoi, l - 96, 1);
        texte(x + 48, yy + 8, c, RVB(90, 60, 40), 14, 0);
        texte(x + 48, yy + 26, "Ton système, lui, n'a rien reçu : la boîte le protège.", RVB(120, 95, 70), 13, 0);
        yy += 56;
    }
    int bl = (l - 72 - 18) / 2, by = y + h - 64;
    z_detruire = (zone){ x + 36, by, bl, 46 };
    z_continuer = (zone){ x + 36 + bl + 18, by, bl, 46 };
    bouton_hd(&z_detruire, "Détruire la boîte et revenir à l'état d'avant", RVB(90, 170, 90), RVB(40, 120, 50), 15);
    if (alerte_confirmer) bouton_hd(&z_continuer, "Oui, vraiment continuer", RVB(200, 90, 60), RVB(150, 40, 25), 15);
    else bouton_hd(&z_continuer, "Continuer à mes risques", RVB(180, 180, 185), RVB(140, 140, 146), 15);
    texte(x + 36, by - 22, "Conseillé : détruire la boîte. Rien ne sera perdu d'important.", RVB(90, 140, 70), 13, 0);
}

static void dessiner_arret(void)
{
    voile();
    int l = EL > 720 ? 600 : EL - 80, h = 300, x = (EL - l) / 2, y = (EH - h) / 2;
    gfx_ombre(toile, x, y, l, h, 40, 24, RVBA(0, 0, 0, 170));
    gfx_rect_arrondi_degrade(toile, x, y, l, h, 18, RVB(238, 246, 240), RVB(214, 234, 222));
    gfx_contour_arrondi(toile, x, y, l, h, 18, RVB(90, 170, 110));
    gfx_disque_degrade(toile, x + 52, y + 56, 26, RVB(110, 200, 130), RVB(40, 140, 70));
    gfx_ligne(toile, x + 42, y + 56, x + 50, y + 65, 4, RVB(255, 255, 255));
    gfx_ligne(toile, x + 50, y + 65, x + 64, y + 46, 4, RVB(255, 255, 255));
    texte(x + 92, y + 40, "Nous avons arrêté l'attaque", RVB(30, 110, 60), 24, 1);
    int yy = y + 96;
    yy += para(x + 36, yy, l - 72, arret_message, RVB(40, 70, 50), 18) + 10;
    if (arret_site[0]) {
        char c[300];
        snprintf(c, sizeof c, "Le site « %s » a été bloqué.", arret_site);
        yy += para(x + 36, yy, l - 72, c, RVB(90, 110, 95), 15) + 2;
        yy += para(x + 36, yy, l - 72, "Tu peux le débloquer dans Paramètres → Sécurité → Bacs à sable.", RVB(110, 130, 115), 14);
    }
    z_relancer = (zone){ x + (l - 220) / 2, y + h - 64, 220, 46 };
    bouton_hd(&z_relancer, "D'accord", RVB(90, 170, 110), RVB(40, 130, 70), 16);
}

static void dessiner_choix(void)
{
    voile();
    int l = EL > 620 ? 520 : EL - 60, h = 420, x = (EL - l) / 2, y = (EH - h) / 2;
    gfx_ombre(toile, x, y, l, h, 40, 24, RVBA(0, 0, 0, 160));
    gfx_rect_arrondi_degrade(toile, x, y, l, h, 16, RVB(248, 250, 253), RVB(228, 236, 246));
    gfx_contour_arrondi(toile, x, y, l, h, 16, RVB(150, 175, 210));
    texte(x + 30, y + 24, choix_image ? "Remplacer l'image par…" : "Envoyer un fichier au site", RVB(40, 70, 120), 22, 1);
    para(x + 30, y + 58, l - 60, choix_image ?
         "Une image du dossier « Envoi » (PNG, JPEG, GIF, WebP). Elle ne remplace l'originale que sur ton écran, pour ce site." :
         "Dépose tes fichiers dans le dossier « Envoi » de tes gamelles. Le site ne voit rien d'autre.",
         RVB(90, 105, 130), 14);
    int ly = y + 110, lh = 34, vis = 6;
    z_liste = (zone){ x + 30, ly, l - 60, lh * vis };
    gfx_rect_arrondi(toile, x + 30, ly, l - 60, lh * vis, 8, RVB(255, 255, 255));
    gfx_contour_arrondi(toile, x + 30, ly, l - 60, lh * vis, 8, RVB(190, 200, 215));
    if (!choix_nb) texte(x + 46, ly + 14, "Le dossier Envoi est vide.", RVB(150, 155, 165), 15, 0);
    for (int i = choix_defile; i < choix_nb && i < choix_defile + vis; i++) {
        int yy = ly + (i - choix_defile) * lh;
        if (choix_pris[i]) gfx_rect_arrondi(toile, x + 34, yy + 2, l - 68, lh - 4, 6, RVBA(60, 140, 240, 40));
        gfx_rect_arrondi(toile, x + 44, yy + 10, 14, 14, 3, choix_pris[i] ? RVB(60, 140, 240) : RVB(255, 255, 255));
        gfx_contour_arrondi(toile, x + 44, yy + 10, 14, 14, 3, RVB(150, 160, 175));
        if (choix_pris[i]) { gfx_ligne(toile, x + 47, yy + 17, x + 50, yy + 20, 2, RVB(255, 255, 255)); gfx_ligne(toile, x + 50, yy + 20, x + 55, yy + 13, 2, RVB(255, 255, 255)); }
        char c[140];
        gfx_texte_coupe(c, choix_noms[i], l - 110, 1);
        texte(x + 68, yy + 8, c, RVB(40, 50, 70), 15, 0);
    }
    z_envoyer = (zone){ x + l - 30 - 150, y + h - 60, 150, 42 };
    z_annuler = (zone){ x + 30, y + h - 60, 130, 42 };
    bouton_hd(&z_envoyer, choix_image ? "Choisir" : "Envoyer", RVB(90, 160, 240), RVB(40, 110, 210), 16);
    bouton_hd(&z_annuler, "Annuler", RVB(185, 190, 198), RVB(150, 155, 164), 16);
}

static void ouvrir_service(int i)
{
    const service *s = &SERVICES[i];
    if (!s->disponible) {
        dire("Ce service exige Widevine (DRM de Google), réservé aux navigateurs sous contrat : indisponible");
        return;
    }
    if (!moteur_pret) { dire(erreur_moteur[0] ? erreur_moteur : "Le moteur démarre…"); return; }
    if (nb_onglets && ACTIF->image) { surface_detruire(ACTIF->image); ACTIF->image = NULL; }
    vue = VUE_PAGE;
    aller(s->adresse);
    journal("navigateur", "lecteur : %s", s->nom);
}

static void video_js(const char *js)
{
    char e[600];
    snprintf(e, sizeof e, "(()=>{const v=window.__puppyVideo&&window.__puppyVideo();if(!v)return;%s})()", js);
    evaluer(e, 1);
    osd_jusqua = maintenant + 3000;
    sale = 1;
}


/* ------------------------- scripts injectés (0.12) ------------------------ */
/* « Ne me pistez pas » : Global Privacy Control */
static const char *SCRIPT_GPC =
    "(()=>{try{Object.defineProperty(Navigator.prototype,'globalPrivacyControl',{get:()=>true});"
    "Object.defineProperty(Navigator.prototype,'doNotTrack',{get:()=>'1'})}catch(e){}})()";

/* Pas de vidéo ni de son qui démarre tout seul (tant qu'on n'a pas cliqué) */
static const char *SCRIPT_PAS_AUTO =
    "(()=>{const o=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){"
    "if(!(navigator.userActivation&&navigator.userActivation.hasBeenActive)){this.pause();"
    "return Promise.reject(new DOMException('lecture automatique coupée par PuppyOS','NotAllowedError'))}"
    "return o.apply(this,arguments)}})()";

/* Coffre : quand un formulaire avec un mot de passe part, on propose de
 * l'enregistrer. Jamais les champs de carte bancaire (autocomplete cc-*,
 * noms « card », « cvc »…) : ils sont ignorés ici ET refusés par l'enclave. */
static const char *SCRIPT_CAPTURE =
    "(()=>{if(window.top!==window)return;const carte=e=>/cc-|card|carte|cvc|cvv|iban|expir/i.test("
    "(e.autocomplete||'')+' '+(e.name||'')+' '+(e.id||''));let vu=0;const prendre=()=>{"
    "const p=[...document.querySelectorAll('input[type=password]')].find(e=>e.value&&!carte(e));if(!p)return;"
    "const tous=[...(p.form||document).querySelectorAll('input')];let u='';for(const e of tous){if(e===p)break;"
    "if(/^(text|email|tel)$/.test(e.type)&&e.value&&!carte(e))u=e.value}"
    "if(!u||vu===u+p.value||typeof puppyCoffre!=='function')return;vu=u+p.value;"
    "puppyCoffre(JSON.stringify({u:u.slice(0,120),p:p.value.slice(0,120),h:location.hostname}))};"
    "document.addEventListener('submit',prendre,true);"
    "document.addEventListener('click',e=>{const b=e.target&&e.target.closest&&e.target.closest("
    "'button,input[type=submit],[role=button]');if(b)setTimeout(prendre,0)},true);"
    "document.addEventListener('keydown',e=>{if(e.key==='Enter')setTimeout(prendre,0)},true)})()";

/* 2.1 : permissions (caméra, micro, position, notifications). La page
 * demande ; le Navigateur pose la question (ou applique le choix retenu
 * pour ce site) et répond par __puppyPerm. Rien n'est permis sans réponse. */
static const char *SCRIPT_PERMISSIONS =
    "(()=>{if(window.top!==window||window.__puppyPerm)return;const att=new Map();let n=0;"
    "Object.defineProperty(window,'__puppyPerm',{value:(id,ok)=>{const f=att.get(id);if(f){att.delete(id);f(!!ok)}}});"
    "const demander=t=>new Promise(r=>{if(typeof puppyPermission!=='function')return r(false);const id=++n;att.set(id,r);"
    "puppyPermission(JSON.stringify({t,id}))});"
    "const refus={code:1,message:'Refusé (PuppyOS)',PERMISSION_DENIED:1,POSITION_UNAVAILABLE:2,TIMEOUT:3};"
    "const G=navigator.geolocation;if(G){const gc=G.getCurrentPosition.bind(G),wp=G.watchPosition.bind(G);"
    "G.getCurrentPosition=function(s,e,o){demander(2).then(ok=>ok?gc(s,e,o):e&&e(refus))};"
    "G.watchPosition=function(s,e,o){demander(2).then(ok=>ok?wp(s,e,o):e&&e(refus));return 1}}"
    "const MD=navigator.mediaDevices;if(MD&&MD.getUserMedia){const gum=MD.getUserMedia.bind(MD);"
    "MD.getUserMedia=async c=>{c=c||{};if(c.video&&!(await demander(0)))throw new DOMException('Caméra refusée','NotAllowedError');"
    "if(c.audio&&!(await demander(1)))throw new DOMException('Micro refusé','NotAllowedError');return gum(c)}}"
    "if(window.Notification){Notification.requestPermission=async cb=>{const v=(await demander(3))?'granted':'denied';"
    "if(typeof cb==='function')cb(v);return v}}})()";

/* 2.1 : sonde de l'audit. Compte, sans rien bloquer, ce qu'un pisteur ou
 * un script piégé utilise : empreinte (canvas, WebGL, audio, polices,
 * matériel), WebAssembly, travailleurs, websockets, et un mot de passe
 * tapé qui partirait dans une requête. Lu seulement par l'audit, en local. */
static const char *SCRIPT_SONDE =
    "(()=>{if(window.__puppySonde)return;const S={canvas:0,webgl:0,audio:0,polices:0,plugins:0,ecran:0,batterie:0,materiel:0,"
    "wasm:0,workers:0,clavier:0,ws:[],fuite:[]};Object.defineProperty(window,'__puppySonde',{value:S});"
    "const env=(o,k,f)=>{try{const d=o&&o[k];if(typeof d!=='function')return;const w=function(){try{f.apply(this,arguments)}catch(e){}"
    "return d.apply(this,arguments)};w.toString=()=>d.toString();o[k]=w}catch(e){}};"
    "const get=(o,k,f)=>{try{const d=Object.getOwnPropertyDescriptor(o,k);if(!d||!d.get)return;"
    "Object.defineProperty(o,k,{get(){try{f()}catch(e){}return d.get.call(this)},configurable:true,enumerable:d.enumerable})}catch(e){}};"
    "env(HTMLCanvasElement.prototype,'toDataURL',()=>S.canvas++);env(HTMLCanvasElement.prototype,'toBlob',()=>S.canvas++);"
    "env(CanvasRenderingContext2D.prototype,'getImageData',()=>S.canvas++);env(CanvasRenderingContext2D.prototype,'measureText',()=>S.polices++);"
    "for(const W of[window.WebGLRenderingContext,window.WebGL2RenderingContext])if(W)env(W.prototype,'getParameter',p=>{if(p===37445||p===37446)S.webgl++});"
    "if(window.OfflineAudioContext)env(OfflineAudioContext.prototype,'startRendering',()=>S.audio++);"
    "if(window.BaseAudioContext)env(BaseAudioContext.prototype,'createDynamicsCompressor',()=>S.audio++);"
    "if(window.FontFaceSet)env(FontFaceSet.prototype,'check',()=>S.polices++);"
    "get(Navigator.prototype,'plugins',()=>S.plugins++);get(Navigator.prototype,'mimeTypes',()=>S.plugins++);"
    "get(Navigator.prototype,'hardwareConcurrency',()=>S.materiel++);get(Navigator.prototype,'deviceMemory',()=>S.materiel++);"
    "get(Screen.prototype,'colorDepth',()=>S.ecran++);get(Screen.prototype,'availWidth',()=>S.ecran++);"
    "env(Navigator.prototype,'getBattery',()=>S.batterie++);"
    "if(window.WebAssembly){env(WebAssembly,'instantiate',()=>S.wasm++);env(WebAssembly,'compile',()=>S.wasm++);"
    "env(WebAssembly,'instantiateStreaming',()=>S.wasm++)}"
    "env(EventTarget.prototype,'addEventListener',t=>{if(t==='keydown'||t==='keypress'||t==='keyup')S.clavier++});"
    "const prox=(k,f)=>{const C=window[k];if(!C)return;window[k]=new Proxy(C,{construct(t,a,n){try{f(a)}catch(e){}return Reflect.construct(t,a,n)}})};"
    "prox('Worker',()=>S.workers++);prox('SharedWorker',()=>S.workers++);prox('WebSocket',a=>{if(S.ws.length<20)S.ws.push(String(a[0]).slice(0,200))});"
    "const mdp=()=>[...document.querySelectorAll('input[type=password]')].map(e=>e.value).filter(v=>v&&v.length>=4);"
    "const corps=b=>{try{if(typeof b==='string')return b;if(b instanceof URLSearchParams)return b.toString();"
    "if(b instanceof FormData)return[...b.values()].filter(v=>typeof v==='string').join('&')}catch(e){}return''};"
    "const verif=(u,b)=>{const m=mdp();if(!m.length)return;const c=corps(b);if(!c)return;"
    "for(const v of m)if(c.includes(v)||c.includes(encodeURIComponent(v))){let x;try{x=new URL(u,location.href)}catch(e){return}"
    "if(S.fuite.length<10)S.fuite.push({h:x.hostname,c:x.protocol==='https:'||x.protocol==='wss:'?1:0});break}};"
    "env(window,'fetch',(i,o)=>verif(typeof i==='string'?i:(i&&i.url)||'',o&&o.body));"
    "env(XMLHttpRequest.prototype,'open',function(m,u){this.__pu=u});env(XMLHttpRequest.prototype,'send',function(b){verif(this.__pu||'',b)});"
    "env(Navigator.prototype,'sendBeacon',(u,d)=>verif(u,d));env(WebSocket.prototype,'send',function(d){verif(this.url,d)})})()";

/* Mode lecture : le texte principal, sans rien autour */
static const char *JS_LECTURE =
    "(()=>{let best=null,s=0;for(const e of document.querySelectorAll('article,main,[role=main],div,section')){"
    "const t=[...e.querySelectorAll('p')].reduce((a,p)=>a+p.textContent.length,0);if(t>s){s=t;best=e}}"
    "if(!best)return 0;const h=document.querySelector('h1');const c=best.cloneNode(true);"
    "c.querySelectorAll('script,style,iframe,nav,aside,form,button,[class*=ad],[id*=ad]').forEach(x=>x.remove());"
    "document.body.innerHTML='';const d=document.createElement('div');d.id='puppy-lecture';const S=%d;"
    "d.style.cssText='max-width:720px;margin:40px auto;font:20px/1.7 Georgia,serif;padding:30px 44px;border-radius:12px;'"
    "+(S?'color:#e8e2d4;background:#22201c':'color:#222;background:#fbf8f1');"
    "if(h){const t=document.createElement('h1');t.textContent=h.textContent;d.appendChild(t)}d.appendChild(c);"
    "document.body.style.background=S?'#141311':'#ece6d8';document.body.appendChild(d);"
    "if(S)c.querySelectorAll('a').forEach(a=>a.style.color='#9ec1ff');"
    "c.querySelectorAll('img').forEach(i=>{i.style.maxWidth='100%%';i.style.height='auto'});return 1})()";

/* --------------------------- outils divers -------------------------------- */
static int adresse_locale(const char *h)
{
    if (!strcmp(h, "localhost") || !strchr(h, '.') || finit_par(h, ".local") || finit_par(h, ".lan")) return 1;
    int a, b;
    if (sscanf(h, "%d.%d.", &a, &b) == 2)
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31) || (a == 169 && b == 254);
    return 0;
}

/* enlève les paramètres de pistage d'une adresse ; 1 si elle a changé */
static int nettoyer_lien(const char *u, char *d, size_t n)
{
    static const char *PISTEURS[] = { "utm_", "fbclid", "gclid", "dclid", "msclkid", "mc_eid", "mc_cid", "igshid",
                                      "yclid", "_hsenc", "_hsmi", "mkt_tok", "oly_anon_id", "oly_enc_id", "vero_id",
                                      "wickedid", "twclid", "ttclid", "srsltid", NULL };
    const char *q = strchr(u, '?');
    if (!q) return 0;
    const char *diese = strchr(q, '#');
    size_t base = (size_t)(q - u);
    if (base + 2 >= n) return 0;
    memcpy(d, u, base);
    size_t o = base;
    int change = 0, premier = 1;
    const char *p = q + 1, *fin = diese ? diese : q + strlen(q);
    while (p < fin) {
        const char *amp = memchr(p, '&', (size_t)(fin - p));
        if (!amp) amp = fin;
        int pisteur = 0;
        for (int i = 0; PISTEURS[i]; i++) if (!strncmp(p, PISTEURS[i], strlen(PISTEURS[i]))) pisteur = 1;
        if (pisteur) change = 1;
        else if (amp > p && o + (size_t)(amp - p) + 2 < n) {
            d[o++] = premier ? '?' : '&';
            memcpy(d + o, p, (size_t)(amp - p));
            o += (size_t)(amp - p);
            premier = 0;
        }
        p = amp + (amp < fin);
    }
    if (diese && o + strlen(diese) + 1 < n) { memcpy(d + o, diese, strlen(diese)); o += strlen(diese); }
    d[o] = 0;
    return change;
}

static onglet *onglet_session(const char *s)
{
    if (!s[0]) return NULL;
    for (int i = 0; i < nb_onglets; i++) if (onglets[i].vivant && !strcmp(onglets[i].session, s)) return &onglets[i];
    return NULL;
}

static int onglet_cible(const char *t)
{
    for (int i = 0; i < nb_onglets; i++) if (onglets[i].vivant && onglets[i].cible[0] && !strcmp(onglets[i].cible, t)) return i;
    return -1;
}

/* mémoire de la machine (en Mo), pour expliquer un plantage */
static void memoire(long *libre, long *totale)
{
    *libre = *totale = 0;
    FILE *f = fopen("/proc/meminfo", "r");
    if (!f) return;
    char l[128];
    long v;
    while (fgets(l, sizeof l, f)) {
        if (sscanf(l, "MemTotal: %ld", &v) == 1) *totale = v / 1024;
        else if (sscanf(l, "MemAvailable: %ld", &v) == 1) *libre = v / 1024;
    }
    fclose(f);
}

/* 0.12.1 : pourquoi la page s'est arrêtée (statut et code donnés par le moteur) */
static void cause_plantage(onglet *o, const char *statut, int code)
{
    long libre, totale;
    memoire(&libre, &totale);
    const char *c;
    if (!strcmp(statut, "oom") || (code == 9 && totale && libre < totale / 8))
        c = "Mémoire pleine : le système a arrêté la page pour rester fluide.";
    else if (code == 31)
        c = "Le second mur de Honey-Dog a arrêté la page (geste interdit).";
    else if (code == 9)
        c = "La page a été arrêtée de force (mémoire ou Honey-Dog).";
    else if (code == 5 || code == 4 || code == 133 || code == 132)
        c = "Le moteur JavaScript a manqué de mémoire ou a refusé un calcul.";
    else if (code == 11 || code == 7 || code == 139)
        c = "Bogue du moteur Chromium sur ce site.";
    else c = "Le moteur a arrêté cette page.";
    snprintf(o->erreur_texte, sizeof o->erreur_texte, "%s", c);
    snprintf(o->erreur_detail, sizeof o->erreur_detail, "statut %s, code %d, mémoire libre %ld Mo sur %ld Mo",
             statut[0] ? statut : "?", code, libre, totale);
    journal("navigateur", "page plantée : %s (%s)", o->hote, o->erreur_detail);
}

static void evenement_moteur(char *m, void *d);

/* commande sur un onglet, puis attente de la réponse (les autres messages sont traités) */
static char *demander(const char *session, const char *methode, const char *params, int delai)
{
    int id = cdp_envoyer_a(&moteur, session, methode, params);
    if (id < 0) return NULL;
    return cdp_attendre(&moteur, id, delai, evenement_moteur, NULL);
}

static void metrique(onglet *o)
{
    if (!o->session[0]) return;
    char p[300];
    double f = o->zoom / 100.0;
    const identite *id = &IDENTITES[R.ua < NB_IDENTITES ? R.ua : 0];
    snprintf(p, sizeof p, "{\"width\":%d,\"height\":%d,\"deviceScaleFactor\":%.3f,\"mobile\":%s}",
             (int)(PL / f), (int)(PH / f), f, id->mobile && !streaming ? "true" : "false");
    cdp_envoyer_a(&moteur, o->session, "Emulation.setDeviceMetricsOverride", p);
}

static void identite_onglet(onglet *o)
{
    if (!o->session[0]) return;
    char p[2000];
    identite_json(R.ua, R.ua_perso, p, sizeof p);
    cdp_envoyer_a(&moteur, o->session, "Emulation.setUserAgentOverride", p);
}

static void reglages_onglet(onglet *o)
{
    if (!o->session[0]) return;
    cdp_envoyer_a(&moteur, o->session, "Emulation.setAutoDarkModeOverride", R.sombre ? "{\"enabled\":true}" : "{}");
    char p[200];
    snprintf(p, sizeof p, "{\"enableThirdPartyCookieRestriction\":%s,\"disableThirdPartyCookieMetadata\":false,"
             "\"disableThirdPartyCookieHeuristics\":%s}", R.tiers ? "false" : "true", R.tiers ? "false" : "true");
    cdp_envoyer_a(&moteur, o->session, "Network.setCookieControls", p);
}

/* les scripts posés sur chaque nouvelle page de l'onglet (et, 0.12.2, de
 * chacun de ses cadres venus d'un autre site : cadre = 1) */
static void scripts_session(const char *session, int prive, int cadre)
{
    const char *liste[8];
    int n = 0;
    if (streaming) liste[n++] = SCRIPT_LECTEUR;
    else {
        liste[n++] = SCRIPT_SONDE;
        if (!cadre) liste[n++] = SCRIPT_PERMISSIONS;
        if (R.bannieres) liste[n++] = SCRIPT_COOKIES;
        if (R.gpc) liste[n++] = SCRIPT_GPC;
        if (!R.lecture_auto) liste[n++] = SCRIPT_PAS_AUTO;
        if (R.coffre && !prive && !cadre) liste[n++] = SCRIPT_CAPTURE;
    }
    for (int i = 0; i < n; i++) {
        size_t l = strlen(liste[i]) * 2 + 64;
        char *e = malloc(l), *q = malloc(l + 64);
        if (e && q) {
            json_echapper(e, l, liste[i]);
            snprintf(q, l + 64, "{\"source\":%s}", e);
            cdp_envoyer_a(&moteur, session, "Page.addScriptToEvaluateOnNewDocument", q);
        }
        free(e);
        free(q);
    }
    /* extensions : jamais dans les onglets privés */
    for (int i = 0; !prive && !streaming && i < nb_extensions; i++) {
        if (!extensions[i].actif) continue;
        char *src = extension_source(i);
        if (!src) continue;
        char motifs[2000] = "[";
        for (int k = 0; k < extensions[i].nb; k++) {
            char e[420];
            json_echapper(e, sizeof e, extensions[i].motifs[k]);
            size_t lm = strlen(motifs);
            snprintf(motifs + lm, sizeof motifs - lm, "%s%s", k ? "," : "", e);
        }
        size_t lm = strlen(motifs);
        snprintf(motifs + lm, sizeof motifs - lm, "]");
        /* petite réserve « GM_ » pour les scripts Greasemonkey courants */
        size_t ls = strlen(src) + 4096;
        char *js = malloc(ls);
        if (js) {
            snprintf(js, ls,
                "(()=>{const m=%s;const g=(p,u)=>new RegExp('^'+p.replace(/[.+?^${}()|[\\]\\\\]/g,'\\\\$&').replace(/\\*/g,'.*')+'$').test(u);"
                "if(!m.some(p=>g(p,location.href)))return;const GM_addStyle=c=>{const s=document.createElement('style');"
                "s.textContent=c;(document.head||document.documentElement).appendChild(s);return s};"
                "const GM_getValue=(k,d)=>{try{const v=localStorage.getItem('puppy-gm-'+k);return v===null?d:JSON.parse(v)}catch(e){return d}};"
                "const GM_setValue=(k,v)=>{try{localStorage.setItem('puppy-gm-'+k,JSON.stringify(v))}catch(e){}};"
                "const GM_info={script:{name:'ext'}};const unsafeWindow=window;try{\n%s\n}catch(e){console.error(e)}})()",
                motifs, src);
            size_t l = strlen(js) * 2 + 64;
            char *e = malloc(l), *q = malloc(l + 64);
            if (e && q) {
                json_echapper(e, l, js);
                snprintf(q, l + 64, "{\"source\":%s}", e);
                cdp_envoyer_a(&moteur, session, "Page.addScriptToEvaluateOnNewDocument", q);
            }
            free(e);
            free(q);
            free(js);
        }
        free(src);
    }
}

/* 2.1 : un script posé sur les pages à venir, dont on garde le numéro
 * pour pouvoir le retirer (langage, thème, images d'un site) */
static void script_poser(const char *session, const char *js, char *id, size_t nid)
{
    if (id[0]) {
        char p[96];
        snprintf(p, sizeof p, "{\"identifier\":\"%s\"}", id);
        cdp_envoyer_a(&moteur, session, "Page.removeScriptToEvaluateOnNewDocument", p);
        id[0] = 0;
    }
    if (!js) return;
    size_t l = strlen(js) * 2 + 64;
    char *e = malloc(l), *q = malloc(l + 64);
    if (e && q) {
        json_echapper(e, l, js);
        snprintf(q, l + 64, "{\"source\":%s}", e);
        char *r = demander(session, "Page.addScriptToEvaluateOnNewDocument", q, 5000);
        if (r) json_chaine(r, "result.identifier", id, nid);
        free(r);
    }
    free(e);
    free(q);
}

/* langage et thème Puppyplay : posés pour les pages à venir, et appliqués
 * tout de suite à la page affichée (affichage seulement) */
static void evaluer_session(const char *session, const char *js);
static void scripts_perso(onglet *o, int maintenant_aussi)
{
    if (streaming || !o->session[0]) return;
    char *lg = script_langage();
    const char *th = script_theme_puppyplay();
    script_poser(o->session, lg, o->id_langage, sizeof o->id_langage);
    script_poser(o->session, th, o->id_theme, sizeof o->id_theme);
    if (maintenant_aussi && !o->interne) {
        if (lg) evaluer_session(o->session, lg);
        if (th) evaluer_session(o->session, th);
    }
    free(lg);
}

void perso_appliquer(void)
{
    for (int i = 0; i < nb_onglets; i++) if (onglets[i].vivant && onglets[i].session[0]) scripts_perso(&onglets[i], 1);
}

static void scripts_onglet(onglet *o) { scripts_session(o->session, o->prive, 0); scripts_perso(o, 0); }

/* 0.12.2 : les cadres d'autres sites (publicités, captchas, bannières de
 * consentement…) vivent dans leur propre processus : sans cela ils
 * n'avaient ni notre identité (User-Agent) ni nos scripts. */
static const char *AUTO_CADRES =
    "{\"autoAttach\":true,\"waitForDebuggerOnStart\":true,\"flatten\":true,"
    "\"filter\":[{\"type\":\"iframe\",\"exclude\":false},{\"exclude\":true}]}";

#define CADRES_MAX 96
static struct { char session[64]; int onglet; } cadres[CADRES_MAX];

static int onglet_de_session(const char *s)
{
    onglet *o = onglet_session(s);
    if (o) return (int)(o - onglets);
    for (int i = 0; i < CADRES_MAX; i++) if (cadres[i].session[0] && !strcmp(cadres[i].session, s)) return cadres[i].onglet;
    return -1;
}

static void cadre_attache(const char *parent, const char *m)
{
    char s[64] = "", type[24] = "";
    json_chaine(m, "params.sessionId", s, sizeof s);
    json_chaine(m, "params.targetInfo.type", type, sizeof type);
    int k = onglet_de_session(parent);
    if (!s[0]) return;
    if (k >= 0 && !strcmp(type, "iframe")) {
        for (int i = 0; i < CADRES_MAX; i++)
            if (!cadres[i].session[0]) { chaine_copier(cadres[i].session, s, sizeof cadres[i].session); cadres[i].onglet = k; break; }
        char p[2000];
        if (!streaming) identite_json(R.ua, R.ua_perso, p, sizeof p);
        else identite_json(0, NULL, p, sizeof p);
        cdp_envoyer_a(&moteur, s, "Emulation.setUserAgentOverride", p);
        scripts_session(s, onglets[k].prive, 1);
        cdp_envoyer_a(&moteur, s, "Target.setAutoAttach", AUTO_CADRES);
    }
    cdp_envoyer_a(&moteur, s, "Runtime.runIfWaitingForDebugger", "{}");
}

static void cadre_detache(const char *m)
{
    char s[64] = "";
    json_chaine(m, "params.sessionId", s, sizeof s);
    for (int i = 0; s[0] && i < CADRES_MAX; i++) if (!strcmp(cadres[i].session, s)) cadres[i].session[0] = 0;
}

static void fetch_onglet(onglet *o)
{
    /* 2.2 : les suggestions et recherches de Google faites par script (pas
     * des pages) passent aussi par ici, pour ne rien laisser partir */
#define MOTIFS_GOOGLE ",{\"urlPattern\":\"*://www.google.*/complete/search*\"},{\"urlPattern\":\"*://google.*/complete/search*\"}," \
                      "{\"urlPattern\":\"*://www.google.*/search?*\"},{\"urlPattern\":\"*://google.*/search?*\"}"
    if (streaming)
        cdp_envoyer_a(&moteur, o->session, "Fetch.enable", "{\"patterns\":[{\"urlPattern\":\"*\",\"resourceType\":\"Document\"}]}");
    else if (o->prive)
        cdp_envoyer_a(&moteur, o->session, "Fetch.enable",
                      google_neutralise ? "{\"patterns\":[{\"urlPattern\":\"*\",\"resourceType\":\"Document\"}" MOTIFS_GOOGLE "]}"
                                        : "{\"patterns\":[{\"urlPattern\":\"*\",\"resourceType\":\"Document\"}]}");
    else
        cdp_envoyer_a(&moteur, o->session, "Fetch.enable", google_neutralise ?
                      "{\"patterns\":[{\"urlPattern\":\"*\",\"resourceType\":\"Document\"},"
                      "{\"urlPattern\":\"*\",\"resourceType\":\"Document\",\"requestStage\":\"Response\"},"
                      "{\"urlPattern\":\"*.user.js\",\"requestStage\":\"Response\"}" MOTIFS_GOOGLE "]}" :
                      "{\"patterns\":[{\"urlPattern\":\"*\",\"resourceType\":\"Document\"},"
                      "{\"urlPattern\":\"*\",\"resourceType\":\"Document\",\"requestStage\":\"Response\"},"
                      "{\"urlPattern\":\"*.user.js\",\"requestStage\":\"Response\"}]}");
}

/* tout ce qu'un onglet neuf doit savoir */
static void configurer_onglet(onglet *o)
{
    const char *s = o->session;
    cdp_envoyer_a(&moteur, s, "Page.enable", "{}");
    cdp_envoyer_a(&moteur, s, "Runtime.enable", "{}");
    if (!streaming && !o->prive) cdp_envoyer_a(&moteur, s, "Runtime.addBinding", "{\"name\":\"puppyCoffre\"}");
    if (!streaming) {
        /* 2.1 : permissions, et ce que la barre d'adresse et l'audit montrent
         * (pisteurs, certificat, redirections, websockets) */
        cdp_envoyer_a(&moteur, s, "Runtime.addBinding", "{\"name\":\"puppyPermission\"}");
        cdp_envoyer_a(&moteur, s, "Network.enable", "{\"maxTotalBufferSize\":0,\"maxResourceBufferSize\":0}");
    }
    metrique(o);
    if (!streaming) identite_onglet(o);
    else {
        char p[2000];
        identite_json(0, NULL, p, sizeof p);
        cdp_envoyer_a(&moteur, s, "Emulation.setUserAgentOverride", p);
    }
    scripts_onglet(o);
    fetch_onglet(o);
    cdp_envoyer_a(&moteur, s, "Target.setAutoAttach", AUTO_CADRES);
    if (!streaming) {
        reglages_onglet(o);
        cdp_envoyer_a(&moteur, s, "Page.setInterceptFileChooserDialog", "{\"enabled\":true}");
        cdp_envoyer_a(&moteur, s, "Input.setInterceptDrags", "{\"enabled\":true}");
    }
}

static void screencast(onglet *o, int marche)
{
    if (!o->session[0]) return;
    if (marche) {
        char p[200];
        snprintf(p, sizeof p, "{\"format\":\"jpeg\",\"quality\":82,\"maxWidth\":%d,\"maxHeight\":%d,\"everyNthFrame\":1}", PL, PH);
        cdp_envoyer_a(&moteur, o->session, "Page.startScreencast", p);
    } else cdp_envoyer_a(&moteur, o->session, "Page.stopScreencast", "{}");
}

/* un contexte de navigation à part : privé (jetable) ou derrière le VPN */
static int creer_contexte(char *dst, size_t n, int avec_vpn)
{
    char p[400] = "{\"disposeOnDetach\":false";
    if (avec_vpn && R.vpn && R.vpn_hote[0]) {
        static const char *SCH[] = { "", "socks5", "http", "https" };
        size_t l = strlen(p);
        snprintf(p + l, sizeof p - l, ",\"proxyServer\":\"%s://%s:%d\",\"proxyBypassList\":\"<-loopback>\"",
                 SCH[R.vpn], R.vpn_hote, R.vpn_port);
    }
    strcat(p, "}");
    char *r = demander(NULL, "Target.createBrowserContext", p, 10000);
    int ok = r && json_chaine(r, "result.browserContextId", dst, n);
    free(r);
    return ok ? 0 : -1;
}

/* attache un onglet à une cible Chromium existante */
static int attacher(onglet *o, const char *cible)
{
    chaine_copier(o->cible, cible, sizeof o->cible);
    char p[200];
    snprintf(p, sizeof p, "{\"targetId\":\"%s\",\"flatten\":true}", cible);
    char *r = demander(NULL, "Target.attachToTarget", p, 15000);
    int ok = r && json_chaine(r, "result.sessionId", o->session, sizeof o->session);
    free(r);
    if (!ok) return -1;
    configurer_onglet(o);
    return 0;
}

static int nouvelle_place(int prive)
{
    if (nb_onglets >= ONGLETS_MAX) { dire("12 onglets au plus : ferme un onglet d'abord"); return -1; }
    onglet *o = &onglets[nb_onglets];
    memset(o, 0, sizeof *o);
    o->vivant = 1;
    o->prive = prive;
    o->zoom = R.zoom;
    o->id_nav = -1;
    return nb_onglets++;
}

int onglet_nouveau(const char *u, int activer, int prive)
{
    if (!moteur_pret) { dire(erreur_moteur[0] ? erreur_moteur : "Le moteur démarre…"); return -1; }
    const char *ctx = NULL;
    if (prive) {
        if (!contexte_prive[0] && creer_contexte(contexte_prive, sizeof contexte_prive, 1) < 0) { dire("Navigation privée impossible"); return -1; }
        ctx = contexte_prive;
    } else if (R.vpn && R.vpn_hote[0]) {
        if (!contexte_vpn[0] && creer_contexte(contexte_vpn, sizeof contexte_vpn, 1) < 0) { dire("VPN : contexte impossible"); return -1; }
        ctx = contexte_vpn;
    }
    int i = nouvelle_place(prive);
    if (i < 0) return -1;
    char p[200];
    if (ctx) snprintf(p, sizeof p, "{\"url\":\"about:blank\",\"browserContextId\":\"%s\"}", ctx);
    else snprintf(p, sizeof p, "{\"url\":\"about:blank\"}");
    char *r = demander(NULL, "Target.createTarget", p, 30000);
    char cible[64] = "";
    if (!r || !json_chaine(r, "result.targetId", cible, sizeof cible) || attacher(&onglets[i], cible) < 0) {
        free(r);
        onglets[i].vivant = 0;
        nb_onglets--;
        dire("Impossible d'ouvrir un onglet");
        return -1;
    }
    free(r);
    if (activer) onglet_activer(i);
    if (u && u[0]) aller_onglet(i, u);
    else if (activer) barre_saisie_activer();
    return i;
}

int onglet_interne(int quoi, int activer)
{
    for (int i = 0; i < nb_onglets; i++)
        if (onglets[i].interne == quoi) { if (activer) onglet_activer(i); return i; }
    int i = nouvelle_place(0);
    if (i < 0) return -1;
    onglet *o = &onglets[i];
    o->interne = quoi;
    static const char *NOMS[] = { "", "parametres", "historique", "favoris", "mots-de-passe", "extensions", "aide", "source" };
    static const char *TITRES[] = { "", "Paramètres", "Historique", "Favoris", "Mots de passe", "Extensions", "Aide et lexique", "Code source" };
    snprintf(o->url, sizeof o->url, "puppy://%s", NOMS[quoi]);
    chaine_copier(o->titre, TITRES[quoi], sizeof o->titre);
    if (activer) onglet_activer(i);
    return i;
}

void onglet_activer(int i)
{
    if (i < 0 || i >= nb_onglets) return;
    if (i != actif && actif < nb_onglets) screencast(&onglets[actif], 0);
    actif = i;
    chaine_copier(moteur.session, onglets[i].session, sizeof moteur.session);
    screencast(&onglets[i], 1);
    if (fen) pf_titre(fen, onglets[i].titre[0] ? onglets[i].titre : "Navigateur");
    sale = 1;
}

void onglet_fermer(int i)
{
    if (i < 0 || i >= nb_onglets) return;
    onglet *o = &onglets[i];
    if (o->cible[0]) {
        char p[120];
        snprintf(p, sizeof p, "{\"targetId\":\"%s\"}", o->cible);
        cdp_envoyer(&moteur, "Target.closeTarget", p, 0);
    }
    if (o->image) surface_detruire(o->image);
    memmove(&onglets[i], &onglets[i + 1], sizeof(onglet) * (size_t)(nb_onglets - i - 1));
    nb_onglets--;
    memset(&onglets[nb_onglets], 0, sizeof(onglet));
    /* plus aucun onglet privé : le contexte jetable part avec ses cookies */
    int prives = 0;
    for (int k = 0; k < nb_onglets; k++) prives += onglets[k].prive;
    if (!prives && contexte_prive[0]) {
        char p[120];
        snprintf(p, sizeof p, "{\"browserContextId\":\"%s\"}", contexte_prive);
        cdp_envoyer(&moteur, "Target.disposeBrowserContext", p, 0);
        contexte_prive[0] = 0;
    }
    if (!nb_onglets) {
        if (streaming) { quitter = 1; return; }
        onglet_nouveau(R.accueil, 1, 0);
        return;
    }
    if (actif > i || actif >= nb_onglets) actif--;
    if (actif < 0) actif = 0;
    actif = actif >= nb_onglets ? nb_onglets - 1 : actif;
    int a = actif;
    actif = -1;
    onglet_activer(a);
}

void onglet_deplacer(int de, int vers)
{
    if (de < 0 || de >= nb_onglets || vers < 0 || vers >= nb_onglets || de == vers) return;
    onglet o = onglets[de];
    if (de < vers) memmove(&onglets[de], &onglets[de + 1], sizeof(onglet) * (size_t)(vers - de));
    else memmove(&onglets[vers + 1], &onglets[vers], sizeof(onglet) * (size_t)(de - vers));
    onglets[vers] = o;
    if (actif == de) actif = vers;
    else if (de < actif && vers >= actif) actif--;
    else if (de > actif && vers <= actif) actif++;
    sale = 1;
}

/* ------------------------------ navigation -------------------------------- */
/* 0.12.2 : « puppy://test-plantage » tapé dans la barre plante exprès, pour
 * voir la fenêtre de plantage du système (une page web ne peut pas y aller) */
static volatile unsigned long adresse_nulle;
__attribute__((noinline)) void plantage_volontaire(void)
{
    *(volatile int *)adresse_nulle = 1;
}

void aller_onglet(int i, const char *u)
{
    onglet *o = &onglets[i];
    if (!strcmp(u, "puppy://test-plantage")) plantage_volontaire();
    if (!strncmp(u, "puppy://", 8)) {                 /* pages internes */
        static const char *N[] = { "", "parametres", "historique", "favoris", "mots-de-passe", "extensions", "aide" };
        for (int k = 1; k <= NB_PAGES_MENU; k++) if (!strcmp(u + 8, N[k])) { onglet_interne(k, 1); return; }
        dire("Page interne inconnue");
        return;
    }
    if (o->interne) {                                 /* une page interne devient un vrai onglet */
        o->interne = 0;
        o->defile = 0;
        char *r = demander(NULL, "Target.createTarget", R.vpn && contexte_vpn[0] ?
                           (snprintf(o->erreur_url, sizeof o->erreur_url, "{\"url\":\"about:blank\",\"browserContextId\":\"%s\"}", contexte_vpn), o->erreur_url)
                           : "{\"url\":\"about:blank\"}", 30000);
        char cible[64] = "";
        if (!r || !json_chaine(r, "result.targetId", cible, sizeof cible) || attacher(o, cible) < 0) { free(r); dire("Onglet impossible"); return; }
        free(r);
        if (i == actif) { onglet_activer(i); }
    }
    char hb[256] = "";
    if (hote_de(u, hb, sizeof hb) && site_bloque(hb)) {
        char t[300];
        snprintf(t, sizeof t, "%s est bloqué par Honey-Dog (Paramètres → Sécurité → Bacs à sable)", hb);
        dire(t);
    }
    if (i == actif) dire_honeydog(u);
    char e[1200], p[1300];
    json_echapper(e, sizeof e, u);
    snprintf(p, sizeof p, "{\"url\":%s}", e);
    o->id_nav = cdp_envoyer_a(&moteur, o->session, "Page.navigate", p);
    o->chargement = 1;
    o->erreur = 0;
    o->depuis = maintenant;
    chaine_copier(o->url, u, sizeof o->url);
    sale = 1;
}

void aller(const char *u) { if (nb_onglets) aller_onglet(actif, u); }

void valider_saisie(const char *s)
{
    char u[1100];
    while (*s == ' ') s++;
    if (!*s) return;
    if (!strncmp(s, "puppy://", 8)) { aller(s); return; }
    if (strstr(s, "://")) chaine_copier(u, s, sizeof u);
    else if (!strchr(s, ' ') && (strchr(s, '.') || !strncmp(s, "localhost", 9))) snprintf(u, sizeof u, "https://%s", s);
    else if (moteur_est_google() && google_actif()) {
        /* 2.2 : Google Recherche indisponible : les mots partent vers
         * DuckDuckGo, aucun n'est envoyé à Google */
        adresse_duckduckgo(s, u, sizeof u);
        google_signaler_redirection();
        journal("navigateur", "recherche Google redirigée vers DuckDuckGo (barre d'adresse)");
    } else {
        char q[1024];
        encoder_recherche(s, q, sizeof q);
        snprintf(u, sizeof u, "%s%s", MOTEURS_RECHERCHE[R.recherche].adresse, q);
        if (moteur_est_google()) google_avertir_sans_redirection();
    }
    /* jamais d'adresses locales du système (file:, chrome:, javascript:…) */
    if (strncmp(u, "https://", 8) && strncmp(u, "http://", 7)) { dire("Seules les adresses web (http, https) sont permises"); return; }
    aller(u);
}

void recharger(void)
{
    onglet *o = ACTIF;
    if (o->interne) { sale = 1; return; }
    if (o->erreur && o->erreur_url[0]) { aller(o->erreur_url); return; }
    cdp_envoyer_a(&moteur, o->session, "Page.reload", "{}");
}

/* précédent / suivant par l'historique de l'onglet (marche même sans JavaScript) */
static void histoire(int sens)
{
    onglet *o = ACTIF;
    if (o->interne || !o->session[0]) return;
    char *r = demander(o->session, "Page.getNavigationHistory", "{}", 5000);
    if (!r) return;
    double cur = -1;
    json_nombre(r, "result.currentIndex", &cur);
    const char *e = json_element(r, "result.entries", (int)cur + sens);
    double id;
    if (e && json_nombre(e, "id", &id)) {
        char p[64];
        snprintf(p, sizeof p, "{\"entryId\":%d}", (int)id);
        cdp_envoyer_a(&moteur, o->session, "Page.navigateToHistoryEntry", p);
        o->chargement = 1;
        o->erreur = 0;
    }
    free(r);
}
void precedent(void) { histoire(-1); }
void suivant(void) { histoire(1); }

void appliquer_identite(void)
{
    for (int i = 0; i < nb_onglets; i++) { identite_onglet(&onglets[i]); metrique(&onglets[i]); }
}

void appliquer_reglages(void)
{
    int py = H_ONGLETS + H_OUTILS + (R.barre_favoris ? H_FAVORIS : 0);
    if (py != PY) {
        PY = py;
        PH = EH - PY;
        for (int i = 0; i < nb_onglets; i++) metrique(&onglets[i]);
        if (nb_onglets) screencast(ACTIF, 1);
    }
    for (int i = 0; i < nb_onglets; i++) reglages_onglet(&onglets[i]);
    sale = 1;
}

void zoom_changer(int delta)
{
    onglet *o = ACTIF;
    static const int PAS[] = { 33, 50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300 };
    int n = (int)(sizeof PAS / sizeof PAS[0]), k = 6;
    for (int i = 0; i < n; i++) if (PAS[i] <= o->zoom) k = i;
    if (delta == 0) o->zoom = 100;
    else { k += delta > 0 ? 1 : -1; if (k < 0) k = 0; if (k >= n) k = n - 1; o->zoom = PAS[k]; }
    metrique(o);
    char t[64];
    snprintf(t, sizeof t, "Zoom : %d %%", o->zoom);
    dire(t);
}

void rechercher_page(const char *t, int arriere)
{
    char e[600], js[800];
    json_echapper(e, sizeof e, t);
    snprintf(js, sizeof js, "window.find(%s,false,%s,true,false,false,false)", e, arriere ? "true" : "false");
    char p[1800], ej[1700];
    json_echapper(ej, sizeof ej, js);
    snprintf(p, sizeof p, "{\"expression\":%s,\"returnByValue\":true}", ej);
    char *r = demander(ACTIF->session, "Runtime.evaluate", p, 3000);
    int trouve = 0;
    if (r) json_booleen(r, "result.result.value", &trouve);
    free(r);
    if (!trouve) dire("Introuvable dans la page");
}

/* 2.1 : compatible avec le mode sombre (couleurs du lecteur) et le langage
 * Puppyplay (son observateur traduit le texte posé par le lecteur) */
void mode_lecture(void)
{
    char js[4096];
    snprintf(js, sizeof js, JS_LECTURE, R.sombre ? 1 : 0);
    evaluer(js, 1);
}

/* donne un fichier au moteur comme un téléchargement : il arrive dans le
 * volume Téléchargements (et passe par Flair), comme tout le reste */
static void telecharger_b64(const char *nom, const char *mime, const char *b64)
{
    size_t n = strlen(b64) + 600;
    char *js = malloc(n);
    if (!js) return;
    snprintf(js, n, "(()=>{const a=document.createElement('a');a.href='data:%s;base64,%s';a.download='%s';"
             "document.documentElement.appendChild(a);a.click();a.remove();return 1})()", mime, b64, nom);
    evaluer(js, 1);
    free(js);
}

void imprimer_pdf(void)
{
    onglet *o = ACTIF;
    if (o->interne) return;
    dire("Fabrication du PDF…");
    char *r = demander(o->session, "Page.printToPDF", "{\"printBackground\":true,\"preferCSSPageSize\":true}", 60000);
    char *b = r ? json_chaine_alloc(r, "result.data", NULL) : NULL;
    if (b) { telecharger_b64("page.pdf", "application/pdf", b); dire("PDF enregistré dans Téléchargements"); }
    else dire("PDF impossible sur cette page");
    free(b);
    free(r);
}

static void capture_page(void)
{
    onglet *o = ACTIF;
    if (o->interne) return;
    char *r = demander(o->session, "Page.captureScreenshot", "{\"format\":\"png\",\"captureBeyondViewport\":false}", 20000);
    char *b = r ? json_chaine_alloc(r, "result.data", NULL) : NULL;
    if (b) { telecharger_b64("capture.png", "image/png", b); dire("Capture enregistrée dans Téléchargements"); }
    free(b);
    free(r);
}

void effacer_donnees(int hist, int cookies, int cache, double depuis_ms)
{
    if (hist) historique_effacer(depuis_ms);
    if (hist && depuis_ms <= 0) { audits_effacer(); google_tests_effacer(); aide_invalider(); }   /* audits, tests de Google */
    if (moteur_pret && cookies) cdp_envoyer(&moteur, "Storage.clearCookies", "{}", 0);
    if (moteur_pret && cache)
        for (int i = 0; i < nb_onglets; i++)
            if (onglets[i].session[0]) { cdp_envoyer_a(&moteur, onglets[i].session, "Network.clearBrowserCache", "{}"); break; }
    dire("Données effacées");
}

void vpn_appliquer(void)
{
    /* les onglets normaux repartent dans un contexte qui passe par le VPN */
    if (contexte_vpn[0]) {
        char p[120];
        snprintf(p, sizeof p, "{\"browserContextId\":\"%s\"}", contexte_vpn);
        contexte_vpn[0] = 0;
        for (int i = nb_onglets - 1; i >= 0; i--)
            if (!onglets[i].prive && !onglets[i].interne && onglets[i].cible[0]) {
                char u[1024];
                chaine_copier(u, onglets[i].url, sizeof u);
                onglet_fermer(i);
                onglet_nouveau(u, 1, 0);
            }
        cdp_envoyer(&moteur, "Target.disposeBrowserContext", p, 0);
    } else if (R.vpn && R.vpn_hote[0]) {
        for (int i = nb_onglets - 1; i >= 0; i--)
            if (!onglets[i].prive && !onglets[i].interne && onglets[i].cible[0]) {
                char u[1024];
                chaine_copier(u, onglets[i].url, sizeof u);
                onglet_fermer(i);
                onglet_nouveau(u, 1, 0);
            }
    }
    dire(R.vpn ? "VPN actif : les onglets passent par ton serveur (rien ne sort sans lui)" : "VPN coupé");
}

/* --------------------------- coffre (côté page) --------------------------- */
char coffre_hote[256], coffre_nom[128];
static char coffre_mdp[128];
int coffre_proposition;
static double coffre_activite;
int coffre_apres;                     /* 1 : remplir une fois ouvert ; 2 : enregistrer */

static int coffre_ouvert(void)
{
    uint8_t r[16];
    return coffre_disponible() && coffre_requete('T', NULL, 0, r, sizeof r, NULL) == 'K' && r[1] == '1';
}

int coffre_existe(void) { return fichier_existe(chemin_donnees("navigateur-coffre.bin")); }

static void coffre_sauver(const uint8_t *r, size_t n)
{
    if (n > 1) ecrire_fichier_atomique(chemin_donnees("navigateur-coffre.bin"), r + 1, n - 1);
}

static uint8_t rep_coffre[COFFRE_MESSAGE_MAX];

/* ouvre le coffre avec le mot de passe maître (effacé ensuite par l'appelant) */
int coffre_ouvrir(const char *mdp, char *err, size_t nerr)
{
    size_t lb = 0;
    char *blob = lire_fichier(chemin_donnees("navigateur-coffre.bin"), &lb);
    size_t lm = strlen(mdp), n = lm + 1 + (blob ? lb : 0);
    uint8_t *m = malloc(n);
    if (!m) { free(blob); return -1; }
    memcpy(m, mdp, lm + 1);
    if (blob) memcpy(m + lm + 1, blob, lb);
    size_t lu = 0;
    int c = coffre_requete('O', m, n, rep_coffre, sizeof rep_coffre, &lu);
    explicit_bzero(m, n);
    free(m);
    free(blob);
    if (c == 'K') {
        if (lu > 1) coffre_sauver(rep_coffre, lu);           /* coffre neuf */
        explicit_bzero(rep_coffre, lu);
        coffre_activite = maintenant;
        return 0;
    }
    snprintf(err, nerr, "%.*s", (int)(lu > 1 ? lu - 1 : 0), (char *)rep_coffre + 1);
    if (c < 0) snprintf(err, nerr, "le coffre ne répond pas");
    return -1;
}

void coffre_verrouiller(void)
{
    uint8_t r[8];
    if (coffre_disponible()) coffre_requete('V', NULL, 0, r, sizeof r, NULL);
}

/* liste des comptes (sans mots de passe) : « n\thote\tnom\n »… */
int coffre_liste(char *dst, size_t n)
{
    size_t lu = 0;
    int c = coffre_requete('L', NULL, 0, rep_coffre, sizeof rep_coffre, &lu);
    if (c != 'K') { if (n) dst[0] = 0; return -1; }
    size_t l = lu - 1 < n - 1 ? lu - 1 : n - 1;
    memcpy(dst, rep_coffre + 1, l);
    dst[l] = 0;
    coffre_activite = maintenant;
    return 0;
}

int coffre_supprimer(int i)
{
    char m[16];
    int l = snprintf(m, sizeof m, "%d", i);
    size_t lu = 0;
    if (coffre_requete('S', m, (size_t)l + 1, rep_coffre, sizeof rep_coffre, &lu) != 'K') return -1;
    coffre_sauver(rep_coffre, lu);
    return 0;
}

int coffre_montrer(int i, char *dst, size_t n)
{
    char m[16];
    int l = snprintf(m, sizeof m, "%d", i);
    size_t lu = 0;
    if (coffre_requete('M', m, (size_t)l + 1, rep_coffre, sizeof rep_coffre, &lu) != 'K') return -1;
    snprintf(dst, n, "%s", (char *)rep_coffre + 1);
    explicit_bzero(rep_coffre, lu);
    return 0;
}

int coffre_changer(const char *mdp)
{
    size_t lu = 0;
    if (coffre_requete('C', mdp, strlen(mdp) + 1, rep_coffre, sizeof rep_coffre, &lu) != 'K') return -1;
    coffre_sauver(rep_coffre, lu);
    return 0;
}

int coffre_tout_effacer(void)
{
    size_t lu = 0;
    if (coffre_requete('X', NULL, 0, rep_coffre, sizeof rep_coffre, &lu) != 'K') return -1;
    coffre_sauver(rep_coffre, lu);
    return 0;
}

/* remplit le formulaire de l'onglet avec le compte n (site exact seulement) */
void coffre_remplir_compte(int n)
{
    onglet *o = ACTIF;
    char m[300];
    int l = snprintf(m, sizeof m, "%s", o->hote);
    l += 1 + snprintf(m + l + 1, sizeof m - (size_t)l - 1, "%d", n);
    size_t lu = 0;
    if (coffre_requete('P', m, (size_t)l + 1, rep_coffre, sizeof rep_coffre, &lu) != 'K') { dire("Pas de mot de passe pour ce site"); return; }
    const char *nom = (const char *)rep_coffre + 1, *mdp = nom + strlen(nom) + 1;
    static char en[300], ep[300], eh[300], js[2000], ej[4200], p[4400];
    json_echapper(en, sizeof en, nom);
    json_echapper(ep, sizeof ep, mdp);
    json_echapper(eh, sizeof eh, o->hote);
    snprintf(js, sizeof js,
        "(()=>{if(location.hostname!==%s)return -1;const set=(e,v)=>{const d=Object.getOwnPropertyDescriptor("
        "HTMLInputElement.prototype,'value');d.set.call(e,v);e.dispatchEvent(new Event('input',{bubbles:true}));"
        "e.dispatchEvent(new Event('change',{bubbles:true}))};const ps=[...document.querySelectorAll('input[type=password]')];"
        "const pw=ps.find(e=>e.offsetParent!==null)||ps[0];let u=null;const tous=[...(pw&&pw.form||document).querySelectorAll('input')];"
        "for(const e of tous){if(e===pw)break;if(/^(text|email|tel)$/.test(e.type))u=e}"
        "if(!u)u=document.querySelector('input[autocomplete=username],input[type=email]');if(u)set(u,%s);if(pw)set(pw,%s);"
        "return pw||u?1:0})()", eh, en, ep);
    json_echapper(ej, sizeof ej, js);
    snprintf(p, sizeof p, "{\"expression\":%s,\"returnByValue\":true,\"userGesture\":true}", ej);
    cdp_envoyer_secret(&moteur, o->session, "Runtime.evaluate", p);
    /* rien ne reste en mémoire */
    explicit_bzero(rep_coffre, lu);
    explicit_bzero(en, sizeof en); explicit_bzero(ep, sizeof ep);
    explicit_bzero(js, sizeof js); explicit_bzero(ej, sizeof ej); explicit_bzero(p, sizeof p);
    coffre_activite = maintenant;
}

/* comptes de ce site : n et noms (pour un menu) ; renvoie le nombre */
int coffre_comptes_site(const char *hote, int *ids, char noms[][128], int max)
{
    static char liste[COFFRE_MESSAGE_MAX];
    if (coffre_liste(liste, sizeof liste) < 0) return -1;
    int k = 0;
    for (char *l = strtok(liste, "\n"); l && k < max; l = strtok(NULL, "\n")) {
        char *t1 = strchr(l, '\t'), *t2 = t1 ? strchr(t1 + 1, '\t') : NULL;
        if (!t1 || !t2) continue;
        *t1 = *t2 = 0;
        if (strcmp(t1 + 1, hote)) continue;
        ids[k] = atoi(l);
        chaine_copier(noms[k], t2 + 1, 128);
        k++;
    }
    explicit_bzero(liste, sizeof liste);
    return k;
}

void menu_comptes(int *ids, char noms[][128], int n);

void coffre_remplir(void)
{
    onglet *o = ACTIF;
    if (o->interne || !o->hote[0]) return;
    if (!coffre_disponible()) { dire("Le coffre n'est pas disponible"); return; }
    if (!coffre_ouvert()) { coffre_apres = 1; dialogue_coffre(!coffre_existe()); return; }
    int ids[16];
    char noms[16][128];
    int n = coffre_comptes_site(o->hote, ids, noms, 16);
    if (n <= 0) { dire("Aucun mot de passe enregistré pour ce site"); return; }
    if (n == 1) coffre_remplir_compte(ids[0]);
    else menu_comptes(ids, noms, n);
}

static int jamais_pour(const char *h)
{
    char *t = lire_fichier(chemin_donnees("navigateur-coffre-jamais.txt"), NULL);
    if (!t) return 0;
    int oui = 0;
    for (char *l = strtok(t, "\n"); l && !oui; l = strtok(NULL, "\n")) if (!strcmp(l, h)) oui = 1;
    free(t);
    return oui;
}

void coffre_enregistrer_proposition(int oui, int jamais)
{
    if (jamais) {
        char *t = lire_fichier(chemin_donnees("navigateur-coffre-jamais.txt"), NULL);
        size_t l = t ? strlen(t) : 0;
        char *n = malloc(l + strlen(coffre_hote) + 2);
        if (n) {
            snprintf(n, l + strlen(coffre_hote) + 2, "%s%s\n", t ? t : "", coffre_hote);
            ecrire_fichier_atomique(chemin_donnees("navigateur-coffre-jamais.txt"), n, strlen(n));
            free(n);
        }
        free(t);
    }
    if (oui) {
        if (!coffre_ouvert()) { coffre_apres = 2; dialogue_coffre(!coffre_existe()); return; }
        char m[400];
        int l = snprintf(m, sizeof m, "%s", coffre_hote);
        l += 1 + snprintf(m + l + 1, sizeof m - (size_t)l - 1, "%s", coffre_nom);
        l += 1 + snprintf(m + l + 1, sizeof m - (size_t)l - 1, "%s", coffre_mdp);
        size_t lu = 0;
        int c = coffre_requete('A', m, (size_t)l + 1, rep_coffre, sizeof rep_coffre, &lu);
        explicit_bzero(m, sizeof m);
        if (c == 'K') { coffre_sauver(rep_coffre, lu); dire("Mot de passe rangé dans le coffre chiffré"); }
        else { char e[200]; snprintf(e, sizeof e, "Refusé : %.*s", (int)(lu > 1 ? lu - 1 : 0), (char *)rep_coffre + 1); dire(e); }
        explicit_bzero(rep_coffre, lu);
        ACTIF->identifiants = 1;
    }
    explicit_bzero(coffre_mdp, sizeof coffre_mdp);
    explicit_bzero(coffre_nom, sizeof coffre_nom);
    coffre_proposition = 0;
    interface_fermer_info();
    sale = 1;
}

/* le dialogue du mot de passe maître a réussi : on fait ce qui attendait */
void coffre_suite(void)
{
    int a = coffre_apres;
    coffre_apres = 0;
    if (a == 1) coffre_remplir();
    else if (a == 2) coffre_enregistrer_proposition(1, 0);
}

static int ressemble_carte(const char *s)
{
    int n = 0, somme = 0, ch[32];
    for (const char *p = s; *p; p++) {
        if (*p >= '0' && *p <= '9') { if (n >= 32) return 0; ch[n++] = *p - '0'; }
        else if (*p != ' ' && *p != '-') return 0;
    }
    if (n < 13 || n > 19) return 0;
    for (int i = 0; i < n; i++) { int d = ch[n - 1 - i]; if (i % 2) { d *= 2; if (d > 9) d -= 9; } somme += d; }
    return somme % 10 == 0;
}

static void coffre_capture(onglet *o, const char *payload)
{
    if (!R.coffre || o->prive || !json_valide(payload)) return;
    char u[128], p[128], h[256];
    if (!json_chaine(payload, "u", u, sizeof u) || !json_chaine(payload, "p", p, sizeof p) || !json_chaine(payload, "h", h, sizeof h)) return;
    /* le site exact de l'onglet, pas ce que la page prétend */
    if (strcmp(h, o->hote) || !u[0] || !p[0] || ressemble_carte(p) || ressemble_carte(u) || jamais_pour(o->hote)) {
        explicit_bzero(p, sizeof p);
        return;
    }
    chaine_copier(coffre_hote, o->hote, sizeof coffre_hote);
    chaine_copier(coffre_nom, u, sizeof coffre_nom);
    chaine_copier(coffre_mdp, p, sizeof coffre_mdp);
    explicit_bzero(p, sizeof p);
    coffre_proposition = 1;
    char t[400];
    snprintf(t, sizeof t, "Enregistrer le mot de passe de « %s » pour %s dans le coffre chiffré ?", coffre_nom, coffre_hote);
    interface_info(1, t);
}

/* ------------------------------ évènements -------------------------------- */
static void fulfill_redirection(const char *session, const char *erid, const char *vers)
{
    char e[1200], p[1500];
    json_echapper(e, sizeof e, vers);
    snprintf(p, sizeof p, "{\"requestId\":%s,\"responseCode\":307,\"responseHeaders\":[{\"name\":\"Location\",\"value\":%s}]}", erid, e);
    cdp_envoyer_a(&moteur, session, "Fetch.fulfillRequest", p);
}

static char sites_http_permis[16][128];
static int nb_http_permis;

static int http_permis(const char *h)
{
    for (int i = 0; i < nb_http_permis; i++) if (!strcmp(sites_http_permis[i], h)) return 1;
    return 0;
}

void permettre_http(const char *h)
{
    if (http_permis(h)) return;
    chaine_copier(sites_http_permis[nb_http_permis % 16], h, 128);
    nb_http_permis++;
    if (nb_http_permis > 16) nb_http_permis = 16;
}

/* une extension .user.js proposée (corps reçu) */
static char *ext_attente;
static size_t ext_attente_n;
void dialogue_extension(const char *nom, const char *motifs);

void extension_confirmer(int oui)
{
    if (oui && ext_attente) {
        char err[200];
        int i = extension_installer(ext_attente, ext_attente_n, err, sizeof err);
        if (i < 0) { char t[260]; snprintf(t, sizeof t, "Extension refusée : %s", err); dire(t); }
        else {
            char t[200];
            snprintf(t, sizeof t, "Extension « %s » installée (elle agit sur les nouvelles pages)", extensions[i].nom);
            dire(t);
            for (int k = 0; k < nb_onglets; k++) if (onglets[k].session[0] && !onglets[k].prive) scripts_onglet(&onglets[k]);
        }
    }
    free(ext_attente);
    ext_attente = NULL;
}

/* 2.2 : une requête vers Google Recherche, avant qu'elle parte. Renvoie 1 si
 * elle est traitée ici (redirigée, refusée ou laissée passer). */
static int requete_google(onglet *o, const char *m, const char *u, const char *hb, const char *erid, const char *frame)
{
    char type[24] = "", mots[600], p[400];
    json_chaine(m, "params.resourceType", type, sizeof type);
    int page = !strcmp(type, "Document"), principal = !strcmp(frame, o->cible);
    int g = google_classer(u, mots, sizeof mots);
    if (!page && g == GOOGLE_AUTRE) {                /* motif large : rien à voir avec la recherche */
        snprintf(p, sizeof p, "{\"requestId\":%s}", erid);
        cdp_envoyer_a(&moteur, o->session, "Fetch.continueRequest", p);
        return 1;
    }
    if (g == GOOGLE_AUTRE) {
        if (principal && strcmp(o->google_une_fois, hb)) o->google_une_fois[0] = 0;
        return 0;
    }
    /* le test demandé par l'utilisateur : cette recherche-là seulement */
    if (g == GOOGLE_RECHERCHE && page && principal && o->google_test == 1) {
        o->google_test = 2;
        journal("navigateur", "test de Google Recherche envoyé (mot « puppyos »)");
        return 0;
    }
    if (!google_actif()) {                           /* redirection coupée : Google reçoit la recherche */
        if (g == GOOGLE_RECHERCHE && page) google_avertir_sans_redirection();
        return 0;
    }
    if (g == GOOGLE_SUGGESTION || !page) {           /* suggestions, recherches par script : jamais */
        snprintf(p, sizeof p, "{\"requestId\":%s,\"errorReason\":\"BlockedByClient\"}", erid);
        cdp_envoyer_a(&moteur, o->session, "Fetch.failRequest", p);
        return 1;
    }
    if (g == GOOGLE_RECHERCHE) {                     /* un lien, un formulaire, une adresse tapée */
        char v[1100];
        adresse_duckduckgo(mots, v, sizeof v);
        fulfill_redirection(o->session, erid, v);
        google_signaler_redirection();
        journal("navigateur", "recherche Google redirigée vers DuckDuckGo (%s)", principal ? "page" : "cadre");
        return 1;
    }
    /* l'accueil de Google : d'abord la page locale qui explique, sauf si la
     * personne a choisi d'ouvrir le vrai site */
    if (!principal || !strcmp(o->google_une_fois, hb)) return 0;
    snprintf(p, sizeof p, "{\"requestId\":%s,\"errorReason\":\"BlockedByClient\"}", erid);
    cdp_envoyer_a(&moteur, o->session, "Fetch.failRequest", p);
    o->erreur = 4;
    o->chargement = 0;
    chaine_copier(o->erreur_url, u, sizeof o->erreur_url);
    chaine_copier(o->erreur_texte, "GOOGLE", sizeof o->erreur_texte);
    sale = 1;
    return 1;
}

static void requete_en_pause(onglet *o, const char *m)
{
    char rid[96] = "", u[1024] = "", frame[64] = "";
    json_chaine(m, "params.requestId", rid, sizeof rid);
    json_chaine(m, "params.request.url", u, sizeof u);
    json_chaine(m, "params.frameId", frame, sizeof frame);
    char erid[200], p[400];
    json_echapper(erid, sizeof erid, rid);
    snprintf(p, sizeof p, "{\"requestId\":%s}", erid);
    double statut;
    if (json_nombre(m, "params.responseStatusCode", &statut)) {
        /* 2.1 : une page faite en PHP ? (adresse, en-têtes de la réponse) */
        char type[24] = "";
        json_chaine(m, "params.resourceType", type, sizeof type);
        if (!strcmp(type, "Document") && !strcmp(frame, o->cible) && !streaming && (statut < 300 || statut >= 400)) {
            char *entetes = NULL;
            /* les en-têtes en texte (le tableau entier, tel quel) */
            const char *deb = strstr(m, "\"responseHeaders\"");
            if (deb) {
                const char *fin = strchr(deb, ']');
                if (fin) entetes = strndup(deb, (size_t)(fin - deb));
            }
            char hp[256] = "";
            hote_de(u, hp, sizeof hp);
            o->php = page_php(u, entetes);
            free(entetes);
            if (o->php && !site_php_permis(hp) && strcmp(o->php_une_fois, hp)) {
                snprintf(p, sizeof p, "{\"requestId\":%s,\"errorReason\":\"BlockedByClient\"}", erid);
                cdp_envoyer_a(&moteur, o->session, "Fetch.failRequest", p);
                o->erreur = 3;
                o->chargement = 0;
                chaine_copier(o->erreur_url, u, sizeof o->erreur_url);
                chaine_copier(o->erreur_texte, "PHP", sizeof o->erreur_texte);
                journal("navigateur", "page PHP bloquée : %s", hp);
                sale = 1;
                return;
            }
        }
        /* étape « réponse » : un script utilisateur (.user.js) */
        if (statut == 200 && !o->prive && finit_par(u, ".user.js")) {
            char *r = demander(o->session, "Fetch.getResponseBody", p, 10000);
            if (r) {
                int b64 = 0;
                json_booleen(r, "result.base64Encoded", &b64);
                size_t n = 0;
                char *corps = json_chaine_alloc(r, "result.body", &n);
                if (corps && b64) {
                    uint8_t *d = malloc(n + 1);
                    if (d) { n = base64_decoder(corps, n, d); d[n] = 0; free(corps); corps = (char *)d; }
                }
                if (corps) {
                    extension e;
                    free(ext_attente);
                    ext_attente = corps;
                    ext_attente_n = n;
                    memset(&e, 0, sizeof e);
                    /* l'en-tête est relu à l'installation ; ici, un aperçu */
                    const char *nm = strstr(corps, "@name");
                    char nom[100] = "script sans nom", mot[300] = "";
                    if (nm) sscanf(nm + 5, " %99[^\r\n]", nom);
                    const char *mt = strstr(corps, "@match");
                    if (!mt) mt = strstr(corps, "@include");
                    if (mt) sscanf(strchr(mt, ' ') ? strchr(mt, ' ') : mt, " %299[^\r\n]", mot);
                    dialogue_extension(nom, mot);
                }
                free(r);
            }
        }
        cdp_envoyer_a(&moteur, o->session, "Fetch.continueRequest", p);
        return;
    }
    char hb[256] = "";
    hote_de(u, hb, sizeof hb);
    if (!streaming && google_neutralise && requete_google(o, m, u, hb, erid, frame)) return;
    if (site_bloque(hb)) {
        page_bloquee(o->session, erid, hb);
        journal("navigateur", "site bloqué par Honey-Dog refusé : %s", hb);
        return;
    }
    int principal = !strcmp(frame, o->cible);
    if (streaming) {
        if (!principal || autorise(u)) cdp_envoyer_a(&moteur, o->session, "Fetch.continueRequest", p);
        else {
            snprintf(p, sizeof p, "{\"requestId\":%s,\"errorReason\":\"BlockedByClient\"}", erid);
            cdp_envoyer_a(&moteur, o->session, "Fetch.failRequest", p);
            char t[260];
            snprintf(t, sizeof t, "%s n'est pas un site de streaming : bloqué", hb[0] ? hb : "ce site");
            dire(t);
            journal("navigateur", "lecteur : page refusée %s", hb);
        }
        return;
    }
    if (principal) {
        /* 2.1 : une nouvelle page : on repart de zéro pour la barre d'adresse */
        o->pisteurs_connus = o->pisteurs_inconnus = o->mixte = o->nb_pisteurs = o->nb_sockets = 0;
        memset(&o->cert, 0, sizeof o->cert);
        if (strcmp(o->php_une_fois, hb)) o->php_une_fois[0] = 0;
        if (o->perms) permissions_page_finie(o);
        /* images modifiées de ce site : leur script est posé avant la page */
        if (strcmp(o->hote_images, hb)) {
            chaine_copier(o->hote_images, hb, sizeof o->hote_images);
            char *js = script_images(hb);
            script_poser(o->session, js, o->id_images, sizeof o->id_images);
            free(js);
        }
        /* HTTPS d'abord : une adresse http est d'abord essayée en https */
        if (R.https && !strncmp(u, "http://", 7) && !adresse_locale(hb) && !http_permis(hb)) {
            char v[1100];
            snprintf(v, sizeof v, "https://%s", u + 7);
            fulfill_redirection(o->session, erid, v);
            return;
        }
        char propre_[1100];
        if (R.liens && nettoyer_lien(u, propre_, sizeof propre_)) {
            fulfill_redirection(o->session, erid, propre_);
            return;
        }
        /* JavaScript et cookies, réglés pour ce site avant qu'il arrive */
        int js = site_js_permis(hb), ck = site_cookies_permis(hb);
        if (o->js_coupe != !js) {
            o->js_coupe = !js;
            cdp_envoyer_a(&moteur, o->session, "Emulation.setScriptExecutionDisabled", js ? "{\"value\":false}" : "{\"value\":true}");
        }
        if (o->cookies_coupes != !ck) {
            o->cookies_coupes = !ck;
            cdp_envoyer_a(&moteur, o->session, "Emulation.setDocumentCookieDisabled", ck ? "{\"disabled\":false}" : "{\"disabled\":true}");
        }
    }
    cdp_envoyer_a(&moteur, o->session, "Fetch.continueRequest", p);
}

/* cookies coupés pour un site : il peut en poser, ils disparaissent aussitôt */
static void purger_cookies(onglet *o)
{
    if (!o->cookies_coupes || !o->hote[0] || !o->session[0]) return;
    char p[400];
    snprintf(p, sizeof p, "{\"origin\":\"https://%s\",\"storageTypes\":\"cookies,local_storage,indexeddb,websql,cache_storage,service_workers\"}", o->hote);
    cdp_envoyer_a(&moteur, o->session, "Storage.clearDataForOrigin", p);
    snprintf(p, sizeof p, "{\"origin\":\"http://%s\",\"storageTypes\":\"cookies,local_storage,indexeddb\"}", o->hote);
    cdp_envoyer_a(&moteur, o->session, "Storage.clearDataForOrigin", p);
}

/* 0.12.1 : le moteur ne signale pas toujours le titre d'une page (titre
 * posé par JavaScript) : on le lui demande */
static void demander_titre(onglet *o)
{
    if (!o->session[0] || o->interne || o->id_titre > 0) return;
    o->id_titre = cdp_envoyer_a(&moteur, o->session, "Runtime.evaluate",
                                "{\"expression\":\"document.title\",\"returnByValue\":true}");
}

/* 2.2 : ce que la page de test de Google a montré */
static const char JS_GOOGLE_TEST[] =
    "(function(){var s=location.pathname.indexOf('/sorry')==0;"
    "var c=!!document.querySelector('iframe[src*=\"recaptcha\"],#captcha-form,div.g-recaptcha,form[action*=\"sorry\"]');"
    "var r=document.querySelectorAll('#search a h3,#rso a h3').length;"
    "return JSON.stringify({s:s,c:c,r:r,h:location.hostname});})()";

static void google_test_lire(onglet *o)
{
    o->google_test = 0;
    if (o->erreur) {
        google_test_noter(0, 0, "échec : Google injoignable");
        aide_invalider();
        dire("Test de Google Recherche : Google injoignable");
        return;
    }
    char e[700], p[800];
    json_echapper(e, sizeof e, JS_GOOGLE_TEST);
    snprintf(p, sizeof p, "{\"expression\":%s,\"returnByValue\":true}", e);
    o->id_google = cdp_envoyer_a(&moteur, o->session, "Runtime.evaluate", p);
}

static void google_test_reponse(onglet *o, const char *m)
{
    o->id_google = 0;
    char v[400] = "", h[160] = "", detail[100];
    json_chaine(m, "result.result.value", v, sizeof v);
    int s = 0, c = 0;
    double r = 0;
    json_booleen(v, "s", &s);
    json_booleen(v, "c", &c);
    json_nombre(v, "r", &r);
    json_chaine(v, "h", h, sizeof h);
    int ok = google_test_juger(s, c, (int)r, h, detail, sizeof detail);
    google_test_noter(ok, (int)r, detail);
    aide_invalider();
    journal("navigateur", "test de Google Recherche : %s", detail);
    char t[200];
    snprintf(t, sizeof t, "Test de Google Recherche : %s. Il est noté ; il ne réactive pas Google.", detail);
    dire(t);
}

static void fin_chargement(onglet *o)
{
    if (o->google_test == 2) google_test_lire(o);
    o->id_titre = 0;
    demander_titre(o);
    o->chargement = 0;
    purger_cookies(o);
    sale = 1;
}

/* un onglet a reçu une réponse à sa navigation */
static void reponse_navigation(onglet *o, const char *m)
{
    o->id_nav = -1;
    char err[96] = "";
    if (json_chaine(m, "result.errorText", err, sizeof err) && err[0] && strcmp(err, "net::ERR_ABORTED")) {
        o->chargement = 0;
        if (streaming) {
            char t[200];
            snprintf(t, sizeof t, "Site injoignable (%s) : vérifie la connexion internet", err);
            dire(t);
            vue = VUE_ACCUEIL;
            if (o->image) { surface_detruire(o->image); o->image = NULL; }
        } else if (o->erreur != 3 && o->erreur != 4) {   /* PHP bloquée, Google : leur page à elles */
            o->erreur = 1;
            chaine_copier(o->erreur_texte, err, sizeof o->erreur_texte);
            chaine_copier(o->erreur_url, o->url, sizeof o->erreur_url);
        }
        sale = 1;
    }
}

static void evenement_moteur(char *m, void *d)
{
    (void)d;
    char methode[64] = "", session[64] = "";
    json_chaine(m, "method", methode, sizeof methode);
    json_chaine(m, "sessionId", session, sizeof session);
    onglet *o = onglet_session(session);
    if (!methode[0]) {                                      /* une réponse */
        double id;
        if (o && o->id_nav > 0 && json_nombre(m, "id", &id) && (int)id == o->id_nav) reponse_navigation(o, m);
        else if (o && o->id_google > 0 && json_nombre(m, "id", &id) && (int)id == o->id_google) google_test_reponse(o, m);
        else if (o && o->id_titre > 0 && json_nombre(m, "id", &id) && (int)id == o->id_titre) {
            o->id_titre = 0;
            char t[256] = "";
            if (json_chaine(m, "result.result.value", t, sizeof t) && t[0] && strcmp(t, o->titre)) {
                chaine_copier(o->titre, t, sizeof o->titre);
                if (!o->prive) historique_titre(o->url, o->titre);
                if (fen && o == ACTIF) pf_titre(fen, o->titre);
                sale = 1;
            }
        }
        else if (id_etat > 0 && cdp_reponse_a(m, id_etat)) {
            id_etat = -1;
            char v[256] = "";
            json_chaine(m, "result.result.value", v, sizeof v);
            int avant = video_plein;
            if (!v[0]) { video_plein = 0; video_d = 0; }
            else {
                json_nombre(v, "t", &video_t);
                json_nombre(v, "d", &video_d);
                double pp = 0, f = 0;
                json_nombre(v, "p", &pp);
                json_nombre(v, "f", &f);
                video_pause = pp > 0;
                video_plein = f > 0;
            }
            if (avant != video_plein) { osd_jusqua = maintenant + 3000; sale = 1; }
            if (video_plein && maintenant < osd_jusqua) sale = 1;
        }
        free(m);
        return;
    }
    if (!strcmp(methode, "Page.screencastFrame")) {
        double sid = 0;
        json_nombre(m, "params.sessionId", &sid);
        char ack[64];
        snprintf(ack, sizeof ack, "{\"sessionId\":%d}", (int)sid);
        cdp_envoyer_a(&moteur, session, "Page.screencastFrameAck", ack);
        size_t n = 0;
        char *b64 = o ? json_chaine_alloc(m, "params.data", &n) : NULL;
        if (b64) {
            uint8_t *jpg = malloc(n);
            if (jpg) {
                size_t l = base64_decoder(b64, n, jpg);
                surface *s = jpeg_decoder(jpg, l, 1);
                if (s) { if (o->image) surface_detruire(o->image); o->image = s; o->derniere_image = maintenant; sale = 1; }
                free(jpg);
            }
            free(b64);
        }
    } else if (!strcmp(methode, "Fetch.requestPaused")) {
        if (o) requete_en_pause(o, m);
    } else if (o && !strcmp(methode, "Network.requestWillBeSent")) {
        reseau_requete(o, m);
    } else if (o && !strcmp(methode, "Network.responseReceived")) {
        reseau_reponse(o, m);
    } else if (o && !strcmp(methode, "Network.webSocketCreated")) {
        char u[200] = "";
        json_chaine(m, "params.url", u, sizeof u);
        if (u[0] && o->nb_sockets < 6) chaine_copier(o->sockets[o->nb_sockets++], u, sizeof o->sockets[0]);
    } else if (!strcmp(methode, "Target.targetCreated")) {
        char id[64] = "", type[32] = "", ouvreur[64] = "";
        json_chaine(m, "params.targetInfo.targetId", id, sizeof id);
        json_chaine(m, "params.targetInfo.type", type, sizeof type);
        json_chaine(m, "params.targetInfo.openerId", ouvreur, sizeof ouvreur);
        if (!strcmp(type, "page") && ouvreur[0] && onglet_cible(id) < 0) {
            /* fenêtre surgissante (target=_blank, window.open) */
            int ok = !streaming && (R.popups || maintenant - dernier_geste < 2500) && nb_onglets < ONGLETS_MAX;
            int k = ok ? nouvelle_place(0) : -1;
            if (k >= 0) {
                int pv = onglet_cible(ouvreur);
                onglets[k].prive = pv >= 0 && onglets[pv].prive;
                if (attacher(&onglets[k], id) == 0) onglet_activer(k);
                else { onglets[k].vivant = 0; nb_onglets--; }
            } else {
                char p[200], eid[160];
                json_echapper(eid, sizeof eid, id);
                snprintf(p, sizeof p, "{\"targetId\":%s}", eid);
                cdp_envoyer(&moteur, "Target.closeTarget", p, 0);
                if (!streaming) dire("Fenêtre surgissante bloquée");
            }
        }
    } else if (!strcmp(methode, "Target.targetInfoChanged")) {
        char id[64] = "";
        json_chaine(m, "params.targetInfo.targetId", id, sizeof id);
        int k = onglet_cible(id);
        if (k >= 0) {
            onglet *t = &onglets[k];
            char avant[sizeof t->url];
            chaine_copier(avant, t->url, sizeof avant);
            char nu[1024] = "";
            json_chaine(m, "params.targetInfo.url", nu, sizeof nu);
            if (nu[0] && strncmp(nu, "chrome-error:", 13) && strcmp(nu, "about:blank")) chaine_copier(t->url, nu, sizeof t->url);
            json_chaine(m, "params.targetInfo.title", t->titre, sizeof t->titre);
            hote_de(t->url, t->hote, sizeof t->hote);
            if (strcmp(avant, t->url) && k == actif) dire_honeydog(t->url);
            if (!t->prive && t->titre[0]) historique_titre(t->url, t->titre);
            if (fen && k == actif) pf_titre(fen, t->titre[0] ? t->titre : "Navigateur");
            sale = 1;
        }
    } else if (!strcmp(methode, "Target.targetCrashed")) {
        /* 0.12.1 : la vraie cause du plantage, dite sur la page et au journal */
        char id[64] = "", st[48] = "";
        double code = 0;
        json_chaine(m, "params.targetId", id, sizeof id);
        json_chaine(m, "params.status", st, sizeof st);
        json_nombre(m, "params.errorCode", &code);
        int k = onglet_cible(id);
        if (k >= 0) {
            onglet *t = &onglets[k];
            t->erreur = 2;
            t->chargement = 0;
            chaine_copier(t->erreur_url, t->url, sizeof t->erreur_url);
            cause_plantage(t, st, (int)code);
            sale = 1;
        }
    } else if (!strcmp(methode, "Target.attachedToTarget") && session[0]) {
        cadre_attache(session, m);
    } else if (!strcmp(methode, "Target.detachedFromTarget") && session[0]) {
        cadre_detache(m);
    } else if (!strcmp(methode, "Target.targetDestroyed") || !strcmp(methode, "Target.detachedFromTarget")) {
        char id[64] = "";
        json_chaine(m, "params.targetId", id, sizeof id);
        int k = id[0] ? onglet_cible(id) : -1;
        if (k < 0 && session[0] == 0) {
            char s2[64] = "";
            json_chaine(m, "params.sessionId", s2, sizeof s2);
            onglet *t = onglet_session(s2);
            if (t) k = (int)(t - onglets);
        }
        if (k >= 0) { onglets[k].cible[0] = 0; onglet_fermer(k); }
    } else if (!o) {
        /* évènements du navigateur entier */
        if (!strcmp(methode, "Browser.downloadWillBegin")) {
            char nom[200] = "", t[260];
            json_chaine(m, "params.suggestedFilename", nom, sizeof nom);
            snprintf(t, sizeof t, "Téléchargement de %s…", nom);
            dire(t);
        } else if (!strcmp(methode, "Browser.downloadProgress")) {
            char etat[32] = "";
            json_chaine(m, "params.state", etat, sizeof etat);
            if (!strcmp(etat, "completed")) dire("Téléchargement terminé : il passe par Flair (panier du Dock)");
            else if (!strcmp(etat, "canceled")) dire("Téléchargement annulé");
        }
    } else if (!strcmp(methode, "Page.frameNavigated")) {
        if (!json_existe(m, "params.frame.parentId")) {
            char u[1024] = "";
            json_chaine(m, "params.frame.url", u, sizeof u);
            if (!strncmp(u, "chrome-error:", 13) && (o->erreur == 3 || o->erreur == 4)) {
                /* page PHP bloquée, Google Recherche indisponible : la page d'erreur est la nôtre */
            } else if (!strncmp(u, "chrome-error:", 13)) {
                char ur[1024] = "";
                json_chaine(m, "params.frame.unreachableUrl", ur, sizeof ur);
                o->erreur = 1;
                if (ur[0]) chaine_copier(o->erreur_url, ur, sizeof o->erreur_url);
                if (!o->erreur_texte[0]) chaine_copier(o->erreur_texte, "net::ERR_FAILED", sizeof o->erreur_texte);
            } else {
                o->erreur = 0;
                o->erreur_texte[0] = 0;
                o->erreur_detail[0] = 0;
                chaine_copier(o->url, u, sizeof o->url);
                hote_de(u, o->hote, sizeof o->hote);
                o->identifiants = 0;
                if (!o->prive) historique_ajouter(u, o->titre);
            }
            sale = 1;
        }
    } else if (!strcmp(methode, "Page.frameStartedLoading")) {
        char f[64] = "";
        json_chaine(m, "params.frameId", f, sizeof f);
        if (!strcmp(f, o->cible)) { o->chargement = 1; o->depuis = maintenant; sale = 1; }
    } else if (!strcmp(methode, "Page.loadEventFired")) {
        fin_chargement(o);
    } else if (!strcmp(methode, "Page.frameStoppedLoading")) {
        char f[64] = "";
        json_chaine(m, "params.frameId", f, sizeof f);
        if (!strcmp(f, o->cible)) fin_chargement(o);
    } else if (!strcmp(methode, "Page.javascriptDialogOpening")) {
        /* alert / confirm / prompt : sans réponse, la page reste figée */
        char type[24] = "", msg[600] = "", defaut[300] = "";
        json_chaine(m, "params.type", type, sizeof type);
        json_chaine(m, "params.message", msg, sizeof msg);
        json_chaine(m, "params.defaultPrompt", defaut, sizeof defaut);
        int k = (int)(o - onglets);
        if (k != actif) onglet_activer(k);
        if (!strcmp(type, "beforeunload")) cdp_envoyer_a(&moteur, o->session, "Page.handleJavaScriptDialog", "{\"accept\":true}");
        else dialogue_js(!strcmp(type, "confirm") ? 1 : !strcmp(type, "prompt") ? 2 : 0, msg, defaut);
    } else if (!strcmp(methode, "Page.fileChooserOpened")) {
        double nd = 0;
        char mode_choix[32] = "";
        if (json_nombre(m, "params.backendNodeId", &nd) && nd > 0 && nd < 1e9) {
            json_chaine(m, "params.mode", mode_choix, sizeof mode_choix);
            int k = (int)(o - onglets);
            if (k != actif) onglet_activer(k);
            ouvrir_choix((int)nd, !strcmp(mode_choix, "selectMultiple"));
        }
    } else if (!strcmp(methode, "Inspector.targetCrashed")) {
        if (o->erreur != 2) {           /* Target.targetCrashed n'est pas encore arrivé */
            o->erreur = 2;
            o->chargement = 0;
            chaine_copier(o->erreur_url, o->url, sizeof o->erreur_url);
            cause_plantage(o, "", -1);
        }
        sale = 1;
    } else if (!strcmp(methode, "Runtime.bindingCalled")) {
        char nom[32] = "";
        json_chaine(m, "params.name", nom, sizeof nom);
        char *pl = json_chaine_alloc(m, "params.payload", NULL);
        if (pl && !strcmp(nom, "puppyCoffre")) coffre_capture(o, pl);
        else if (pl && !strcmp(nom, "puppyPermission")) permission_demande(o, pl);
        if (pl) { explicit_bzero(pl, strlen(pl)); free(pl); }
    } else if (!strcmp(methode, "Input.dragIntercepted")) {
        char u[1024] = "", t[200] = "";
        for (int k = 0; k < 8; k++) {
            const char *e = json_element(m, "params.data.items", k);
            if (!e) break;
            char mime[64] = "";
            json_chaine(e, "mimeType", mime, sizeof mime);
            if (!strcmp(mime, "text/uri-list") || (!u[0] && !strcmp(mime, "text/plain"))) json_chaine(e, "data", u, sizeof u);
            if (!t[0]) json_chaine(e, "title", t, sizeof t);
        }
        u[strcspn(u, "\r\n")] = 0;
        if (!strncmp(u, "http://", 7) || !strncmp(u, "https://", 8)) glisser_lien(u, t);
    }
    free(m);
}


/* =========================== 2.1 (0.12.2) ================================ */
/* ---------------------------- permissions -------------------------------- */
static const char *const PERM_CHROMIUM[NB_PERMS] = { "videoCapture", "audioCapture", "geolocation", "notifications" };
static struct { int actif, id, type; char session[64], hote[256], origine[300]; } perm_attente;
static char perm_origine[ONGLETS_MAX][300];          /* origine des permissions accordées par onglet */

static void origine_de(const char *u, char *d, size_t n)
{
    const char *p = strstr(u, "://");
    size_t l = p ? (size_t)(p + 3 - u) + strcspn(p + 3, "/?#") : strlen(u);
    if (l >= n) l = n - 1;
    memcpy(d, u, l);
    d[l] = 0;
}

static void perm_chromium(const char *origine, int type, const char *etat)
{
    char e[640], p[900];
    json_echapper(e, sizeof e, origine);
    snprintf(p, sizeof p, "{\"permission\":{\"name\":\"%s\"},\"setting\":\"%s\",\"origin\":%s}", PERM_CHROMIUM[type], etat, e);
    cdp_envoyer(&moteur, "Browser.setPermission", p, 0);
}

static void perm_repondre_js(const char *session, int id, int ok)
{
    char js[96];
    snprintf(js, sizeof js, "window.__puppyPerm&&window.__puppyPerm(%d,%d)", id, ok);
    evaluer_session(session, js);
}

static void permission_demande(onglet *o, const char *pl)
{
    double t = -1, id = 0;
    json_nombre(pl, "t", &t);
    json_nombre(pl, "id", &id);
    int type = (int)t;
    if (type < 0 || type >= NB_PERMS || !o->hote[0]) return;
    char origine[300];
    origine_de(o->url, origine, sizeof origine);
    int e = permission_etat(o->hote, type);
    if (e == 1) {
        perm_chromium(origine, type, "granted");
        o->perms |= 1 << type;
        chaine_copier(perm_origine[o - onglets], origine, sizeof perm_origine[0]);
        perm_repondre_js(o->session, (int)id, 1);
        sale = 1;
        return;
    }
    if (e == 0 || perm_attente.actif || streaming) {
        perm_repondre_js(o->session, (int)id, 0);
        if (e == 0) {
            char m[200];
            snprintf(m, sizeof m, "%s : bloqué pour ce site (Paramètres → Permissions des sites)", PERM_TITRES[type]);
            dire(m);
        }
        return;
    }
    perm_attente.actif = 1;
    perm_attente.id = (int)id;
    perm_attente.type = type;
    chaine_copier(perm_attente.session, o->session, sizeof perm_attente.session);
    chaine_copier(perm_attente.hote, o->hote, sizeof perm_attente.hote);
    chaine_copier(perm_attente.origine, origine, sizeof perm_attente.origine);
    int k = (int)(o - onglets);
    if (k != actif) onglet_activer(k);
    dialogue_permission(o->hote, type);
}

void permission_repondre(int oui, int retenir)
{
    if (!perm_attente.actif) return;
    perm_attente.actif = 0;
    onglet *o = onglet_session(perm_attente.session);
    if (retenir) permission_retenir(perm_attente.hote, perm_attente.type, oui ? 1 : 0);
    if (oui) {
        perm_chromium(perm_attente.origine, perm_attente.type, "granted");
        if (o) {
            o->perms |= 1 << perm_attente.type;
            chaine_copier(perm_origine[o - onglets], perm_attente.origine, sizeof perm_origine[0]);
        }
    }
    perm_repondre_js(perm_attente.session, perm_attente.id, oui);
    journal("navigateur", "permission %s %s pour %s%s", PERM_TITRES[perm_attente.type], oui ? "accordée" : "refusée",
            perm_attente.hote, retenir ? " (retenu)" : "");
    sale = 1;
}

/* la page change : ce qui avait été permis « cette fois » est repris */
void permissions_page_finie(onglet *o)
{
    int k = (int)(o - onglets);
    for (int t = 0; t < NB_PERMS; t++) if (o->perms & (1 << t)) perm_chromium(perm_origine[k], t, "denied");
    o->perms = 0;
}

void permission_retirer(const char *hote, int type)
{
    permission_retenir(hote, type, -1);
    char origine[300];
    snprintf(origine, sizeof origine, "https://%s", hote);
    if (moteur_pret) perm_chromium(origine, type, "denied");
}

/* ------------------------- 2.2 : Google Recherche ------------------------- */
/* la page locale → le vrai site de Google (son accueil : les recherches
 * restent redirigées tant que la politique le demande) */
void google_ouvrir_vrai(void)
{
    onglet *o = ACTIF;
    if (o->erreur != 4) return;
    hote_de(o->erreur_url, o->google_une_fois, sizeof o->google_une_fois);
    char u[1024];
    chaine_copier(u, o->erreur_url, sizeof u);
    o->erreur = 0;
    aller(u);
}

/* un test sur clic : seul le mot « puppyos » part chez Google */
void google_tester(void)
{
    if (!google_neutralise) return;
    int i = onglet_nouveau("", 1, 0);
    if (i < 0) return;
    onglets[i].google_test = 1;
    aller_onglet(i, "https://www.google.com/search?q=puppyos&hl=fr");
}

/* ------------------------------- PHP ------------------------------------- */
void php_ouvrir_cette_fois(void)
{
    onglet *o = ACTIF;
    if (o->erreur != 3) return;
    hote_de(o->erreur_url, o->php_une_fois, sizeof o->php_une_fois);
    char u[1024];
    chaine_copier(u, o->erreur_url, sizeof u);
    o->erreur = 0;
    aller(u);
}

void php_toujours_permettre(void)
{
    onglet *o = ACTIF;
    if (o->erreur != 3) return;
    char h[256] = "";
    hote_de(o->erreur_url, h, sizeof h);
    exception_site *e = site_exception(h, 1);
    if (e) { e->php = 1; sites_enregistrer(); }
    char u[1024];
    chaine_copier(u, o->erreur_url, sizeof u);
    o->erreur = 0;
    aller(u);
}

/* ----------------------------- réseau ------------------------------------ */
static void ajouter_unique(char t[][96], int *n, int max, const char *v)
{
    for (int i = 0; i < *n; i++) if (!strcmp(t[i], v)) return;
    if (*n < max) chaine_copier(t[(*n)++], v, 96);
}

static void reseau_requete(onglet *o, const char *m)
{
    char u[1024] = "", type[24] = "", frame[64] = "", h[256] = "";
    json_chaine(m, "params.request.url", u, sizeof u);
    json_chaine(m, "params.type", type, sizeof type);
    json_chaine(m, "params.frameId", frame, sizeof frame);
    if (!hote_de(u, h, sizeof h)) return;
    if (!strcmp(type, "Document") && !strcmp(frame, o->cible)) {
        /* redirections de la page principale (chaîne des domaines) */
        if (!json_existe(m, "params.redirectResponse")) { o->nb_redir = 0; o->redirections = 0; }
        else o->redirections++;
        if (!o->nb_redir || strcmp(o->chaine_redir[o->nb_redir - 1], h))
            ajouter_unique(o->chaine_redir, &o->nb_redir, 6, h);
        return;
    }
    if (!strncmp(u, "http://", 7) && !strncmp(o->url, "https://", 8)) o->mixte++;
    if (!o->hote[0] || meme_site(h, o->hote)) return;
    int connu = pisteur_connu(h);
    if (!connu && !pisteur_probable(u)) return;
    int avant = o->nb_pisteurs;
    ajouter_unique(o->pisteurs, &o->nb_pisteurs, 12, h);
    if (o->nb_pisteurs > avant) {
        if (connu) o->pisteurs_connus++; else o->pisteurs_inconnus++;
        sale = 1;
    }
}

static void reseau_reponse(onglet *o, const char *m)
{
    char type[24] = "", frame[64] = "";
    json_chaine(m, "params.type", type, sizeof type);
    json_chaine(m, "params.frameId", frame, sizeof frame);
    if (strcmp(type, "Document") || strcmp(frame, o->cible)) return;
    memset(&o->cert, 0, sizeof o->cert);
    if (!json_existe(m, "params.response.securityDetails")) return;
    o->cert.present = 1;
    json_chaine(m, "params.response.securityDetails.subjectName", o->cert.sujet, sizeof o->cert.sujet);
    json_chaine(m, "params.response.securityDetails.issuer", o->cert.emetteur, sizeof o->cert.emetteur);
    json_chaine(m, "params.response.securityDetails.protocol", o->cert.protocole, sizeof o->cert.protocole);
    json_chaine(m, "params.response.securityDetails.cipher", o->cert.chiffre, sizeof o->cert.chiffre);
    json_chaine(m, "params.response.securityDetails.keyExchangeGroup", o->cert.echange, sizeof o->cert.echange);
    if (!o->cert.echange[0]) json_chaine(m, "params.response.securityDetails.keyExchange", o->cert.echange, sizeof o->cert.echange);
    json_nombre(m, "params.response.securityDetails.validFrom", &o->cert.debut);
    json_nombre(m, "params.response.securityDetails.validTo", &o->cert.fin);
    json_chaine(m, "params.response.securityDetails.certificateTransparencyCompliance", o->cert.ct, sizeof o->cert.ct);
    int n = 0;
    while (n < 500 && json_element(m, "params.response.securityDetails.sanList", n)) n++;
    o->cert.noms = n;
}

/* --------------------------- oublier un site ----------------------------- */
void site_effacer_stockage(const char *hote)
{
    if (!moteur_pret || !hote[0]) return;
    char p[400];
    for (int k = 0; k < 2; k++) {
        snprintf(p, sizeof p, "{\"origin\":\"%s://%s\",\"storageTypes\":\"all\"}", k ? "http" : "https", hote);
        cdp_envoyer(&moteur, "Storage.clearDataForOrigin", p, 0);
    }
    /* les cookies du domaine (et de ses sous-domaines) */
    snprintf(p, sizeof p, "{\"urls\":[\"https://%s/\",\"http://%s/\"]}", hote, hote);
    onglet *o = nb_onglets ? ACTIF : NULL;
    char *r = o && o->session[0] ? demander(o->session, "Network.getCookies", p, 3000) : NULL;
    for (int i = 0; r && i < 300; i++) {
        const char *c = json_element(r, "result.cookies", i);
        if (!c) break;
        char nom[256] = "", dom[256] = "", chemin[256] = "", e1[600], e2[600], e3[600], q[2000];
        json_chaine(c, "name", nom, sizeof nom);
        json_chaine(c, "domain", dom, sizeof dom);
        json_chaine(c, "path", chemin, sizeof chemin);
        json_echapper(e1, sizeof e1, nom);
        json_echapper(e2, sizeof e2, dom);
        json_echapper(e3, sizeof e3, chemin);
        snprintf(q, sizeof q, "{\"name\":%s,\"domain\":%s,\"path\":%s}", e1, e2, e3);
        cdp_envoyer_a(&moteur, o->session, "Network.deleteCookies", q);
    }
    free(r);
}

void site_oublier(const char *hote)
{
    char h[256];
    chaine_copier(h, hote, sizeof h);
    site_effacer_stockage(h);
    historique_supprimer_site(h);
    historique_sauver_maintenant();
    for (int t = 0; t < NB_PERMS; t++) if (permission_etat(h, t) >= 0 && moteur_pret) {
        char origine[300];
        snprintf(origine, sizeof origine, "https://%s", h);
        perm_chromium(origine, t, "denied");
    }
    permission_oublier_site(h);
    images_restaurer(h, NULL);
    exception_site *e = site_exception(h, 0);
    if (e) { e->js = e->cookies = e->php = -1; sites_enregistrer(); }
    for (int i = 0; i < nb_onglets; i++) if (!strcmp(onglets[i].hote_images, h)) onglets[i].hote_images[0] = 0;
    journal("navigateur", "site oublié : %s", h);
    dire("Site oublié : cookies, stockage, permissions, historique et images modifiées effacés");
}

/* ------------------------------ images ----------------------------------- */

void image_modifier(const char *original)
{
    if (!nb_onglets || ACTIF->interne || !ACTIF->hote[0]) return;
    chaine_copier(image_originale, original, sizeof image_originale);
    choix_image = 1;
    ouvrir_choix(0, 0);
    if (!choix_nb) {
        choix_ouvert = choix_image = 0;
        dire("Dépose d'abord ton image dans le dossier « Envoi » (gamelle principale)");
    }
}

static void image_choisie(void)
{
    choix_ouvert = 0;
    choix_image = 0;
    int i = 0;
    while (i < choix_nb && !choix_pris[i]) i++;
    if (i >= choix_nb) { dire("Aucune image choisie"); sale = 1; return; }
    char chemin[400], err[200];
    snprintf(chemin, sizeof chemin, "%s/%s", BAC_ENVOI, choix_noms[i]);
    onglet *o = ACTIF;
    if (image_remplacer(o->hote, image_originale, chemin, err, sizeof err) < 0) { dire(err); sale = 1; return; }
    o->hote_images[0] = 0;                       /* script reposé au rechargement */
    char *js = script_images(o->hote);
    script_poser(o->session, js, o->id_images, sizeof o->id_images);
    chaine_copier(o->hote_images, o->hote, sizeof o->hote_images);
    if (js) evaluer_session(o->session, js);
    free(js);
    dire("Image modifiée pour ce site, sur cette machine seulement");
    sale = 1;
}

void image_restaurer(const char *original)
{
    onglet *o = nb_onglets ? ACTIF : NULL;
    if (!o || !o->hote[0]) return;
    int n = images_restaurer(o->hote, original && original[0] ? original : NULL);
    if (!n) { dire("Rien à restaurer sur ce site"); return; }
    o->hote_images[0] = 0;
    char *js = script_images(o->hote);
    script_poser(o->session, js, o->id_images, sizeof o->id_images);
    free(js);
    chaine_copier(o->hote_images, o->hote, sizeof o->hote_images);
    recharger();
    dire(n > 1 ? "Images d'origine restaurées" : "Image d'origine restaurée");
}

/* ------------------------- applications web ------------------------------ */
int appli;
static char appli_url[1024], appli_titre[128];

void appli_installer(void)
{
    onglet *o = nb_onglets ? ACTIF : NULL;
    if (!o || o->interne || o->prive || strncmp(o->url, "http", 4)) { dire("Ouvre d'abord le site à installer"); return; }
    char chemin[400];
    if (appli_creer(o->url, o->titre[0] ? o->titre : o->hote, chemin, sizeof chemin) < 0) { dire("Raccourci impossible sur le Bureau"); return; }
    char m[500];
    snprintf(m, sizeof m, "Application installée : %s sur le Bureau (plein écran, son propre bac à sable)", strrchr(chemin, '/') + 1);
    dire(m);
    journal("navigateur", "application web créée : %s", o->url);
}

/* ---------------------------- premier lancement --------------------------- */
void premier_terminer(void)
{
    R.premier_fait = 1;
    reglages_enregistrer();
    sites_appliquer();
    if (moteur_pret && !nb_onglets) {
        if (appli) onglet_nouveau(appli_url, 1, 0);
        else if (onglet_nouveau(R.accueil, 1, 0) >= 0 && !R.accueil[0]) barre_saisie_activer();
    }
    journal("navigateur", "premier lancement terminé : JavaScript %s, cookies %s, blocage PHP %s",
            R.js ? "permis" : "coupé", R.cookies ? "permis" : "coupés", R.php_bloque ? "actif" : "coupé");
    sale = 1;
}

/* ------------------------------ moteur ------------------------------------ */
static void dessiner(void);
static int demarrer_moteur(void)
{
    /* 2.1 : chaque application web a son bac et son profil à elle */
    char mode[24] = "navigateur";
    if (streaming) chaine_copier(mode, "streaming", sizeof mode);
    else if (appli) {
        char h[256] = "";
        hote_de(appli_url, h, sizeof h);
        uint32_t x = 2166136261u;
        for (const char *c = h; *c; c++) x = (x ^ (unsigned char)*c) * 16777619u;
        snprintf(mode, sizeof mode, "appli-%08x", x);
    }
    if (cdp_lancer(&moteur, mode, PL, PH, erreur_moteur, sizeof erreur_moteur) < 0)
        return -1;
    TRACE("bac lancé (canal %d)", moteur.canal);
    /* 0.12 : Chromium peut mettre longtemps à démarrer sur un PC lent */
    int id = cdp_envoyer(&moteur, "Target.getTargets", "{}", 0);
    /* on attend en gardant l'œil sur Honey-Dog : s'il gèle la boîte pendant
     * le démarrage, sa question doit s'afficher (avant, le Navigateur restait
     * bloqué sans rien montrer) */
    char *r = NULL;
    double fin = horloge() + 90000;
    while (!quitter) {
        r = cdp_attendre(&moteur, id, 250, NULL, NULL);
        if (r) break;
        maintenant = horloge();
        if (moteur.canal >= 0) lire_honeydog();
        pf_evenement e;
        while (fen && pf_attendre(fen, &e, 0) == 1) {
            if (e.type == PF_EV_FERMETURE) quitter = 1;
            else if (e.type == PF_EV_SOURIS && (alerte || arret) && e.bouton == 0) clic_honeydog(e.x, e.y, e.appuye);
            else if (e.type == PF_EV_TOUCHE && alerte && e.code == KEY_ESC) decision_honeydog("detruire");
            else if (e.type == PF_EV_TOUCHE && arret) arret = 0;
        }
        if (alerte) fin = maintenant + 90000;           /* la personne décide : pas de délai */
        if (sale || alerte || arret) dessiner();
        struct pollfd q = { moteur.lire, POLLIN, 0 };
        poll(&q, 1, 0);
        if ((q.revents & (POLLHUP | POLLERR)) && !(q.revents & POLLIN)) { TRACE("moteur fermé au démarrage"); break; }
        if (maintenant > fin || arret) break;
    }
    TRACE("réponse au démarrage : %s", r ? "oui" : "non");
    if (!r) {
        snprintf(erreur_moteur, sizeof erreur_moteur, "le moteur Chromium ne répond pas");
        cdp_arreter(&moteur);
        return -1;
    }
    /* l'onglet vide ouvert par la ligne de commande : fermé, on fait les nôtres */
    for (int k = 0; k < 8; k++) {
        const char *e = json_element(r, "result.targetInfos", k);
        if (!e) break;
        char t[64] = "", type[24] = "";
        json_chaine(e, "targetId", t, sizeof t);
        json_chaine(e, "type", type, sizeof type);
        if (!strcmp(type, "page")) {
            char p[120];
            snprintf(p, sizeof p, "{\"targetId\":\"%s\"}", t);
            cdp_envoyer(&moteur, "Target.closeTarget", p, 0);
        }
    }
    free(r);
    moteur_pret = 1;
    cdp_envoyer(&moteur, "Target.setDiscoverTargets", "{\"discover\":true}", 0);
    if (streaming) cdp_envoyer(&moteur, "Browser.setDownloadBehavior", "{\"behavior\":\"deny\"}", 0);
    else cdp_envoyer(&moteur, "Browser.setDownloadBehavior",
                     "{\"behavior\":\"allow\",\"downloadPath\":\"/telechargements\",\"eventsEnabled\":true}", 0);
    /* permissions refusées d'office, pour tous les sites */
    static const char *REFUS[] = { "geolocation", "notifications", "camera", "microphone", "midi", "sensors",
                                   "idleDetection", "localFonts", "windowManagement", "clipboardReadWrite",
                                   "backgroundSync", "nfc", "displayCapture", NULL };
    for (int i = 0; REFUS[i]; i++) {
        char p[200];
        snprintf(p, sizeof p, "{\"permission\":{\"name\":\"%s\"},\"setting\":\"denied\"}", REFUS[i]);
        cdp_envoyer(&moteur, "Browser.setPermission", p, 0);
    }
    journal("navigateur", "moteur Chromium prêt (%s, %dx%d)", streaming ? "lecteur streaming" : "navigateur", PL, PH);
    return 0;
}

/* ---------------------------- clavier / souris ---------------------------- */
static void touche(const pf_evenement *e)
{
    sale = 1;
    if (e->code == KEY_F4 && e->ctrl) { quitter = 1; return; }
    if (arret) { if (e->code == KEY_ENTER || e->code == KEY_ESC || e->code == KEY_KPENTER) { arret = 0; } return; }
    if (alerte) {                            /* Échap = détruire (le choix conseillé) */
        if (e->code == KEY_ESC) decision_honeydog("detruire");
        return;
    }
    if (choix_ouvert) {
        if (e->code == KEY_ESC) {
            if (!choix_image) cdp_envoyer(&moteur, "DOM.setFileInputFiles", "{\"backendNodeId\":0,\"files\":[]}", 1);
            choix_ouvert = choix_image = 0;
        }
        else if (e->code == KEY_ENTER || e->code == KEY_KPENTER) envoyer_choix();
        return;
    }
    if (streaming) {
        if (vue == VUE_ACCUEIL) {
            int col = 4;
            switch (e->code) {
            case KEY_LEFT:  if (choix > 0) choix--; break;
            case KEY_RIGHT: if (choix < NB_SERVICES - 1) choix++; break;
            case KEY_UP:    if (choix >= col) choix -= col; break;
            case KEY_DOWN:  if (choix + col < NB_SERVICES) choix += col; break;
            case KEY_ENTER: case KEY_KPENTER: case KEY_SPACE: ouvrir_service(choix); break;
            case KEY_ESC: case KEY_BACKSPACE: quitter = 1; break;
            }
            return;
        }
        if (video_plein) {                         /* les touches du lecteur */
            switch (e->code) {
            case KEY_SPACE: case KEY_PLAYPAUSE: video_js("v.paused?v.play():v.pause()"); return;
            case KEY_RIGHT: video_js("v.currentTime=Math.min((v.duration||1e9),v.currentTime+10)"); return;
            case KEY_LEFT:  video_js("v.currentTime=Math.max(0,v.currentTime-10)"); return;
            case KEY_UP:    case KEY_VOLUMEUP:   video_js("v.muted=false;v.volume=Math.min(1,v.volume+0.1)"); return;
            case KEY_DOWN:  case KEY_VOLUMEDOWN: video_js("v.volume=Math.max(0,v.volume-0.1)"); return;
            case KEY_ESC:   evaluer("document.exitFullscreen&&document.exitFullscreen()", 1); video_plein = 0; return;
            }
        } else if (e->code == KEY_ESC) {
            vue = VUE_ACCUEIL;
            aller("about:blank");
            if (nb_onglets && ACTIF->image) { surface_detruire(ACTIF->image); ACTIF->image = NULL; }
            return;
        } else if (e->code == KEY_F && !e->ctrl) {
            evaluer("(()=>{const v=window.__puppyVideo&&window.__puppyVideo();if(v&&v.requestFullscreen)v.requestFullscreen()})()", 1);
        }
        if (moteur_pret) envoyer_touche(e);
        return;
    }
    if (interface_touche(e)) return;
    onglet *o = ACTIF;
    if (o->interne) return;
    dernier_geste = maintenant;
    if (moteur_pret && o->session[0]) envoyer_touche(e);
}

static void souris(const pf_evenement *e)
{
    if (e->bouton >= 0) sale = 1;
    if ((alerte || arret || choix_ouvert) && e->bouton == 0) { clic_honeydog(e->x, e->y, e->appuye); return; }
    if (alerte || arret || choix_ouvert) return;      /* la fenêtre de Honey-Dog garde la main */
    if (streaming && vue == VUE_ACCUEIL) {
        for (int i = 0; i < NB_SERVICES; i++) {
            int y, l, h, x = tuile_x(i, &y, &l, &h);
            if (e->x >= x && e->x < x + l && e->y >= y && e->y < y + h) {
                if (choix != i) sale = 1;
                choix = i;
                if (e->bouton == 0 && e->appuye) ouvrir_service(i);
            }
        }
        if (e->bouton == 1 && e->appuye) quitter = 1;
        return;
    }
    if (!streaming && interface_souris(e)) return;
    onglet *o = ACTIF;
    if (o->interne || o->erreur) return;
    if (e->bouton >= 0 && e->appuye) dernier_geste = maintenant;
    if (video_plein) osd_jusqua = maintenant + 3000;
    if (moteur_pret && o->session[0]) {
        double f = o->zoom / 100.0;
        envoyer_souris((int)((e->x - PX) / f), (int)((e->y - PY) / f), e->bouton, e->appuye);
    }
}

static void molette(const pf_evenement *e)
{
    if (!moteur_pret || (streaming && vue == VUE_ACCUEIL)) return;
    if (!streaming && interface_molette(e)) return;
    onglet *o = ACTIF;
    if (o->interne || !o->session[0]) return;
    double f = o->zoom / 100.0;
    char p[160];
    snprintf(p, sizeof p, "{\"type\":\"mouseWheel\",\"x\":%d,\"y\":%d,\"deltaX\":0,\"deltaY\":%d}",
             (int)((e->x - PX) / f), (int)((e->y - PY) / f), -e->delta * 120);
    cdp_envoyer_a(&moteur, o->session, "Input.dispatchMouseEvent", p);
}

/* clic droit dans la page : on demande au moteur ce qu'il y a dessous */
void lien_contexte(int x, int y)
{
    onglet *o = ACTIF;
    char js[2000], ej[4000], p[4200];
    double f = o->zoom / 100.0;
    /* 0.12.1 : aussi le champ modifiable sous la souris (menu d'édition) et
     * le texte choisi dans un champ (getSelection ne le voit pas) */
    snprintf(js, sizeof js,
             "(()=>{const e=document.elementFromPoint(%d,%d);const a=e&&e.closest&&e.closest('a[href]');"
             "const im=e&&e.closest&&e.closest('img');let io='';if(im){io=im.dataset.puppyOriginal||'';"
             "if(!io)try{io=new URL(im.getAttribute('src')||im.currentSrc,location.href).href}catch(x){io=im.currentSrc||''}}"
             "const ch=e&&e.closest&&e.closest('input:not([type=checkbox]):not([type=radio]):not([type=button]):not([type=submit]),textarea,[contenteditable=\"\"],[contenteditable=true]');"
             "const ed=!!(ch&&!ch.readOnly&&!ch.disabled);if(ed&&document.activeElement!==ch)ch.focus();"
             "const f=document.activeElement;let s='';"
             "try{s=(f&&(f.tagName=='INPUT'||f.tagName=='TEXTAREA')&&f.type!='password')?f.value.substring(f.selectionStart,f.selectionEnd):String(getSelection())}catch(x){}"
             "if(f&&f.type=='password')s='';"
             "return JSON.stringify({l:a?a.href:'',i:io,r:im&&im.dataset.puppyOriginal?1:0,s:s.slice(0,2000),e:ed?1:0})})()",
             (int)((x - PX) / f), (int)((y - PY) / f));
    json_echapper(ej, sizeof ej, js);
    snprintf(p, sizeof p, "{\"expression\":%s,\"returnByValue\":true,\"userGesture\":true}", ej);
    char *r = o->session[0] ? demander(o->session, "Runtime.evaluate", p, 1500) : NULL;
    char *v = r ? json_chaine_alloc(r, "result.result.value", NULL) : NULL;
    free(r);
    dialogue_lien(v ? v : "", x, y);
    free(v);
}

/* 0.12.1 : commandes d'édition du menu clic droit (annuler, refaire…) */
void edition_page(const char *commande)
{
    onglet *o = ACTIF;
    if (!o->session[0]) return;
    char p[200];
    snprintf(p, sizeof p, "{\"expression\":\"document.execCommand('%s')\",\"userGesture\":true}", commande);
    cdp_envoyer_a(&moteur, o->session, "Runtime.evaluate", p);
}

/* le clic droit tel quel, pour les sites qui ont leur propre menu */
void clic_droit_site(int x, int y)
{
    onglet *o = ACTIF;
    double f = o->zoom / 100.0;
    envoyer_souris((int)((x - PX) / f), (int)((y - PY) / f), 1, 1);
    envoyer_souris((int)((x - PX) / f), (int)((y - PY) / f), 1, 0);
}

char *nav_demander(const char *session, const char *methode, const char *params, int delai)
{
    return demander(session, methode, params, delai);
}

/* JavaScript et cookies réglés de nouveau (général ou par site) sur chaque onglet */
void sites_appliquer(void)
{
    for (int i = 0; i < nb_onglets; i++) {
        onglet *o = &onglets[i];
        if (!o->session[0] || !o->hote[0]) continue;
        int js = site_js_permis(o->hote), ck = site_cookies_permis(o->hote);
        int change = o->js_coupe != !js || o->cookies_coupes != !ck;
        if (o->js_coupe != !js) {
            o->js_coupe = !js;
            cdp_envoyer_a(&moteur, o->session, "Emulation.setScriptExecutionDisabled", js ? "{\"value\":false}" : "{\"value\":true}");
        }
        if (o->cookies_coupes != !ck) {
            o->cookies_coupes = !ck;
            cdp_envoyer_a(&moteur, o->session, "Emulation.setDocumentCookieDisabled", ck ? "{\"disabled\":false}" : "{\"disabled\":true}");
        }
        if (change) cdp_envoyer_a(&moteur, o->session, "Page.reload", "{}");
    }
    sale = 1;
}

void evaluer_public(const char *js) { evaluer(js, 1); }
void capture_publique(void) { capture_page(); }

/* glisser un lien hors de la page puis le lâcher : le relâcher dans la page
 * le rend au site (glisser-déposer interne des sites) */
void rendre_glisser(int x, int y, const char *u)
{
    onglet *o = ACTIF;
    double f = o->zoom / 100.0;
    char e[1200], p[1600];
    json_echapper(e, sizeof e, u);
    static const char *T[] = { "dragEnter", "dragOver", "drop" };
    for (int i = 0; i < 3; i++) {
        snprintf(p, sizeof p, "{\"type\":\"%s\",\"x\":%d,\"y\":%d,\"data\":{\"items\":[{\"mimeType\":\"text/uri-list\",\"data\":%s},"
                 "{\"mimeType\":\"text/plain\",\"data\":%s}],\"dragOperationsMask\":1}}", T[i], (int)((x - PX) / f), (int)((y - PY) / f), e, e);
        cdp_envoyer_a(&moteur, o->session, "Input.dispatchDragEvent", p);
    }
    bouton_souris = 0;
}

void quitter_navigateur(void) { quitter = 1; }

/* ------------------------------ dessin ------------------------------------ */
static void dessiner_message(void)
{
    if (maintenant > message_jusqua || !message[0]) return;
    int l = gfx_largeur_vect(message, 16, POIDS_NORMAL) + 40;
    int x = (EL - l) / 2, y = streaming ? 40 : PY + 16;
    gfx_ombre(toile, x, y, l, 40, 20, 10, RVBA(0, 0, 0, 120));
    gfx_rect_arrondi(toile, x, y, l, 40, 20, RVBA(30, 35, 50, 235));
    texte(x + 20, y + 10, message, RVB(255, 255, 255), 16, 0);
}

static void dessiner_page(void)
{
    onglet *o = nb_onglets ? ACTIF : NULL;
    if (o && o->interne) { page_interne_dessiner(o); return; }
    if (o && o->erreur) { page_interne_dessiner(o); return; }
    surface *im = o ? o->image : NULL;
    if (im) {
        if (im->l == PL && im->h == PH) {
            for (int j = 0; j < PH; j++)
                memcpy(&toile->px[(PY + j) * toile->l + PX], &im->px[j * im->l], (size_t)PL * 4);
        } else {
            gfx_rect(toile, PX, PY, PL, PH, RVB(255, 255, 255));
            gfx_composer_taille(toile, im, PX, PY, im->l < PL ? im->l : PL, im->h < PH ? im->h : PH, 255);
        }
    } else {
        gfx_rect(toile, PX, PY, PL, PH, streaming ? RVB(8, 12, 24) : RVB(250, 250, 252));
        const char *t = moteur_pret ? "Chargement…" : erreur_moteur[0] ? erreur_moteur : "Démarrage du moteur Chromium…";
        int l = gfx_largeur_vect(t, 22, POIDS_NORMAL);
        texte(PX + (PL - l) / 2, PY + PH / 2 - 12, t, streaming ? RVB(220, 230, 255) : RVB(60, 60, 70), 22, 0);
        /* 0.12 : au lieu d'attendre sans fin, on dit ce qui se passe */
        if (o && moteur_pret && maintenant - o->depuis > 15000) {
            const char *d = "Le moteur n'envoie aucune image. Recharge (F5) ; si rien ne change, ferme et rouvre le Navigateur.";
            int dl = gfx_largeur_vect(d, 15, POIDS_NORMAL);
            texte(PX + (PL - dl) / 2, PY + PH / 2 + 22, d, RVB(140, 100, 60), 15, 0);
        }
    }
}

void interface_debut(void);

static void dessiner(void)
{
    if (!streaming) interface_debut();       /* appli : dialogues et menus seulement */
    if (streaming && vue == VUE_ACCUEIL) dessiner_accueil();
    else {
        dessiner_page();
        if (!streaming) interface_dessiner();
        else if (nb_onglets && ACTIF->chargement)
            gfx_rect(toile, 0, 0, (int)(EL * (0.2 + 0.7 * fmod(maintenant / 1500.0, 1.0))), 4, RVB(70, 130, 230));
        dessiner_osd();
    }
    dessiner_message();
    if (choix_ouvert) dessiner_choix();
    if (alerte) dessiner_alerte();
    if (arret) dessiner_arret();
    pf_afficher(fen);
    sale = 0;
}

/* ------------------------------ main -------------------------------------- */
static int icone(const char *chemin)
{
    surface *s = surface_creer(64, 64);
    surface_vider(s, 0);
    gfx_disque_degrade(s, 32, 32, 29, RVB(110, 180, 255), RVB(20, 70, 170));
    gfx_cercle(s, 32, 32, 29, 2, RVBA(255, 255, 255, 160));
    gfx_ellipse(s, 32, 32, 12, 28, RVBA(255, 255, 255, 0));
    gfx_cercle(s, 32, 32, 20, 2, RVBA(255, 255, 255, 120));
    gfx_ligne(s, 4, 32, 60, 32, 2, RVBA(255, 255, 255, 140));
    gfx_ligne(s, 32, 4, 32, 60, 2, RVBA(255, 255, 255, 140));
    gfx_degrade(s, 12, 6, 40, 22, RVBA(255, 255, 255, 110), RVBA(255, 255, 255, 0));
    FILE *f = fopen(chemin, "wb");
    if (!f) return 1;
    fprintf(f, "P6\n64 64\n255\n");
    for (int i = 0; i < 64 * 64; i++) {
        couleur c = s->px[i];
        int a = (int)(c >> 24);
        unsigned char rgb[3] = { (unsigned char)(((c >> 16) & 255) * a / 255 + 236 * (255 - a) / 255),
                                 (unsigned char)(((c >> 8) & 255) * a / 255 + 240 * (255 - a) / 255),
                                 (unsigned char)((c & 255) * a / 255 + 247 * (255 - a) / 255) };
        fwrite(rgb, 1, 3, f);
    }
    fclose(f);
    surface_detruire(s);
    return 0;
}

#ifdef NAV_DEBUG
#include <execinfo.h>
#include <signal.h>
static void plantage(int sig)
{
    void *pile[24];
    int n = backtrace(pile, 24);
    unsigned long base = 0;
    FILE *m = fopen("/proc/self/maps", "r");
    if (m) { fscanf(m, "%lx", &base); fclose(m); }
    char t[1400];
    int o = snprintf(t, sizeof t, "signal %d :", sig);
    for (int i = 0; i < n && o < (int)sizeof t - 20; i++) o += snprintf(t + o, sizeof t - (size_t)o, " %lx", (unsigned long)pile[i] - base);
    if (toile) {
        gfx_rect(toile, 0, 0, toile->l, 120, RVB(255, 255, 200));
        gfx_texte(toile, 4, 4, t, RVB(0, 0, 0), 1);
        if (o > 190) gfx_texte(toile, 4, 24, t + 190, RVB(0, 0, 0), 1);
        if (o > 380) gfx_texte(toile, 4, 44, t + 380, RVB(0, 0, 0), 1);
        pf_afficher(fen);
    }
    journal("navigateur", "%s", t);
    sleep(120);
    _exit(1);
}
#endif

/* le bureau a réduit la fenêtre (petit écran) */
static void nouvelle_taille(int l, int h)
{
    if (l < 400 || h < PY + 100 || (l == EL && h == EH)) return;
    EL = l; EH = h;
    PL = EL; PH = EH - PY;
    for (int i = 0; i < nb_onglets; i++) if (onglets[i].vivant && !onglets[i].interne) metrique(&onglets[i]);
    if (nb_onglets && moteur_pret && !ACTIF->interne) screencast(ACTIF, 1);
    sale = 1;
}

int main(int argc, char **argv)
{
#ifdef NAV_DEBUG
    signal(SIGSEGV, plantage); signal(SIGABRT, plantage); signal(SIGBUS, plantage); signal(SIGFPE, plantage); signal(SIGILL, plantage);
    signal(SIGSYS, plantage);
    {
        char f[600];
        snprintf(f, sizeof f, "%s/navigateur-debug.txt", getenv("HOME") ? getenv("HOME") : "/tmp");
        freopen(f, "a", stderr);
        setvbuf(stderr, NULL, _IONBF, 0);
    }
#endif
    /* 0.12 : le moteur peut disparaître (Honey-Dog, plantage) : écrire vers
     * lui ne doit jamais tuer le Navigateur (SIGPIPE) */
    signal(SIGPIPE, SIG_IGN);
    if (argc == 3 && !strcmp(argv[1], "--icone")) return icone(argv[2]);
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--streaming")) streaming = 1;
        /* 2.1 : une application web (raccourci .pupweb du Bureau) */
        else if (!strncmp(argv[i], "--appli=", 8) && appli_lire(argv[i] + 8, appli_url, sizeof appli_url, appli_titre, sizeof appli_titre) == 0)
            appli = 1;
    }
    /* l'enclave du coffre naît avant tout le reste : rien du moteur ne la touche */
    if (!streaming) coffre_demarrer();
    donnees_charger();
    google_politique_charger();
    pf_evenement e;
    if (streaming || appli) {
        fen = pf_ouvrir_plein_ecran(appli ? appli_titre : "Streaming");
        if (!fen) { fprintf(stderr, "navigateur : pas de bureau PuppyOS\n"); return 1; }
        int pret = 0;
        while (!pret) {
            int r = pf_attendre(fen, &e, 3000);
            if (r <= 0) { pf_fermer(fen); return 1; }
            if (e.type == PF_EV_TAILLE) pret = 1;
        }
        toile = pf_surface(fen);
        EL = toile->l; EH = toile->h;
        PX = PY = 0; PL = EL; PH = EH;
    } else {
        EL = 1200; EH = 720;
        fen = pf_ouvrir("Navigateur", EL, EH);
        if (!fen) { fprintf(stderr, "navigateur : pas de bureau PuppyOS\n"); return 1; }
        toile = pf_surface(fen);
        PX = 0; PY = H_ONGLETS + H_OUTILS + (R.barre_favoris ? H_FAVORIS : 0); PL = EL; PH = EH - PY;
        /* 0.12.1 : sur un petit écran, le bureau répond tout de suite avec
         * la taille vraiment affichée (avant, elle se perdait pendant le
         * démarrage du moteur, et la droite des barres était coupée) */
        pf_evenement t;
        for (int k = 0; k < 4 && pf_attendre(fen, &t, 150) > 0; k++)
            if (t.type == PF_EV_TAILLE) { nouvelle_taille(t.x, t.y); break; }
    }
    /* 0.12.2 : sans alsactl, la carte démarre muette ; seul le MediaCenter
     * allumait les sorties, le Navigateur jouait donc dans le vide */
    son_mixeur_preparer();
    maintenant = horloge();
    dessiner();
    TRACE("lancement du moteur");
    if (demarrer_moteur() == 0) {
        TRACE("moteur prêt");
        if (appli) {
            /* l'application : un seul onglet, plein écran, son propre bac
             * (après le premier lancement, s'il n'a jamais été fait) */
            if (R.premier_fait) onglet_nouveau(appli_url, 1, 0);
        } else if (streaming) {
            int i = nouvelle_place(0);
            char *r = demander(NULL, "Target.createTarget", "{\"url\":\"about:blank\"}", 30000);
            char cible[64] = "";
            if (r && json_chaine(r, "result.targetId", cible, sizeof cible) && attacher(&onglets[i], cible) == 0) onglet_activer(i);
            else { snprintf(erreur_moteur, sizeof erreur_moteur, "onglet impossible à ouvrir"); moteur_pret = 0; }
            free(r);
        } else if (!R.premier_fait) {
            /* 2.1 : rien ne s'ouvre avant le premier lancement (présentation
             * et réglage de la sécurité) : premier_terminer() ouvrira l'accueil */
        } else if (onglet_nouveau(R.accueil, 1, 0) >= 0 && !R.accueil[0]) barre_saisie_activer();
    }
    TRACE("premier onglet fait, moteur_pret=%d %s", moteur_pret, erreur_moteur);
    if (!moteur_pret) journal("navigateur", "moteur indisponible : %s", erreur_moteur);
    sale = 1;
    double prochain_tic = 0;
    while (!quitter) {
        maintenant = horloge();
        struct pollfd p[3] = { { pf_descripteur(fen), POLLIN, 0 }, { moteur.lire, POLLIN, 0 }, { moteur.canal, POLLIN, 0 } };
        int anime = (streaming && vue == VUE_ACCUEIL) || (nb_onglets && ACTIF->chargement) || maintenant < anime_jusqua ||
                    (video_plein && maintenant < osd_jusqua + 500) || maintenant < message_jusqua + 200;
        int np = (moteur_pret ? 2 : 1) + (moteur.canal >= 0 ? 1 : 0);
        if (moteur.canal >= 0 && !moteur_pret) p[1] = p[2];
        poll(p, np, sale ? 0 : anime ? 33 : 250);
        maintenant = horloge();
        if (moteur.canal >= 0 && ((moteur_pret ? p[2] : p[1]).revents & (POLLIN | POLLHUP))) lire_honeydog();
        if (moteur_pret && (p[1].revents & (POLLHUP | POLLERR)) && !(p[1].revents & POLLIN)) {
            moteur_pret = 0;
            snprintf(erreur_moteur, sizeof erreur_moteur, "le moteur Chromium s'est arrêté");
            TRACE("moteur arrêté (revents %x)", p[1].revents);
            journal("navigateur", "%s", erreur_moteur);
            for (int i = 0; i < nb_onglets; i++) if (onglets[i].image) { surface_detruire(onglets[i].image); onglets[i].image = NULL; }
            sale = 1;
        }
        if (moteur_pret) {
            char *m;
            while ((m = cdp_suivant(&moteur))) evenement_moteur(m, NULL);
        }
        int r;
        while ((r = pf_attendre(fen, &e, 0)) == 1) {
            if (e.type == PF_EV_TOUCHE) touche(&e);
            else if (e.type == PF_EV_SOURIS) souris(&e);
            else if (e.type == PF_EV_MOLETTE) molette(&e);
            else if (e.type == PF_EV_FERMETURE) quitter = 1;
            else if (e.type == PF_EV_TAILLE && !streaming) nouvelle_taille(e.x, e.y);
            if (quitter) break;
        }
        if (r < 0) break;
        if (!streaming && moteur_pret && nb_onglets && maintenant > prochain_titre) {
            prochain_titre = maintenant + 2000;
            demander_titre(ACTIF);
        }
        if (streaming && moteur_pret && vue == VUE_PAGE && maintenant > prochain_etat && id_etat < 0) {
            id_etat = evaluer(JS_ETAT, 0);
            prochain_etat = maintenant + 500;
        }
        if (maintenant > prochain_tic) {
            prochain_tic = maintenant + 1000;
            if (!streaming) {
                historique_enregistrer();
                /* coffre : refermé après un temps sans usage, et quand la session se verrouille */
                if (coffre_disponible() && (maintenant - coffre_activite > R.verrou_min * 60000.0 ||
                                            access("/run/puppy/verrouille", F_OK) == 0)) {
                    static double dernier;
                    if (maintenant - dernier > 5000) { coffre_verrouiller(); dernier = maintenant; }
                }
                for (int i = 0; i < nb_onglets; i++) if (onglets[i].cookies_coupes && !onglets[i].chargement && (int)(maintenant / 1000) % 5 == 0) purger_cookies(&onglets[i]);
            }
        }
        if (sale || anime) dessiner();
    }
    journal("navigateur", "fermé");
    if (!streaming) {
        if (R.effacer_en_quittant) effacer_donnees(1, 1, 1, 0);
        historique_sauver_maintenant();
        coffre_arreter();
    }
    if (moteur_pret || moteur.pid) cdp_arreter(&moteur);
    pf_fermer(fen);
    return 0;
}
