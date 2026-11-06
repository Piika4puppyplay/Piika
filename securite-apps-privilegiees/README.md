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
> compile et se lie proprement, et le cœur Rust passe **105/105 tests**. Mais
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

## Contenu du dossier

| Chemin | Rôle |
|---|---|
| `pupdate/` | Cœur **Rust** (crate `pupdate`) : `jail`, `installateur`, `apps`, `registre`, `verif`, `format`, `coffre`, `secours`, `secours_iso`, `secours_filet`, `ffi` (pont C ABI), `moteur`, `pupdate` |
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
./pupdate-test     # 125 passed
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
- [ ] UI de réparation + câblage de l'icône

Rien n'est câblé, publié ni installé sur du matériel sans accord explicite.
