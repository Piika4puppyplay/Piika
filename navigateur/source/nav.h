/*
 * Navigateur.puppy 2.1 (PuppyOS 0.12.2) — déclarations communes.
 *
 *   navigateur.c   fenêtre, boucle, moteur, onglets, évènements Chromium
 *   interface.c    barres, menus, champs, dialogues, pages internes,
 *                  premier lancement, aide et lexique
 *   donnees.c      réglages, favoris, historique, exceptions par site,
 *                  extensions (scripts utilisateur)
 *   protections.c  explications, permissions des sites, PHP, pisteurs
 *   perso.c        langage et thème Puppyplay, images, applications web
 *   source.c       code source, audit de sécurité et son historique
 *   ua.c           identités (User-Agent) proposées dans la barre d'adresse
 *   coffre-nav.c   coffre à mots de passe (enclave chiffrée)
 */
#ifndef NAV_H
#define NAV_H

#include "cdp.h"
#include "gfx.h"
#include "pf.h"
#include <stddef.h>

/* ---------------------------- mise en page ---------------------------- */
#define H_ONGLETS  34
#define H_OUTILS   44
#define H_FAVORIS  28
#define H_INFO     36

#define ONGLETS_MAX 12

enum { INT_AUCUNE, INT_PARAMETRES, INT_HISTORIQUE, INT_FAVORIS, INT_COFFRE, INT_EXTENSIONS, INT_AIDE, INT_SOURCE };
#define NB_PAGES_MENU 6                /* pages internes du menu de gauche (1 à 6) */

typedef struct {
    int     vivant;
    int     interne;               /* page interne (puppy://…) : pas de cible Chromium */
    int     defile;                /* défilement d'une page interne */
    char    cible[64], session[64];
    char    url[1024], titre[256], hote[256];
    surface *image;                /* dernière image reçue */
    int     chargement, id_nav, prive;
    int     id_titre;              /* 0.12.1 : demande de document.title en cours */
    int     erreur;                /* 1 : page d'erreur maison (le moteur n'en a pas) ; 2 : plantage ;
                                    * 3 : page PHP bloquée ; 4 : Google Recherche indisponible (2.2) */
    char    erreur_texte[128], erreur_url[1024];
    char    erreur_detail[128];        /* 0.12.1 : cause technique d'un plantage */
    int     http_permis;           /* la personne a choisi d'ouvrir ce site en http */
    int     zoom;                  /* en % */
    int     js_coupe, cookies_coupes;
    int     identifiants;          /* comptes du coffre pour ce site */
    double  depuis;                /* début du chargement (ms) */
    double  derniere_image;
    /* 2.1 (0.12.2) : ce que la barre d'adresse montre du site */
    char    id_langage[24], id_theme[24], id_images[24];   /* scripts posés (pour les retirer) */
    char    hote_images[128];
    int     php;                   /* la page est faite en PHP (vu dans la réponse) */
    char    php_une_fois[256];     /* site PHP ouvert « cette fois » malgré le blocage */
    /* 2.2 : recherche Google neutralisée (google.c) */
    char    google_une_fois[128];  /* accueil de Google ouvert quand même (accès direct) */
    int     google_test;           /* 1 : test demandé, 2 : la recherche de test est partie */
    int     id_google;             /* lecture du résultat du test en cours */
    int     perms;                 /* permissions accordées à cette page (bits PERM_) */
    int     pisteurs_connus, pisteurs_inconnus, mixte, redirections;
    char    pisteurs[12][96];      /* les domaines vus (pour le menu du site et l'audit) */
    int     nb_pisteurs;
    char    sockets[6][160];       /* websockets ouverts */
    int     nb_sockets;
    char    chaine_redir[6][96];   /* domaines traversés par les redirections */
    int     nb_redir;
    struct {                       /* certificat de la page (réponse principale) */
        int    present;
        char   sujet[160], emetteur[160], protocole[24], chiffre[64], echange[48];
        double debut, fin;         /* secondes depuis 1970 */
        int    noms;               /* nombre de noms couverts */
        char   ct[24];             /* certificate transparency */
    } cert;
} onglet;

