# puppy-GRUB — gestionnaire d'amorçage UEFI maison de PuppyOS

**Ce n'est PAS GNU GRUB** (aucune ligne de code GNU). C'est une petite application
UEFI écrite en Rust, de la famille des gestionnaires d'amorçage (systemd-boot,
rEFInd) : elle s'exécute **avant** le noyau, affiche un menu, et « chaîne » le
système choisi — le noyau de PuppyOS, ou le chargeur d'un autre OS (dualboot),
via `LoadImage`/`StartImage`.

Pourquoi maison : séparé de l'OS (vit sur l'ESP), pas de GPLv3 ni de l'énorme
surface d'attaque de GRUB2, Secure Boot signé par nos clés, thèmes à nous.

## Fichiers

- `uefi.rs` — liaisons UEFI écrites à la main (aucune crate externe) : table
  système, entrée/sortie texte, système de fichiers de l'ESP, chargement et
  lancement d'image EFI (chaînage), protocole graphique GOP.
- `puppy-grub.rs` — l'application : lit `\EFI\PUPPYOS\grub.conf`, affiche le
  menu, gère le clavier et le compte à rebours, chaîne l'entrée choisie.
- `grub.conf.exemple` — exemple de configuration du menu.
- `construire.sh` — compile `BOOTX64.EFI` et, en option, le teste dans QEMU+OVMF.

## Construire

Dépendance (une seule fois) : la cible UEFI de rustc
```sh
rustup target add x86_64-unknown-uefi
```
Puis :
```sh
puppy-grub/construire.sh            # produit puppy-grub/build/BOOTX64.EFI
puppy-grub/construire.sh --tester   # + lance un test QEMU+OVMF (menu + chaînage)
```

## Format de `grub.conf`

Texte simple, posé sur l'ESP en `\EFI\PUPPYOS\grub.conf` :
```
delai=5          # secondes avant démarrage auto (omis ou -1 = pas d'auto)
defaut=0         # index de l'entrée sélectionnée au départ
titre=PuppyOS
fichier=\EFI\PUPPYOS\puppyos.efi
titre=Windows
fichier=\EFI\Microsoft\Boot\bootmgfw.efi
```
Chaque `titre=` ouvre une entrée ; le `fichier=` qui suit donne le chemin EFI à
chaîner. Les chemins utilisent `\` (ou `/`, converti).

## État

**Marche et testé en VM (OVMF)** : lecture de la config sur l'ESP, menu navigable
(↑/↓, Entrée, Échap), compte à rebours, et chaînage réel d'une image EFI
(`LoadImage` depuis un tampon + `StartImage`).

**À venir** : menu graphique GOP thématisable (polices PuppySans), auto-détection
des autres OS (variables EFI `BootOrder` + scan des chargeurs), intégration à
PuppyUpdate (paquet `.pupgrub`, A/B sur l'ESP, recovery), signature Secure Boot.

**Important** : ce composant **ne modifie pas encore le démarrage réel** de
PuppyOS. Aujourd'hui le micrologiciel lance directement le noyau en
`/EFI/BOOT/BOOTX64.EFI`. Intercaler puppy-GRUB (devenir ce `BOOTX64.EFI`, le
noyau passant en `/EFI/PUPPYOS/puppyos.efi`) touche l'installateur, `maj.c` et
la fabrication de l'ISO : c'est une étape **séparée et sensible**, à valider à
part. Voir `/mnt/project-files/PuppyOS/RAPPORT-AUDIT-PUPPY-GRUB.md`.
