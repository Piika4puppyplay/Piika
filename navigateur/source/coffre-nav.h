/* Coffre à mots de passe du Navigateur : enclave isolée (voir coffre-nav.c) */
#ifndef NAV_COFFRE_H
#define NAV_COFFRE_H

#include <stddef.h>
#include <stdint.h>

#define COFFRE_MESSAGE_MAX (192 * 1024)

/* opérations : 'O' ouvrir (mdp\0 fichier chiffré, vide = coffre neuf),
 * 'V' verrouiller, 'T' état, 'L' liste (n\thote\tnom\n, sans mots de passe),
 * 'A' ajouter (hote\0nom\0mdp), 'S' supprimer (n), 'P' prendre (hote\0n),
 * 'M' montrer (n), 'C' changer le mot de passe maître, 'X' tout effacer.
 * Réponse : 'K' (+ fichier chiffré à enregistrer pour A, S, C, X et un
 * coffre neuf), 'F' coffre fermé, 'E' erreur (+ texte). */
int  coffre_demarrer(void);
void coffre_arreter(void);
int  coffre_disponible(void);
int  coffre_requete(char op, const void *d, size_t n, uint8_t *rep, size_t nrep, size_t *lu);

void gcm_chiffrer(const uint8_t cle[32], const uint8_t iv[12], uint8_t *buf, size_t n, uint8_t tag[16]);
int  gcm_dechiffrer(const uint8_t cle[32], const uint8_t iv[12], uint8_t *buf, size_t n, const uint8_t tag[16]);

#endif