/* -------------------------------- réglages ---------------------------- */
typedef struct {
    char accueil[512];
    int  recherche;                /* moteur de recherche (MOTEURS_RECHERCHE) */
    int  zoom;
    int  cookies, tiers, js;       /* 1 = permis ; tiers : cookies tiers */
    int  https;                    /* HTTPS d'abord */
    int  bannieres;                /* masquer les bannières cookies */
    int  sombre;                   /* mode sombre forcé */
    int  liens;                    /* nettoyer les liens (utm_, fbclid…) */
    int  gpc;                      /* « ne me pistez pas » (Global Privacy Control) */
    int  popups;                   /* fenêtres surgissantes permises */
    int  coffre;                   /* proposer d'enregistrer les mots de passe */
    int  verrou_min;               /* le coffre se referme après N minutes */
    int  barre_favoris;
    int  lecture_auto;             /* vidéos qui démarrent seules */
    int  ua;                       /* identité (ua.c), 0 = Chrome Linux */
    char ua_perso[300];
    int  vpn;                      /* 0 aucun, 1 SOCKS5, 2 HTTP, 3 HTTPS */
    char vpn_hote[128];
    int  vpn_port;
    int  vpn_coupe_circuit;        /* pas de réseau si le VPN ne répond pas */
    int  effacer_en_quittant;      /* historique + cookies à la fermeture */
    /* 2.1 (0.12.2) */
    int  premier_fait;             /* premier lancement (présentation + sécurité) terminé */
    int  php_bloque;               /* blocage des pages PHP */
    int  expert;                   /* « Je sais ce que je fais » : plus d'explications */
    int  langage;                  /* langage Puppyplay (dictionnaire) */
    int  theme_puppyplay;          /* extension intégrée « Thème Puppyplay » */
    /* 2.2 */
    int  google_redirige;          /* recherches Google → DuckDuckGo tant que la politique le demande */
} reglages;

typedef struct { const char *nom, *adresse; } moteur_recherche;
extern const moteur_recherche MOTEURS_RECHERCHE[];
extern const int NB_MOTEURS_RECHERCHE;

/* ------------------------------- données ------------------------------ */
#define FAVORIS_MAX    200
#define HISTORIQUE_MAX 3000
#define SITES_MAX      200
#define EXT_MAX        24

typedef struct { char url[1024], titre[200]; } favori;
typedef struct { double t; char url[512], titre[160]; } passage;
typedef struct { char hote[128]; int js, cookies, php; } exception_site;  /* -1 = réglage général */
typedef struct { char fichier[80], nom[100], version[24]; int actif, nb; char motifs[8][200]; } extension;

extern reglages R;
extern favori favoris[FAVORIS_MAX];
extern int nb_favoris;
extern passage historique[HISTORIQUE_MAX];
extern int nb_historique;
extern exception_site sites[SITES_MAX];
extern int nb_sites;
extern extension extensions[EXT_MAX];
extern int nb_extensions;

void donnees_charger(void);
void reglages_enregistrer(void);
void favoris_enregistrer(void);
void historique_enregistrer(void);    /* au plus toutes les 3 s, et en quittant */
void historique_sauver_maintenant(void);
void sites_enregistrer(void);
int  favori_trouver(const char *url);
void favori_ajouter(const char *url, const char *titre);
void favori_supprimer(int i);
void favori_deplacer(int de, int vers);
void historique_ajouter(const char *url, const char *titre);
void historique_titre(const char *url, const char *titre);
void historique_supprimer(int i);
void historique_supprimer_site(const char *hote);
void historique_effacer(double depuis_ms);   /* 0 = tout */
exception_site *site_exception(const char *hote, int creer);
int  site_js_permis(const char *hote);
int  site_cookies_permis(const char *hote);
int  site_php_permis(const char *hote);
/* extensions : scripts utilisateur (« .user.js ») */
int  extension_installer(const char *source, size_t n, char *err, size_t nerr);
void extension_supprimer(int i);
void extensions_enregistrer(void);
char *extension_source(int i);           /* à libérer */
int  hote_correspond(const char *motif, const char *url);
const char *chemin_donnees(const char *f);

/* ----------------------------- identités ------------------------------ */
typedef struct { const char *nom, *court, *ua, *plateforme, *ch_plateforme, *ch_version; int mobile, chromium; } identite;
extern const identite IDENTITES[];
extern const int NB_IDENTITES;
/* paramètres JSON de Emulation.setUserAgentOverride pour l'identité choisie */
void identite_json(int i, const char *perso, char *dst, size_t n);
const char *identite_court(int i);

/* ------------------------------ fenêtre ------------------------------- */
extern pf_fenetre *fen;
extern surface *toile;
extern int EL, EH, PX, PY, PL, PH;
extern double maintenant;
extern double anime_jusqua;           /* 2.1 : redessiner sans attendre jusqu'à cette heure */
extern int sale;
extern cdp moteur;
extern int moteur_pret, streaming;
extern char erreur_moteur[200];
extern onglet onglets[ONGLETS_MAX];
extern int nb_onglets, actif;
#define ACTIF (&onglets[actif])

