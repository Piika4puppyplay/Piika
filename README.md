# Piika 🐾

Suivi de défis **libre, gratuit et hors-ligne** : NoFap, NoPorn, NoTélé et Chasteté.
Un chrono par défi, un pointage quotidien, des badges, une collection de cartes et un coach bienveillant.

- 100 % gratuit, sans publicité
- Zéro serveur, zéro collecte de données : tout reste sur ton appareil
- PWA installable, fonctionne sans connexion
- Code sous licence **GNU GPLv3** (voir [LICENSE](LICENSE))

Créée par **Piika** en collaboration avec une IA (Claude, d'Anthropic).

## Fichiers

| Fichier | Rôle |
|---|---|
| `index.html` | L'application (HTML, CSS et JavaScript dans un seul fichier) |
| `manifest.json` | Manifeste PWA (mode standalone) |
| `sw.js` | Service worker (hors-ligne) |
| `icons/` | Icônes temporaires |
| `LICENSE` | Licence GNU GPLv3 |

## Tester en local

Un service worker demande `http://localhost` ou `https`. Depuis ce dossier :

```
python3 -m http.server 8000
```

puis ouvre <http://localhost:8000>.

## Publier avec GitHub Pages

Dépôt → Settings → Pages → *Deploy from a branch* → `main` / racine.
L'appli est alors servie en https et s'installe depuis le navigateur (« Ajouter à l'écran d'accueil »).

## Publier une mise à jour

Change `VERSION` dans `sw.js` (par exemple `piika-v1.1.1`) pour que les anciens caches soient remplacés.

## Sauvegarde

Le fichier de sauvegarde est chiffré et signé. Le code étant public, ce chiffrement dissuade les modifications
involontaires ou faciles, mais n'est pas une protection absolue.

## Licence

Copyright (C) 2026 Piika. Ce programme est un logiciel libre : vous pouvez le redistribuer et/ou le modifier
selon les termes de la GNU General Public License version 3 (ou toute version ultérieure). Il est distribué sans aucune garantie.


## Version 1.2.0
- Nouvel onglet **Jeux** : problèmes d'échecs (mat en 1, 2, 3), maths, français, Memory.
- Bouton **SOS** (jeu plus long, une récompense toutes les 48 h).
- Une carte par jour et par jeu ; succès liés aux jeux ; règlement et FAQ mis à jour.


## Version 1.3.0
- Nouveaux défis : **Tabac**, **Drogues**, **Alcool** (règles équitables, sans jugement, précautions adaptées, aides officielles).
- Onglet **Réglages** : activer ou masquer chaque défi, avec ses succès, et le réactiver à tout moment.
- Conseils et précautions par défi ; motif du créateur ajouté à l'histoire de Piika.


## Version 1.4.0
- Nouveau défi **Chemsex** (règles justes et sans jugement), prévention et réduction des risques.
- Rappel de dépistage dans le coach, numéros Sida Info Service, texte de loi sur le secret médical.


## Version 1.5.0
- Bouton flottant **🆘 Aide** (toujours accessible) : numéros d'aide par situation et textes de loi (consentement, soumission chimique, violences, homophobie et transphobie, plainte, secret médical).


## Version 1.6.0
- Jeu **2048** (carte dès la tuile 128, ou 256 en mode SOS), annulations, messages d'encouragement et conseils.
- Temps passé en mode SOS affiché.


## Version 1.7.0
- Réglages : activer/désactiver chaque jeu (Mes jeux). Un jeu désactivé disparaît des Jeux et du mode SOS, ses succès sont masqués (non supprimés).
- Nouveau jeu **Simon** : objectif de carte adaptatif selon ta moyenne, conseils de mémorisation, succès.


## Version 1.7.1
- Le geste « tirer pour rafraîchir » de Chrome est désactivé (plus de rechargement accidentel en jouant).


## Version 1.7.2
- Bouton « Installer l'application » dans Réglages (caché si déjà installée ou si le navigateur ne propose pas l'installation).


