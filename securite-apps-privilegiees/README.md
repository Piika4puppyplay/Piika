# Sécurité des applications privilégiées — installateur confiné, coffre, console de secours

> **PuppyOS : même la confiance doit passer les tests.**
>
> « PuppyOS ne fait pas confiance à son créateur. La console ne fait pas
> confiance à PuppyOS. Et PuppyOS ne fait pas confiance à sa console. »

Ce dossier rassemble un chantier de sécurité (cible 0.20) autour des **deux
seules applications à droits élevés** de PuppyOS — `paquets-autorite`
(installation des `.puppy`) et `puppy-maj` (mise à jour de l'OS) — et de
l'installateur de paquets `.puppy`.

La règle d'or : la **décision** vit en **Rust testé** (pur, sans privilège) ;
le **C** ne fait que les appels système privilégiés obligatoires (seccomp,
openat2, abandon de privilèges, TPM, memfd). C'est le même modèle que le Media
Center Rust : un cœur testé relié par un pont C ABI.

> ⚠️ **État : squelettes compilés et testés, NON câblés au démarrage.** Tout
> compile et se lie proprement, et le cœur Rust passe **176/176 tests**. Mais
> le TPM, le seccomp, `openat2`, `memfd`+`fexecve` et l'install réel ne sont
> **validables qu'en VM** sur la machine de Piika (QEMU/VirtualBox + swtpm).
> Rien n'est branché au boot ni à `puppy-autorite` tant que la VM n'a pas
> validé. Aucune clé réelle, aucun paquet publié.

---

## 1. Installateur `.puppy` confiné — `paquets-autorite`

Poser un `.puppy` n'a besoin que d'écrire sous les dossiers d'applications. On
sort donc ce pouvoir du gros gardien `puppy-autorite` et on le confie à un
binaire **le plus muselé possible** :

- écrit **uniquement** sous `/system/{programmes,themes,icones}` (+ miroir ISO).
  La « prison de chemin » est décidée par le cœur Rust `jail.rs` (testé) ;
- `PR_SET_NO_NEW_PRIVS`, limites de ressources, aucun groupe, puis **seccomp
  liste blanche** (tout le reste est **tué** : ni `exec`, ni réseau, ni
  `ptrace`, ni montage, ni `bpf`, ni `clone`) ;
- **n'exécute jamais** de code venu du paquet ;
- **re-vérifie la signature** lui-même ; refuse un paquet **système non signé
  ou falsifié**, même pour une simple mise à jour d'application. « Système »
  vient de la **confiance (catalogue)**, jamais auto-déclaré ;
- ouvre la racine en `O_NOFOLLOW` + `openat2 RESOLVE_BENEATH` (anti-TOCTOU) ;
- pose **atomique A/B** : `<id>.part` → `<id>`, l'ancienne devient
  `<id>.precedent`.

**Anti-bombe par entrée** dans l'extracteur générique `libpuppy/paquet.c` (voir
le patch) : chaque fichier d'un `.puppy` est borné un par un (chemin,
profondeur ≤ 32, taille ≤ 256 Mio) + cumul (≤ 512 Mio, ≤ 20 000 fichiers, ratio
de décompression surveillé). C'est un **second mur en C** qui recopie à
l'identique les bornes du cœur Rust (`jail.rs`) : deux remparts indépendants.
Prouvé par `tests/test_bombe.c` (5/5 : paquet légitime accepté, bombes refusées
**avant la moindre écriture**).

## 2. Parseurs de mise à jour en Rust — `format.rs`

Lecture des formats **non fiables** `.pupupdate` (en-tête chiffré) et `.pup`
(conteneur) réécrite en Rust : bornes partout, aucun débordement d'entier
possible (`checked_add`), tests de troncature et pseudo-aléatoires qui ne
paniquent jamais.

## 3. Chiffrement AU REPOS des deux apps — le coffre (`coffre.rs`)

`fs-verity` (déjà en place) garantit l'**intégrité** au repos ; le coffre ajoute
la **confidentialité** : on ne peut pas désassembler un binaire privilégié hors
ligne sans d'abord vaincre le TPM + le démarrage mesuré. Le coffre ne protège
**pas** le processus en cours.