void texte(int x, int y, const char *t, couleur c, float taille, int gras);
void dire(const char *m);
int  hote_de(const char *u, char *h, size_t n);

/* actions (navigateur.c) */
int  onglet_nouveau(const char *url, int activer, int prive);
int  onglet_interne(int quoi, int activer);
void onglet_fermer(int i);
void onglet_activer(int i);
void onglet_deplacer(int de, int vers);
void aller(const char *u);
void aller_onglet(int i, const char *u);
void valider_saisie(const char *s);
void recharger(void);
void precedent(void);
void suivant(void);
void appliquer_identite(void);       /* sur tous les onglets */
void appliquer_reglages(void);       /* cookies, JS, sombre… sur tous les onglets */
void zoom_changer(int delta);        /* 0 : 100 % */
void rechercher_page(const char *t, int arriere);
void coffre_remplir(void);
void coffre_enregistrer_proposition(int oui, int jamais);
void effacer_donnees(int historique_aussi, int cookies, int cache, double depuis_ms);
void vpn_appliquer(void);
void lien_contexte(int x, int y);
void edition_page(const char *commande);
void source_ouvrir(int audit);         /* source.c : code source et audit du site */
void quitter_navigateur(void);
void mode_lecture(void);
void imprimer_pdf(void);

/* ---------------- 2.2 : recherche Google neutralisée (google.c) ----------- */
enum { GOOGLE_AUTRE, GOOGLE_ACCUEIL, GOOGLE_RECHERCHE, GOOGLE_SUGGESTION };
extern int google_neutralise;         /* politique.conf du paquet signé */
extern const char *GOOGLE_NOTIFICATION;
void google_politique_charger(void);
int  google_actif(void);               /* neutralisée ET redirection voulue */
int  google_classer(const char *url, char *q, size_t nq);
void encoder_recherche(const char *mots, char *q, size_t nq);
void adresse_duckduckgo(const char *mots, char *u, size_t n);
int  moteur_est_google(void);
void google_signaler_redirection(void);
void google_avertir_sans_redirection(void);
void google_test_noter(int ok, int resultats, const char *detail);
void google_tests_effacer(void);
void google_tests_resume(char *d, size_t n);
int  google_test_juger(int sorry, int captcha, int resultats, const char *hote, char *detail, size_t n);
const char *google_ressource(const char *fichier);   /* fichier du paquet */
int  appareil_mobile(void);
void google_ouvrir_vrai(void);         /* navigateur.c : la page locale → le vrai Google */
void google_tester(void);              /* navigateur.c : un test, sur clic */
void google_aide(void);                /* interface.c : l'aide, à la bonne rubrique */
void aide_invalider(void);             /* interface.c : l'aide se reconstruit (résumé des tests) */

/* ------------------------------ interface ----------------------------- */
typedef struct { char t[1024]; int cur, masque, tout; } champ;
int  champ_touche(champ *c, const pf_evenement *e);   /* 1 : Entrée, -1 : Échap */
void champ_dessiner(champ *c, int x, int y, int l, int h, int actif_, const char *indice);
void champ_vider(champ *c);

void interface_dessiner(void);
int  interface_souris(const pf_evenement *e);         /* 1 : consommé */
int  interface_touche(const pf_evenement *e);         /* 1 : consommé */
int  interface_molette(const pf_evenement *e);
void interface_info(int genre, const char *texte);    /* bandeau sous la barre */
void interface_fermer_info(void);
void menu_principal(int x, int y);
void dialogue_coffre(int creer);
void dialogue_favori(int i);
void dialogue_js(int genre, const char *message, const char *defaut);  /* 0 alerte, 1 confirmation, 2 question */
void dialogue_lien(const char *url, int x, int y);
void barre_saisie_activer(void);
int  saisie_active(void);
void page_interne_dessiner(onglet *o);
void glisser_lien(const char *url, const char *titre);  /* glisser-déposer depuis la page */

/* proposition d'enregistrement du coffre (interface.c l'affiche) */
extern char coffre_hote[256], coffre_nom[128];
extern int coffre_proposition;

/* pour les icônes de la barre */
void dessiner_icone_page(int x, int y, const char *hote, int prive);

