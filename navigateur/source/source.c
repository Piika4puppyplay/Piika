/*
 * Navigateur.puppy 2.1 (PuppyOS 0.12.2) — code source et audit d'un site.
 *
 * Le code est lu dans la page elle-même (le HTML tel que le site l'a
 * construit, scripts compris), découpé en lignes d'affichage et coloré par
 * interface.c. L'audit est fait par un petit script exécuté dans la page :
 * chiffrement, domaines contactés, pisteurs connus, code dissimulé, mineurs
 * de cryptomonnaie, formulaires envoyés ailleurs, cadres invisibles…
 * Rien n'est envoyé nulle part : tout reste dans le Navigateur.
 */
#define _GNU_SOURCE
#include "nav.h"
#include "puppy.h"
#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

char *nav_demander(const char *session, const char *methode, const char *params, int delai);

source_page SRC;

#define SOURCE_MAX   (600 * 1024)       /* au-delà, le code est tronqué */
#define LARGEUR_LIGNE 150                /* octets par ligne d'affichage */

/* Le script répond en texte simple :
 *   ligne 1 : adresse
 *   ligne 2 : taille totale du HTML
 *   ligne 3 : nombre N de constats
 *   N lignes : gravité (0 info, 1 attention, 2 danger), tabulation, texte
 *   le reste : le HTML */
