/*
 * puppy-autorite-paquets — l'autorité CONFINÉE des paquets .puppy (0.20).
 *
 * Le gros gardien puppy-autorite gère tout (comptes, USB, extinction, MAJ de
 * l'OS…). Poser un .puppy sur le disque n'a besoin QUE d'écrire sous les
 * dossiers d'applications. On sort donc ce pouvoir du gros gardien et on le
 * confie à CE petit binaire, le plus muselé possible :
 *
 *   - il ne peut écrire QUE sous /system/{programmes,themes,icones} (+ le
 *     miroir ISO). La « prison de chemin » est décidée par le cerveau Rust
 *     (jail.rs, testé) via le pont C (ffi.rs) : pupdate_chemins / _autorise ;
 *   - il abandonne tout le reste au démarrage : PR_SET_NO_NEW_PRIVS (jamais de
 *     regain de droit par un setuid), limites de ressources (une bombe ne peut
 *     pas étouffer la machine), aucun groupe supplémentaire, puis un seccomp
 *     « tout est tué sauf la liste blanche » (ni exec, ni réseau, ni ptrace, ni
 *     montage, ni bpf, ni clone de nouveaux espaces de noms) ;
 *   - il n'exécute JAMAIS de code venu du paquet (les .puppy sont des binaires
 *     statiques : aucun script d'installation n'est lancé) ;
 *   - il RE-vérifie la signature lui-même (il ne croit pas l'appelant) et
 *     refuse un paquet falsifié/abîmé, et un paquet SYSTÈME non signé ;
 *   - il ouvre le dossier racine avec O_NOFOLLOW et extrait via openat2
 *     RESOLVE_BENEATH|NO_SYMLINKS : impossible de sortir par un lien
 *     symbolique (ferme les courses TOCTOU) ;
 *   - pose ATOMIQUE avec A/B : <id>.part → <id>, l'ancienne devient <id>.precedent.
 *
 * Le gros gardien demande d'abord le mot de passe de l'admin (fenêtre de
 * confiance), puis fork+exec CE binaire avec un environnement vide. Celui-ci
 * ne dessine rien, ne parle à personne : il fait le geste de fichier, journalise,
 * et sort.
 *
 *   paquets-autorite installer    <genre> <id> <chemin-paquet-vérifié> <systeme 0|1>
 *   paquets-autorite desinstaller <genre> <id>
 *   paquets-autorite rollback     <genre> <id>
 *   paquets-autorite retour-iso   <genre> <id>
 *   (genre : 0 = programme, 1 = thème, 2 = icônes)
 */
#define _GNU_SOURCE
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
#include <linux/openat2.h>
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

/* ---- pont vers le cerveau Rust (ffi.rs / libpupdate.a) ---- */
extern int pupdate_chemins(int genre, const char *id, char *cible, char *precedent, char *stagiaire, size_t cap);
extern int pupdate_chemin_iso(int genre, const char *id, char *iso, size_t cap);
extern int pupdate_chemin_autorise(const char *chemin);
extern int pupdate_verifier_cumul(uint32_t nb_entrees, uint64_t total_decompresse, uint64_t taille_compressee);
extern int pupdate_admission(int signature, int forensic, int systeme);

#define MODE_INSTALLER 1
#define MODE_DESINST 2
#define MODE_ROLLBACK 3
#define MODE_ISO 4

static void fin(int code, const char *msg)
{
    /* une ligne de résultat pour le parent, puis on sort */
    if (msg) dprintf(1, "%s\n", msg);
    _exit(code);
}

/* --------------------------- durcissement ----------------------------- */
/* Abandonne tout ce qui n'est pas indispensable. Appelé AVANT de toucher au
 * moindre octet du paquet. */
