/*
 * puppy-lanceur.c — lanceur de confiance des applications privilégiées
 *                   CHIFFRÉES AU REPOS (coffre .pupcoffre).
 *
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │ SQUELETTE — À VALIDER EN VM AVANT TOUT CÂBLAGE.                         │
 * │ Compile et se lie proprement, mais N'EST PAS branché au démarrage ni   │
 * │ dans puppy-autorite : rien ne s'exécute encore à travers lui. Le TPM,  │
 * │ memfd+fexecve et le secret de machine ne sont testables que sur la     │
 * │ machine de Piika (QEMU/VirtualBox avec swtpm). Ne pas activer avant.   │
 * └───────────────────────────────────────────────────────────────────────┘
 *
 * RÔLE : déchiffrer EN MÉMOIRE SEULEMENT un binaire privilégié
 * (paquets-autorite, puppy-maj) et l'exécuter, sans jamais écrire le clair sur
 * le disque. La clé vient du secret de machine déjà scellé au TPM
 * (SCEAU_SECRET), dérivée par le cœur Rust testé (coffre.rs) :
 *     clé = HMAC-SHA256(secret_machine, "puppy-coffre-v1" | app | génération)
 * Toute la logique crypto et les bornes sont dans coffre.rs (95 tests) ; ici
 * on ne fait QUE les appels système privilégiés (lecture du secret root,
 * memfd, exec), comme paquets-autorite délègue sa décision au Rust.
 *
 * DEUX MURS : fs-verity (déjà en place) garantit l'INTÉGRITÉ au repos ; le
 * coffre ajoute la CONFIDENTIALITÉ (on ne peut pas désassembler le binaire
 * privilégié hors ligne sans vaincre d'abord le TPM + le démarrage mesuré).
 * Le coffre ne protège PAS le processus en cours (le clair vit en RAM).
 *
 * POLITIQUE (choix de Piika, 06/11) : REFUS PUR. Si le coffre ne peut pas être
 * ouvert (TPM inactif, secret absent, blob abîmé, descellement refusé, retour
 * arrière…), le lanceur NE lance RIEN — pas de repli sur le binaire en clair.
 * Ce n'est pas dangereux au démarrage : les deux outils de MAJ ne sont pas
 * critiques au boot, PuppyOS démarre sans eux. La réparation est confiée à une
 * application séparée et cloisonnée, la « console de secours » (puprecovery),
 * qui réinstalle proprement les deux apps à partir d'une copie SIGNÉE, sans
 * jamais manipuler de clé (pas de pouvoir de déchiffrage hors TPM = pas de
 * surface d'attaque à grand pouvoir). Voir puprecovery.c / secours.rs.
 *
 * USAGE (prévu) :
 *   puppy-lanceur <app> <coffre.pupcoffre> [-- args...]
 *     <app>     nom pour le KDF ("paquets-autorite" | "puppy-maj")
 *     <coffre>  chemin du .pupcoffre (chiffré)
 *
 * Rotation (faite par puppy-maj, hors de ce fichier) : nouvelle génération,
 * nouvelle clé dérivée, réécriture du .pupcoffre + du fichier <coffre>.gen
 * (génération minimale acceptée, monotone → anti-retour-arrière).
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "crypto.h"
#include "puppy.h"
#include "scellement.h"
#include "securite.h"
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

/* --- cœur Rust (coffre.rs via libpupdate.a) --- */
extern int pupdate_coffre_deriver(const uint8_t *secret, const char *app, uint32_t generation, uint8_t *out_cle);
extern int pupdate_coffre_ouvrir(const uint8_t *cle, const uint8_t *blob, size_t blob_len, uint32_t generation_min,
                                 uint8_t *out, size_t out_cap, size_t *out_len, uint32_t *out_gen);

/* mêmes constantes que coffre.rs (en-tête authentifié + bornes) */
#define COFFRE_TETE 44
#define COFFRE_MAC  16
#define COFFRE_MAX  (64ull * 1024 * 1024)

/* code de sortie en cas de refus (non nul : rien n'a été lancé) */
#define LANCEUR_REFUS 2

static void effacer(void *p, size_t n) { if (p) effacer_memoire(p, n); }

/* REFUS PUR : trace la cause et rend la main sans rien exécuter. La réparation
 * est l'affaire de la console de secours, pas du lanceur. */
static int refuser(const char *coffre, const char *cause)
{
    securite_noter("LANCEUR", "REFUSE", GRAVITE_ALERTE, coffre, "%s", cause);
    fprintf(stderr, "puppy-lanceur : application privilégiée non ouverte (%s). "
                    "Réparation via la console de secours.\n", cause);
    return LANCEUR_REFUS;
}