static const char *JS_AUDIT =
    "(()=>{const D=document,L=location,R=[];const add=(g,t)=>R.push(g+'\\t'+String(t).replace(/[\\t\\n]/g,' '));"
    "const h=L.hostname;const racine=d=>d.split('.').slice(-2).join('.');"
    "const dom=x=>{try{return new URL(x,L.href).hostname}catch(e){return ''}};"
    "const meme=d=>!d||racine(d)===racine(h);"
    "if(L.protocol==='https:')add(0,'Connexion chiffrée (https).');"
    "else if(L.protocol==='http:')add(2,'Page non chiffrée (http) : ce que tu tapes ici peut être lu sur le réseau.');"
    "const sc=[...D.scripts],ext=sc.filter(s=>s.src),inl=sc.filter(s=>!s.src);"
    "add(0,sc.length+' scripts : '+ext.length+' fichiers, '+inl.length+' écrits dans la page.');"
    "const res=performance.getEntriesByType('resource').map(e=>e.name);"
    "const doms={};res.concat(ext.map(s=>s.src)).forEach(u=>{const d=dom(u);if(d&&!meme(d))doms[d]=(doms[d]||0)+1});"
    "const dl=Object.keys(doms).sort((a,b)=>doms[b]-doms[a]);"
    "if(dl.length)add(dl.length>15?1:0,'Contacte '+dl.length+' autre(s) domaine(s) : '+dl.slice(0,15).join(', ')+(dl.length>15?'…':''));"
    "else add(0,'Ne contacte aucun autre domaine.');"
    "const TR={'google-analytics':'Google Analytics','googletagmanager':'Google Tag Manager','doubleclick':'DoubleClick',"
    "'googlesyndication':'Google AdSense','facebook.net':'Facebook','connect.facebook':'Facebook','hotjar':'Hotjar (enregistre la souris)',"
    "'clarity.ms':'Microsoft Clarity (enregistre la souris)','criteo':'Criteo','taboola':'Taboola','outbrain':'Outbrain',"
    "'scorecardresearch':'comScore','quantserve':'Quantcast','adnxs':'AppNexus','amazon-adsystem':'Amazon Ads',"
    "'analytics.tiktok':'TikTok','sc-static.net':'Snapchat','snap.licdn':'LinkedIn','bat.bing':'Bing Ads','mc.yandex':'Yandex Metrica',"
    "'mixpanel':'Mixpanel','cdn.segment':'Segment','fullstory':'FullStory (enregistre la session)','mouseflow':'Mouseflow (enregistre la souris)',"
    "'smartlook':'Smartlook (enregistre la session)','xiti':'AT Internet','matomo':'Matomo','piwik':'Matomo','pinimg.com/ct':'Pinterest',"
    "'static.ads-twitter':'X / Twitter Ads','cdn.heapanalytics':'Heap','newrelic':'New Relic','sentry':'Sentry (rapports d’erreurs)'};"
    "const tout=res.concat(ext.map(s=>s.src)).join(' ');"
    "const tr=[...new Set(Object.keys(TR).filter(k=>tout.includes(k)).map(k=>TR[k]))];"
    "if(tr.length)add(1,tr.length+' pisteur(s) connu(s) : '+tr.join(', ')+'.');else add(0,'Aucun pisteur connu repéré.');"
    "const code=inl.map(s=>s.textContent).join('\\n');"
    "const P=[[/\\beval\\s*\\(/,'eval()'],[/new\\s+Function\\s*\\(/,'new Function()'],[/document\\.write\\s*\\(/,'document.write()'],"
    "[/\\batob\\s*\\(/,'atob() (texte décodé du base64)'],[/fromCharCode/,'fromCharCode (texte reconstruit)'],"
    "[/[A-Za-z0-9+\\/=]{600,}/,'très longue chaîne base64'],[/(\\\\x[0-9a-fA-F]{2}){30,}/,'texte en hexadécimal'],"
    "[/_0x[0-9a-f]{4,}/,'noms de variables brouillés (obfuscateur)']];"
    "const f=P.filter(p=>p[0].test(code)).map(p=>p[1]);"
    "if(f.length)add(1,'Code peut-être dissimulé dans la page : '+f.join(', ')+'. Souvent normal (code compressé), parfois signe d’un piège.');"
    "if(/coinhive|cryptonight|coin-hive|webminer|cryptoloot|deepminer|minero\\.cc|webmine\\.|monerominer/i.test(code+' '+tout))"
    "add(2,'Mineur de cryptomonnaie repéré : la page utilise ton processeur pour gagner de l’argent.');"
    "const pw=D.querySelectorAll('input[type=password]');"
    "[...D.forms].forEach(x=>{const ac=x.getAttribute('action')?x.action:L.href;const d=dom(ac);const mdp=!!x.querySelector('input[type=password]');"
    "if(mdp&&ac.startsWith('http:'))add(2,'Un formulaire de mot de passe est envoyé sans chiffrement.');"
    "if(d&&!meme(d))add(mdp?2:1,(mdp?'Un formulaire de mot de passe':'Un formulaire')+' est envoyé à un autre site : '+d+'.')});"
    "if(pw.length)add(0,pw.length+' champ(s) de mot de passe sur la page.');"
    "if(D.querySelector('input[autocomplete=cc-number],input[name*=card i],input[name*=carte i]'))"
    "add(1,'La page demande un numéro de carte bancaire : le Navigateur ne l’enregistre jamais.');"
    "const ifr=[...D.querySelectorAll('iframe')];"
    "const cach=ifr.filter(i=>{const r=i.getBoundingClientRect(),s=getComputedStyle(i);return r.width<5||r.height<5||s.visibility=='hidden'||s.display=='none'||s.opacity=='0'});"
    "if(ifr.length)add(cach.length?1:0,ifr.length+' cadre(s) intégré(s)'+(cach.length?', dont '+cach.length+' invisible(s)':'')+'.');"
    "const mx=[...D.querySelectorAll('script[src],img[src],iframe[src],link[href][rel=stylesheet]')].filter(e=>(e.src||e.href||'').startsWith('http:'));"
    "if(L.protocol=='https:'&&mx.length)add(1,mx.length+' ressource(s) chargée(s) sans chiffrement sur une page chiffrée.');"
    "if(D.querySelector('[onkeydown],[onkeypress],[onkeyup]'))add(0,'Des éléments écoutent les touches du clavier (normal pour un champ, à surveiller ailleurs).');"
    "const nv=[...D.links].filter(a=>a.target=='_blank'&&!/noopener|noreferrer/.test(a.rel)).length;"
    "if(nv)add(0,nv+' lien(s) vers un nouvel onglet sans protection noopener.');"
    "if(D.querySelector('meta[http-equiv=Content-Security-Policy i]'))add(0,'Politique de sécurité du contenu (CSP) déclarée dans la page.');"
    "let ck=0,ls=0;try{ck=D.cookie?D.cookie.split(';').length:0}catch(e){}try{ls=localStorage.length}catch(e){}"
    "add(0,ck+' cookie(s) lisible(s) par la page, '+ls+' élément(s) en stockage local.');"
    "if(sc.some(s=>s.src&&!meme(dom(s.src))&&!s.integrity))add(0,'Des scripts d’autres sites sont chargés sans empreinte d’intégrité (SRI).');"
    "const C=D.documentElement.cloneNode(true);C.querySelectorAll('[id^=puppy-]').forEach(e=>e.remove());const H=C.outerHTML;"
    "return L.href+'\\n'+H.length+'\\n'+R.length+'\\n'+R.join('\\n')+'\\n'+H.slice(0,%d)})()";

static void audit_reseau(onglet *o);
static void audit_sonde(onglet *o);
static void audit_scripts(onglet *o);
static void audit_noter(void);

void source_liberer(void)
{
    free(SRC.texte);
    free(SRC.lignes);
    free(SRC.etat);
    memset(&SRC, 0, sizeof SRC);
}