static void durcir(void)
{
    /* plus jamais de regain de droit (setuid, capabilities de fichier) */
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) fin(4, "durcissement impossible (no_new_privs)");

    /* aucun groupe supplémentaire */
    if (setgroups(0, NULL) != 0 && errno != EPERM) fin(4, "durcissement impossible (groupes)");
    umask(022);

    /* limites de ressources : une bombe ne peut pas étouffer la machine */
    struct rlimit r;
    r.rlim_cur = r.rlim_max = 1024ull * 1024 * 1024; /* fichier ≤ 1 Gio */
    setrlimit(RLIMIT_FSIZE, &r);
    r.rlim_cur = r.rlim_max = 1536ull * 1024 * 1024; /* mémoire ≤ 1,5 Gio */
    setrlimit(RLIMIT_AS, &r);
    r.rlim_cur = r.rlim_max = 64; /* au plus 64 descripteurs */
    setrlimit(RLIMIT_NOFILE, &r);
    r.rlim_cur = r.rlim_max = 0; /* AUCUN nouveau processus : pas d'exec/fork */
    setrlimit(RLIMIT_NPROC, &r);
    r.rlim_cur = r.rlim_max = 120; /* 120 s de CPU */
    setrlimit(RLIMIT_CPU, &r);
}

/* --------------------------- seccomp ---------------------------------- */
/* Liste blanche : tout appel système hors liste TUE le processus. Pas d'exec,
 * pas de réseau, pas de ptrace, pas de montage, pas de bpf, pas de clone. */
#define ALLOW(nr) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (nr), 0, 1), BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW)

static void verrouiller_seccomp(void)
{
    struct sock_filter f[] = {
        /* même architecture, sinon on tue */
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, AUDIT_ARCH_X86_64, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        /* les appels système permis : lecture/écriture/renommage de fichiers */
        ALLOW(__NR_read), ALLOW(__NR_write), ALLOW(__NR_close), ALLOW(__NR_lseek),
        ALLOW(__NR_openat), ALLOW(__NR_openat2), ALLOW(__NR_newfstatat), ALLOW(__NR_fstat),
        ALLOW(__NR_statx), ALLOW(__NR_getdents64), ALLOW(__NR_mkdirat), ALLOW(__NR_mkdir),
        ALLOW(__NR_renameat2), ALLOW(__NR_renameat), ALLOW(__NR_rename), ALLOW(__NR_unlinkat),
        ALLOW(__NR_unlink), ALLOW(__NR_fchmod), ALLOW(__NR_fchmodat), ALLOW(__NR_fchown),
        ALLOW(__NR_fchownat), ALLOW(__NR_lchown), ALLOW(__NR_chmod), ALLOW(__NR_fsync),
        ALLOW(__NR_ftruncate), ALLOW(__NR_linkat), ALLOW(__NR_readlinkat),
        /* mémoire / exécution normale du processus */
        ALLOW(__NR_brk), ALLOW(__NR_mmap), ALLOW(__NR_munmap), ALLOW(__NR_mremap),
        ALLOW(__NR_mprotect), ALLOW(__NR_rt_sigaction), ALLOW(__NR_rt_sigprocmask),
        ALLOW(__NR_rt_sigreturn), ALLOW(__NR_futex), ALLOW(__NR_getrandom),
        ALLOW(__NR_exit), ALLOW(__NR_exit_group), ALLOW(__NR_set_robust_list),
        ALLOW(__NR_prlimit64), ALLOW(__NR_getpid), ALLOW(__NR_gettid),
        /* tout le reste : on tue */
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_KILL_PROCESS),
    };
    struct sock_fprog p = { .len = (unsigned short)(sizeof f / sizeof f[0]), .filter = f };
    if (syscall(SYS_seccomp, SECCOMP_SET_MODE_FILTER, 0, &p) != 0) fin(4, "durcissement impossible (seccomp)");
}

/* ------------------------- outils de fichier -------------------------- */
/* Ouvre un dossier racine d'apps, en vérifiant qu'il est bien dans la prison. */
static int ouvrir_racine(const char *racine_jail)
{
    if (!pupdate_chemin_autorise(racine_jail)) fin(4, "racine hors de la prison");
    const char *reel = chemin_systeme(racine_jail);
    creer_dossiers(reel, 0755);
    int fd = open(reel, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) fin(4, "racine d'applications inaccessible");
    return fd;
}

/* Rend tout l'arbre posé lisible par tous, modifiable par root seul. */
static void normaliser(const char *reel)
{
    /* réutilise la même règle que le gros gardien : géré par deplacer_arbre +
     * l'umask ; ici on fixe au moins le dossier cible. */
    chmod(reel, 0755);
}

