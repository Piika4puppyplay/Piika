/*
 * coffre-apps — ROTATION / scellement au repos des deux apps privilégiées (0.20).
 *
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │ SQUELETTE — À VALIDER EN VM AVANT TOUT CÂBLAGE.                         │
 * │ Compile et se lie. N'est PAS encore appelé par puppy-maj : l'appel se   │
 * │ branche et se valide en VM (le TPM et getrandom ne sont testables que   │
 * │ là). À NE PAS confondre avec « puppy-coffre » (coffre à clés disque).   │
 * └───────────────────────────────────────────────────────────────────────┘
 *
 * RÔLE : à CHAQUE mise à jour, puppy-maj appelle cet outil pour (re)chiffrer
 * chacune des deux apps privilégiées (paquets-autorite, puppy-maj) dans son
 * coffre .pupcoffre, avec une clé NEUVE :
 *   - génération incrémentée (monotone) → anti-retour-arrière ;
 *   - clé dérivée du secret machine scellé au TPM (coffre.rs : deriver_cle) ;
 *   - nonce aléatoire (getrandom) ;
 *   - scellement XChaCha20-Poly1305 (coffre.rs : sceller), via le cœur Rust
 *     testé (95 tests du coffre). Rien de crypto n'est refait ici.
 * Pose atomique : <coffre>.part → <coffre>, puis <coffre>.gen (génération
 * minimale acceptée, monotone).
 *
 * SANS TPM : pas de bascule silencieuse. S'il n'y a pas de scellement TPM, on
 * ne crée PAS de coffre (l'app reste en clair sous fs-verity, exactement le
 * socle d'avant le coffre — ce n'est pas une dégradation puisque le coffre
 * n'a jamais été actif). Le coffre est une couche EN PLUS, uniquement quand le
 * TPM est disponible.
 *
 *   coffre-apps rotation <app> <binaire-clair> <coffre-sortie>
 *     <app>           "paquets-autorite" | "puppy-maj" (étiquette du KDF)
 *     <binaire-clair> le binaire en clair fraîchement installé (lecture)
 *     <coffre-sortie> le .pupcoffre à (ré)écrire (+ .gen à côté)
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "crypto.h"
#include "puppy.h"
#include "scellement.h"
#include "securite.h"
#include <errno.h>
#include <fcntl.h>
#include <grp.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/random.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

/* ---- cœur Rust (coffre.rs via libpupdate.a) ---- */
extern int pupdate_coffre_deriver(const uint8_t *secret, const char *app, uint32_t generation, uint8_t *out_cle);
extern int pupdate_coffre_sceller(const uint8_t *cle, uint32_t generation, const uint8_t *nonce,
                                  const uint8_t *clair, size_t clair_len,
                                  uint8_t *out, size_t out_cap, size_t *out_len);

#define COFFRE_MAX (64ull * 1024 * 1024)

static void fin(int code, const char *msg) { if (msg) dprintf(1, "%s\n", msg); _exit(code); }
static void effacer(void *p, size_t n) { if (p) effacer_memoire(p, n); }

static void durcir(void)
{
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) fin(4, "durcissement impossible (no_new_privs)");
    if (setgroups(0, NULL) != 0 && errno != EPERM) fin(4, "durcissement impossible (groupes)");
    umask(077); /* le coffre est illisible par les autres */
    struct rlimit r;
    r.rlim_cur = r.rlim_max = 1024ull * 1024 * 1024; setrlimit(RLIMIT_FSIZE, &r);
    r.rlim_cur = r.rlim_max = 1536ull * 1024 * 1024; setrlimit(RLIMIT_AS, &r);
    r.rlim_cur = r.rlim_max = 64;  setrlimit(RLIMIT_NOFILE, &r);
    r.rlim_cur = r.rlim_max = 0;   setrlimit(RLIMIT_NPROC, &r);
    r.rlim_cur = r.rlim_max = 120; setrlimit(RLIMIT_CPU, &r);
}

#define ALLOW(nr) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (nr), 0, 1), BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)
static void verrouiller_seccomp(void)
{
    struct sock_filter f[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_X86_64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        ALLOW(__NR_read), ALLOW(__NR_write), ALLOW(__NR_close), ALLOW(__NR_lseek),
        ALLOW(__NR_openat), ALLOW(__NR_openat2), ALLOW(__NR_newfstatat), ALLOW(__NR_fstat),
        ALLOW(__NR_statx), ALLOW(__NR_renameat2), ALLOW(__NR_renameat), ALLOW(__NR_rename),
        ALLOW(__NR_unlinkat), ALLOW(__NR_unlink), ALLOW(__NR_fchmod), ALLOW(__NR_fsync),
        ALLOW(__NR_ftruncate), ALLOW(__NR_mkdirat), ALLOW(__NR_mkdir),
        ALLOW(__NR_brk), ALLOW(__NR_mmap), ALLOW(__NR_munmap), ALLOW(__NR_mremap),
        ALLOW(__NR_mprotect), ALLOW(__NR_rt_sigaction), ALLOW(__NR_rt_sigprocmask),
        ALLOW(__NR_rt_sigreturn), ALLOW(__NR_futex), ALLOW(__NR_getrandom),
        ALLOW(__NR_exit), ALLOW(__NR_exit_group), ALLOW(__NR_set_robust_list),
        ALLOW(__NR_prlimit64), ALLOW(__NR_getpid), ALLOW(__NR_gettid),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
    };
    struct sock_fprog p = { .len = (unsigned short)(sizeof f / sizeof f[0]), .filter = f };
    if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p) != 0) fin(4, "durcissement impossible (seccomp)");
}

