/*
 * cdp — dialogue avec le moteur Chromium (Chrome DevTools Protocol) par
 * deux tubes : messages JSON terminés par un octet nul. Le moteur tourne
 * dans le bac à sable puppy-bac ; nous ne faisons que lui parler.
 */
#ifndef NAV_CDP_H
#define NAV_CDP_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

typedef struct {
    int    ecrire, lire;          /* tubes vers / depuis le moteur */
    pid_t  pid;
    int    prochain_id;
    char  *tampon;
    size_t lu, cap;
    char   session[64];           /* session de l'onglet attaché */
    int    canal;                 /* 0.11 : alertes de Honey-Dog (-1 : aucun) */
} cdp;

/* lance le moteur dans le bac à sable ; mode = "streaming" ou "navigateur" */
int  cdp_lancer(cdp *c, const char *mode, int largeur, int hauteur, char *err, size_t nerr);
void cdp_arreter(cdp *c);

/* envoie une commande ; params = objet JSON déjà écrit ("{}" si vide).
 * avec_session : sur l'onglet attaché. Renvoie l'identifiant. */
int  cdp_envoyer(cdp *c, const char *methode, const char *params, int avec_session);
/* 0.12 : vers une session précise (onglet) ; NULL ou "" : le navigateur entier */
int  cdp_envoyer_a(cdp *c, const char *session, const char *methode, const char *params);
/* pareil, puis le message est effacé de la mémoire (mots de passe) */
int  cdp_envoyer_secret(cdp *c, const char *session, const char *methode, const char *params);
/* lit ce qui est arrivé (non bloquant). Renvoie le message suivant complet
 * (à libérer) ou NULL. */
char *cdp_suivant(cdp *c);
/* attend la réponse à l'identifiant id (les autres messages sont passés à
 * rappel, qui peut être NULL), délai en ms ; NULL si rien. */
char *cdp_attendre(cdp *c, int id, int delai_ms, void (*rappel)(char *msg, void *d), void *d);

/* le message est-il une réponse à la commande id ? */
int  cdp_reponse_a(const char *m, int id);

/* --- lecture JSON (0.11 : messages validés, valeurs lues par chemin) --- */
/* 1 si json est un texte JSON complet et correct (profondeur ≤ 64) */
int  json_valide(const char *json);
/* chaîne au chemin « a.b.c » (objets imbriqués) ; 0 si absente ou pas une chaîne */
int  json_chaine(const char *json, const char *chemin, char *dst, size_t n);
/* comme json_chaine, mais renvoie un pointeur alloué (grandes valeurs : images) */
char *json_chaine_alloc(const char *json, const char *chemin, size_t *n);
int  json_nombre(const char *json, const char *chemin, double *v);
int  json_objet_parcourir(const char *json, const char *chemin, void (*f)(const char *cle, const char *val, void *d), void *d);
int  json_booleen(const char *json, const char *chemin, int *v);
int  json_existe(const char *json, const char *chemin);
const char *json_element(const char *json, const char *chemin, int i);
/* écrit s en chaîne JSON (avec les guillemets) dans dst */
void json_echapper(char *dst, size_t n, const char *s);

/* base64 → octets (dst assez grand : 3/4 de n) ; renvoie la taille */
size_t base64_decoder(const char *src, size_t n, uint8_t *dst);
size_t base64_encoder(const uint8_t *src, size_t n, char *dst, size_t nd);

#endif