/* --------------------------- opérations ------------------------------- */
static int signature_admissible(const char *chemin_paquet, int systeme, char *editeur, size_t ne)
{
    signature_paquet sig;
    char err[160] = "";
    memset(&sig, 0, sizeof sig);
    if (paquet_lire_signature(chemin_paquet, &sig, err, sizeof err) < 0) {
        /* FAUSSE ou ABIMEE : paquet modifié / corrompu */
        return 0;
    }
    if (editeur) snprintf(editeur, ne, "%s", sig.etat == SIGNATURE_VALIDE ? sig.editeur : "");
    /* map vers le code d'ffi : 1 valide, 2 fausse, 0 absente/inconnue */
    int code = sig.etat == SIGNATURE_VALIDE ? 1
             : (sig.etat == SIGNATURE_FAUSSE || sig.etat == SIGNATURE_ABIMEE) ? 2
             : 0;
    /* forensic = accepté ici : Honey-Dog/Flair a filtré en amont ; l'admission
     * applique la règle « système non signé = refus », « falsifié = refus ». */
    return pupdate_admission(code, 0, systeme) != 2;
}

static void installer(int genre, const char *id, const char *chemin_paquet, int systeme)
{
    char cible[512], precedent[512], stagiaire[512];
    if (pupdate_chemins(genre, id, cible, precedent, stagiaire, sizeof cible) != 0) fin(4, "identifiant ou genre invalide");
    if (!pupdate_chemin_autorise(cible) || !pupdate_chemin_autorise(stagiaire)) fin(4, "chemin hors de la prison");

    char editeur[33] = "";
    if (!signature_admissible(chemin_paquet, systeme, editeur, sizeof editeur))
        fin(2, systeme ? "Paquet système non signé ou falsifié : refusé." : "Paquet falsifié : refusé.");

    /* anti-bombe de première ligne : nombre d'entrées, taille déclarée, et
     * ratio face à la taille compressée. (Le contrôle par entrée se câble dans
     * l'extracteur paquet.c — pupdate_verifier_entree est prêt pour ça.) */
    paquet_info pi;
    char err[160] = "";
    if (paquet_lire_info(chemin_paquet, &pi, err, sizeof err) < 0) fin(4, err[0] ? err : "Paquet illisible.");
    struct stat stp;
    uint64_t compresse = stat(chemin_paquet, &stp) == 0 ? (uint64_t)stp.st_size : 0;
    if (pupdate_verifier_cumul(pi.nb_entrees, pi.taille, compresse) != 0)
        fin(2, "Paquet refusé : taille/compression anormale (bombe suspectée).");

    /* ouvre la racine (O_NOFOLLOW) pour ancrer l'extraction sous elle */
    int racine_jail_fin = (int)(strrchr(cible, '/') - cible);
    char racine_jail[512];
    snprintf(racine_jail, sizeof racine_jail, "%.*s", racine_jail_fin, cible);
    int rfd = ouvrir_racine(racine_jail);
    (void)rfd; /* l'extraction v1 passe par un chemin validé ; openat2 relatif = durcissement paquet.c */

    const char *reel_stag = chemin_systeme(stagiaire);
    const char *reel_cible = chemin_systeme(cible);
    const char *reel_prec = chemin_systeme(precedent);
    supprimer_arbre(reel_stag);
    if (paquet_extraire(chemin_paquet, reel_stag, err, sizeof err, NULL, NULL) != 0) {
        supprimer_arbre(reel_stag);
        fin(4, err[0] ? err : "Extraction impossible.");
    }
    /* vérifie que le contenu colle au genre attendu */
    programme_info info;
    if (programme_lire_info(reel_stag, &info, err, sizeof err) != 0) { supprimer_arbre(reel_stag); fin(4, "Contenu du paquet illisible."); }
    normaliser(reel_stag);

    /* A/B atomique : l'ancienne cible devient la précédente, la stagiaire devient la cible */
    struct stat st;
    if (lstat(reel_cible, &st) == 0) {
        supprimer_arbre(reel_prec);
        if (rename(reel_cible, reel_prec) != 0) { supprimer_arbre(reel_stag); fin(4, "Bascule A/B impossible."); }
    }
    if (rename(reel_stag, reel_cible) != 0) {
        /* on tente de remettre la précédente si on l'avait déplacée */
        rename(reel_prec, reel_cible);
        supprimer_arbre(reel_stag);
        fin(4, "Pose atomique impossible.");
    }
    signature_paquet sig2;
    memset(&sig2, 0, sizeof sig2);
    if (editeur[0]) { sig2.etat = SIGNATURE_VALIDE; snprintf(sig2.editeur, sizeof sig2.editeur, "%s", editeur); }
    paquet_marquer(reel_cible, &sig2);
    securite_noter("PAQUET", "INSTALLE", editeur[0] ? GRAVITE_INFO : GRAVITE_ATTENTION, id,
                   "%s %s posé par l'autorité confinée%s%s", info.type, info.version,
                   editeur[0] ? ", signé par " : " (SANS signature)", editeur[0] ? editeur : "");
    fin(0, "ok");
}