static int lire_secret_machine(uint8_t s[32])
{
    size_t n = 0;
    char *d = lire_fichier(chemin_systeme(SCEAU_SECRET), &n);
    int ok = d && n == 32;
    if (ok) memcpy(s, d, 32);
    if (d) { effacer(d, n); free(d); }
    return ok ? 0 : -1;
}

static uint32_t lire_generation(const char *coffre)
{
    char p[1024];
    if ((size_t)snprintf(p, sizeof p, "%s.gen", coffre) >= sizeof p) return 0;
    size_t n = 0;
    char *d = lire_fichier(p, &n);
    uint32_t g = 0;
    if (d && n >= 4) g = (uint8_t)d[0] | (uint8_t)d[1] << 8 | (uint8_t)d[2] << 16 | (uint32_t)(uint8_t)d[3] << 24;
    if (d) free(d);
    return g;
}

/* Écriture atomique d'un tampon : <chemin>.part → <chemin>. */
static int ecrire_atomique(const char *chemin, const uint8_t *buf, size_t n, mode_t mode)
{
    char tmp[1100];
    if ((size_t)snprintf(tmp, sizeof tmp, "%s.part", chemin) >= sizeof tmp) return -1;
    int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, mode);
    if (fd < 0) return -1;
    size_t o = 0;
    while (o < n) {
        ssize_t w = write(fd, buf + o, n - o);
        if (w < 0) { if (errno == EINTR) continue; close(fd); unlink(tmp); return -1; }
        o += (size_t)w;
    }
    if (fsync(fd) != 0) { close(fd); unlink(tmp); return -1; }
    close(fd);
    if (rename(tmp, chemin) != 0) { unlink(tmp); return -1; }
    return 0;
}

static void rotation(const char *app, const char *clair_chemin, const char *coffre)
{
    /* sans scellement TPM : pas de coffre (socle clair + fs-verity), sans bruit */
    if (!sceau_actif()) {
        securite_noter("COFFRE", "IGNORE", GRAVITE_INFO, app,
                       "pas de scellement TPM : l'app reste en clair (fs-verity), coffre non créé");
        fin(0, "sans-tpm");
    }

    uint8_t secret[32];
    if (lire_secret_machine(secret) != 0) fin(4, "secret de machine illisible");

    /* génération suivante (monotone) ; première fois = 1 */
    uint32_t gen = lire_generation(coffre);
    uint32_t neuve = gen ? gen + 1 : 1;
    if (neuve <= gen) { effacer(secret, 32); fin(4, "génération saturée"); }

    /* binaire en clair à sceller */
    size_t nclair = 0;
    char *clair = lire_fichier(clair_chemin, &nclair);
    if (!clair || nclair == 0 || (uint64_t)nclair > COFFRE_MAX) {
        effacer(secret, 32); if (clair) { effacer(clair, nclair); free(clair); }
        fin(4, "binaire en clair absent ou trop gros");
    }

    uint8_t cle[32];
    if (pupdate_coffre_deriver(secret, app, neuve, cle) != 0) {
        effacer(secret, 32); effacer(clair, nclair); free(clair);
        fin(4, "dérivation de clé impossible");
    }
    effacer(secret, 32);

    uint8_t nonce[24];
    if (getrandom(nonce, sizeof nonce, 0) != (ssize_t)sizeof nonce) {
        effacer(cle, 32); effacer(clair, nclair); free(clair);
        fin(4, "aléa indisponible");
    }

    size_t cap = nclair + 64; /* en-tête(44) + MAC(16) + marge */
    uint8_t *blob = malloc(cap);
    if (!blob) { effacer(cle, 32); effacer(clair, nclair); free(clair); fin(4, "mémoire"); }
    size_t nblob = 0;
    int rc = pupdate_coffre_sceller(cle, neuve, nonce, (const uint8_t *)clair, nclair, blob, cap, &nblob);
    effacer(cle, 32);
    effacer(clair, nclair); free(clair);
    if (rc != 0) { effacer(blob, cap); free(blob); fin(4, "scellement impossible"); }

    /* pose atomique du coffre, puis de la génération minimale (monotone) */
    if (ecrire_atomique(coffre, blob, nblob, 0600) != 0) { effacer(blob, cap); free(blob); fin(4, "écriture du coffre impossible"); }
    effacer(blob, cap); free(blob);

    char gchemin[1100];
    if ((size_t)snprintf(gchemin, sizeof gchemin, "%s.gen", coffre) >= sizeof gchemin) fin(4, "chemin .gen trop long");
    uint8_t g4[4] = { (uint8_t)neuve, (uint8_t)(neuve >> 8), (uint8_t)(neuve >> 16), (uint8_t)(neuve >> 24) };
    if (ecrire_atomique(gchemin, g4, 4, 0644) != 0) fin(4, "écriture de la génération impossible");

    securite_noter("COFFRE", "SCELLE", GRAVITE_INFO, app, "coffre (re)scellé, génération %u", neuve);
    fin(0, "ok");
}

int main(int argc, char **argv)
{
    durcir();
    verrouiller_seccomp();
    if (argc < 5 || strcmp(argv[1], "rotation") != 0)
        fin(4, "usage: coffre-apps rotation <app> <binaire-clair> <coffre-sortie>");
    rotation(argv[2], argv[3], argv[4]);
    return 0;
}
