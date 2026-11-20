# Radio Monde

Application Android de radio en direct : plus de 50 000 stations du monde entier,
avec les radios **françaises** mises en avant à l'ouverture.

## Fonctions

- **Accueil** : les radios les plus écoutées du pays choisi (France par défaut, bouton « Pays ▾ » pour changer).
- **Rechercher** : par nom (FIP, NRJ, Nostalgie, RFI…), en France ou dans le monde entier.
- **Genres** : jazz, pop, rock, info, classique, afro, électro… (en France ou partout).
- **Pays** : toutes les radios d'un pays, avec son drapeau.
- **Favoris** (étoile ☆) et **Récents** (30 dernières radios écoutées).
- Écoute **en arrière-plan**, écran éteint, avec les commandes dans la notification,
  sur l'écran de verrouillage et dans les écouteurs Bluetooth.
- **Titre du morceau** en cours quand la radio le diffuse.
- **Minuteur** de mise en veille (15 min à 2 h).
- Pause automatique quand on débranche le casque ou qu'un appel arrive ; nouvelle tentative
  automatique si le flux coupe.

## Installation sur le téléphone

1. Sur le téléphone, ouvrir le lien direct (toujours la dernière version) :
   https://github.com/Bacez01/https-github.com-morluto-rea/releases/download/radio-monde/RadioMonde.apk
   (ou la page **Releases** du dépôt → **Radio Monde** → `RadioMonde.apk`).
2. Ouvrir le fichier téléchargé.
3. Android demande d'autoriser l'installation d'applis depuis cette source : accepter, puis **Installer**.

Aucune autorisation spéciale n'est nécessaire à l'ouverture.

## Technique

- Kotlin, `minSdk 26` (Android 8), `targetSdk 35`. Lecture avec **Media3 / ExoPlayer**
  (flux MP3, AAC, HLS ; listes `.pls` / `.m3u` résolues).
- Annuaire : [Radio Browser](https://www.radio-browser.info), libre et gratuit, sans clé.
  L'appli choisit un serveur miroir au hasard et passe au suivant en cas de panne.
- Compilation : `cd radio && ./gradlew assembleDebug` ; tests : `./gradlew testDebugUnitTest`.
