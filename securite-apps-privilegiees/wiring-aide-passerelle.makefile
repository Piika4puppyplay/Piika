# Câblage de la passerelle d'aide dans le bureau (desktop/shell).
#
# La passerelle (c/aide-passerelle.c + .h, posés dans desktop/shell/) est le seul
# point d'entrée des raccourcis d'aide vers le centre d'aide PuppyHelp. La
# DÉCISION « ouvrir la page / fenêtre de secours » est prise par le cœur Rust
# testé pupdate/aide.rs (pont C ABI pupdate_aide_raccourci) ; le C ne fait que
# détecter l'état de PuppyHelp et dessiner la fenêtre de secours indépendante.
#
# Comme aide-passerelle.c vit sous desktop/shell/, il est déjà pris par le
# $(wildcard desktop/*/*.c) du BUREAU : AUCUN ajout de source n'est nécessaire.
# Il suffit de LIER le cœur Rust (libpupdate.a) au binaire du bureau.
#
# [squelette compilé et lié — à valider en VM avec PuppyHelp installé/retiré]

# --- Makefile : la règle puppy-bureau lie désormais $(PUPDATE_A) --------------
# AVANT :
#   $(BIN)/puppy-bureau: $(BUREAU) $(MAJ) $(SECURITE) $(SCELLEMENT) $(LIBHAL) \
#                        $(LIBPUPPY) $(LIBGFX) $(LIBENTREE) $(ENTETES)   ; $(lier)
# APRÈS :
$(BIN)/puppy-bureau:       $(BUREAU) $(MAJ) $(SECURITE) $(SCELLEMENT) $(LIBHAL) $(LIBPUPPY) $(LIBGFX) $(LIBENTREE) $(PUPDATE_A) $(ENTETES)
	@mkdir -p $(dir $@)
	@echo "  CC    puppy-bureau (+ coeur Rust pupdate : passerelle d'aide)"
	@$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(filter %.c,$^) $(PUPDATE_A) $(LDLIBS)


# --- desktop/shell/barre.c : une entrée « Centre d'aide » dans le menu Aide ---
# (toujours cliquable : la passerelle ouvre PuppyHelp s'il est là, sinon la
#  fenêtre de secours qui explique quoi faire — griser priverait l'utilisateur
#  de cette explication ; le bouton grisé est réservé aux raccourcis par-app.)
#
#  #include "aide-passerelle.h"
#
#  enum { ..., L_INFO, L_AIDE };                 /* nouveau type */
#
#  case 3: /* Aide */
#      o[n++] = (ligne){ .texte = "Centre d'aide",       .type = L_AIDE };
#      o[n++] = (ligne){ .type = L_SEP };
#      o[n++] = (ligne){ .texte = "À propos de PuppyOS", .type = L_APP, .app = "a-propos" };
#      o[n++] = (ligne){ .texte = "Mises à jour…",       .type = L_APP, .app = "mises-a-jour" };
#      break;
#
#  static void top_executer(const ligne *o) {
#      switch (o->type) {
#      ...
#      case L_AIDE: aide_ouvrir_page(NULL); break;   /* passe par la passerelle */
#      ...
#
# Les autres apps système appelleront aide_ouvrir_page("categorie/page") depuis
# leur propre raccourci d'aide (chantier à suivre), avec le même garde-fou :
# jamais de blocage, fenêtre de secours si l'aide est HS.