## Version 1.7.3
- Demande de stockage persistant au navigateur et avertissement visible sur le vidage des données du site (Réglages et FAQ).


## Version 1.8.0
- Joker : un oubli de pointage (ou une restauration de vieille sauvegarde) peut être rattrapé une fois par mois. Joker offert à la première ouverture, indisponible 30 jours après usage, non cumulable.


## Version 1.8.1
- Maintenance interne.


## Version 1.8.2
- Onglet « À propos » renommé « Guide » ; la section À propos passe tout en bas des Réglages.
- Règlement renuméroté dans l'ordre.
- Carte Joker plus lisible avec compte à rebours ; coach du jour repliable.


## Version 1.8.3
- Le bouton « Masquer » du coach est maintenant en haut de sa carte.


## Version 1.9.0
- Trois nouveaux jeux : Sudoku (4×4, 6×6, 9×9), Taquin (3×3, 4×4) et Respiration guidée (2 ou 5 minutes), avec carte du jour, mode SOS, succès et interrupteurs « Mes jeux ».


## Version 1.10.0
- Trois nouveaux jeux : Mot mystère, Lights Out et Tours de Hanoï, avec carte du jour, mode SOS, succès et interrupteurs « Mes jeux ».


## Version 1.11.0
- Deux nouveaux jeux : Morpion (3×3 et 4×4, IA tolérante) et Pop-it (outil apaisant sans score, son et vibration réglables), avec mode SOS, succès pour le morpion et interrupteurs « Mes jeux ».


## Version 1.12.0
- Page « Mon chemin » (onglet Succès) : jours cumulés, records, barres des 30 derniers jours, progression de chaque défi face à son record. Les chronos terminés sont archivés pour alimenter le graphique.


## Version 2.0.0
- Piika parle maintenant français, anglais (international), espagnol (neutre, Amérique latine) et allemand.
- La langue se choisit au premier lancement et se change à tout moment dans Réglages (« Langue · Language »), sans justification. Les installations existantes restent en français.
- Les textes traduits sont dans `lang/en.js`, `lang/es.js` et `lang/de.js` (rien n'est envoyé, tout reste hors-ligne).
- Numéros d'aide, extraits de loi, quiz et mots mystère adaptés à chaque langue.
- Les traductions sont automatiques : une relecture par des locuteurs natifs est recommandée.


## Version 2.1.0
- Nouvelle langue : portugais du Brésil (`lang/pt.js`), avec numéros d'aide du Brésil et du Portugal, quiz et mots mystère en portugais.
- Avertissement au choix de la langue et dans Réglages : les traductions sont faites par IA et peuvent contenir des erreurs ; le concepteur ne parle que français. Un lien de signalement anonyme (discussion Reddit) s'affiche dès que `REPORT_URL` est renseigné dans `index.html`.
- Les numéros d'aide cités dans l'Aide rapide et les conseils sont maintenant cliquables dans toutes les langues.


## Version 2.1.1
- Astuce affichée dans un navigateur (écran de bienvenue et coach du jour) : si une bannière de l'hébergeur gêne en bas de l'écran, on peut la fermer avec sa croix. Elle ne vient pas de Piika. Le bouton « Compris » la masque.


## Version 2.2.0
- Détection de mise à jour : à l'ouverture (ou au retour sur l'appli), Piika vérifie en arrière-plan s'il existe une nouvelle version. Sans nouveauté, aucune fenêtre. Sinon, une fenêtre propose « Mettre à jour » (environ une seconde, rien n'est perdu) ou « Plus tard ».
- La fenêtre n'apparaît jamais pendant une partie, une respiration guidée ou une autre fenêtre ouverte : elle attend la fin.
- `sw.js` n'active plus la nouvelle version tout seul : c'est le bouton « Mettre à jour » qui le demande.


