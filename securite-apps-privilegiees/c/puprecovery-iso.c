/*
 * puprecovery-iso — le RECOVERY EMBARQUÉ DANS L'ISO (0.20).
 *
 *   « La console ne prescrit jamais à l'aveugle. Elle vérifie, elle
 *     diagnostique, puis elle soigne. »
 *
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │ SQUELETTE — À VALIDER EN VM AVANT TOUT CÂBLAGE DANS L'ISO.              │
 * │ La DÉTECTION (lecture seule) et la DÉCISION (cerveau Rust testé) sont   │
 * │ réelles et tournent déjà. L'OUVERTURE du volume chiffré (mot de passe   │
 * │ -> dm-crypt), le MONTAGE et la POSE des apps touchent un vrai disque et │
 * │ une vraie puce : non testables dans le conteneur, à valider sur la      │
 * │ machine de Piika. Par défaut cet outil ne fait que DÉTECTER + DIRE ce   │
 * │ qu'il ferait (dry-run) ; il n'écrit rien.                               │
 * └───────────────────────────────────────────────────────────────────────┘
 *
 * RÔLE : booté depuis l'ISO (sans démarrer l'OS installé), il détecte une
 * installation PuppyOS par l'inspecteur LECTURE SEULE (inspection.c : lit les
 * tables et signatures, aucun montage), puis — c'est le cerveau Rust testé
 * secours_iso.rs qui décide — répare les deux apps privilégiées à partir d'une
 * copie dorée SIGNÉE + ÉPINGLÉE embarquée dans l'ISO (même vérification que la
 * console puprecovery : empreinte épinglée vérifiée AVANT tout parsing).
 *
 * RÈGLES DURES (directive installation/multiboot + Piika 06/11) :
 *   - JAMAIS de format automatique. Réparer ne détruit pas les données.
 *   - Installation chiffrée : le MOT DE PASSE de déchiffrement est OBLIGATOIRE
 *     avant de toucher quoi que ce soit. Le recovery ne stocke AUCUNE clé ;
 *     c'est la phrase de l'utilisateur qui ouvre le volume (réparation sans
 *     perte de données). Pas de phrase = pas de réparation.
 *   - On ne touche JAMAIS un disque qui n'est pas visiblement PuppyOS.
 *
 * FILET DE SECOURS (secours_filet.rs) : ce recovery ISO est l'étage en dessous
 * de la console dans l'OS ; s'il échoue à son tour, l'étage suivant est la
 * réinstallation propre depuis l'ISO signée. On ne dégrade jamais la sécurité
 * en silence : à court de filet, on le DIT.
 *
 *   puprecovery-iso detecter <disque|image>     (lecture seule, dry-run)
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "inspection.h"
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

/* ---- pont vers le cerveau Rust (secours_iso.rs / secours_filet.rs) ---- */
extern int pupdate_secours_iso_decider(int puppyos_detecte, int chiffre, int montable, int apps_presentes);
extern int pupdate_secours_filet_suivante(int couche, int a_echoue);
extern int pupdate_secours_filet_libelle(int couche, char *out, size_t cap);

/* Codes de pupdate_secours_iso_decider (miroir de ActionIso) */
enum { ACT_RIEN = 0, ACT_MOT_DE_PASSE = 1, ACT_REPARER = 2, ACT_REINSTALL_SANS_FORMAT = 3 };
/* Couches du filet (miroir de secours_filet::Couche) */
enum { CO_CONSOLE = 0, CO_ISO = 1, CO_REINSTALL = 2, CO_PLUS_DE_FILET = 3 };

static int egal(const char *a, const char *b) { return a && b && !strcmp(a, b); }

/* Un système de fichiers qui dénote du chiffrement au repos. */
static int fs_chiffre(const char *fs)
{
    return egal(fs, "LUKS") || egal(fs, "chiffré/inconnu");
}

