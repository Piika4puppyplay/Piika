/*
 * Coffre à mots de passe du Navigateur (0.12) : une ENCLAVE.
 *
 * Les mots de passe ne vivent en clair que dans un petit processus à part,
 * créé avant le moteur Chromium et enfermé aussitôt :
 *   - seccomp strict : il ne peut plus que lire et écrire sur sa prise,
 *     tirer du hasard et s'arrêter (ni fichier, ni réseau, ni processus) ;
 *   - sa mémoire est verrouillée (mlock : jamais sur un disque), exclue des
 *     vidages (MADV_DONTDUMP) et illisible par les autres (PR_SET_DUMPABLE 0) ;
 *   - la clé AES-256 est dérivée du mot de passe maître par Argon2id
 *     (32 Mio, 3 passes) et ne sort jamais de l'enclave.
 * Le fichier du coffre est chiffré en AES-256-GCM (l'étiquette GCM détecte
 * la moindre modification). Le Navigateur ne garde que le fichier chiffré ;
 * il demande un mot de passe précis, pour le site exact, au moment de
 * remplir, puis efface sa copie (explicit_bzero).
 *
 * Jamais de carte bancaire : l'enclave refuse tout ce qui ressemble à un
 * numéro de carte (13 à 19 chiffres valides selon Luhn).
 */
#define _GNU_SOURCE
#include "coffre-nav.h"
#include "crypto.h"
#include "monocypher.h"
#include <errno.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/random.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

/* ----------------------------- AES-256-GCM ----------------------------- */
static void gf_mul(uint8_t x[16], const uint8_t h[16])
{
    uint8_t z[16] = { 0 }, v[16];
    memcpy(v, h, 16);
    for (int i = 0; i < 128; i++) {
        if (x[i / 8] & (0x80 >> (i % 8)))
            for (int k = 0; k < 16; k++) z[k] ^= v[k];
        int bas = v[15] & 1;
        for (int k = 15; k > 0; k--) v[k] = (uint8_t)((v[k] >> 1) | (v[k - 1] << 7));
        v[0] >>= 1;
        if (bas) v[0] ^= 0xE1;
    }
    memcpy(x, z, 16);
}

static void ghash(const uint8_t h[16], const uint8_t *c, size_t n, uint8_t s[16])
{
    memset(s, 0, 16);
    for (size_t i = 0; i < n; i += 16) {
        size_t l = n - i < 16 ? n - i : 16;
        for (size_t k = 0; k < l; k++) s[k] ^= c[i + k];
        gf_mul(s, h);
    }
    uint8_t lg[16] = { 0 };                       /* pas de données associées ; longueur en bits */
    uint64_t bits = (uint64_t)n * 8;
    for (int k = 0; k < 8; k++) lg[15 - k] = (uint8_t)(bits >> (8 * k));
    for (int k = 0; k < 16; k++) s[k] ^= lg[k];
    gf_mul(s, h);
}

static void ctr32(const aes256_ctx *a, const uint8_t j0[16], uint8_t *buf, size_t n)
{
    uint8_t cpt[16], flux[16];
    memcpy(cpt, j0, 16);
    for (size_t i = 0; i < n; i += 16) {
        for (int k = 15; k >= 12; k--) if (++cpt[k]) break;     /* inc32 */
        aes256_bloc(a, cpt, flux);
        size_t l = n - i < 16 ? n - i : 16;
        for (size_t k = 0; k < l; k++) buf[i + k] ^= flux[k];
    }
    explicit_bzero(flux, sizeof flux);
}

/* chiffre buf (n octets) en place ; tag = 16 octets */
void gcm_chiffrer(const uint8_t cle[32], const uint8_t iv[12], uint8_t *buf, size_t n, uint8_t tag[16])
{
    aes256_ctx a;
    aes256_cle(&a, cle);
    uint8_t h[16] = { 0 }, j0[16] = { 0 }, s[16], e[16];
    aes256_bloc(&a, h, h);
    memcpy(j0, iv, 12);
    j0[15] = 1;
    ctr32(&a, j0, buf, n);
    ghash(h, buf, n, s);
    aes256_bloc(&a, j0, e);
    for (int k = 0; k < 16; k++) tag[k] = s[k] ^ e[k];
    explicit_bzero(&a, sizeof a);
    explicit_bzero(h, sizeof h);
}

