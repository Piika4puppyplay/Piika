#define _GNU_SOURCE
#include "cdp.h"
#include "autorite.h"
#include "chromium.h"
#include "puppy.h"
#include <sys/socket.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* puppy-bac est un programme du système (dans le PATH de PuppyOS) */
static const char *BAC[] = { "/system/actuel/bin/puppy-bac", "/system/bin/puppy-bac", NULL };

static int finir_lancement(cdp *c, int ecrire, int lire, pid_t pid)
{
    c->ecrire = ecrire;
    c->lire = lire;
    c->pid = pid;
    fcntl(c->lire, F_SETFL, fcntl(c->lire, F_GETFL) | O_NONBLOCK);
    c->cap = 1 << 20;
    c->tampon = malloc(c->cap);
    c->prochain_id = 1;
    return c->tampon ? 0 : -1;
}

int cdp_lancer(cdp *c, const char *mode, int largeur, int hauteur, char *err, size_t nerr)
{
    memset(c, 0, sizeof *c);
    c->ecrire = c->lire = c->canal = -1;
    const char *bac = getenv("PUPPY_BAC");          /* tests sur le PC de développement */
    if (access(CHROMIUM_MOTEUR "/chrome/headless_shell", F_OK) < 0 && !bac) {
        snprintf(err, nerr, "Moteur Chromium absent : Mises à jour → Moteur Chromium → Installer");
        return -1;
    }

    if (!bac && geteuid() != 0) {
        /* 0.10 : un programme n'a aucun droit ; puppy-autorite (root) vérifie
         * que nous sommes bien le Navigateur et lance le bac à sable pour nous.
         * Une seule prise sert aux deux sens (le moteur lit 3 et écrit 4). */
        int sv[2];
        if (socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, sv) < 0) {
            snprintf(err, nerr, "prise : %s", strerror(errno));
            return -1;
        }
        char taille[32];
        snprintf(taille, sizeof taille, "%dx%d", largeur, hauteur);
        autorite_reponse r;
        int canal = -1;
        int code = autorite_demander(AUT_BAC, mode, taille, NULL, sv[1], &canal, &r);
        close(sv[1]);
        if (code != AUT_OK) {
            if (canal >= 0) close(canal);
            close(sv[0]);
            snprintf(err, nerr, "bac à sable refusé : %s", r.texte);
            return -1;
        }
        int lire = fcntl(sv[0], F_DUPFD_CLOEXEC, 0);
        if (lire < 0) { close(sv[0]); if (canal >= 0) close(canal); return -1; }
        c->canal = canal;                  /* Honey-Dog (0.11) ; -1 avec un ancien système */
        return finir_lancement(c, sv[0], lire, 0);
    }

    for (int i = 0; !bac && BAC[i]; i++) if (access(BAC[i], X_OK) == 0) bac = BAC[i];
    if (!bac) { snprintf(err, nerr, "puppy-bac introuvable (PuppyOS 0.10 ou plus récent)"); return -1; }
    int vers[2], depuis[2], alerte[2];
    if (pipe2(vers, O_CLOEXEC) < 0 || pipe2(depuis, O_CLOEXEC) < 0 ||
        socketpair(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0, alerte) < 0) {
        snprintf(err, nerr, "tubes : %s", strerror(errno));
        return -1;
    }
    char *argv[CHROMIUM_ARGS_MAX], stock[512];
    if (chromium_arguments(bac, mode, largeur, hauteur, argv, stock, sizeof stock) < 0) {
        snprintf(err, nerr, "taille d'écran invalide");
        return -1;
    }
    pid_t p = fork();
    if (p < 0) { snprintf(err, nerr, "fork : %s", strerror(errno)); return -1; }
    if (p == 0) {
        /* le moteur lit le descripteur 3 et écrit sur le 4 */
        int r = dup(vers[0]), w = dup(depuis[1]), a = dup(alerte[1]);
        if (r < 0 || w < 0 || a < 0 || dup2(r, 3) < 0 || dup2(w, 4) < 0 || dup2(a, 5) < 0) _exit(127);
        int nul = open("/dev/null", O_RDWR);
        if (nul >= 0) { dup2(nul, 0); }
        execv(bac, argv);
        _exit(127);
    }
    close(vers[0]);
    close(depuis[1]);
    close(alerte[1]);
    c->canal = alerte[0];
    return finir_lancement(c, vers[1], depuis[0], p);
}