- Format `.pupcoffre` : en-tête authentifié (magie + génération + taille +
  nonce) ‖ MAC ‖ chiffré. **XChaCha20-Poly1305** (Monocypher). La **génération**
  est en donnée associée → toute altération casse l'authentification.
- **Clé dérivée du secret machine déjà scellé au TPM** (`SCEAU_SECRET`), pas une
  nouvelle clé indépendante : on réutilise toute la machinerie validée
  (prédiction des PCR du noyau suivant = pas de verrouillage sur une MAJ noyau,
  clé de secours). `clé = HMAC-SHA256(secret, "puppy-coffre-v1" | app | génération)`.
  Clé **unique par app**, **renouvelée à chaque MAJ** (rotation = `coffre-apps`).
- **Anti-retour-arrière** : une génération inférieure au minimum stocké est refusée.
- `puppy-lanceur` déchiffre **en mémoire seule** (`memfd` + `fexecve`, jamais sur
  disque) et exécute.

**Politique en cas d'échec = REFUS PUR** (choix de Piika, renforcé sur conseil
de ChatGPT) : si le coffre ne s'ouvre pas (TPM mort, blob abîmé, retour
arrière…), le lanceur **ne lance rien**. Les deux outils de MAJ n'étant pas
critiques au démarrage, PuppyOS démarre sans eux. **Jamais de bascule
silencieuse en clair** : un TPM définitivement mort laisse les deux apps
**désactivées** jusqu'à une **restauration sécurisée explicite** décidée par le
propriétaire (re-sceller le TPM, ou choix authentifié assumé). Le propriétaire
n'est jamais enfermé dehors ; la sécurité ne baisse jamais en silence.

`coffre-apps` effectue la **rotation** (à chaque MAJ, puppy-maj l'appellera, à
brancher en VM) : génération incrémentée, clé neuve, nonce aléatoire,
scellement, pose atomique du `.pupcoffre` et du `.gen`.

## 4. Console de secours — `puprecovery`

> « Le petit chiot infirmier ne distribue pas les médicaments sans vérifier les
> ordonnances. » 💉

Quand les deux apps ne peuvent plus s'ouvrir, le lanceur refuse ; **cette
console les réinstalle** proprement à partir d'une **copie dorée signée**,
embarquée. Elle **ne manipule aucune clé** — pas de pouvoir de déchiffrage hors
TPM, donc **pas de surface d'attaque « à grand pouvoir »** à détourner (option
volontairement abandonnée car trop dangereuse).

**On ne fait pas confiance au créateur** (il peut se faire pirater) : une
signature valide est **nécessaire mais pas suffisante**. La console porte, en
dur, l'**empreinte SHA-256 épinglée** de chaque app ; un paquet même
parfaitement signé mais ≠ épingle est **refusé** (empreinte à zéro par défaut =
refus, tant que le vrai condensat n'est pas scellé au build). C'est l'antivirus
interne. + anti-retour + anti-bombe. **N'accepte aucun fichier entrant** : elle
ne lit que ses copies dorées (chemins **fixes**) et n'écrit que le dossier isolé
des deux apps. Même durcissement que l'installateur (seccomp, prison en dur de
deux entrées, aucun chemin calculé depuis un argument).

**Durcissement surface d'attaque (06/11).** L'empreinte épinglée est vérifiée
par un simple hachage **avant tout parsing structurel** du paquet : le parseur
de signature et l'extracteur (en C) ne reçoivent jamais des octets qui ne valent
pas déjà l'épingle connue-bonne — une copie dorée altérée meurt sur une
comparaison à temps constant, sans qu'un seul octet de structure ne soit
interprété (phase Rust testée `verdict_avant_parsing`). Le hachage lui-même est
borné (jamais de fichier démesuré). La décision/vérification reste en Rust sûr
(pas de débordement) ; le C ne garde que les appels système obligatoires — un
rewrite complet en Rust n'est donc pas nécessaire.

**Mode audit (lecture seule) :** `puprecovery verifier[-tout]` rejoue la même
vérification (signature + épingle) **sans rien réécrire**, pour revérifier à la
demande (ou au démarrage, une fois câblé) que les copies dorées restent
authentiques et intègres — on ne fait pas confiance à un paquet au seul motif
qu'il a déjà été installé.

