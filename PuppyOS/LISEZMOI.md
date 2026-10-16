# 🐾 Mises à jour de PuppyOS

Ce dossier est lu par PuppyOS (0.3 et plus) pour se mettre à jour :

- `derniere.txt` : la dernière version publiée (version, fichier, taille, SHA-256) ;
- `puppyos-X.Y.Z.pupupdate` : la mise à jour, chiffrée et **signée Ed25519**.

PuppyOS refuse tout fichier qui n'a pas la signature de sa clé, ou plus ancien
que la version installée. La clé secrète de signature n'est jamais dans ce dépôt.

## Moteurs (0.10 et plus)

- `moteur-chromium.txt` : l'annonce du moteur Chromium (version, nombre de morceaux, taille, SHA-256) ;
- `moteur-chromium-…pupmoteur.1`, `.2`, `.3` : le moteur, découpé en morceaux de 45 Mo
  (GitHub refuse les fichiers de plus de 100 Mo), recollés et vérifiés par PuppyOS.

Le moteur est Chromium (headless_shell, logiciel libre, licence BSD et licences de ses
composants), compressé et **signé Ed25519** avec la même clé que les mises à jour.
Il sert au Navigateur et à la tuile Streaming du MediaCenter. Il ne contient aucun
module DRM (pas de Widevine) : Netflix, Prime Video et Disney+ ne marchent pas.