/* lignes d'affichage : coupées aux retours à la ligne et tous les
 * LARGEUR_LIGNE octets (sans couper un caractère UTF-8). On garde l'état
 * de la coloration au début de chaque ligne (texte, balise, commentaire). */
static void decouper(void)
{
    size_t cap = 1024;
    SRC.lignes = malloc(cap * sizeof *SRC.lignes);
    SRC.etat = malloc(cap);
    if (!SRC.lignes || !SRC.etat) return;
    int etat = 0;                                  /* 0 texte, 1 balise, 2 commentaire */
    size_t i = 0, n = SRC.n;
    while (i < n) {
        if ((size_t)SRC.nb == cap) {
            cap *= 2;
            int *nl = realloc(SRC.lignes, cap * sizeof *nl);
            unsigned char *ne = realloc(SRC.etat, cap);
            if (nl) SRC.lignes = nl;
            if (ne) SRC.etat = ne;
            if (!nl || !ne) return;
        }
        SRC.lignes[SRC.nb] = (int)i;
        SRC.etat[SRC.nb] = (unsigned char)etat;
        SRC.nb++;
        size_t fin = i;
        while (fin < n && SRC.texte[fin] != '\n' && fin - i < LARGEUR_LIGNE) fin++;
        if (fin < n && SRC.texte[fin] != '\n')
            while (fin > i && ((unsigned char)SRC.texte[fin] & 0xC0) == 0x80) fin--;
        for (size_t k = i; k < fin; k++) {       /* état pour la ligne suivante */
            char c = SRC.texte[k];
            if (etat == 2) { if (c == '>' && k >= 2 && SRC.texte[k - 1] == '-' && SRC.texte[k - 2] == '-') etat = 0; }
            else if (etat == 1) { if (c == '>') etat = 0; }
            else if (c == '<') etat = (k + 3 < n && !strncmp(SRC.texte + k, "<!--", 4)) ? 2 : 1;
        }
        i = fin < n && SRC.texte[fin] == '\n' ? fin + 1 : fin;
    }
}

void source_ouvrir(int audit)
{
    onglet *o = ACTIF;
    if (!nb_onglets) return;
    if (o->interne == INT_SOURCE) { SRC.vue = audit; sale = 1; return; }
    if (o->interne || !o->session[0]) { dire("Ouvre d'abord un site"); return; }
    if (o->erreur) { dire("Rien à analyser : la page n'a pas chargé"); return; }
    char js[9000], ej[12000], p[12200];
    snprintf(js, sizeof js, JS_AUDIT, SOURCE_MAX);
    json_echapper(ej, sizeof ej, js);
    snprintf(p, sizeof p, "{\"expression\":%s,\"returnByValue\":true}", ej);
    dire("Lecture du code de la page…");
    char *r = nav_demander(o->session, "Runtime.evaluate", p, 8000);
    char *v = r ? json_chaine_alloc(r, "result.result.value", NULL) : NULL;
    free(r);
    if (!v) { dire("Code source illisible (JavaScript coupé sur ce site ?)"); return; }
    source_liberer();
    char *l = v, *nl;
    /* adresse */
    if ((nl = strchr(l, '\n'))) { *nl = 0; chaine_copier(SRC.url, l, sizeof SRC.url); l = nl + 1; }
    if ((nl = strchr(l, '\n'))) { *nl = 0; SRC.total = atol(l); l = nl + 1; }
    int nc = 0;
    if ((nl = strchr(l, '\n'))) { *nl = 0; nc = atoi(l); l = nl + 1; }
    for (int i = 0; i < nc && (nl = strchr(l, '\n')); i++) {
        *nl = 0;
        if (SRC.nb_constats < CONSTATS_MAX && l[0] >= '0' && l[0] <= '2' && l[1] == '\t') {
            constat *c = &SRC.constats[SRC.nb_constats++];
            c->g = l[0] - '0';
            chaine_copier(c->t, l + 2, sizeof c->t);
            if (c->g > SRC.pire) SRC.pire = c->g;
        }
        l = nl + 1;
    }
    SRC.n = strlen(l);
    SRC.texte = malloc(SRC.n + 1);
    if (SRC.texte) {
        /* tabulations en espaces, caractères de contrôle effacés */
        for (size_t i = 0; i <= SRC.n; i++) {
            char c = l[i];
            SRC.texte[i] = c == '\t' || (c > 0 && c < 32 && c != '\n') ? ' ' : c;
        }
        decouper();
    }
    free(v);
    /* 2.1 : ce que le Navigateur a vu lui-même, puis l'historique */
    dire("Analyse des scripts de la page…");
    audit_reseau(o);
    audit_sonde(o);
    audit_scripts(o);
    if (!o->prive) audit_noter();
    SRC.vue = audit;
    int i = onglet_interne(INT_SOURCE, 1);
    if (i >= 0) {
        char h[256] = "";
        hote_de(SRC.url, h, sizeof h);
        snprintf(onglets[i].titre, sizeof onglets[i].titre, "Source : %s", h[0] ? h : SRC.url);
        onglets[i].defile = 0;
        if (fen && i == actif) pf_titre(fen, onglets[i].titre);
    }
    journal("navigateur", "code source lu : %s (%ld octets, %d constats)", SRC.url, SRC.total, SRC.nb_constats);
    sale = 1;
}