/* 0 si l'étiquette est bonne (buf est alors déchiffré), -1 sinon (buf intact) */
int gcm_dechiffrer(const uint8_t cle[32], const uint8_t iv[12], uint8_t *buf, size_t n, const uint8_t tag[16])
{
    aes256_ctx a;
    aes256_cle(&a, cle);
    uint8_t h[16] = { 0 }, j0[16] = { 0 }, s[16], e[16], t[16];
    aes256_bloc(&a, h, h);
    memcpy(j0, iv, 12);
    j0[15] = 1;
    ghash(h, buf, n, s);
    aes256_bloc(&a, j0, e);
    for (int k = 0; k < 16; k++) t[k] = s[k] ^ e[k];
    int ok = comparer_constant(t, tag, 16) == 0;
    if (ok) ctr32(&a, j0, buf, n);
    explicit_bzero(&a, sizeof a);
    explicit_bzero(h, sizeof h);
    return ok ? 0 : -1;
}

/* ------------------------------ l'enclave ------------------------------ */
#define MAGIC        "PUPCOF01"
#define ENTREES_MAX  400
#define ARGON_KIO    (32 * 1024)
#define ARGON_PASSES 3

typedef struct { char hote[128], nom[128], mdp[128]; } entree;

/* tout ce qui est secret est ici, dans une seule zone verrouillée */
static struct {
    uint8_t cle[32], sel[16];
    int     ouvert, nb;
    entree  e[ENTREES_MAX];
    uint8_t clair[ENTREES_MAX * 390 + 64];
    uint8_t message[COFFRE_MESSAGE_MAX];
    uint8_t reponse[COFFRE_MESSAGE_MAX];
} S __attribute__((aligned(4096)));
static uint8_t travail[ARGON_KIO * 1024] __attribute__((aligned(4096)));

static int ressemble_carte(const char *s)
{
    int chiffres[32], n = 0;
    for (const char *p = s; *p; p++) {
        if (*p >= '0' && *p <= '9') { if (n >= 32) return 0; chiffres[n++] = *p - '0'; }
        else if (*p != ' ' && *p != '-') return 0;
    }
    if (n < 13 || n > 19) return 0;
    int somme = 0;
    for (int i = 0; i < n; i++) {
        int d = chiffres[n - 1 - i];
        if (i % 2) { d *= 2; if (d > 9) d -= 9; }
        somme += d;
    }
    return somme % 10 == 0;
}

static void deriver(const char *mdp)
{
    crypto_argon2_config c = { CRYPTO_ARGON2_ID, ARGON_KIO, ARGON_PASSES, 1 };
    crypto_argon2_inputs in = { (const uint8_t *)mdp, S.sel, (uint32_t)strlen(mdp), 16 };
    crypto_argon2(S.cle, 32, travail, c, in, crypto_argon2_no_extras);
    explicit_bzero(travail, sizeof travail);
}

static void tout_effacer(void)
{
    explicit_bzero(&S.cle, sizeof S.cle);
    explicit_bzero(S.e, sizeof S.e);
    explicit_bzero(S.clair, sizeof S.clair);
    S.ouvert = 0;
    S.nb = 0;
}

/* réponse : statut + données */
static size_t rep(char statut, const void *d, size_t n)
{
    S.reponse[0] = (uint8_t)statut;
    if (n > sizeof S.reponse - 1) n = sizeof S.reponse - 1;
    if (d && n) memcpy(S.reponse + 1, d, n);
    return n + 1;
}

/* blob chiffré du coffre dans S.reponse (après 'K') */
static size_t sceller(void)
{
    size_t n = 0;
    for (int i = 0; i < S.nb; i++) {
        int l = snprintf((char *)S.clair + n, sizeof S.clair - n, "%s\t%s\t%s\n", S.e[i].hote, S.e[i].nom, S.e[i].mdp);
        if (l < 0 || (size_t)l >= sizeof S.clair - n) break;
        n += (size_t)l;
    }
    uint8_t *o = S.reponse + 1, iv[12], tag[16];
    if (8 + 16 + 12 + n + 16 > sizeof S.reponse - 1) return rep('E', "coffre plein", 12);
    getrandom(iv, sizeof iv, 0);
    memcpy(o, MAGIC, 8);
    memcpy(o + 8, S.sel, 16);
    memcpy(o + 24, iv, 12);
    memcpy(o + 36, S.clair, n);
    gcm_chiffrer(S.cle, iv, o + 36, n, tag);
    memcpy(o + 36 + n, tag, 16);
    explicit_bzero(S.clair, n);
    S.reponse[0] = 'K';
    return 1 + 36 + n + 16;
}

static int champ_sur(const char *s)
{
    if (!*s || strlen(s) >= 128) return 0;
    for (const char *p = s; *p; p++) if (*p == '\t' || *p == '\n' || *p == '\r') return 0;
    return 1;
}