---

## 5. Recovery embarqué dans l'ISO — `puprecovery-iso`

> « La console ne prescrit jamais à l'aveugle. Elle vérifie, elle diagnostique,
> puis elle soigne. »

Booté **depuis l'ISO** (sans démarrer l'OS installé), il détecte une
installation PuppyOS par l'inspecteur **lecture seule** (`inspection.c` : lit
tables et signatures, aucun montage), puis — c'est le cerveau Rust testé
`secours_iso.rs` qui décide — répare les deux apps privilégiées depuis une copie
dorée **signée + épinglée** embarquée dans l'ISO (même vérification que la
console : empreinte épinglée **avant tout parsing**).

Règles dures : **jamais de format automatique** ; installation chiffrée → le
**mot de passe** de déchiffrement est **obligatoire** avant de toucher quoi que
ce soit (aucune clé stockée, c'est la phrase de l'utilisateur qui ouvre le
volume → réparation **sans perte de données**) ; on ne touche jamais un disque
qui n'est pas visiblement PuppyOS.

**Filet de secours** (`secours_filet.rs`) : les couches s'empilent, de la plus
riche à la plus fiable — console dans l'OS → recovery de l'ISO → réinstallation
propre depuis l'ISO signée → (plancher) intervention manuelle annoncée. Quand
une couche échoue, on descend d'un cran ; on ne dégrade jamais la sécurité en
silence.

> **Squelette.** La détection (lecture seule) et la décision (Rust) tournent déjà
> — `puprecovery-iso detecter <disque>` fait un dry-run qui n'écrit rien.
> L'ouverture dm-crypt (mot de passe), le montage et la pose A/B touchent un vrai
> disque et une vraie puce : **à valider en VM**, non câblés dans l'ISO.

---

## 6. Montage sécurisé des lecteurs chiffrés — `montage_securise.rs`

> « Un bon mot de passe n'ouvre pas la porte. Il ouvre le sas. »

Socle **commun** (demande Piika 06/11) au déchiffrement multi-OS, à
l'explorateur et au mode live. Deux idées pures et testées ici ; le C fera
l'ouverture dm-crypt, l'analyse Honey-Dog et le montage réel.

- **Anti-brute-force SANS destruction.** On ne supprime **jamais** les données
  après X essais ratés (ce ne sont pas forcément nos disques — effacer serait
  une catastrophe). À la place, un **chrono évolutif** : chaque échec allonge le
  délai avant le prochain essai (`0, 1, 2, 4, 8…` secondes, plafonné à **1 h**,
  sans débordement). Le dictionnaire et le brute-force deviennent impraticables,
  rien n'est détruit, le propriétaire n'est jamais enfermé dehors.
- **Jamais de montage à l'aveugle.** Un bon mot de passe n'entraîne **pas** un
  montage immédiat : il ouvre un **sas Honey-Dog** qui impose une analyse.
  Refus de l'analyse → lecteur **non monté**, motif donné, et on dit la vérité :
  le code **était** bon (on ne ment pas). Analyse qui trouve du dangereux → non
  monté (motif). Analyse propre + acceptée → on monte.

Module **pur** : aucune E/S, aucun privilège, aucune horloge (le délai est
calculé, c'est l'appelant qui attend), jamais de panique. 7 tests.

> C'est la **fondation** du déchiffrement multi-OS, de l'explorateur Rust et du
> mode live à venir. L'ouverture dm-crypt réelle et le vrai moteur Honey-Dog
> sont du C privilégié : **à valider en VM**.

---

## 7. Déchiffrement multi-OS — `dechiffrement.rs`

> « On n'ouvre que ce qu'on sait vraiment ouvrir, et on n'abîme jamais l'OS d'à
> côté. »

Demande Piika (06/11) : ouvrir des volumes Windows / Mac / Linux + clés USB /
cartes / téléphone, **uniquement** avec le bon mot de passe, **sans** jamais
brute-forcer, casser ni forcer quoi que ce soit. Ce module est le **cerveau**
(pur, testé) qui, à partir du système de fichiers détecté en **lecture seule**
par `inspection.c`, décide ce qu'on peut honnêtement offrir :