/* ======================= 2.1 : audit renforcé ========================== */
/* En plus du script ci-dessus (qui lit la page), le Navigateur ajoute ce
 * qu'il a vu lui-même : le réseau (pisteurs connus et probables, contenu
 * mixte, redirections, websockets), le certificat, la sonde posée dans
 * chaque page (empreinte, WebAssembly, fuites de mot de passe) et une
 * lecture en C de chaque script téléchargé : code dissimulé, mineurs,
 * évasion, scripts suspects. Rien n'est envoyé nulle part. */
#include <stdarg.h>
#include <time.h>

audit_passe audits[AUDITS_MAX];
int nb_audits;

static double horloge(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000.0 + t.tv_nsec / 1e6;
}

static void constat_ajouter(int g, const char *fmt, ...)
{
    if (SRC.nb_constats >= CONSTATS_MAX) return;
    constat *c = &SRC.constats[SRC.nb_constats++];
    c->g = g;
    va_list a;
    va_start(a, fmt);
    vsnprintf(c->t, sizeof c->t, fmt, a);
    va_end(a);
    if (g > SRC.pire) SRC.pire = g;
}

static int compter(const char *t, const char *m)
{
    int n = 0;
    size_t lm = strlen(m);
    for (const char *p = strstr(t, m); p && n < 100000; p = strstr(p + lm, m)) n++;
    return n;
}

static int un_de(const char *t, const char *const *mots, int insensible)
{
    for (int i = 0; mots[i]; i++) if (insensible ? strcasestr(t, mots[i]) != NULL : strstr(t, mots[i]) != NULL) return 1;
    return 0;
}

/* identifiants « _0x1a2b » des obfuscateurs */
static int ids_hex(const char *t)
{
    int n = 0;
    for (const char *p = strstr(t, "_0x"); p && n < 100000; p = strstr(p + 3, "_0x")) {
        int k = 0;
        while (isxdigit((unsigned char)p[3 + k])) k++;
        if (k >= 4) n++;
    }
    return n;
}

/* ce qui ne va pas dans un script : bits SC_ */
enum { SC_DISSIMULE = 1, SC_MINEUR = 2, SC_EVASION = 4, SC_ANTIDEBUG = 8, SC_COOKIES = 16, SC_CLAVIER = 32,
       SC_PRESSE = 64, SC_DROPPER = 128, SC_CADRE = 256, SC_WEBRTC = 512, NB_SC = 10 };

