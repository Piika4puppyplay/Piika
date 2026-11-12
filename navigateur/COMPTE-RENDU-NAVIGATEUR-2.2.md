# Navigateur 2.2 : Google Recherche indisponible, redirection vers DuckDuckGo

Paquet signé : `ressources-navigateur/Navigateur-2.2.0.puppy` (SHA-256 `989d95b7…df257b`, signature « vérifiée : PuppyOS » dans QEMU).
Captures des essais : `ressources-navigateur/essais-2.2/`. Tes deux images à retoucher : `ressources-navigateur/google-indisponible-images.zip`.

## Ce que Piika doit faire

1. Fusionner la PR (lien dans le fil).
2. Double-cliquer **Navigateur.puppy** sur le Bureau pour l'installer (comme d'habitude, le Navigateur est lié statiquement).
3. Sur ta vraie machine, avec Internet : taper des mots dans la barre d'adresse (moteur Google), ouvrir google.com, ouvrir YouTube et Gmail. Ce sont les seuls essais que la VM ne pouvait pas faire (pas d'Internet).
4. Plus tard : renvoyer les deux images corrigées, et tes captures d'écran du captcha pour la rubrique d'aide (sans info perso).

## Architecture

Rien n'est bloqué au niveau du domaine Google. Le Navigateur arrête seulement les **requêtes de recherche**, avant qu'elles sortent de la machine, grâce à l'interception Fetch de Chromium (étape « requête », avant tout envoi réseau). Les pages et scripts de Google ne sont jamais modifiés.

