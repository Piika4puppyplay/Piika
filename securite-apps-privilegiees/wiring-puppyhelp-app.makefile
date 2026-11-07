# Empaquetage de l'app PuppyHelp (.puppy système à part) dans le Makefile.
#
# PuppyHelp est une application .puppy standalone (vit sous exemples/puppyhelp/,
# comme les autres .puppy de démonstration). Elle se compile sur la libfenetre
# (pf.h) et s'empaquette avec puppy-maj, exactement comme Bonjour / PuppyWatch.
# Elle est posée dans $(wildcard exemples/*/*.c) ? NON : les .puppy ont chacun
# leur règle explicite. On ajoute donc la cible, et on l'accroche à `exemples:`.
#
# [squelette compilé et empaqueté — paquet NON signé en conteneur (clé absente),
#  à valider en VM : paquet installé / désactivé / retiré + persistance de l'habillage]

# --- Makefile : ajouter $(EX)/PuppyHelp.puppy à la liste `exemples:` ----------
# AVANT :
#   exemples: … $(EX)/PuppyWatch.puppy
# APRÈS :
#   exemples: … $(EX)/PuppyWatch.puppy $(EX)/PuppyHelp.puppy


# --- Makefile : les deux règles de l'app -------------------------------------
# PuppyHelp : centre d'aide, application systeme .puppy a part. Volets
# categories/fiche facon aide Win98, 3 habillages (classique Win98, icones XP,
# Help&Support XP) choisis dans ses Parametres et PERSISTES (puppyhelp.conf, clef
# `habillage`, relu au demarrage quel que soit le chemin d'ouverture), recherche
# interne, bouton « Ou suis-je ? », mise en page adaptative (large / etroit).
# Non monolithique : une fiche vide => erreur ISOLEE dans le volet droit.
# Ouverture directe par --page=categorie/fiche (raccourcis d'aide) et
# --contexte=<cle> (« Ou suis-je ? »).
$(EX)/puppyhelp/bin/puppyhelp: exemples/puppyhelp/puppyhelp.c $(LIBFENETRE) $(THEMES) $(LIBPUPPY) $(LIBGFX) $(ENTETES) boot/table-lignes.py ; $(lier_programme)
$(EX)/PuppyHelp.puppy: $(EX)/puppyhelp/bin/puppyhelp exemples/puppyhelp/puppy.info $(BIN)/puppy-maj $(SIGNER)
	cp exemples/puppyhelp/puppy.info $(EX)/puppyhelp/
	$(BIN)/puppy-maj creer $(EX)/puppyhelp 0.1 $@
	$(signer_paquet)

# Rappels (comme toutes les apps PuppyOS) : la source réelle vit hors de ce dépôt
# (arbre exemples/). Le paquet n'est signé que si $(CLE_PAQUETS) est présent —
# donc NON signé en conteneur, ce qui est attendu. Facultative, supprimable,
# désactivable : aucune autre app n'en dépend ; jamais réinstallée de force si
# l'utilisateur l'a retirée ou désactivée (politique jugée par le cœur aide.rs).