static int analyser_script(const char *t)
{
    static const char *const PACKERS[] = { "eval(function(p,a,c,k,e,", "[][(![]+[])", "(!![]+[])[+!+[]]", "eval(atob(",
        "eval(unescape(", "Function(atob(", "eval(decodeURIComponent(escape(", "eval(String.fromCharCode(", NULL };
    static const char *const MINEURS[] = { "coinhive", "coin-hive", "cryptonight", "cryptoloot", "crypto-loot", "coinimp",
        "webminer", "deepminer", "minero.cc", "webmine.", "monerominer", "jsecoin", "stratum+tcp", "stratum+ssl",
        "throttleminer", "coinhive.anonymous", "webmr.", "cryptonoter", "minr.pw", NULL };
    static const char *const EVASION[] = { "HeadlessChrome", "navigator.webdriver", "callPhantom", "_phantom", "__nightmare",
        "domAutomation", "$cdc_", "__webdriver_evaluate", "__selenium", "__fxdriver", NULL };
    int r = 0;
    int hex = ids_hex(t), xesc = compter(t, "\\x");
    if (hex >= 25 || un_de(t, PACKERS, 0) || (xesc >= 400 && strstr(t, "eval")) ||
        (compter(t, "String.fromCharCode(") >= 40 && strstr(t, "eval")))
        r |= SC_DISSIMULE;
    if (un_de(t, MINEURS, 1)) r |= SC_MINEUR;
    if (un_de(t, EVASION, 0)) r |= SC_EVASION;
    if (compter(t, "debugger") >= 3 || (strstr(t, "debugger") && strstr(t, "setInterval") && strstr(t, "constructor(\"debugger")) ||
        (strstr(t, "devtools") && (strstr(t, "outerWidth") || strstr(t, "outerHeight"))))
        r |= SC_ANTIDEBUG;
    if ((strstr(t, "encodeURIComponent(document.cookie)") || strstr(t, "escape(document.cookie)") || strstr(t, "+document.cookie")) &&
        (strstr(t, "new Image") || strstr(t, "sendBeacon") || strstr(t, ".src=")))
        r |= SC_COOKIES;
    if ((strstr(t, "\"keypress\"") || strstr(t, "'keypress'") || strstr(t, "\"keydown\"") || strstr(t, "'keydown'") || strstr(t, "onkeypress")) &&
        (strstr(t, "sendBeacon") || strstr(t, "new Image().src") || strstr(t, "new Image;")) &&
        (strstr(t, "String.fromCharCode(e.") || strstr(t, ".key+") || strstr(t, "+=e.key") || strstr(t, "+= e.key")))
        r |= SC_CLAVIER;
    if ((strstr(t, "\"copy\"") || strstr(t, "'copy'")) && strstr(t, "clipboardData.setData")) r |= SC_PRESSE;
    if (strstr(t, "document.write(unescape(") || strstr(t, "document.write(atob(") ||
        (strstr(t, "ActiveXObject") && strstr(t, "WScript"))) r |= SC_DROPPER;
    if ((strstr(t, "createElement(\"iframe\")") || strstr(t, "createElement('iframe')")) &&
        (strstr(t, "width=\"0\"") || strstr(t, "width=0") || strstr(t, "height=\"0\"") || strstr(t, "visibility:hidden") ||
         strstr(t, "display:none") || strstr(t, ".width=0") || strstr(t, ".width=\"0\"")))
        r |= SC_CADRE;
    if (strstr(t, "RTCPeerConnection") && strstr(t, "onicecandidate")) r |= SC_WEBRTC;
    return r;
}

static const char *nom_script(const char *url, char *d, size_t n)
{
    const char *q = url + strcspn(url, "?#");
    const char *b = url;
    for (const char *p = url; p < q; p++) if (*p == '/') b = p + 1;
    if (b >= q) b = url;
    char h[128] = "";
    hote_de(url, h, sizeof h);
    snprintf(d, n, "%.*s%s%s", (int)(q - b > 40 ? 40 : q - b), b, h[0] ? " (" : "", h[0] ? h : "");
    if (h[0]) { size_t k = strlen(d); if (k + 1 < n) { d[k] = ')'; d[k + 1] = 0; } }
    return d;
}