static size_t ouvrir(const uint8_t *m, size_t n)
{
    const char *mdp = (const char *)m;
    size_t lm = strnlen(mdp, n);
    if (lm == n || lm < 4) return rep('E', "mot de passe trop court (4 caractères au moins)", 47);
    const uint8_t *b = m + lm + 1;
    size_t nb = n - lm - 1;
    tout_effacer();
    if (nb == 0) {                                 /* coffre neuf */
        getrandom(S.sel, sizeof S.sel, 0);
        deriver(mdp);
        S.ouvert = 1;
        return sceller();
    }
    if (nb < 8 + 16 + 12 + 16 || memcmp(b, MAGIC, 8) || nb - 52 > sizeof S.clair) return rep('E', "fichier du coffre abîmé", 24);
    memcpy(S.sel, b + 8, 16);
    deriver(mdp);
    size_t lc = nb - 52;
    memcpy(S.clair, b + 36, lc);
    if (gcm_dechiffrer(S.cle, b + 24, S.clair, lc, b + 36 + lc) < 0) {
        tout_effacer();
        return rep('E', "mot de passe maître faux", 25);
    }
    char *p = (char *)S.clair, *fin = p + lc;
    while (p < fin && S.nb < ENTREES_MAX) {
        char *nl = memchr(p, '\n', (size_t)(fin - p));
        if (!nl) break;
        *nl = 0;
        char *t1 = strchr(p, '\t'), *t2 = t1 ? strchr(t1 + 1, '\t') : NULL;
        if (t1 && t2) {
            *t1 = *t2 = 0;
            entree *e = &S.e[S.nb++];
            snprintf(e->hote, sizeof e->hote, "%s", p);
            snprintf(e->nom, sizeof e->nom, "%s", t1 + 1);
            snprintf(e->mdp, sizeof e->mdp, "%s", t2 + 1);
        }
        p = nl + 1;
    }
    explicit_bzero(S.clair, lc);
    S.ouvert = 1;
    return rep('K', NULL, 0);
}

static size_t traiter(size_t n)
{
    uint8_t *m = S.message;
    if (n < 1) return rep('E', "?", 1);
    char op = (char)m[0];
    m++; n--;
    m[n] = 0;                                     /* le tampon a toujours un octet de plus */
    if (op == 'O') return ouvrir(m, n);
    if (op == 'V') { tout_effacer(); return rep('K', NULL, 0); }
    if (op == 'T') return rep('K', S.ouvert ? "1" : "0", 1);
    if (!S.ouvert) return rep('F', "coffre fermé", 13);
    switch (op) {
    case 'L': {                                   /* liste sans les mots de passe */
        size_t o = 0;
        char *d = (char *)S.clair;
        for (int i = 0; i < S.nb; i++) {
            int l = snprintf(d + o, sizeof S.clair - o, "%d\t%s\t%s\n", i, S.e[i].hote, S.e[i].nom);
            if (l < 0 || (size_t)l >= sizeof S.clair - o) break;
            o += (size_t)l;
        }
        size_t r = rep('K', d, o);
        explicit_bzero(d, o);
        return r;
    }
    case 'A': {                                   /* hote \0 nom \0 mdp */
        const char *h = (const char *)m, *nom = h + strnlen(h, n) + 1;
        if ((const uint8_t *)nom >= m + n) return rep('E', "?", 1);
        const char *mdp = nom + strnlen(nom, (size_t)(m + n - (const uint8_t *)nom)) + 1;
        if ((const uint8_t *)mdp > m + n) return rep('E', "?", 1);
        if (!champ_sur(h) || !champ_sur(nom) || !champ_sur(mdp)) return rep('E', "valeur refusée", 15);
        if (ressemble_carte(mdp) || ressemble_carte(nom)) return rep('E', "numéro de carte bancaire : jamais enregistré", 46);
        int i;
        for (i = 0; i < S.nb; i++) if (!strcmp(S.e[i].hote, h) && !strcmp(S.e[i].nom, nom)) break;
        if (i == S.nb) {
            if (S.nb >= ENTREES_MAX) return rep('E', "coffre plein", 12);
            S.nb++;
        }
        snprintf(S.e[i].hote, 128, "%s", h);
        snprintf(S.e[i].nom, 128, "%s", nom);
        snprintf(S.e[i].mdp, 128, "%s", mdp);
        return sceller();
    }
    case 'S': {                                   /* supprimer : numéro */
        int i = atoi((const char *)m);
        if (i < 0 || i >= S.nb) return rep('E', "?", 1);
        explicit_bzero(&S.e[i], sizeof S.e[i]);
        memmove(&S.e[i], &S.e[i + 1], sizeof(entree) * (size_t)(S.nb - i - 1));
        S.nb--;
        explicit_bzero(&S.e[S.nb], sizeof S.e[S.nb]);
        return sceller();
    }
    case 'P': {                                   /* hote \0 numéro : seulement si le site est EXACTEMENT le même */
        const char *h = (const char *)m, *num = h + strnlen(h, n) + 1;
        if ((const uint8_t *)num > m + n) return rep('E', "?", 1);
        int i = atoi(num);
        if (i < 0 || i >= S.nb || strcmp(S.e[i].hote, h)) return rep('E', "pas pour ce site", 16);
        size_t ln = strlen(S.e[i].nom), lp = strlen(S.e[i].mdp);
        memcpy(S.clair, S.e[i].nom, ln + 1);
        memcpy(S.clair + ln + 1, S.e[i].mdp, lp + 1);
        size_t r = rep('K', S.clair, ln + lp + 2);
        explicit_bzero(S.clair, ln + lp + 2);
        return r;
    }
    case 'M': {                                   /* montrer (gestionnaire) */
        int i = atoi((const char *)m);
        if (i < 0 || i >= S.nb) return rep('E', "?", 1);
        return rep('K', S.e[i].mdp, strlen(S.e[i].mdp) + 1);
    }
    case 'C': {                                   /* nouveau mot de passe maître */
        const char *mdp = (const char *)m;
        if (strlen(mdp) < 4) return rep('E', "mot de passe trop court", 23);
        getrandom(S.sel, sizeof S.sel, 0);
        deriver(mdp);
        return sceller();
    }
    case 'X': {                                   /* tout effacer (coffre vidé) */
        explicit_bzero(S.e, sizeof S.e);
        S.nb = 0;
        return sceller();
    }
    }
    return rep('E', "?", 1);
}