| Format | Ouverture |
|---|---|
| Notre volume / LUKS | **par mot de passe** → sas `montage_securise`, lecture/écriture |
| FAT / exFAT (échange amovible) | en clair, lecture/écriture |
| NTFS / ext / APFS en clair, ISO | en clair, **lecture seule** (OS voisin jamais abîmé) |
| NTFS **BitLocker** | **clé de récupération Microsoft** obligatoire, zéro force |
| APFS **FileVault** | **clé de récupération Apple** obligatoire, zéro force |
| swap | rien à monter |
| chiffré d'un format inconnu | **non supporté** — on le dit franchement |

Trois principes : **honnêteté sur nos limites** (BitLocker/FileVault ne se
contournent pas — il faut la clé du fournisseur, et c'est tant mieux) ; **zéro
brute-force** (un volume à mot de passe passe par le chrono évolutif + Honey-Dog
du module 6 ; un volume verrouillé fournisseur n'est **jamais** attaqué, on
réclame la clé) ; **on n'abîme pas l'OS voisin** (un système de fichiers
étranger est offert en lecture seule par défaut). Module pur, 9 tests. Le C fait
l'ouverture dm-crypt réelle et le montage : **à valider en VM**.

---

## 8. Clavier ultra-protégé pendant la saisie des mots de passe — `clavier_securise.rs`

> « Une frappe de confiance, ou rien. »

Demande Piika (06/11) : pendant la saisie d'un mot de passe (session,
déverrouillage, et désormais les invites de déchiffrement de l'explorateur), le
clavier est protégé de la **falsification** et du **piratage**, **local** comme
**réseau**. Ce module décide (pur, testé) :

- **une seule source de confiance** : au démarrage de la saisie, on fige les
  claviers physiques déjà présents et approuvés ; pendant la saisie, on
  n'accepte **que** leurs frappes ;
- **tout le reste est rejeté**, avec motif : événement synthétique (injection
  logicielle — anti-piratage **local**), entrée réseau/distante (KVM, prise de
  contrôle — anti-piratage **réseau**), périphérique branché **à chaud** pendant
  la saisie (BadUSB, keylogger déguisé en clavier — anti-**falsification**) ;
- **rien ne fuit** : à l'ouverture, capture exclusive + écho coupé +
  journalisation coupée ; à la fermeture/annulation, le tampon du mot de passe
  est **effacé** (zéroïsation) et la capture rendue.

Module pur, 7 tests. Le C applique (capture `EVIOCGRAB`, `crypto_wipe`) : **à
valider en VM**.

## 9. Autorités de l'explorateur + vidage RAM — `explorateur.rs`

> « L'explorateur voit les fichiers, jamais les clés une seconde de trop. »

Demande Piika (06/11) : explorateur réécrit en **Rust**, séparé du système (app
`.puppy` signée, comme le Media Center Rust), thèmes + packs d'icônes futurs,
**autorités bridées au maximum**, **vidage RAM à la fermeture** pour que les
clés des volumes déchiffrés (disques, clés, cartes, téléphones) ne fuitent pas.
Ce module décide (pur, testé) :

- **autorités minimales** — liste blanche la plus courte possible : lire des
  fichiers, écrire sur un support **approuvé**, **demander** un montage (qui
  passe par le sas `montage_securise` ; l'explorateur ne monte jamais lui-même).
  **Interdits** : réseau, exécution de code, privilège, `ptrace`, montage direct ;
- **vidage RAM** — une clé déchiffrée ne survit jamais à sa fenêtre : elle est
  effacée au **démontage** du volume comme à la **fermeture** de l'explorateur,
  avec les tampons sensibles, et les volumes ouverts sont démontés proprement.

La sûreté mémoire (anti buffer-overflow) vient du langage : tout est borné, pas
d'`unsafe`. Module pur, 6 tests. L'UI de l'explorateur et le `.puppy` signé
restent à construire ; le C applique seccomp + `crypto_wipe` : **à valider en VM**.

## 10. Verdict des chiens de garde — `honeydog.rs`

> « Il ne te supprime rien sans ton avis. »

Les chiens (flair/antivirus, bac, etc.) sont le cœur de la sécurité. Quand l'un
trouve un fichier à problème, ce cœur commun (pur, testé) décide — jamais de
suppression automatique :

- **quarantaine par défaut** : le fichier est **bloqué** sous PuppyOS mais **pas
  effacé** du lecteur (réversible) ;
- **suppression seulement sur accord explicite** du propriétaire **et** volume
  inscriptible ; sinon quarantaine. « Supprimer, c'est éradiquer » → jamais sans
  ton avis ;
- **OS voisin en lecture seule** : on ne peut rien écrire → on *ne pas expose* le
  fichier et on note son chemin, sans toucher ce disque ;
- **humeur purement cosmétique** : énervé, fatigué ou calme, le chien protège
  exactement pareil. Garanti par le type (le verdict ne prend pas l'humeur en
  entrée) et par `protege_toujours`.

Module pur, 6 tests. Le C fournit le chemin exact + l'explication et applique.

## 11. Le 4e chien : dernier rempart dormant — `chien_dormant.rs`

> « Il ne s'éveille jamais pour rien. »

Un chien **chiffré** (scellé dans le coffre TPM existant) qui dort et ne s'éveille
que sur une menace **grave ET un signal signé** (un faux réveil provoqué par un
attaquant est donc impossible). Sa posture (pur, testé) est **tout défensif,
rien de destructif** : couper le réseau, forcer la lecture seule, quarantaine
agressive, re-sceller les apps privilégiées, exiger la ré-auth du propriétaire —
et deux garanties invariables : **ne détruit jamais les données**, **n'enferme
jamais le propriétaire dehors**. Il n'a **aucun pouvoir** que la sécurité normale
n'a pas (sinon ce serait lui la cible). Danger éliminé → il se **re-scelle** et
efface ses secrets d'éveil. Qu'il fasse ramer à fond : c'est le prix du dernier
rempart. Non affiché dans l'OS, aucun réveil manuel possible. Module pur.

**Journal d'intervention vérifiable** (demande Piika) : le dernier rempart n'agit
jamais en silence. Chaque événement **grave** laisse une entrée — éveil réel
**comme** tentative de faux réveil refusée (forensic : quelqu'un a essayé sans
signature) — avec des octets canoniques stables à authentifier par HMAC côté C,
pour un journal **infalsifiable, vérifiable après coup**. Les menaces anodines ne
le polluent pas. Module pur, 10 tests.

## 12. Le 5e chien « Scrap-Dog » : douane des entrées non fiables — `douane.rs`

> « Rust avale le danger ; le C agit sur du propre. »

Le poste de douane par lequel passe **toute entrée non fiable** (fichier ouvert,
catalogue, paquet, en-tête de déchiffrement, métadonnées) avant qu'un chien C ne
la touche. Décide (pur, testé), à partir des bornes déclarées, s'il la **remet au
C** ou la **rejette avant tout traitement** : un **type inconnu ne passe jamais**
(on ne remet au C que ce qu'on sait borner), et toute borne dépassée (256 Mio par
entrée, 512 Mio cumulés sans débordement, profondeur 32, 20 000 entrées — les
mêmes que l'anti-bombe de l'extracteur) est refusée. C'est **là** que vivent les
buffer-overflows : Rust les absorbe, le C ne reçoit que du borné. Module pur, 6 tests.

---

## Contenu du dossier

| Chemin | Rôle |
|---|---|
| `pupdate/` | Cœur **Rust** (crate `pupdate`) : `jail`, `installateur`, `apps`, `registre`, `verif`, `format`, `coffre`, `secours`, `secours_iso`, `secours_filet`, `montage_securise`, `dechiffrement`, `clavier_securise`, `explorateur`, `honeydog`, `chien_dormant`, `douane`, `ffi` (pont C ABI), `moteur`, `pupdate` |
| `c/paquets-autorite.c` | Installateur `.puppy` confiné (privilégié) |
| `c/puppy-lanceur.c` | Lanceur « refus pur » des apps chiffrées (memfd+fexecve) |
| `c/puprecovery.c` | Console de secours dans l'OS (réinstalle par copie signée + épinglée) |
| `c/puprecovery-iso.c` | Recovery embarqué dans l'ISO (répare sans démarrer l'OS) |
| `c/coffre-apps.c` | Rotation / scellement des coffres `.pupcoffre` |
| `wiring-autorite-paquets.patch` | Modifs des fichiers existants : `libpuppy/paquet.c` (anti-bombe), `security/puppy-autorite.c` (câblage + retrait du doublon), `Makefile` |
| `wiring-puprecovery-iso.makefile` | Règle Makefile du recovery ISO (près de `puppy-installateur`) |
| `tests/test_bombe.c` | Test ciblé de l'anti-bombe de l'extracteur (5/5) |
| `ressources/` | Icône maison de la console (chiot infirmier + seringue) |

> Ces fichiers sont une **copie pour revue** : la source de référence de l'OS
> vit hors de ce dépôt (arbre `system/`, `libs/`, `Makefile`). Le patch
> reconstruit les modifs des fichiers existants.

## Compiler et tester le cœur Rust

Sans Cargo (comme le reste de l'OS), bibliothèque statique + shim C :

```sh
# objets crypto depuis libpuppy (crypto.c, monocypher.c, monocypher-ed25519.c)
rustc --edition 2021 --test pupdate/pupdate.rs \
      -C link-arg=crypto.o -C link-arg=monocypher.o -C link-arg=mced.o -o pupdate-test
./pupdate-test     # 176 passed
# bibliothèque statique reliée aux binaires C :
rustc --edition 2021 --crate-type=staticlib -C panic=abort -C relocation-model=pic \
      -C opt-level=2 pupdate/pupdate.rs -o libpupdate.a
```

## Checklist de validation **en VM** (rien de tout ça n'est testable hors machine réelle)

- [ ] seccomp / `openat2 RESOLVE_BENEATH` / abandon de privilèges effectifs
- [ ] install réel confiné d'un `.puppy` (A/B atomique)
- [ ] TPM : scellement, dérivation, ouverture du coffre, `memfd`+`fexecve`
- [ ] **6 scénarios de panne** (conseil ChatGPT) : TPM indisponible · signature
      invalide · copie dorée altérée · version ancienne · stockage défectueux ·
      **interruption pendant la réparation** (la pose A/B atomique doit garantir
      « soit l'ancienne, soit la neuve, jamais un demi-état »)
- [ ] renseigner les **vraies empreintes épinglées** des copies dorées (au build)
- [ ] **recovery ISO** : détection d'une install depuis l'ISO, ouverture dm-crypt
      par mot de passe (aucune clé stockée), réparation des 2 apps **sans démarrer
      l'OS**, réinstallation **sans format** (données préservées)
- [ ] **filet de secours** : enchaînement console → ISO → réinstallation signée
      quand chaque couche échoue (jamais de dégradation silencieuse)
- [ ] brancher l'appel `coffre-apps rotation` dans `puppy-maj`
- [ ] **montage sécurisé** : chrono anti-brute-force réel sur une invite de
      déchiffrement, ouverture dm-crypt, sas Honey-Dog (analyse réelle) avant tout
      montage, fenêtre « code bon / code mauvais » + motif de non-montage
- [ ] **déchiffrement multi-OS** : brancher `dechiffrement` sur la détection
      `inspection.c` (sous-détection BitLocker/FileVault par signature), ouverture
      dm-crypt des volumes à mot de passe, montage lecture seule des OS voisins,
      invite de clé de récupération pour BitLocker/FileVault (aucune tentative)
- [ ] **clavier protégé** : capture exclusive réelle (`EVIOCGRAB`), rejet des
      événements synthétiques / réseau / périphériques à chaud, écho coupé,
      effacement du tampon du mot de passe à la fermeture
- [ ] **explorateur** : construire l'app `.puppy` signée en Rust (UI, thèmes,
      packs d'icônes), seccomp sur la liste blanche minimale, `crypto_wipe` des
      clés au démontage et à la fermeture (vidage RAM vérifié)
- [ ] UI de réparation + câblage de l'icône

Rien n'est câblé, publié ni installé sur du matériel sans accord explicite.