static void audit_scripts(onglet *o)
{
    char vus[NB_SC][4][90];
    int nb[NB_SC] = { 0 }, total = 0, lus = 0;
    double debut = horloge();
    /* le code écrit dans la page (scripts « inline ») */
    if (SRC.texte) {
        int r = analyser_script(SRC.texte);
        for (int b = 0; b < NB_SC; b++) if ((r & (1 << b)) && nb[b] < 4) chaine_copier(vus[b][nb[b]++], "(dans la page)", 90);
        /* le constat plus vague du script de la page devient inutile */
        if (r & SC_DISSIMULE)
            for (int i = 0; i < SRC.nb_constats; i++)
                if (!strncmp(SRC.constats[i].t, "Code peut-être dissimulé", 25)) {
                    memmove(&SRC.constats[i], &SRC.constats[i + 1], sizeof SRC.constats[0] * (size_t)(SRC.nb_constats - i - 1));
                    SRC.nb_constats--;
                    break;
                }
    }
    /* les fichiers .js téléchargés par la page principale */
    char *r = nav_demander(o->session, "Page.getResourceTree", "{}", 3000);
    char frame[64] = "";
    if (r) json_chaine(r, "result.frameTree.frame.id", frame, sizeof frame);
    for (int i = 0; r && frame[0] && i < 400 && lus < 40; i++) {
        const char *e = json_element(r, "result.frameTree.resources", i);
        if (!e) break;
        char type[24] = "", url[1024] = "";
        json_chaine(e, "type", type, sizeof type);
        json_chaine(e, "url", url, sizeof url);
        if (strcmp(type, "Script") || strncmp(url, "http", 4)) continue;
        total++;
        if (horloge() - debut > 9000) continue;          /* pas plus de 9 s en tout */
        char eu[1100], eid[100], p[1300];
        json_echapper(eu, sizeof eu, url);
        json_echapper(eid, sizeof eid, frame);
        snprintf(p, sizeof p, "{\"frameId\":%s,\"url\":%s}", eid, eu);
        char *c = nav_demander(o->session, "Page.getResourceContent", p, 2500);
        int b64 = 0;
        if (c) json_booleen(c, "result.base64Encoded", &b64);
        char *code = c && !b64 ? json_chaine_alloc(c, "result.content", NULL) : NULL;
        free(c);
        if (!code) continue;
        lus++;
        int f = analyser_script(code);
        free(code);
        char n[90];
        for (int b = 0; b < NB_SC; b++) if ((f & (1 << b)) && nb[b] < 4) chaine_copier(vus[b][nb[b]++], nom_script(url, n, sizeof n), 90);
    }
    free(r);
    char liste[NB_SC][400];
    for (int b = 0; b < NB_SC; b++) {
        liste[b][0] = 0;
        for (int k = 0; k < nb[b]; k++) { size_t l = strlen(liste[b]); snprintf(liste[b] + l, sizeof liste[b] - l, "%s%s", k ? ", " : "", vus[b][k]); }
    }
    constat_ajouter(0, "Scripts lus et analysés par le Navigateur : %d sur %d fichier(s), plus le code écrit dans la page.", lus, total);
    if (nb[7]) constat_ajouter(2, "Script typique des pages piégées (code décodé puis écrit dans la page, ou lancement de programmes) : %s.", liste[7]);
    if (nb[1]) constat_ajouter(2, "Mineur de cryptomonnaie dans : %s. Ton processeur travaille pour quelqu'un d'autre.", liste[1]);
    if (nb[0]) constat_ajouter(1, "Code volontairement illisible (obfuscateur, « packer », eval de texte décodé) dans : %s. "
                               "Parfois pour protéger un produit, parfois pour cacher un piège.", liste[0]);
    if (nb[4]) constat_ajouter(1, "Script qui semble envoyer tes cookies ailleurs : %s.", liste[4]);
    if (nb[5]) constat_ajouter(1, "Script qui écoute le clavier et envoie ce qu'il capte (enregistreur de frappe possible) : %s.", liste[5]);
    if (nb[6]) constat_ajouter(1, "Script qui modifie ce que tu copies (piège classique : adresse de paiement remplacée) : %s.", liste[6]);
    if (nb[8]) constat_ajouter(1, "Script qui crée des cadres invisibles : %s.", liste[8]);
    if (nb[3]) constat_ajouter(1, "Piège anti-observation (debugger en boucle ou détection des outils de développement) : %s.", liste[3]);
    if (nb[2]) constat_ajouter(0, "La page cherche à savoir si un robot ou un outil l'observe (évasion) : %s. Courant dans les "
                               "protections anti-robot, mais aussi chez les pages piégées.", liste[2]);
    if (nb[9]) constat_ajouter(0, "Script qui interroge WebRTC (peut révéler ton adresse IP ; hors VPN, le Navigateur ne le laisse pas sortir) : %s.", liste[9]);
}