/* Lit le secret de machine (32 o) scellé au TPM. 0 = OK. */
static int lire_secret_machine(uint8_t s[32])
{
    size_t n = 0;
    char *d = lire_fichier(chemin_systeme(SCEAU_SECRET), &n);
    int ok = d && n == 32;
    if (ok) memcpy(s, d, 32);
    if (d) { effacer(d, n); free(d); }
    return ok ? 0 : -1;
}

/* Lit la génération minimale acceptée (<coffre>.gen), 0 si absent. */
static uint32_t lire_generation_min(const char *coffre)
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

/* Exécute le binaire en clair `elf`/`n` depuis un memfd (jamais sur disque).
 * Ne revient qu'en cas d'échec. */
static void exec_depuis_memoire(const uint8_t *elf, size_t n, char *const argv[])
{
    int fd = memfd_create("puppy-priv", MFD_CLOEXEC);
    if (fd < 0) return;
    size_t ecrit = 0;
    while (ecrit < n) {
        ssize_t w = write(fd, elf + ecrit, n - ecrit);
        if (w < 0) { close(fd); return; }
        ecrit += (size_t)w;
    }
    extern char **environ;
    fexecve(fd, argv, environ);   /* ne revient qu'en cas d'échec */
    close(fd);
}

int main(int argc, char **argv)
{
    if (argc < 3) {
        fprintf(stderr, "usage: puppy-lanceur <app> <coffre.pupcoffre> [-- args...]\n");
        return 2;
    }
    const char *app = argv[1];
    const char *coffre = argv[2];

    /* argv de l'exécutable final : argv[0] = app, puis les arguments après « -- » */
    char **sous = &argv[3];
    if (argc > 3 && !strcmp(argv[3], "--")) sous = &argv[4];
    int nsub = 0; while (sous[nsub]) nsub++;
    char **argv_final = calloc((size_t)nsub + 2, sizeof *argv_final);
    if (!argv_final) return 1;
    argv_final[0] = (char *)app;
    for (int i = 0; i < nsub; i++) argv_final[i + 1] = sous[i];

    /* Sans scellement TPM : pas de confidentialité possible → refus pur. */
    if (!sceau_actif()) return refuser(coffre, "scellement TPM inactif");

    uint8_t secret[32];
    if (lire_secret_machine(secret) != 0) return refuser(coffre, "secret de machine illisible");

    size_t nblob = 0;
    char *blob = lire_fichier(coffre, &nblob);
    if (!blob || nblob < COFFRE_TETE + COFFRE_MAC) {
        effacer(secret, 32);
        if (blob) free(blob);
        return refuser(coffre, "coffre absent ou trop court");
    }

    /* génération portée par l'en-tête (authentifiée comme donnée associée) */
    const uint8_t *b = (const uint8_t *)blob;
    uint32_t generation = (uint32_t)b[8] | (uint32_t)b[9] << 8 | (uint32_t)b[10] << 16 | (uint32_t)b[11] << 24;
    uint32_t gen_min = lire_generation_min(coffre);

    uint8_t cle[32];
    if (pupdate_coffre_deriver(secret, app, generation, cle) != 0) {
        effacer(secret, 32); free(blob);
        return refuser(coffre, "dérivation de clé impossible");
    }
    effacer(secret, 32);   /* le secret ne sert plus */

    /* clair en mémoire : alloué, borné par coffre.rs (≤ 64 Mio) */
    size_t cap = nblob;    /* le clair est toujours < blob (en-tête+MAC en moins) */
    uint8_t *clair = malloc(cap ? cap : 1);
    if (!clair) { effacer(cle, 32); free(blob); return 1; }
    size_t nclair = 0; uint32_t gen_ouvert = 0;
    int rc = pupdate_coffre_ouvrir(cle, b, nblob, gen_min, clair, cap, &nclair, &gen_ouvert);
    effacer(cle, 32);
    free(blob);
    if (rc != 0) {
        /* 4 = retour arrière, 5 = authentification (clé/altération), etc. */
        char d[80]; snprintf(d, sizeof d, "coffre refusé (code %d)", rc);
        effacer(clair, cap); free(clair);
        return refuser(coffre, d);
    }

    /* exécution en mémoire ; si memfd/fexecve échoue, c'est aussi un refus */
    exec_depuis_memoire(clair, nclair, argv_final);
    effacer(clair, cap); free(clair);
    return refuser(coffre, "exécution en mémoire impossible (memfd/fexecve)");
}
