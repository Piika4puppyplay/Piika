/*
 * Navigateur 2.1 (PuppyOS 0.12.2) — personnalisation de l'affichage.
 *
 *   langage Puppyplay   un dictionnaire « Mot --> remplacement » appliqué au
 *                       texte affiché des pages
 *   images              « Modifier » une image d'un site par une image locale
 *                       (gardée pour ce site, sur cette machine), « Restaurer
 *                       l'original »
 *   thème Puppyplay     extension intégrée (couleurs douces, coins arrondis)
 *   applications web    raccourci « .pupweb » sur le Bureau, ouvert en plein
 *                       écran dans son propre bac à sable
 *
 * Règle : tout cela ne change que ce que TU vois, ici. Le site, ses fonctions
 * payantes, ce qu'il envoie ou reçoit, rien côté serveur n'est touché.
 */
#define _GNU_SOURCE
#include "nav.h"
#include "cdp.h"
#include "puppy.h"
#include <ctype.h>
#include <dirent.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

/* ------------------------------ langage --------------------------------- */
mot_remplace mots[MOTS_MAX];
int nb_mots;

static void nettoyer(char *s)
{
    while (*s == ' ' || *s == '\t') memmove(s, s + 1, strlen(s));
    size_t n = strlen(s);
    while (n && (s[n - 1] == ' ' || s[n - 1] == '\t' || s[n - 1] == '\r')) s[--n] = 0;
    for (; *s; s++) if (*s == '\n' || *s == '|') *s = ' ';
}

int langage_ajouter(const char *ligne)
{
    const char *f = strstr(ligne, "-->");
    if (!f || nb_mots >= MOTS_MAX) return -1;
    mot_remplace m;
    size_t n = (size_t)(f - ligne);
    if (n >= sizeof m.de) n = sizeof m.de - 1;
    memcpy(m.de, ligne, n);
    m.de[n] = 0;
    chaine_copier(m.vers, f + 3, sizeof m.vers);
    nettoyer(m.de);
    nettoyer(m.vers);
    if (!m.de[0]) return -1;
    for (int i = 0; i < nb_mots; i++)
        if (!strcasecmp(mots[i].de, m.de)) { mots[i] = m; return i; }
    mots[nb_mots] = m;
    return nb_mots++;
}

void langage_supprimer(int i)
{
    if (i < 0 || i >= nb_mots) return;
    memmove(&mots[i], &mots[i + 1], sizeof mots[0] * (size_t)(nb_mots - i - 1));
    nb_mots--;
}

void langage_charger(void)
{
    nb_mots = 0;
    char *t = lire_fichier(chemin_donnees("navigateur-langage.txt"), NULL);
    if (!t) {
        /* un exemple pour commencer, celui de Dylan */
        langage_ajouter("Maison --> niche");
        return;
    }
    for (char *l = strtok(t, "\n"); l; l = strtok(NULL, "\n")) if (l[0] != '#') langage_ajouter(l);
    free(t);
}

void langage_enregistrer(void)
{
    char b[MOTS_MAX * 140 + 128];
    size_t o = (size_t)snprintf(b, sizeof b, "# Langage Puppyplay : « Mot --> remplacement », un par ligne\n");
    for (int i = 0; i < nb_mots; i++) o += (size_t)snprintf(b + o, sizeof b - o, "%s --> %s\n", mots[i].de, mots[i].vers);
    ecrire_fichier_atomique(chemin_donnees("navigateur-langage.txt"), b, o);
}

/* Le texte affiché (noeuds de texte, titre, bulles) est remplacé mot entier,
 * en gardant la majuscule ; les champs de saisie, le code et les zones
 * modifiables ne sont jamais touchés. Un observateur suit les ajouts. */