static void audit_sonde(onglet *o)
{
    char *r = nav_demander(o->session, "Runtime.evaluate",
                           "{\"expression\":\"JSON.stringify(window.__puppySonde||null)\",\"returnByValue\":true}", 2000);
    char *v = r ? json_chaine_alloc(r, "result.result.value", NULL) : NULL;
    free(r);
    if (!v || !strcmp(v, "null")) {
        if (site_js_permis(o->hote)) constat_ajouter(0, "La sonde du Navigateur n'a pas vu cette page (ouverte avant elle) : recharge puis refais l'audit.");
        free(v);
        return;
    }
    double canvas = 0, webgl = 0, audio = 0, polices = 0, plugins = 0, ecran = 0, batterie = 0, materiel = 0, wasm = 0, workers = 0, clavier = 0;
    json_nombre(v, "canvas", &canvas); json_nombre(v, "webgl", &webgl); json_nombre(v, "audio", &audio);
    json_nombre(v, "polices", &polices); json_nombre(v, "plugins", &plugins); json_nombre(v, "ecran", &ecran);
    json_nombre(v, "batterie", &batterie); json_nombre(v, "materiel", &materiel); json_nombre(v, "wasm", &wasm);
    json_nombre(v, "workers", &workers); json_nombre(v, "clavier", &clavier);
    char tech[300] = "";
    int score = 0;
#define TECH(cond, nom) if (cond) { size_t l = strlen(tech); snprintf(tech + l, sizeof tech - l, "%s%s", score ? ", " : "", nom); score++; }
    TECH(canvas > 0, "dessin invisible (canvas)");
    TECH(webgl > 0, "carte graphique (WebGL)");
    TECH(audio > 0, "son (AudioContext)");
    TECH(polices >= 150, "liste des polices");
    TECH(batterie > 0, "batterie");
    TECH(plugins > 0 && materiel > 0, "modules et matériel");
#undef TECH
    if (score >= 3) constat_ajouter(1, "Empreinte de ta machine probable (fingerprinting) : %s. Le site peut te reconnaître sans cookies.", tech);
    else if (score) constat_ajouter(0, "La page lit quelques caractéristiques de ta machine (%s), comme beaucoup de sites.", tech);
    if (wasm > 0 && workers >= 2) constat_ajouter(1, "Calcul lourd en arrière-plan : WebAssembly et %d workers. Normal pour un jeu ou un éditeur, "
                                                 "signe classique d'un mineur sinon.", (int)workers);
    else if (wasm > 0) constat_ajouter(0, "La page utilise WebAssembly (code rapide).");
    if (clavier >= 25) constat_ajouter(0, "%d écouteurs de clavier posés par la page.", (int)clavier);
    for (int i = 0; i < 10; i++) {
        const char *f = json_element(v, "fuite", i);
        if (!f) break;
        char h[200] = "";
        double c = 1;
        json_chaine(f, "h", h, sizeof h);
        json_nombre(f, "c", &c);
        if (!c) constat_ajouter(2, "Ton mot de passe tapé ici est parti SANS chiffrement vers %s.", h);
        else if (h[0] && o->hote[0] && !meme_site(h, o->hote)) constat_ajouter(2, "Ton mot de passe tapé ici a été envoyé à un autre site : %s.", h);
        else constat_ajouter(0, "Mot de passe envoyé chiffré au site lui-même (%s) : normal pour une connexion.", h);
    }
    int nws = 0;
    char ws[400] = "";
    int autre = 0;
    for (int i = 0; i < 20; i++) {
        const char *w = json_element(v, "ws", i);
        if (!w) break;
        char u[200] = "", h[128] = "";
        if (*w == '"') { const char *e = strchr(w + 1, '"'); if (e) snprintf(u, sizeof u, "%.*s", (int)(e - w - 1), w + 1); }
        hote_de(u, h, sizeof h);
        if (h[0] && !meme_site(h, o->hote)) autre = 1;
        if (nws < 4) { size_t l = strlen(ws); snprintf(ws + l, sizeof ws - l, "%s%s", nws ? ", " : "", h[0] ? h : u); }
        nws++;
    }
    for (int i = 0; !nws && i < o->nb_sockets; i++) {      /* vus par le réseau si la page a échappé à la sonde */
        char h[128] = "";
        hote_de(o->sockets[i], h, sizeof h);
        if (h[0] && !meme_site(h, o->hote)) autre = 1;
        size_t l = strlen(ws);
        snprintf(ws + l, sizeof ws - l, "%s%s", i ? ", " : "", h);
    }
    if (!nws) nws = o->nb_sockets;
    if (nws) constat_ajouter(autre ? 1 : 0, "%d connexion(s) permanente(s) (WebSocket) : %s%s.", nws, ws,
                             autre ? " (vers un autre site : peut envoyer en continu ce que tu fais)" : "");
    free(v);
}

static void audit_reseau(onglet *o)
{
    if (o->nb_pisteurs) {
        char l[600] = "";
        for (int i = 0; i < o->nb_pisteurs; i++) { size_t k = strlen(l); snprintf(l + k, sizeof l - k, "%s%s", i ? ", " : "", o->pisteurs[i]); }
        constat_ajouter(o->pisteurs_inconnus ? 1 : 0, "Pisteurs vus par le Navigateur : %d connu(s), bloqué(s) par le moteur ; %d probable(s), "
                        "absents des listes mais qui en ont l'allure : %s.", o->pisteurs_connus, o->pisteurs_inconnus, l);
    }
    if (o->mixte && !strncmp(o->url, "https://", 8))
        constat_ajouter(1, "%d requête(s) non chiffrée(s) depuis cette page chiffrée (contenu mixte).", o->mixte);
    if (o->nb_redir >= 2) {
        char l[700] = "";
        int sites = 1;
        for (int i = 0; i < o->nb_redir; i++) {
            size_t k = strlen(l);
            snprintf(l + k, sizeof l - k, "%s%s", i ? " → " : "", o->chaine_redir[i]);
            if (i && !meme_site(o->chaine_redir[i], o->chaine_redir[i - 1])) sites++;
        }
        constat_ajouter(sites >= 3 || o->redirections >= 4 ? 1 : 0, "Redirections avant d'arriver ici (%d) : %s.", o->redirections, l);
    }
    if (!strncmp(o->url, "https://", 8) && o->cert.present) {
        double reste = (o->cert.fin - (double)time(NULL)) / 86400.0;
        int vieux = !strcmp(o->cert.protocole, "TLS 1") || !strcmp(o->cert.protocole, "TLS 1.0") || !strcmp(o->cert.protocole, "TLS 1.1");
        if (vieux) constat_ajouter(1, "Chiffrement ancien (%s), plus considéré comme sûr.", o->cert.protocole);
        if (reste < 14) constat_ajouter(1, "Le certificat du site expire dans %d jour(s).", reste < 0 ? 0 : (int)reste);
        if (!strcmp(o->cert.ct, "not-compliant")) constat_ajouter(1, "Le certificat n'est pas publié dans les journaux publics (transparence).");
        if (o->cert.sujet[0] && !strcmp(o->cert.sujet, o->cert.emetteur)) constat_ajouter(1, "Certificat signé par le site lui-même.");
        constat_ajouter(0, "Certificat : %s, délivré par %s, %s (%s).", o->cert.sujet, o->cert.emetteur, o->cert.protocole, o->cert.chiffre);
    }
    if (o->php) constat_ajouter(0, "La page est faite en PHP (vu dans l'adresse ou les en-têtes du serveur).");
}

