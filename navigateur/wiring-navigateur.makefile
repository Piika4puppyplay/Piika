# Navigateur 2.2 : règle Navigateur.puppy du Makefile PuppyOS.
#
# Remplace la règle 2.1 (qui copiait seulement puppy.info et écrivait
# « 2.1.0 » en dur). La 2.2 embarque politique.conf et les illustrations dans
# le paquet signé, et prend la version dans puppy.info. Déjà appliquée dans
# l'arbre partagé /mnt/project-files/PuppyOS/Makefile.
#
# Les sources vont dans exemples/navigateur/ (copie de navigateur/source/).

# 2.2 : politique.conf (recherche Google neutralisée ou normale) et les
# illustrations voyagent dans le paquet signé : les changer = une mise à jour
# signée du Navigateur
$(EX)/Navigateur.puppy: $(EX)/navigateur/bin/navigateur exemples/navigateur/puppy.info exemples/navigateur/politique.conf \
                        $(wildcard exemples/navigateur/illustrations/*.png) $(BIN)/puppy-maj $(SIGNER)
	cp exemples/navigateur/puppy.info exemples/navigateur/politique.conf $(EX)/navigateur/
	rm -rf $(EX)/navigateur/illustrations && cp -r exemples/navigateur/illustrations $(EX)/navigateur/
	$(EX)/navigateur/bin/navigateur --icone $(EX)/navigateur/icone.ppm
	$(BIN)/puppy-maj creer $(EX)/navigateur $(shell sed -n 's/^version=//p' exemples/navigateur/puppy.info) $@
	$(signer_paquet)