static const char *JS_LANGAGE =
    "(()=>{if(window.__puppyLangage)return;window.__puppyLangage=1;const D=%s;if(!D.length)return;"
    "const esc=s=>s.replace(/[.*+?^${}()|[\\]\\\\]/g,'\\\\$&');"
    "const M=new Map(D.map(([a,b])=>[a.toLowerCase(),b]));"
    "const R=new RegExp('(?<![\\\\p{L}\\\\p{N}_])('+D.map(d=>esc(d[0])).sort((a,b)=>b.length-a.length).join('|')+')(?![\\\\p{L}\\\\p{N}_])','giu');"
    "const casse=(o,r)=>o===o.toUpperCase()&&o.length>1?r.toUpperCase():o[0]===o[0].toUpperCase()?r.charAt(0).toUpperCase()+r.slice(1):r;"
    "const tr=s=>s.replace(R,m=>casse(m,M.get(m.toLowerCase())??m));"
    "const NON=/^(SCRIPT|STYLE|TEXTAREA|INPUT|CODE|NOSCRIPT|SELECT|OPTION)$/;const vu=new WeakMap();"
    "const texte=n=>{const p=n.parentNode;if(!p||NON.test(p.nodeName)||(p.isContentEditable))return;"
    "if(vu.get(n)===n.data)return;const t=tr(n.data);if(t!==n.data)n.data=t;vu.set(n,n.data)};"
    "const tout=r=>{if(!r)return;if(r.nodeType===3){texte(r);return}if(r.nodeType!==1&&r.nodeType!==9&&r.nodeType!==11)return;"
    "if(r.nodeType===1&&NON.test(r.nodeName))return;const w=document.createTreeWalker(r,NodeFilter.SHOW_TEXT);let n;"
    "while((n=w.nextNode()))texte(n);if(r.querySelectorAll)for(const e of r.querySelectorAll('[title],[alt],[placeholder]'))"
    "for(const a of['title','alt','placeholder']){const v=e.getAttribute(a);if(v){const t=tr(v);if(t!==v)e.setAttribute(a,t)}}};"
    "const titre=()=>{const t=tr(document.title);if(t!==document.title)document.title=t};"
    "const go=()=>{tout(document.body);titre();new MutationObserver(l=>{for(const m of l){if(m.type==='characterData')texte(m.target);"
    "else for(const n of m.addedNodes)tout(n)}titre()}).observe(document.documentElement,{childList:true,subtree:true,characterData:true})};"
    "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',go);else go()})()";

static void json_ajouter(char **b, size_t *o, size_t *cap, const char *s)
{
    char e[300];
    json_echapper(e, sizeof e, s);
    size_t l = strlen(e);
    if (*o + l + 8 >= *cap) {
        *cap = (*cap + l) * 2;
        char *nb = realloc(*b, *cap);
        if (!nb) return;
        *b = nb;
    }
    memcpy(*b + *o, e, l);
    *o += l;
}

char *script_langage(void)
{
    if (!R.langage || !nb_mots) return NULL;
    size_t cap = 4096, o = 0;
    char *d = malloc(cap);
    if (!d) return NULL;
    d[o++] = '[';
    for (int i = 0; i < nb_mots; i++) {
        if (i) d[o++] = ',';
        d[o++] = '[';
        json_ajouter(&d, &o, &cap, mots[i].de);
        d[o++] = ',';
        json_ajouter(&d, &o, &cap, mots[i].vers);
        d[o++] = ']';
    }
    d[o++] = ']';
    d[o] = 0;
    size_t n = strlen(JS_LANGAGE) + o + 16;
    char *js = malloc(n);
    if (js) snprintf(js, n, JS_LANGAGE, d);
    free(d);
    return js;
}

/* ------------------------------ thème ----------------------------------- */
static const char *JS_THEME =
    "(()=>{if(window.__puppyTheme)return;window.__puppyTheme=1;const css=`"
    "html{scrollbar-color:#e59ab8 #fff3f8}"
    "body{background-image:radial-gradient(circle at 12px 12px,rgba(229,154,184,.10) 3px,transparent 4px)!important;"
    "background-size:44px 44px!important}"
    "a{color:#b24a86!important}a:visited{color:#7d4aa8!important}"
    "button,input,select,textarea{border-radius:12px!important}"
    "button,input[type=submit],input[type=button]{background:#ffe3ef!important;color:#6b2348!important;border:1px solid #e59ab8!important}"
    "img,video{border-radius:10px}"
    "::selection{background:#f7b6d2;color:#3a1028}`;"
    "const s=document.createElement('style');s.id='puppy-theme';s.textContent=css;"
    "const mettre=()=>{if(!document.getElementById('puppy-theme'))(document.head||document.documentElement).appendChild(s)};"
    "mettre();document.addEventListener('DOMContentLoaded',mettre)})()";

const char *script_theme_puppyplay(void) { return R.theme_puppyplay ? JS_THEME : NULL; }

/* ------------------------------ images ---------------------------------- */
/* navigateur-images/index.txt : « site|adresse d'origine|fichier », et les
 * images elles-mêmes dans le même dossier. */
#define IMAGE_MAX (3 * 1024 * 1024)
typedef struct { char hote[128], original[900], fichier[64]; } image_perso;
#define IMAGES_MAX 200
static image_perso imgs[IMAGES_MAX];
static int nb_imgs, imgs_lues;

static const char *dossier_images(void) { return chemin_donnees("navigateur-images"); }