/* ----------------------------- historique ------------------------------- */
void audits_charger(void)
{
    nb_audits = 0;
    char *t = lire_fichier(chemin_donnees("navigateur-audits.txt"), NULL);
    if (!t) return;
    char *sv = NULL;
    for (char *l = strtok_r(t, "\n", &sv); l; l = strtok_r(NULL, "\n", &sv)) {
        if (l[0] == '#') continue;
        audit_passe a;
        memset(&a, 0, sizeof a);
        char *c[6];
        int n = 0;
        for (char *p = l; n < 6; ) { c[n++] = p; char *b = strchr(p, '|'); if (!b) break; *b = 0; p = b + 1; }
        if (n < 6) continue;
        a.t = atof(c[0]); a.pire = atoi(c[1]); a.dangers = atoi(c[2]); a.attentions = atoi(c[3]);
        chaine_copier(a.url, c[4], sizeof a.url);
        chaine_copier(a.resume, c[5], sizeof a.resume);
        if (nb_audits == AUDITS_MAX) { memmove(audits, audits + 1, sizeof audits[0] * (AUDITS_MAX - 1)); nb_audits--; }
        audits[nb_audits++] = a;
    }
    free(t);
}

static void audits_enregistrer(void)
{
    size_t cap = (size_t)AUDITS_MAX * 700 + 128, o = 0;
    char *b = malloc(cap);
    if (!b) return;
    o += (size_t)snprintf(b, cap, "# heure(ms)|pire|dangers|attentions|adresse|résumé\n");
    for (int i = 0; i < nb_audits; i++)
        o += (size_t)snprintf(b + o, cap - o, "%.0f|%d|%d|%d|%s|%s\n", audits[i].t, audits[i].pire, audits[i].dangers,
                              audits[i].attentions, audits[i].url, audits[i].resume);
    ecrire_fichier_atomique(chemin_donnees("navigateur-audits.txt"), b, o < cap ? o : cap - 1);
    free(b);
}

void audits_effacer(void)
{
    nb_audits = 0;
    unlink(chemin_donnees("navigateur-audits.txt"));
    sale = 1;
}

static void sans_separateurs(char *s) { for (; *s; s++) if (*s == '|' || *s == '\n' || *s == '\r') *s = ' '; }

static void audit_noter(void)
{
    audit_passe a;
    memset(&a, 0, sizeof a);
    a.t = (double)time(NULL) * 1000.0;
    a.pire = SRC.pire;
    const char *pire_t = NULL;
    for (int g = 2; g >= 1 && !pire_t; g--)
        for (int i = 0; i < SRC.nb_constats; i++) if (SRC.constats[i].g == g) { pire_t = SRC.constats[i].t; break; }
    for (int i = 0; i < SRC.nb_constats; i++) { if (SRC.constats[i].g == 2) a.dangers++; else if (SRC.constats[i].g == 1) a.attentions++; }
    chaine_copier(a.url, SRC.url, sizeof a.url);
    chaine_copier(a.resume, pire_t ? pire_t : "Rien d'inquiétant repéré.", sizeof a.resume);
    sans_separateurs(a.url);
    sans_separateurs(a.resume);
    if (nb_audits == AUDITS_MAX) { memmove(audits, audits + 1, sizeof audits[0] * (AUDITS_MAX - 1)); nb_audits--; }
    audits[nb_audits++] = a;
    audits_enregistrer();
}

void audit_rapide(void) { source_ouvrir(1); }
