/*
 * puprecovery — la CONSOLE DE SECOURS des deux applications privilégiées (0.20).
 *
 *   « PuppyOS ne fait pas confiance à son créateur. La console ne fait pas
 *     confiance à PuppyOS. Et PuppyOS ne fait pas confiance à sa console. »
 *   « Le petit chiot infirmier ne distribue pas les médicaments sans vérifier
 *     les ordonnances. »   — PuppyOS : même la confiance doit passer les tests.
 *
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │ SQUELETTE — À VALIDER EN VM AVANT TOUT CÂBLAGE.                         │
 * │ Compile et se lie proprement. N'est PAS branché au démarrage ni à      │
 * │ l'interface : rien ne l'appelle encore. La pose réelle, le montage     │
 * │ lecture seule et le privilège dédié se valident sur la machine de      │
 * │ Piika. Les empreintes épinglées (secours.rs) sont à zéro = refus par   │
 * │ défaut tant que les vraies copies dorées ne sont pas scellées au build.│
 * └───────────────────────────────────────────────────────────────────────┘
 *
 * RÔLE : quand les deux apps privilégiées (paquets-autorite, puppy-maj) ne
 * peuvent plus s'ouvrir (coffre illisible, clés perdues), le lanceur refuse de
 * les lancer (refus pur). CETTE console les RÉINSTALLE proprement à partir
 * d'une COPIE DORÉE signée et embarquée. Elle NE MANIPULE AUCUNE CLÉ : pas de
 * déchiffrage hors TPM, donc pas de pouvoir « à grand pouvoir » à détourner
 * (choix de Piika 06/11). Après réinstallation, si le TPM fonctionne, le flux
 * normal regénère une clé et rechiffre.
 *
 * TPM DÉFINITIVEMENT INDISPONIBLE = PAS DE BASCULE SILENCIEUSE EN CLAIR. On ne
 * lance JAMAIS automatiquement les deux apps en clair faute de TPM : ce serait
 * transformer une panne matérielle en baisse silencieuse de sécurité. Comme
 * elles ne sont pas critiques au démarrage, elles restent DÉSACTIVÉES (le
 * lanceur refuse) jusqu'à une restauration sécurisée EXPLICITE décidée par le
 * propriétaire (reprovisionnement du scellement TPM, ou choix authentifié
 * assumé de tourner sans coffre). Le propriétaire n'est jamais enfermé dehors,
 * mais la dégradation de sécurité n'est jamais silencieuse.
 *
 * « MÊME PUPPYOS NE FAIT PAS CONFIANCE À SON CRÉATEUR » : le créateur peut se
 * faire pirater. La décision est prise par le cerveau Rust testé (secours.rs) :
 * signature Ed25519 NÉCESSAIRE + EMPREINTE ÉPINGLÉE (le contenu doit valoir le
 * condensat connu-bon gravé dans la console) + anti-retour-arrière. Un paquet
 * même parfaitement signé mais différent de l'épingle est REFUSÉ. C'est
 * l'« antivirus interne » : on ne croit pas une signature sur parole.
 *
 * CLOISONNEMENT (privilège dédié « puprecovery_updateur », réalisé ici par la
 * muselière) : comme paquets-autorite — PR_SET_NO_NEW_PRIVS, limites, aucun
 * groupe, seccomp « tout est tué sauf la liste blanche » (ni exec, ni réseau,
 * ni ptrace, ni montage, ni bpf, ni clone). N'accepte AUCUN fichier entrant :
 * il ne lit que ses copies dorées (chemins FIXES) et n'écrit QUE le dossier
 * isolé des deux apps (chemins FIXES). Aucun chemin n'est calculé à partir d'un
 * argument : la « prison » est une table en dur de deux entrées.
 *
 *   puprecovery reparer      <paquets-autorite|puppy-maj>
 *   puprecovery reparer-tout
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "crypto.h"
#include "paquet.h"
#include "programmes.h"
#include "puppy.h"
#include "securite.h"
#include "signature-paquet.h"
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
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

/* ---- pont vers le cerveau Rust (secours.rs via libpupdate.a) ---- */
extern int pupdate_secours_analyser(const char *id, int signature_valide, const uint8_t *empreinte,
                                    uint32_t generation, uint64_t taille);