static void images_charger(void)
{
    imgs_lues = 1;
    nb_imgs = 0;
    char c[700];
    snprintf(c, sizeof c, "%s/index.txt", dossier_images());
    char *t = lire_fichier(c, NULL);
    if (!t) return;
    for (char *l = strtok(t, "\n"); l && nb_imgs < IMAGES_MAX; l = strtok(NULL, "\n")) {
        char *a = strchr(l, '|'), *b = a ? strchr(a + 1, '|') : NULL;
        if (!b || l[0] == '#') continue;
        *a = *b = 0;
        image_perso *i = &imgs[nb_imgs++];
        chaine_copier(i->hote, l, sizeof i->hote);
        chaine_copier(i->original, a + 1, sizeof i->original);
        chaine_copier(i->fichier, b + 1, sizeof i->fichier);
    }
    free(t);
}

static void images_enregistrer(void)
{
    size_t cap = (size_t)nb_imgs * 1100 + 64, o = 0;
    char *b = malloc(cap);
    if (!b) return;
    o += (size_t)snprintf(b, cap, "# site|adresse d'origine|fichier\n");
    for (int i = 0; i < nb_imgs; i++)
        o += (size_t)snprintf(b + o, cap - o, "%s|%s|%s\n", imgs[i].hote, imgs[i].original, imgs[i].fichier);
    char c[700];
    snprintf(c, sizeof c, "%s/index.txt", dossier_images());
    ecrire_fichier_atomique(c, b, o);
    free(b);
}

int images_nombre(const char *hote)
{
    if (!imgs_lues) images_charger();
    int n = 0;
    for (int i = 0; i < nb_imgs; i++) if (!strcmp(imgs[i].hote, hote)) n++;
    return n;
}

static const char *type_image(const char *nom)
{
    const char *e = strrchr(nom, '.');
    if (!e) return NULL;
    if (!strcasecmp(e, ".png")) return "image/png";
    if (!strcasecmp(e, ".jpg") || !strcasecmp(e, ".jpeg")) return "image/jpeg";
    if (!strcasecmp(e, ".gif")) return "image/gif";
    if (!strcasecmp(e, ".webp")) return "image/webp";
    if (!strcasecmp(e, ".bmp")) return "image/bmp";
    return NULL;   /* pas de SVG : il peut contenir du script */
}

int image_remplacer(const char *hote, const char *original, const char *fichier, char *err, size_t nerr)
{
    if (!imgs_lues) images_charger();
    if (!type_image(fichier)) { snprintf(err, nerr, "Choisis une image PNG, JPEG, GIF, WebP ou BMP."); return -1; }
    size_t n = 0;
    char *d = lire_fichier(fichier, &n);
    if (!d) { snprintf(err, nerr, "Image illisible."); return -1; }
    if (n > IMAGE_MAX) { free(d); snprintf(err, nerr, "Image trop grande (3 Mo au plus)."); return -1; }
    mkdir(dossier_images(), 0700);
    char nom[64], c[800];
    const char *ext = strrchr(fichier, '.');
    snprintf(nom, sizeof nom, "%08lx%04x%s", (unsigned long)time(NULL), (unsigned)(rand() & 0xffff), ext);
    snprintf(c, sizeof c, "%s/%s", dossier_images(), nom);
    int r = ecrire_fichier_atomique(c, d, n);
    free(d);
    if (r < 0) { snprintf(err, nerr, "Impossible de garder l'image."); return -1; }
    images_restaurer(hote, original);          /* une seule image par original */
    if (nb_imgs >= IMAGES_MAX) { unlink(c); snprintf(err, nerr, "Trop d'images modifiées."); return -1; }
    image_perso *i = &imgs[nb_imgs++];
    chaine_copier(i->hote, hote, sizeof i->hote);
    chaine_copier(i->original, original, sizeof i->original);
    for (char *p = i->original; *p; p++) if (*p == '|' || *p == '\n') *p = ' ';
    chaine_copier(i->fichier, nom, sizeof i->fichier);
    images_enregistrer();
    return 0;
}

int images_restaurer(const char *hote, const char *original)
{
    if (!imgs_lues) images_charger();
    int n = 0;
    for (int i = 0; i < nb_imgs; ) {
        if (!strcmp(imgs[i].hote, hote) && (!original || !strcmp(imgs[i].original, original))) {
            char c[800];
            snprintf(c, sizeof c, "%s/%s", dossier_images(), imgs[i].fichier);
            unlink(c);
            imgs[i] = imgs[--nb_imgs];
            n++;
        } else i++;
    }
    if (n) images_enregistrer();
    return n;
}