void cdp_arreter(cdp *c)
{
    if (c->ecrire >= 0) {
        cdp_envoyer(c, "Browser.close", "{}", 0);
        close(c->ecrire);
    }
    if (c->lire >= 0) close(c->lire);
    if (c->canal >= 0) close(c->canal);
    if (c->pid > 0) {
        /* on laisse 2 s au moteur pour fermer proprement, puis on l'arrête */
        for (int i = 0; i < 40; i++) {
            if (waitpid(c->pid, NULL, WNOHANG) == c->pid) { c->pid = 0; break; }
            usleep(50000);
        }
        if (c->pid > 0) { kill(c->pid, SIGKILL); waitpid(c->pid, NULL, 0); }
    }
    free(c->tampon);
    memset(c, 0, sizeof *c);
    c->ecrire = c->lire = c->canal = -1;
}

/* 0.12 : la prise vers le moteur est NON BLOQUANTE (le même descripteur sert
 * à lire et à écrire quand puppy-autorite a lancé le bac) : quand Chromium
 * est occupé et que la prise est pleine, write() répond EAGAIN. Avant, le
 * message était jeté (souvent l'accusé de réception d'une image ou
 * Page.navigate) : Chromium attendait cet accusé pour envoyer l'image
 * suivante, et la page restait sur « Chargement… ». On attend donc que la
 * prise se vide, sans jamais couper un message en deux. */
static int ecrire_tout(cdp *c, const char *p, size_t l)
{
    while (l > 0) {
        ssize_t w = write(c->ecrire, p, l);
        if (w < 0 && errno == EINTR) continue;
        if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            struct pollfd q = { c->ecrire, POLLOUT, 0 };
            if (poll(&q, 1, 10000) <= 0 || (q.revents & (POLLERR | POLLHUP))) return -1;
            continue;
        }
        if (w <= 0) return -1;
        p += w; l -= (size_t)w;
    }
    return 0;
}

static int envoyer_vers(cdp *c, const char *session, const char *methode, const char *params, int secret)
{
    if (c->ecrire < 0) return -1;
    int id = c->prochain_id++;
    size_t n = strlen(params) + strlen(methode) + 200;
    char *m = malloc(n);
    if (!m) return -1;
    int l;
    if (session && session[0])
        l = snprintf(m, n, "{\"id\":%d,\"sessionId\":\"%s\",\"method\":\"%s\",\"params\":%s}", id, session, methode, params);
    else
        l = snprintf(m, n, "{\"id\":%d,\"method\":\"%s\",\"params\":%s}", id, methode, params);
    if (l < 0 || (size_t)l >= n) { free(m); return -1; }
    m[l++] = 0;                                         /* fin de message */
    int r = ecrire_tout(c, m, (size_t)l);
    if (secret) explicit_bzero(m, n);                   /* mot de passe : rien ne traîne en mémoire */
    free(m);
    if (r < 0) return -1;
    return id;
}

int cdp_envoyer(cdp *c, const char *methode, const char *params, int avec_session)
{
    return envoyer_vers(c, avec_session ? c->session : NULL, methode, params, 0);
}

int cdp_envoyer_a(cdp *c, const char *session, const char *methode, const char *params)
{
    return envoyer_vers(c, session, methode, params, 0);
}

int cdp_envoyer_secret(cdp *c, const char *session, const char *methode, const char *params)
{
    return envoyer_vers(c, session, methode, params, 1);
}

char *cdp_suivant(cdp *c)
{
    for (;;) {
        char *z = memchr(c->tampon, 0, c->lu);
        if (z) {
            size_t n = (size_t)(z - c->tampon);
            char *m = malloc(n + 1);
            if (!m) return NULL;
            memcpy(m, c->tampon, n + 1);
            memmove(c->tampon, z + 1, c->lu - n - 1);
            c->lu -= n + 1;
            if (!json_valide(m)) { free(m); continue; }   /* message mal formé : jeté */
            return m;
        }
        if (c->lu + 65536 > c->cap) {
            if (c->cap >= (64u << 20)) { c->lu = 0; return NULL; }   /* message absurde : on jette */
            char *t = realloc(c->tampon, c->cap * 2);
            if (!t) return NULL;
            c->tampon = t;
            c->cap *= 2;
        }
        ssize_t r = read(c->lire, c->tampon + c->lu, c->cap - c->lu);
        if (r <= 0) return NULL;
        c->lu += (size_t)r;
    }
}