/* --------- table EN DUR des deux (et seules) apps réparables --------- */
/* Rien n'est calculé à partir d'un argument : on compare l'identifiant reçu à
 * ces deux entrées, sinon on refuse. Les chemins sont fixes. */
typedef struct {
    const char *id;
    const char *copie_doree;  /* .puppy signé connu-bon (lecture seule) */
    const char *gen_doree;    /* génération (u32 LE) de la copie dorée */
    const char *destination;  /* dossier isolé de l'app (réécrit pendant la réparation) */
} app_critique;

static const app_critique APPS[] = {
    { "paquets-autorite",
      "/system/secours/doree/paquets-autorite.puppy",
      "/system/secours/doree/paquets-autorite.gen",
      "/system/apps-critiques/paquets-autorite" },
    { "puppy-maj",
      "/system/secours/doree/puppy-maj.puppy",
      "/system/secours/doree/puppy-maj.gen",
      "/system/apps-critiques/puppy-maj" },
};
#define NAPPS ((int)(sizeof APPS / sizeof APPS[0]))

static void fin(int code, const char *msg)
{
    if (msg) dprintf(1, "%s\n", msg);
    _exit(code);
}

/* --------------------------- durcissement ----------------------------- */
static void durcir(void)
{
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) fin(4, "durcissement impossible (no_new_privs)");
    if (setgroups(0, NULL) != 0 && errno != EPERM) fin(4, "durcissement impossible (groupes)");
    umask(022);
    struct rlimit r;
    r.rlim_cur = r.rlim_max = 1024ull * 1024 * 1024; setrlimit(RLIMIT_FSIZE, &r);
    r.rlim_cur = r.rlim_max = 1536ull * 1024 * 1024; setrlimit(RLIMIT_AS, &r);
    r.rlim_cur = r.rlim_max = 64;  setrlimit(RLIMIT_NOFILE, &r);
    r.rlim_cur = r.rlim_max = 0;   setrlimit(RLIMIT_NPROC, &r);  /* aucun exec/fork */
    r.rlim_cur = r.rlim_max = 120; setrlimit(RLIMIT_CPU, &r);
}

/* --------------------------- seccomp ---------------------------------- */
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
        ALLOW(__NR_statx), ALLOW(__NR_getdents64), ALLOW(__NR_mkdirat), ALLOW(__NR_mkdir),
        ALLOW(__NR_renameat2), ALLOW(__NR_renameat), ALLOW(__NR_rename), ALLOW(__NR_unlinkat),
        ALLOW(__NR_unlink), ALLOW(__NR_fchmod), ALLOW(__NR_fchmodat), ALLOW(__NR_fchown),
        ALLOW(__NR_fchownat), ALLOW(__NR_lchown), ALLOW(__NR_chmod), ALLOW(__NR_fsync),
        ALLOW(__NR_ftruncate), ALLOW(__NR_linkat), ALLOW(__NR_readlinkat),
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

