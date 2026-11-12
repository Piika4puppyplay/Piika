# Navigateur 2.2 (Google Recherche indisponible sous PuppyOS)

- `Navigateur-2.2.0.puppy` : paquet signé PuppyOS. Le poser sur le Bureau et double-cliquer pour l'installer.
- `source/` : sources complètes du Navigateur 2.2, à placer dans `exemples/navigateur/`.
- `wiring-navigateur.makefile` : la règle Navigateur.puppy du Makefile.
- `COMPTE-RENDU-NAVIGATEUR-2.2.md` : architecture, tests QEMU, limites, retour à la normale et retour arrière.

Rétablir Google Recherche : `google_recherche=normale` dans `source/politique.conf`, version suivante dans `puppy.info`, puis un nouveau paquet signé.
