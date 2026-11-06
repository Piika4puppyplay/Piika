/* Test ciblé du mur anti-bombe de paquet_extraire (libpuppy/paquet.c).
 * On fabrique des .pup bruts à la main et on vérifie:
 *   1. un paquet légitime s'extrait (0)
 *   2. un en-tête qui déclare 10 millions d'entrées est refusé
 *   3. une entrée fichier > 256 Mio est refusée
 *   4. un chemin trop profond (>32) est refusé
 *   5. un chemin trop long (>255) est refusé
 * Stubs: creer_dossiers, securite_noter (on ne teste QUE les bornes). */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>

/* --- stubs des symboles PuppyOS dont paquet_extraire a besoin --- */
int creer_dossiers(const char *chemin, int mode) {
    char tmp[2048]; snprintf(tmp, sizeof tmp, "%s", chemin);
    for (char *p = tmp + 1; *p; p++) if (*p == '/') { *p = 0; mkdir(tmp, mode); *p = '/'; }
    mkdir(tmp, mode); return 0;
}
void securite_noter(const char *c, const char *r, int g, const char *s, const char *m) {
    (void)c;(void)r;(void)g;(void)s;(void)m;
}
/* stubs des symboles référencés par des fonctions non testées (verifier/creer) */
void sha256_debut(void*c){(void)c;}
void sha256_ajouter(void*c,const void*p,size_t n){(void)c;(void)p;(void)n;}
void sha256_fin(void*c,void*o){(void)c;(void)o;}
void sha256_hex(const void*h,char*o){(void)h;(void)o;}
uint64_t paquet_taille_utile(int fd,uint64_t t){(void)fd;return t;}
int comparer_constant(const void*a,const void*b,size_t n){return memcmp(a,b,n);}
void chaine_copier(char*d,const char*s,size_t n){snprintf(d,n,"%s",s);}

/* déclaration de la fonction testée */
int paquet_extraire(const char *chemin, const char *dest, char *err, size_t nerr,
                    void (*prog)(int,void*), void *d);

/* --- fabrique de .pup brut --- */
static uint8_t BUF[1 << 20];
static size_t L;
static void p8(const char *s){memcpy(BUF+L,s,8);L+=8;}
static void p32(uint32_t v){for(int i=0;i<4;i++)BUF[L++]=v>>(8*i);}
static void pu16(uint16_t v){BUF[L++]=v;BUF[L++]=v>>8;}
static void p64(uint64_t v){for(int i=0;i<8;i++)BUF[L++]=v>>(8*i);}
static void praw(const void*p,size_t n){memcpy(BUF+L,p,n);L+=n;}

/* en-tête PUPPYPKG: magic8 + format32=1 + version[32] + nb32 */
static void entete(uint32_t nb){L=0;p8("PUPPYPKG");p32(1);char v[32]={0};memcpy(v,"0.1",3);praw(v,32);p32(nb);}
/* entrée fichier: type=1, lon16, chemin, mode32, taille64, data[taille] */
static void entree_fichier(const char*rel,uint64_t taille,int ecrire_data){
    BUF[L++]=1;uint16_t lc=strlen(rel);pu16(lc);praw(rel,lc);p32(0644);p64(taille);
    if(ecrire_data)for(uint64_t i=0;i<taille;i++)BUF[L++]=0x41;
}
static void queue(void){p8("PUPFIN!!");char h[32]={0};praw(h,32);}

static const char* ecrire_pup(void){
    static char path[256];
    snprintf(path,sizeof path,"/tmp/claude-0/pupdate-test/essai-%d.pup",rand());
    int fd=open(path,O_WRONLY|O_CREAT|O_TRUNC,0644);
    write(fd,BUF,L);close(fd);return path;
}

static int extraire(const char*msg,int attendu){
    const char*pup=ecrire_pup();
    char dest[256];snprintf(dest,sizeof dest,"/tmp/claude-0/pupdate-test/out-%d",rand());
    char err[300]={0};
    int r=paquet_extraire(pup,dest,err,sizeof err,NULL,NULL);
    int ok=(r==0)==(attendu==0);
    printf("  [%s] %-42s r=%d %s\n", ok?"OK":"ÉCHEC", msg, r, r?err:"");
    return ok;
}

int main(void){
    int tout=1;
    /* 1. légitime : 2 fichiers au premier niveau, chemins normaux */
    entete(2);
    entree_fichier("app",5,1);
    entree_fichier("data.txt",10,1);
    queue();
    tout&=extraire("paquet légitime -> accepté", 0);

    /* 2. en-tête ment : 10 000 000 entrées */
    entete(10000000u);
    /* (pas besoin de corps : le refus doit tomber avant) */
    tout&=extraire("nb_entrees=10M -> refusé", -1);

    /* 3. une entrée > 256 Mio (déclarée, pas de data écrite -> troncature,
     *    mais le refus de borne doit tomber AVANT la lecture des données) */
    entete(1);
    entree_fichier("gros.bin",(257ull*1024*1024),0);
    queue();
    tout&=extraire("fichier 257 Mio -> refusé", -1);

    /* 4. chemin trop profond (>32 segments) */
    {
        char prof[512]="a"; for(int i=0;i<40;i++)strcat(prof,"/a");
        entete(1); entree_fichier(prof,1,1); queue();
        tout&=extraire("chemin profondeur 41 -> refusé", -1);
    }

    /* 5. chemin trop long (>255 octets) */
    {
        char lng[600]; memset(lng,'x',300); lng[300]=0;
        entete(1); entree_fichier(lng,1,1); queue();
        tout&=extraire("chemin 300 octets -> refusé", -1);
    }

    printf("\n%s\n", tout?"=== TOUS LES TESTS ANTI-BOMBE PASSENT ===":"=== ÉCHEC ===");
    return tout?0:1;
}