/* ------------------------- code source (source.c) --------------------- */
#define CONSTATS_MAX 64
typedef struct { int g; char t[600]; } constat;     /* g : 0 info, 1 attention, 2 danger */
typedef struct {
    char   url[1024];
    char  *texte;                  /* le HTML (tronqué à 600 Ko) */
    size_t n;
    long   total;                  /* taille réelle du HTML */
    int   *lignes, nb;             /* début de chaque ligne d'affichage */
    unsigned char *etat;           /* coloration au début de chaque ligne */
    constat constats[CONSTATS_MAX];
    int    nb_constats, pire;
    int    vue;                    /* 0 code, 1 audit */
} source_page;
extern source_page SRC;
void source_liberer(void);
void audits_charger(void);
void audits_effacer(void);
#define AUDITS_MAX 60
typedef struct { double t; char url[300]; int pire, dangers, attentions; char resume[300]; } audit_passe;
extern audit_passe audits[AUDITS_MAX];
extern int nb_audits;

/* ------------------- protections (protections.c, 2.1) ----------------- */
enum { PERM_CAMERA, PERM_MICRO, PERM_POSITION, PERM_NOTIFS, NB_PERMS };
extern const char *const PERM_NOMS[NB_PERMS];      /* « ta caméra »… */
extern const char *const PERM_TITRES[NB_PERMS];    /* « Caméra »… */
#define PERMS_SITES_MAX 120
typedef struct { char hote[128]; signed char p[NB_PERMS]; } perm_site;   /* -1 demander, 0 bloqué, 1 permis */
extern perm_site perms[PERMS_SITES_MAX];
extern int nb_perms;
void permissions_charger(void);
void permissions_enregistrer(void);
int  permission_etat(const char *hote, int type);
void permission_retenir(const char *hote, int type, int etat);
void permission_oublier_site(const char *hote);
int  page_php(const char *url, const char *entetes_json);   /* 1 : la réponse vient de PHP */
int  pisteur_connu(const char *hote);                       /* dans la liste de blocage du moteur */
int  pisteur_probable(const char *url);                     /* inconnu, mais en a l'allure */
int  meme_site(const char *a, const char *b);

/* explications des protections (fenêtres d'avertissement, lexique, premier lancement) */
typedef struct { const char *nom, *activee, *coupee, *risques, *pourquoi, *quand; } explication;
enum { EX_JS, EX_COOKIES, EX_PHP, EX_TIERS, EX_HTTPS, EX_LIENS, EX_GPC, EX_POPUPS, EX_BANNIERES, NB_EXPLICATIONS };
extern const explication EXPLICATIONS[NB_EXPLICATIONS];

/* ---------------- personnalisation (perso.c, 2.1) --------------------- */
/* Tout ne change que l'affichage, sur cette machine : jamais le site, ses
 * fonctions payantes ni rien côté serveur. */
#define MOTS_MAX 300
typedef struct { char de[64], vers[64]; } mot_remplace;
extern mot_remplace mots[MOTS_MAX];
extern int nb_mots;
void langage_charger(void);
void langage_enregistrer(void);
int  langage_ajouter(const char *ligne);           /* « Mot --> remplacement » */
void langage_supprimer(int i);
char *script_langage(void);                        /* à libérer ; NULL si coupé */
const char *script_theme_puppyplay(void);
int  image_remplacer(const char *hote, const char *original, const char *fichier, char *err, size_t nerr);
int  images_restaurer(const char *hote, const char *original);   /* original NULL : toutes ; renvoie le nombre */
int  images_nombre(const char *hote);
char *script_images(const char *hote);             /* à libérer ; NULL si aucune */
int  appli_creer(const char *url, const char *titre, char *chemin, size_t n);   /* raccourci .pupweb sur le Bureau */
int  appli_lire(const char *chemin, char *url, size_t nu, char *titre, size_t nt);

/* actions 2.1 vers navigateur.c */
void php_ouvrir_cette_fois(void);
void php_toujours_permettre(void);
void permission_repondre(int oui, int retenir);
void site_oublier(const char *hote);               /* cookies, stockage, permissions, historique, images */
void site_effacer_stockage(const char *hote);
void premier_terminer(void);
void permissions_page_finie(onglet *o);
void permission_retirer(const char *hote, int type);
void dialogue_permission(const char *hote, int type);   /* interface.c */
void perso_appliquer(void);                     /* langage / thème changés : tous les onglets */
void image_modifier(const char *original);         /* ouvre le choix d'une image locale */
void image_restaurer(const char *original);        /* NULL : toutes celles du site */
void appli_installer(void);
void certificat_details(void);
void audit_rapide(void);                           /* Ctrl+Maj+U */
extern int appli;                                   /* lancé en application web (.pupweb) */

#endif