#define AUTORISER(nr) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (nr), 0, 1), BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)

static void enclave(int s)
{
    /* plus rien d'ouvert sauf la prise */
    for (int fd = 0; fd < 1024; fd++) if (fd != s) close(fd);
    prctl(PR_SET_DUMPABLE, 0, 0, 0, 0);
    prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);
    struct rlimit zero = { 0, 0 };
    setrlimit(RLIMIT_CORE, &zero);
    madvise(&S, sizeof S, MADV_DONTDUMP);
    madvise(travail, sizeof travail, MADV_DONTDUMP);
    mlock(&S, sizeof S);                          /* jamais écrit sur un disque */
    struct sock_filter f[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_X86_64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        AUTORISER(__NR_read), AUTORISER(__NR_write), AUTORISER(__NR_getrandom),
        AUTORISER(__NR_exit), AUTORISER(__NR_exit_group), AUTORISER(__NR_rt_sigreturn),
        AUTORISER(__NR_madvise),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
    };
    struct sock_fprog p = { .len = sizeof f / sizeof f[0], .filter = f };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) < 0 || syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p) < 0) _exit(1);
    for (;;) {
        ssize_t n = read(s, S.message, sizeof S.message - 1);
        if (n <= 0) break;
        size_t r = traiter((size_t)n);
        explicit_bzero(S.message, (size_t)n + 1);
        ssize_t w = write(s, S.reponse, r);
        explicit_bzero(S.reponse, r);
        if (w < 0) break;
    }
    tout_effacer();
    _exit(0);
}

/* --------------------------- côté Navigateur --------------------------- */
static int prise = -1;
static pid_t pid_enclave;

int coffre_demarrer(void)
{
    int sv[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, sv) < 0) return -1;
    pid_t p = fork();
    if (p < 0) { close(sv[0]); close(sv[1]); return -1; }
    if (p == 0) { close(sv[0]); enclave(sv[1]); }
    close(sv[1]);
    prise = sv[0];
    pid_enclave = p;
    return 0;
}

void coffre_arreter(void)
{
    if (prise >= 0) close(prise);
    prise = -1;
    if (pid_enclave > 0) { kill(pid_enclave, SIGKILL); waitpid(pid_enclave, NULL, 0); }
    pid_enclave = 0;
}

int coffre_disponible(void) { return prise >= 0; }

int coffre_requete(char op, const void *d, size_t n, uint8_t *rep_, size_t nrep, size_t *lu)
{
    static uint8_t m[COFFRE_MESSAGE_MAX];
    if (prise < 0 || n + 1 > sizeof m - 1) return -1;
    m[0] = (uint8_t)op;
    if (n) memcpy(m + 1, d, n);
    ssize_t w = write(prise, m, n + 1);
    explicit_bzero(m, n + 1);
    if (w < 0) return -1;
    /* l'ouverture (Argon2id, 32 Mio) prend une seconde ou deux */
    struct pollfd q = { prise, POLLIN, 0 };
    if (poll(&q, 1, 30000) <= 0) return -1;
    ssize_t r = read(prise, rep_, nrep);
    if (r <= 0) { coffre_arreter(); return -1; }
    if (lu) *lu = (size_t)r;
    return rep_[0];
}
