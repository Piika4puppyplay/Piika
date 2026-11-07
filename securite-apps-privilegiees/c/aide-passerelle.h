/*
 * Passerelle d'aide — le SEUL point d'entrée des raccourcis d'aide des
 * applications système .puppy et du bureau vers le centre d'aide PuppyHelp.
 *
 * PuppyHelp est une application système FACULTATIVE : aucune app ne doit en
 * dépendre pour fonctionner (cœur logique garanti et testé : pupdate/aide.rs,
 * PR #62). La passerelle incarne ces garanties côté C :
 *   - elle vérifie la disponibilité de PuppyHelp (installé, actif, intègre) ;
 *   - si l'aide est utilisable ET l'identifiant de page valide, elle ouvre la
 *     bonne page ;
 *   - sinon elle affiche la fenêtre de secours « Centre d'aide indisponible »,
 *     qui fonctionne INDÉPENDAMMENT de PuppyHelp (elle s'affiche même si le
 *     paquet est absent ou corrompu) ;
 *   - elle ne BLOQUE JAMAIS l'application appelante : l'appel retourne tout de
 *     suite, l'appli continue son cours normal quoi qu'il arrive.
 *
 * La décision (ouvrir / secours) est prise par le cœur Rust testé
 * pupdate_aide_raccourci() ; ce module ne fait que détecter l'état et dessiner.
 */
#ifndef PUPPY_AIDE_PASSERELLE_H
#define PUPPY_AIDE_PASSERELLE_H

/* Identifiant du paquet / programme du centre d'aide. */
#define AIDE_PAQUET "PuppyHelp"
#define AIDE_ID     "puppyhelp"

/* État du centre d'aide vu par un appelant. Valeurs alignées sur le cœur
 * Rust (pupdate_aide_raccourci) : 0=opérationnel, 1=désinstallé, 2=désactivé,
 * 3=obsolète, 4=corrompu, 5=indisponible. */
typedef enum {
    AIDE_OPERATIONNEL = 0,
    AIDE_DESINSTALLE  = 1,
    AIDE_DESACTIVE    = 2,
    AIDE_OBSOLETE     = 3,
    AIDE_CORROMPU     = 4,
    AIDE_INDISPONIBLE = 5,
} aide_etat_t;

/* État courant du centre d'aide (détecté : présence, désactivation, intégrité). */
aide_etat_t aide_etat(void);

/* 1 si l'aide peut réellement ouvrir une page maintenant (sinon : griser le
 * bouton/l'entrée de menu « Centre d'aide » plutôt que de la masquer). */
int aide_disponible(void);

/* Ouvre l'aide sur la page page_id (ex. « securite/les-chiens », « confidentialite »).
 * page_id peut être NULL (page d'accueil). Si l'aide n'est pas utilisable ou si
 * l'identifiant est refusé par le cœur, affiche la fenêtre de secours. Ne bloque
 * JAMAIS l'appelant : retourne immédiatement. */
void aide_ouvrir_page(const char *page_id);

/* Ouvre directement la fenêtre de secours indépendante « Centre d'aide
 * indisponible ». Utilisée quand l'utilisateur clique un bouton d'aide grisé,
 * ou pour un diagnostic. Fonctionne même si PuppyHelp est totalement absent. */
void aide_secours_ouvrir(aide_etat_t cause);

#endif