int cdp_reponse_a(const char *m, int id)
{
    double v;
    return json_nombre(m, "id", &v) && v == id;
}

static double ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}

char *cdp_attendre(cdp *c, int id, int delai_ms, void (*rappel)(char *, void *), void *d)
{
    double fin = ms() + delai_ms;
    for (;;) {
        char *m;
        while ((m = cdp_suivant(c))) {
            if (cdp_reponse_a(m, id)) return m;
            if (rappel) rappel(m, d); else free(m);
        }
        double reste = fin - ms();
        if (reste <= 0) return NULL;
        struct pollfd p = { c->lire, POLLIN, 0 };
        if (poll(&p, 1, (int)reste) <= 0) { if (reste <= 0) return NULL; }
        if (p.revents & (POLLHUP | POLLERR) && !(p.revents & POLLIN)) return NULL;
    }
}

/* ------------------------------- JSON ---------------------------------- *
 * 0.11 : le moteur est traité en ennemi (une page a pu en prendre le
 * contrôle). Chaque message est d'abord VALIDÉ en entier (grammaire JSON,
 * profondeur bornée), puis on lit des valeurs par leur CHEMIN exact
 * (« params.request.url »), jamais en cherchant une clé n'importe où :
 * une page ne peut plus glisser une fausse clé dans un titre ou une adresse.
 * ------------------------------------------------------------------------- */
#define JSON_PROFONDEUR 64