int main(int argc, char **argv)
{
    if (argc < 3 || strcmp(argv[1], "detecter") != 0) {
        dprintf(2, "usage: puprecovery-iso detecter <disque|image>\n");
        return 2;
    }
    const char *chemin = argv[2];

    /* LECTURE SEULE : on n'ouvre jamais le disque autrement qu'en O_RDONLY. */
    int fd = open(chemin, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { dprintf(2, "ouverture impossible (lecture seule) : %s\n", chemin); return 2; }

    off_t taille = lseek(fd, 0, SEEK_END);
    if (taille <= 0) { close(fd); dprintf(2, "taille du disque illisible\n"); return 2; }

    insp_partition parts[INSP_MAX_PART];
    insp_table table = INSP_TABLE_AUCUNE;
    int n = insp_table_lire(fd, (uint64_t)taille, 512, parts, INSP_MAX_PART, &table);
    if (n < 0) { close(fd); dprintf(2, "lecture de la table de partitions impossible\n"); return 2; }

    int puppyos = 0, chiffre = 0;
    for (int i = 0; i < n; i++) {
        insp_fs_detecter(fd, parts[i].debut, parts[i].fs, sizeof parts[i].fs,
                         parts[i].etiquette, sizeof parts[i].etiquette);
        if (parts[i].puppyos) puppyos = 1;
        if (fs_chiffre(parts[i].fs)) chiffre = 1;
    }
    insp_os oss[8];
    int nos = insp_os_deduire(parts, n, oss, 8);
    for (int i = 0; i < nos; i++)
        if (oss[i].type == INSP_OS_PUPPYOS) puppyos = 1;
    close(fd);

    /* DÉTECTION seule : le volume n'est pas encore ouvert (pas de mot de passe
     * en dry-run) ni monté → montable = 0, apps_presentes = 0. Le cerveau Rust
     * dit alors l'étape à suivre (typiquement : demander le mot de passe). */
    int montable = 0, apps_presentes = 0;
    int action = pupdate_secours_iso_decider(puppyos, chiffre, montable, apps_presentes);

    dprintf(1, "Disque : %s  (table %s, %d partition(s))\n", chemin, insp_table_nom(table), n);
    dprintf(1, "PuppyOS détecté : %s%s\n", puppyos ? "oui" : "non",
            chiffre ? " · chiffré (mot de passe requis)" : "");

    switch (action) {
    case ACT_RIEN:
        dprintf(1, "→ Aucune installation PuppyOS : le recovery ne touche à rien.\n");
        break;
    case ACT_MOT_DE_PASSE:
        dprintf(1, "→ Installation chiffrée : le recovery demanderait votre MOT DE PASSE\n"
                   "  pour ouvrir le volume (aucune clé stockée), puis réparerait les deux\n"
                   "  apps privilégiées sans démarrer PuppyOS, sans perte de données.\n");
        break;
    case ACT_REPARER:
        dprintf(1, "→ Volume ouvert : le recovery vérifierait (signature + épingle) et\n"
                   "  réinstallerait les deux apps depuis la copie dorée, sans démarrer l'OS.\n");
        break;
    case ACT_REINSTALL_SANS_FORMAT:
        dprintf(1, "→ Apps absentes/illisibles : le recovery proposerait une réinstallation\n"
                   "  qui PRÉSERVE les données (jamais de format automatique).\n");
        break;
    default:
        dprintf(1, "→ décision inconnue (%d)\n", action);
        break;
    }

    /* Rappel du filet : si CE recovery échoue, l'étage suivant. */
    char suite[96];
    int apres = pupdate_secours_filet_suivante(CO_ISO, 1 /* a échoué */);
    if (pupdate_secours_filet_libelle(apres, suite, sizeof suite) > 0)
        dprintf(1, "Filet de secours (si ce recovery échoue) : %s\n", suite);

    /* NON CÂBLÉ : l'ouverture dm-crypt (mot de passe), le montage et la pose A/B
     * se valident en VM. Ils réutilisent exactement le chemin vérifié de la
     * console (empreinte épinglée AVANT parsing, signature, extraction A/B). */
    return 0;
}