/* le script du site : { adresse d'origine : image (data:) } */
char *script_images(const char *hote)
{
    if (images_nombre(hote) == 0) return NULL;
    size_t cap = 65536, o = 0;
    char *m = malloc(cap);
    if (!m) return NULL;
    m[o++] = '{';
    int premier = 1;
    for (int i = 0; i < nb_imgs; i++) {
        if (strcmp(imgs[i].hote, hote)) continue;
        char c[800];
        snprintf(c, sizeof c, "%s/%s", dossier_images(), imgs[i].fichier);
        size_t n = 0;
        char *d = lire_fichier(c, &n);
        const char *type = type_image(imgs[i].fichier);
        if (!d || !type) { free(d); continue; }
        size_t besoin = o + n * 4 / 3 + strlen(imgs[i].original) * 2 + 200;
        if (besoin >= cap) {
            cap = besoin * 2;
            char *nm = realloc(m, cap);
            if (!nm) { free(d); break; }
            m = nm;
        }
        char e[1900];
        json_echapper(e, sizeof e, imgs[i].original);
        o += (size_t)snprintf(m + o, cap - o, "%s%s:\"data:%s;base64,", premier ? "" : ",", e, type);
        o += base64_encoder((const uint8_t *)d, n, m + o, cap - o);
        m[o++] = '"';
        premier = 0;
        free(d);
    }
    m[o++] = '}';
    m[o] = 0;
    static const char *JS =
        "(()=>{const M=%s;const abs=u=>{try{return new URL(u,location.href).href}catch(e){return u}};"
        "const voir=i=>{if(!(i instanceof HTMLImageElement))return;const o=i.dataset.puppyOriginal||abs(i.getAttribute('src')||'');"
        "const c=i.dataset.puppyOriginal?null:(M[o]?o:(M[i.currentSrc]?i.currentSrc:null));if(!c)return;"
        "i.dataset.puppyOriginal=c;i.removeAttribute('srcset');i.removeAttribute('sizes');"
        "const p=i.parentElement;if(p&&p.tagName==='PICTURE')p.querySelectorAll('source').forEach(s=>s.remove());i.src=M[c]};"
        "const tout=r=>{if(r.tagName==='IMG')voir(r);if(r.querySelectorAll)r.querySelectorAll('img').forEach(voir)};"
        "const go=()=>{tout(document);new MutationObserver(l=>{for(const m of l){if(m.type==='attributes')voir(m.target);"
        "else m.addedNodes.forEach(n=>n.nodeType===1&&tout(n))}}).observe(document.documentElement,"
        "{childList:true,subtree:true,attributes:true,attributeFilter:['src','srcset']});"
        "document.addEventListener('load',e=>voir(e.target),true)};"
        "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',go);else go()})()";
    size_t n = strlen(JS) + o + 16;
    char *js = malloc(n);
    if (js) snprintf(js, n, JS, m);
    free(m);
    return js;
}

/* ------------------------- applications web ------------------------------ */
/* « Nom.pupweb » sur le Bureau : quelques lignes « cle=valeur ». Le bureau
 * l'ouvre en lançant le Navigateur avec --appli=<chemin> ; puppy-autorite
 * vérifie que le fichier est bien sur une gamelle. */
int appli_creer(const char *url, const char *titre, char *chemin, size_t n)
{
    char nom[80];
    size_t k = 0;
    for (const char *p = titre[0] ? titre : "Application"; *p && k + 1 < 48; p++) {
        unsigned char c = (unsigned char)*p;
        nom[k++] = (c == '/' || c == '\\' || c == ':' || c == '|' || c < 32 || c == '.') ? ' ' : (char)c;
    }
    while (k && nom[k - 1] == ' ') k--;
    nom[k] = 0;
    if (!nom[0]) chaine_copier(nom, "Application", sizeof nom);
    snprintf(chemin, n, PUPPY_BUREAU_DOSSIER "/%s.pupweb", nom);
    char t[1600];
    int l = snprintf(t, sizeof t, "# Application web PuppyOS (ouverte par le Navigateur, en plein écran, dans son bac à sable)\n"
                     "titre=%s\nadresse=%s\n", nom, url);
    if (l <= 0 || (size_t)l >= sizeof t || strpbrk(url, "\n\r")) return -1;
    return ecrire_fichier_atomique(chemin, t, (size_t)l);
}

int appli_lire(const char *chemin, char *url, size_t nu, char *titre, size_t nt)
{
    char *t = lire_fichier(chemin, NULL);
    if (!t) return -1;
    url[0] = titre[0] = 0;
    config_valeur(t, "adresse", url, nu);
    config_valeur(t, "titre", titre, nt);
    free(t);
    if (strncmp(url, "https://", 8) && strncmp(url, "http://", 7)) return -1;
    if (!titre[0]) hote_de(url, titre, nt);
    return 0;
}