## Version 2.2.1
- Fenêtre de mise à jour sans contrainte : « Mettre à jour », « Plus tard » (la fenêtre ne revient pas avant 24 h) ou « Ignorer cette mise à jour » (elle ne revient qu'à la version suivante). Rien n'est forcé.


## Version 2.2.2
- FAQ : nouvelle question « Comment fonctionnent les mises à jour ? Est-ce sûr ? » (rien n'est envoyé, rien ne se met à jour sans l'accord de la personne). La réponse sur le code source indique qu'il est publié sur GitHub.


## Version 2.3.0
- Deux défis personnalisés (« Réglages › Mes défis › Créer ») : la personne choisit le nom, l'icône et jusqu'à 6 déclarations, chacune avec une case « remet à zéro ». Mêmes règles que les autres défis : pointage obligatoire avant 23h, joker, suspension, badges et cartes. Tout reste sur le téléphone, rien n'est traduit ni transmis. Désactivés tant qu'ils ne sont pas créés ; on peut les modifier ou les effacer.
- Succès « Chrono fantôme » 👻 (lancer un défi personnalisé) et « Fantôme fidèle » (tenir 7 jours).
- Bouton « Partager Piika » dans Réglages : le menu de partage du téléphone (ou copie du lien), sans aucune donnée personnelle.


## Version 2.3.1
- Les défis personnalisés sont désormais proposés dans l'onglet Défis (carte « Créer un défi perso » tant qu'il reste un emplacement libre), et plus seulement en bas de Réglages.


## Version 2.3.2
- Correction : la fenêtre de confirmation (par exemple « Effacer ce défi ? ») passe maintenant au-dessus de la fenêtre de modification d'un défi personnalisé, au lieu de s'ouvrir dessous.


## Version 2.3.3
- Réglages › Mes défis : bouton « 🗑️ Supprimer » à côté de « Modifier » pour les défis personnalisés (avec confirmation).
- Garde-fou : les défis personnalisés donnent au total une seule carte par jour (pointage et paliers), même si on supprime puis recrée un défi, pour éviter la génération abusive de cartes.


## Version 2.4.0
- Félicitations : à chaque nouveau palier de badge, une fenêtre à fermer soi-même montre la carte gagnée ; si plusieurs défis ont franchi un palier pendant l'absence, un seul popup les regroupe. Appli ouverte au moment du palier : simple message qui disparaît tout seul. Interrupteur dans Réglages pour désactiver les fenêtres.
- La fenêtre de mise à jour n'apparaît plus que sur l'écran des défis, jamais pendant une partie ou ailleurs.
- Correction : plus de message vide quand un palier de défi personnalisé ne donne pas de carte.

## v2.5.0

- **Accueil en 3 étapes** pour les nouvelles installations (après le choix de la langue) : comment ça marche, le pointage quotidien sans jugement, la discrétion. Peut être passé. Jamais affiché aux utilisateurs existants.
- **Message de rechute** : ajoute « Tu avais tenu X jour(s) : ça reste acquis. ».
- **Apparence** (Réglages) : Auto / Sombre / Clair, mémorisée sur l'appareil.
- **Rappel quotidien** (Réglages) : génère un fichier `.ics` (événement quotidien nommé « Piika », avec alarme) à ouvrir avec l'application Agenda. Aucune notification, rien n'est envoyé.
- **Calendrier des 5 dernières semaines** dans « Mon chemin » (couleurs neutres, aucun rouge).
- Traductions EN/ES/DE/PT mises à jour.

## v2.5.1

- **Apparence** : nouveau mode « Selon l'heure ». Le thème passe tout seul en sombre puis en clair aux heures choisies (par défaut sombre de 20h30 à 7h00), avec l'horloge protégée de l'appli. Deux menus déroulants (pas de 30 min) règlent les heures. Les modes Auto (téléphone), Sombre et Clair restent disponibles pour tout bloquer à la main.