**La politique voyage dans le paquet signé.** Le fichier `politique.conf` du paquet contient `google_recherche=neutralisee`. Sans ce fichier, ou avec `normale`, le Navigateur se comporte normalement. Tout ce qui concerne Google (page locale, bandeau, rubrique d'aide, réglage) dépend de cette ligne. Le Navigateur ne décide donc **jamais seul** de réactiver Google : seule une mise à jour signée le fait, et le retour arrière est un autre paquet signé.

| Où | Ce qui est classé | Ce qui se passe |
|---|---|---|
| `/search?q=…`, `/`, `/webhp`, `/imghp` avec des mots | Recherche | Redirection 307 vers `https://duckduckgo.com/?q=` (mêmes mots), bandeau une fois par lancement |
| `/complete/search` (suggestions pendant la frappe sur la page Google) | Suggestion | Requête annulée, rien ne part |
| `/`, `/webhp` sans mots (page d'accueil) | Accueil | Page locale PuppyOS (dessinée par le Navigateur, pas par Chromium) |
| Tout le reste (`/maps`, `/url`, `mail.google.com`, YouTube, ports non standards…) | Autre | Intact, jamais touché |

## Fichiers modifiés (exemples/navigateur + règle du Makefile)

- `google.c` (nouveau) : la politique, le classement des adresses Google, l'adresse DuckDuckGo, le bandeau unique, l'historique local des tests (`navigateur-google.txt`, 200 entrées maximum).
- `navigateur.c` : barre d'adresse, interception Fetch (`requete_google`), « Ouvrir quand même », bouton de test.
- `interface.c` : bandeau, page locale, réglage avec avertissement, rubrique d'aide « Pourquoi Google Recherche est-il indisponible sous PuppyOS ? » (avec emplacement pour tes captures).
- `nav.h`, `donnees.c` : réglage `google_redirige` (activé par défaut, gardé entre les lancements).
- `politique.conf`, `illustrations/` (nouveaux), `puppy.info` (version 2.2.0).
- `Makefile` : la règle Navigateur.puppy embarque `politique.conf` et les illustrations, et prend la version dans `puppy.info`.

Rien d'autre n'est touché : ni les protections, ni le bac à sable, ni le moteur Chromium, ni les autres programmes.

## Comportement de chaque point d'entrée

- **Barre d'adresse** (moteur Google choisi) : la recherche part directement vers DuckDuckGo. Aucune requête Google n'est créée.
- **Lien ou formulaire de recherche Google** dans une page : arrêté avant l'envoi, redirigé vers DuckDuckGo avec les mêmes mots.
- **Raccourcis intégrés** : ils passent par la barre d'adresse ou par un lien, donc par les mêmes règles.
- **google.com tapé directement** : page locale PuppyOS. Elle dit que Google Recherche est indisponible, qu'aucune recherche n'est envoyée, propose DuckDuckGo, et a une illustration cliquable vers l'aide. Le bouton « Ouvrir quand même google.com » ouvre le **vrai** site.
- **YouTube, Gmail, Maps, Drive** : jamais interceptés.
- **Réglage** Paramètres → « Recherches Google vers DuckDuckGo » : le couper affiche un avertissement, puis les recherches vont à Google. L'accès direct à google.com reste possible dans tous les cas.

## Tests dans QEMU (PuppyOS 0.17.7 de test, noyau 6.12.48)

Pour **prouver ce qui sort**, le VPN du Navigateur passait par un petit proxy d'essai qui note chaque connexion. Une recherche qui fuiterait vers Google y laisserait une ligne `CONNECT www.google.com`.

| Test | Résultat | Ce que le proxy a vu |
|---|---|---|
| Recherche depuis la barre d'adresse | ✅ DuckDuckGo, bandeau avec le texte exact | `duckduckgo.com` seulement |
| Deuxième recherche | ✅ pas de second bandeau | `duckduckgo.com` |
| Clic sur un lien de recherche Google | ✅ DuckDuckGo, « niche en bois » bien transmis | `duckduckgo.com` |
| Formulaire de recherche Google | ✅ DuckDuckGo | `duckduckgo.com` |
| Lien YouTube, lien Gmail | ✅ intacts | `www.youtube.com`, `mail.google.com` |
| Lien Google Maps | ✅ intact | `www.google.com` (attendu : ce n'est pas une recherche) |
| google.com tapé | ✅ page locale | **rien** : aucune connexion |
| « Ouvrir quand même google.com » | ✅ le vrai site | `google.com` |
| « Tester Google Recherche » | ✅ résultat noté (échec : injoignable), rien réactivé | `www.google.com` (seul le mot « puppyos ») |
| Couper la redirection | ✅ avertissement, puis recherche vers Google | `www.google.com` |
| Redémarrage + réinstallation | ✅ réglages gardés, historique du test gardé | |
| Indisponibilité qui dure | ✅ tout reste en place tant que le paquet dit `neutralisee` | |
| Paquet 2.2.1 altéré d'un octet | ✅ refusé (« empreinte SHA-256 incorrecte ») | |
| **Retour de Google** : paquet signé 2.2.1 `normale` | ✅ recherches vers Google ; page locale, bandeau, rubrique d'aide et réglage disparus | `www.google.com` |
| **Retour arrière** : réinstaller 2.2.0 | ✅ redirection, bandeau, page et rubrique reviennent | `duckduckgo.com` |
| Fuite de mots pendant la frappe dans la barre | ✅ aucune | rien |

Le paquet 2.2.1 n'a servi qu'à l'essai. Il n'est pas publié.

## Retour à la normale (pour le mainteneur)

Le Navigateur ne réactive rien tout seul : un test réussi est seulement noté. Avant de publier la version « normale », il faut avoir vérifié :
- de vraies recherches complètes (plusieurs mots, plusieurs pages de résultats) ;
- sur plusieurs jours, pas un seul essai réussi ;
- sans captcha ni page bloquée.

Ensuite :
1. Mettre `google_recherche=normale` dans `exemples/navigateur/politique.conf`.
2. Passer la version à 2.2.1 dans `puppy.info`.
3. `make` (le paquet est signé par la règle).
4. Publier par PR.

**Si le problème revient :** remettre `neutralisee` et publier une version plus récente, ou réinstaller l'ancien paquet signé.

## Limites connues

- **Pas testé sur le vrai Internet** : la VM n'en a pas. La logique est prouvée par le proxy, mais le vrai comportement de Google reste à voir chez toi.
- **Sur la vraie page google.com** (« Ouvrir quand même »), les suggestions de frappe sont coupées et une recherche lancée depuis la page est redirigée. Mais les propres scripts de Google pourraient envoyer d'autres données de la page, comme sur n'importe quel site : on ne modifie pas Google.
- **Ton identité visuelle Google n'est pas imitée** (ni logo ni couleurs). C'était dans ta demande, mais imiter Google pourrait tromper quelqu'un et poser un problème de marque. La page dit clairement que c'est une page PuppyOS et cite Google en texte.
- **L'illustration actuelle est provisoire** (loupe et patte dessinées par PuppyOS). Tes images ne sont pas intégrées : voir le zip. On la remplace sans recompiler, en changeant le fichier dans le paquet.
- **Les captures du captcha** ne sont pas encore là : la rubrique d'aide affiche un emplacement en attendant les tiennes.
- **Version téléphone/tablette** : le code choisit l'image mobile si `/system/etc/appareil` dit « telephone » ou « tablette ». Ça n'a pas pu être testé, puisque PuppyOS n'existe pas encore sur mobile.
- **Écrit en C** : c'est une extension du Navigateur existant, qui est en C. Le réécrire en Rust n'avait pas de raison ici.
- **Le Navigateur est livré comme paquet .puppy à part**, pas dans une mise à jour système : les sources du tronc 0.18/0.19 ne sont pas dans l'arbre partagé. Le fil OS pourra l'inclure dans sa prochaine mise à jour.