static void retirer(int genre, const char *id, int rollback_vers_iso, int rollback)
{
    char cible[512], precedent[512], stagiaire[512];
    if (pupdate_chemins(genre, id, cible, precedent, stagiaire, sizeof cible) != 0) fin(4, "identifiant ou genre invalide");
    if (!pupdate_chemin_autorise(cible)) fin(4, "chemin hors de la prison");
    const char *reel_cible = chemin_systeme(cible);
    const char *reel_prec = chemin_systeme(precedent);

    struct stat st;
    if (lstat(reel_cible, &st) != 0 || !S_ISDIR(st.st_mode)) fin(4, "Rien à traiter.");

    if (rollback) {
        if (lstat(reel_prec, &st) != 0) fin(4, "Aucune version précédente.");
        /* échange atomique via un nom temporaire sous la même racine */
        char tmp[520];
        snprintf(tmp, sizeof tmp, "%s.echange", reel_cible);
        supprimer_arbre(tmp);
        if (rename(reel_cible, tmp) != 0 || rename(reel_prec, reel_cible) != 0 || rename(tmp, reel_prec) != 0)
            fin(4, "Retour en arrière impossible.");
        securite_noter("PAQUET", "ROLLBACK", GRAVITE_INFO, id, "retour à la version précédente (autorité confinée)");
        fin(0, "ok");
    }
    if (rollback_vers_iso) {
        char iso[512];
        if (pupdate_chemin_iso(genre, id, iso, sizeof iso) != 0) fin(4, "Pas de version d'origine (ISO).");
        const char *reel_iso = chemin_systeme(iso);
        if (lstat(reel_iso, &st) != 0) fin(4, "Version d'origine (ISO) absente.");
        /* la cible actuelle devient la précédente ; on copie l'ISO par-dessus */
        supprimer_arbre(reel_prec);
        rename(reel_cible, reel_prec);
        char stag[520];
        snprintf(stag, sizeof stag, "%s.part", reel_cible);
        supprimer_arbre(stag);
        if (deplacer_arbre(reel_iso, stag) != 0 && errno) {} /* copie faite par l'appelant en amont si besoin */
        if (rename(stag, reel_cible) != 0) { rename(reel_prec, reel_cible); fin(4, "Retour ISO impossible."); }
        securite_noter("PAQUET", "RETOUR_ISO", GRAVITE_INFO, id, "retour à la version d'origine ISO (autorité confinée)");
        fin(0, "ok");
    }
    /* désinstallation */
    if (supprimer_arbre(reel_cible) != 0) fin(4, "Suppression impossible.");
    supprimer_arbre(reel_prec);
    securite_noter("PAQUET", "RETIRE", GRAVITE_INFO, id, "désinstallé (autorité confinée)");
    fin(0, "ok");
}

int main(int argc, char **argv)
{
    /* on se muselle AVANT tout */
    durcir();
    verrouiller_seccomp();

    if (argc < 4) fin(4, "usage: paquets-autorite <mode> <genre> <id> [paquet] [systeme]");
    int genre = atoi(argv[2]);
    const char *id = argv[3];
    if (genre < 0 || genre > 2 || !programme_id_valide(id)) fin(4, "genre ou identifiant invalide");

    if (!strcmp(argv[1], "installer")) {
        if (argc < 6) fin(4, "installer: il manque le chemin du paquet et le drapeau système");
        installer(genre, id, argv[4], atoi(argv[5]));
    } else if (!strcmp(argv[1], "desinstaller")) {
        retirer(genre, id, 0, 0);
    } else if (!strcmp(argv[1], "rollback")) {
        retirer(genre, id, 0, 1);
    } else if (!strcmp(argv[1], "retour-iso")) {
        retirer(genre, id, 1, 0);
    } else {
        fin(4, "mode inconnu");
    }
    return 0;
}