/* --------------------------- outils ---------------------------------- */
/* SHA-256 du fichier entier (le condensat comparé à l'épingle). 0 = OK. */
static int empreinte_fichier(const char *chemin, uint8_t out[32], uint64_t *taille)
{
    int fd = open(chemin, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    sha256_ctx c; sha256_debut(&c);
    static uint8_t buf[65536];
    uint64_t n = 0;
    for (;;) {
        ssize_t k = read(fd, buf, sizeof buf);
        if (k < 0) { if (errno == EINTR) continue; close(fd); return -1; }
        if (k == 0) break;
        sha256_ajouter(&c, buf, (size_t)k);
        n += (uint64_t)k;
    }
    close(fd);
    sha256_fin(&c, out);
    if (taille) *taille = n;
    return 0;
}

/* Lit une génération u32 LE (fichier .gen), 0 si absent. */
static uint32_t lire_generation(const char *chemin)
{
    size_t n = 0;
    char *d = lire_fichier(chemin, &n);
    uint32_t g = 0;
    if (d && n >= 4) g = (uint8_t)d[0] | (uint8_t)d[1] << 8 | (uint8_t)d[2] << 16 | (uint32_t)(uint8_t)d[3] << 24;
    if (d) free(d);
    return g;
}

/* --------------------------- réparation ------------------------------ */
static void reparer_une(const app_critique *a)
{
    /* 1. la copie dorée doit être signée VALIDE (nécessaire, pas suffisant) */
    signature_paquet sig;
    char err[160] = "";
    memset(&sig, 0, sizeof sig);
    int sig_ok = paquet_lire_signature(a->copie_doree, &sig, err, sizeof err) == 0
                 && sig.etat == SIGNATURE_VALIDE;

    /* 2. empreinte réelle de la copie dorée (comparée à l'épingle côté Rust) */
    uint8_t emp[32]; uint64_t taille = 0;
    if (empreinte_fichier(a->copie_doree, emp, &taille) != 0)
        fin(4, "copie dorée illisible");
    uint32_t generation = lire_generation(a->gen_doree);

    /* 3. DÉCISION (secours.rs) : signature + épingle + anti-retour + bornes */
    int rc = pupdate_secours_analyser(a->id, sig_ok ? 1 : 0, emp, generation, taille);
    if (rc != 0) {
        char d[96]; snprintf(d, sizeof d, "copie dorée refusée (motif %d)", rc);
        securite_noter("SECOURS", "REFUSE", GRAVITE_ALERTE, a->id, "%s", d);
        fin(2, d);
    }

    /* 4. réinstallation : extraction A/B de la copie dorée dans le dossier isolé.
     * paquet_extraire porte déjà le mur anti-bombe par entrée. */
    creer_dossiers(a->destination, 0755);
    char stag[1024], prec[1024];
    if ((size_t)snprintf(stag, sizeof stag, "%s.part", a->destination) >= sizeof stag) fin(4, "chemin trop long");
    if ((size_t)snprintf(prec, sizeof prec, "%s.precedent", a->destination) >= sizeof prec) fin(4, "chemin trop long");
    supprimer_arbre(stag);
    if (paquet_extraire(a->copie_doree, stag, err, sizeof err, NULL, NULL) != 0) {
        supprimer_arbre(stag);
        fin(4, err[0] ? err : "extraction de la copie dorée impossible");
    }
    struct stat st;
    if (lstat(a->destination, &st) == 0) {
        supprimer_arbre(prec);
        rename(a->destination, prec);
    }
    if (rename(stag, a->destination) != 0) {
        rename(prec, a->destination);
        supprimer_arbre(stag);
        fin(4, "pose atomique impossible");
    }
    supprimer_arbre(prec);
    securite_noter("SECOURS", "REPARE", GRAVITE_INFO, a->id,
                   "application critique réinstallée depuis la copie dorée signée + épinglée");
}

static const app_critique *trouver(const char *id)
{
    for (int i = 0; i < NAPPS; i++)
        if (!strcmp(APPS[i].id, id)) return &APPS[i];
    return NULL;
}

int main(int argc, char **argv)
{
    /* on se muselle AVANT tout */
    durcir();
    verrouiller_seccomp();

    if (argc < 2) fin(4, "usage: puprecovery reparer <id> | reparer-tout");

    if (!strcmp(argv[1], "reparer-tout")) {
        for (int i = 0; i < NAPPS; i++) reparer_une(&APPS[i]);
        fin(0, "ok");
    }
    if (!strcmp(argv[1], "reparer")) {
        if (argc < 3) fin(4, "reparer: identifiant manquant");
        const app_critique *a = trouver(argv[2]);
        if (!a) fin(2, "identifiant inconnu : la console ne répare que ses deux apps");
        reparer_une(a);
        fin(0, "ok");
    }
    fin(4, "commande inconnue");
    return 0;
}