static const char *blanc(const char *p) { while (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r') p++; return p; }

static int hex(char c)
{
    return c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
}

/* p sur le guillemet ouvrant ; renvoie l'octet après le guillemet fermant */
static const char *sauter_chaine(const char *p)
{
    if (*p != '"') return NULL;
    for (p++; ; p++) {
        unsigned char c = (unsigned char)*p;
        if (c == '"') return p + 1;
        if (c < 0x20) return NULL;                  /* fin de texte ou caractère de contrôle */
        if (c == '\\') {
            p++;
            if (*p == 'u') {
                for (int i = 1; i <= 4; i++) if (hex(p[i]) < 0) return NULL;
                p += 4;
            } else if (!strchr("\"\\/bfnrt", *p) || !*p) return NULL;
        }
    }
}

static const char *sauter_valeur(const char *p, int profondeur)
{
    p = blanc(p);
    if (profondeur > JSON_PROFONDEUR) return NULL;
    if (*p == '"') return sauter_chaine(p);
    if (*p == '{' || *p == '[') {
        char fin = *p == '{' ? '}' : ']';
        p = blanc(p + 1);
        if (*p == fin) return p + 1;
        for (;;) {
            if (fin == '}') {
                if (!(p = sauter_chaine(blanc(p)))) return NULL;
                p = blanc(p);
                if (*p++ != ':') return NULL;
            }
            if (!(p = sauter_valeur(p, profondeur + 1))) return NULL;
            p = blanc(p);
            if (*p == fin) return p + 1;
            if (*p++ != ',') return NULL;
            p = blanc(p);
        }
    }
    if (!strncmp(p, "true", 4)) return p + 4;
    if (!strncmp(p, "false", 5)) return p + 5;
    if (!strncmp(p, "null", 4)) return p + 4;
    const char *d = p;
    if (*p == '-') p++;
    if (*p < '0' || *p > '9') return NULL;
    while ((*p >= '0' && *p <= '9') || *p == '.' || *p == 'e' || *p == 'E' || *p == '+' || *p == '-') p++;
    return p > d ? p : NULL;
}

int json_valide(const char *json)
{
    const char *p = sauter_valeur(json, 0);
    return p && *blanc(p) == 0;
}

/* valeur au chemin « a.b.c » (objets seulement), ou NULL */
static const char *chemin(const char *json, const char *ch)
{
    const char *p = blanc(json);
    while (*ch) {
        size_t lc = strcspn(ch, ".");
        if (*p != '{') return NULL;
        p = blanc(p + 1);
        const char *trouve = NULL;
        while (*p == '"') {
            const char *k = p + 1, *fk = sauter_chaine(p);
            if (!fk) return NULL;
            int egal = (size_t)(fk - 1 - k) == lc && !memcmp(k, ch, lc);
            p = blanc(fk);
            if (*p++ != ':') return NULL;
            p = blanc(p);
            if (egal && !trouve) trouve = p;
            const char *f = sauter_valeur(p, 0);
            if (!f) return NULL;
            p = blanc(f);
            if (*p == ',') p = blanc(p + 1);
        }
        if (!trouve) return NULL;
        p = trouve;
        ch += lc;
        if (*ch == '.') ch++;
    }
    return p;
}

static void utf8(unsigned cp, char *dst, size_t n, size_t *o)
{
    char u[4];
    int k = 0;
    if (cp < 0x80) u[k++] = (char)cp;
    else if (cp < 0x800) { u[k++] = (char)(0xC0 | cp >> 6); u[k++] = (char)(0x80 | (cp & 63)); }
    else if (cp < 0x10000) { u[k++] = (char)(0xE0 | cp >> 12); u[k++] = (char)(0x80 | ((cp >> 6) & 63)); u[k++] = (char)(0x80 | (cp & 63)); }
    else { u[k++] = (char)(0xF0 | cp >> 18); u[k++] = (char)(0x80 | ((cp >> 12) & 63)); u[k++] = (char)(0x80 | ((cp >> 6) & 63)); u[k++] = (char)(0x80 | (cp & 63)); }
    for (int i = 0; i < k; i++) if (*o + 1 < n) dst[(*o)++] = u[i];
}

static unsigned hex4(const char *p)
{
    unsigned v = 0;
    for (int i = 0; i < 4; i++) { int h = hex(p[i]); if (h < 0) return 0xFFFFFFFFu; v = v * 16 + (unsigned)h; }
    return v;
}

/* p sur le guillemet ouvrant d'une chaîne DÉJÀ validée ; écrit au plus n-1
 * octets + 0 ; renvoie la longueur écrite. Jamais d'octet nul au milieu. */
static size_t desechapper(const char *p, char *dst, size_t n)
{
    size_t o = 0;
    for (p++; *p && *p != '"'; p++) {
        unsigned cp = (unsigned char)*p;
        if (*p == '\\' && p[1]) {
            p++;
            switch (*p) {
            case 'n': cp = '\n'; break;
            case 't': cp = '\t'; break;
            case 'r': cp = '\r'; break;
            case 'b': cp = '\b'; break;
            case 'f': cp = '\f'; break;
            case 'u': {
                cp = hex4(p + 1);
                if (cp == 0xFFFFFFFFu) { cp = '?'; break; }
                p += 4;
                if (cp >= 0xD800 && cp <= 0xDBFF && p[1] == '\\' && p[2] == 'u') {
                    unsigned bas = hex4(p + 3);
                    if (bas >= 0xDC00 && bas <= 0xDFFF) { cp = 0x10000 + ((cp - 0xD800) << 10) + (bas - 0xDC00); p += 6; }
                }
                if ((cp >= 0xD800 && cp <= 0xDFFF) || cp == 0) cp = '?';   /* moitié orpheline, ou nul */
                utf8(cp, dst, n, &o);
                continue;
            }
            default: cp = (unsigned char)*p;
            }
        }
        if (o + 1 < n) dst[o++] = (char)cp;
    }
    if (n) dst[o < n ? o : n - 1] = 0;
    return o;
}

int json_chaine(const char *json, const char *ch, char *dst, size_t n)
{
    const char *p = chemin(json, ch);
    if (!p || *p != '"' || !sauter_chaine(p)) { if (n) dst[0] = 0; return 0; }
    desechapper(p, dst, n);
    return 1;
}

char *json_chaine_alloc(const char *json, const char *ch, size_t *n)
{
    const char *p = chemin(json, ch);
    const char *f = p && *p == '"' ? sauter_chaine(p) : NULL;
    if (!f) return NULL;
    size_t l = (size_t)(f - p) + 1;
    char *d = malloc(l);
    if (!d) return NULL;
    size_t o = desechapper(p, d, l);
    if (n) *n = o;
    return d;
}

int json_nombre(const char *json, const char *ch, double *v)
{
    const char *p = chemin(json, ch);
    if (!p || !((*p >= '0' && *p <= '9') || *p == '-')) return 0;
    char *f;
    double x = strtod(p, &f);
    if (f == p || x != x) return 0;
    *v = x;
    return 1;
}

void json_echapper(char *dst, size_t n, const char *s)
{
    size_t o = 0;
    if (o + 1 < n) dst[o++] = '"';
    for (; *s && o + 8 < n; s++) {
        unsigned char ch = (unsigned char)*s;
        if (ch == '"' || ch == '\\') { dst[o++] = '\\'; dst[o++] = (char)ch; }
        else if (ch == '\n') { dst[o++] = '\\'; dst[o++] = 'n'; }
        else if (ch < 32) o += (size_t)snprintf(dst + o, n - o, "\\u%04x", ch);
        else dst[o++] = (char)ch;
    }
    if (o + 1 < n) dst[o++] = '"';
    dst[o < n ? o : n - 1] = 0;
}

size_t base64_decoder(const char *src, size_t n, uint8_t *dst)
{
    static int8_t t[256];
    static int pret;
    if (!pret) {
        memset(t, -1, sizeof t);
        const char *a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < 64; i++) t[(unsigned char)a[i]] = (int8_t)i;
        pret = 1;
    }
    size_t o = 0;
    uint32_t acc = 0;
    int bits = 0;
    for (size_t i = 0; i < n; i++) {
        int v = t[(unsigned char)src[i]];
        if (v < 0) continue;
        acc = acc << 6 | (uint32_t)v;
        bits += 6;
        if (bits >= 8) { bits -= 8; dst[o++] = (uint8_t)(acc >> bits); }
    }
    return o;
}

/* 2.1 : base64 (images locales du site) ; renvoie le nombre d'octets écrits */
size_t base64_encoder(const uint8_t *src, size_t n, char *dst, size_t nd)
{
    static const char a[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    size_t o = 0;
    for (size_t i = 0; i < n && o + 4 < nd; i += 3) {
        uint32_t v = (uint32_t)src[i] << 16 | (i + 1 < n ? (uint32_t)src[i + 1] << 8 : 0) | (i + 2 < n ? src[i + 2] : 0);
        dst[o++] = a[v >> 18 & 63];
        dst[o++] = a[v >> 12 & 63];
        dst[o++] = i + 1 < n ? a[v >> 6 & 63] : '=';
        dst[o++] = i + 2 < n ? a[v & 63] : '=';
    }
    if (o < nd) dst[o] = 0;
    return o;
}

/* 0.12 : chaque membre « chaîne » de l'objet au chemin ch (en-têtes HTTP) */
int json_objet_parcourir(const char *json, const char *ch, void (*f)(const char *cle, const char *val, void *d), void *d)
{
    const char *p = ch && *ch ? chemin(json, ch) : blanc(json);
    if (!p || *p != '{') return 0;
    p = blanc(p + 1);
    int n = 0;
    while (*p == '"') {
        const char *fk = sauter_chaine(p);
        if (!fk) return n;
        char cle[256], val[8192];
        desechapper(p, cle, sizeof cle);
        p = blanc(fk);
        if (*p++ != ':') return n;
        p = blanc(p);
        const char *fv = sauter_valeur(p, 0);
        if (!fv) return n;
        if (*p == '"') { desechapper(p, val, sizeof val); f(cle, val, d); n++; }
        p = blanc(fv);
        if (*p == ',') p = blanc(p + 1);
    }
    return n;
}

int json_booleen(const char *json, const char *ch, int *v)
{
    const char *p = chemin(json, ch);
    if (!p) return 0;
    if (!strncmp(p, "true", 4)) { *v = 1; return 1; }
    if (!strncmp(p, "false", 5)) { *v = 0; return 1; }
    return 0;
}

/* présence d'une clé (n'importe quelle valeur) */
int json_existe(const char *json, const char *ch)
{
    return chemin(json, ch) != NULL;
}

/* 0.12 : le i-ième élément du tableau au chemin ch (pointeur dans json) */
const char *json_element(const char *json, const char *ch, int i)
{
    const char *p = chemin(json, ch);
    if (!p || *p != '[') return NULL;
    p = blanc(p + 1);
    for (int k = 0; *p && *p != ']'; k++) {
        if (k == i) return p;
        const char *f = sauter_valeur(p, 0);
        if (!f) return NULL;
        p = blanc(f);
        if (*p == ',') p = blanc(p + 1);
    }
    return NULL;
}
