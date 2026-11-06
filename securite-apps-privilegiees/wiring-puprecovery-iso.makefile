# Câblage Makefile du recovery embarqué dans l'ISO (à ajouter près de la règle
# de puppy-installateur). Comme l'installateur, il ne fait PAS partie du système
# installé : il vit sur l'ISO. Cerveau Rust testé (libpupdate.a) + squelette C.
#
# [squelette, non câblé dans l'ISO — valider en VM avant intégration]

$(B)/installation/puprecovery-iso: system/installation/puprecovery-iso.c system/installation/inspection.c $(PUPDATE_A) $(LIBHAL) $(LIBPUPPY) $(ENTETES)
	@mkdir -p $(dir $@)
	@echo "  CC    puprecovery-iso (recovery ISO, cerveau Rust) [squelette, non câblé]"
	@$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(filter %.c,$^) $(PUPDATE_A) $(LDLIBS)
