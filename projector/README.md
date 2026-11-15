# Mini Projecteur — Mini Projector

Application Android qui transforme le téléphone en **projecteur « boîte à chaussures »** :
le téléphone est placé dans une boîte noire, l'écran tourné vers une loupe (ou une lentille
de Fresnel), et l'image ou la vidéo apparaît sur le mur.

L'application ne crée pas de lumière : c'est la lentille qui projette l'écran. Elle
prépare l'image pour que le résultat sur le mur soit à l'endroit, net et le plus lumineux possible.

## Fonctions

- **Projeter une photo ou une vidéo** de la galerie (ou « Ouvrir avec » / « Partager » →
  *Projeter sur le mur* depuis la galerie ou le gestionnaire de fichiers).
- **Image retournée pour la lentille** : la loupe affiche l'image la tête en bas, donc
  « Retourner haut / bas » est activé par défaut. « Miroir » (gauche / droite) est réglable.
- **Mire de mise au point** : une grille et des cercles pour trouver la netteté, et les mots
  HAUT / GAUCHE / DROITE pour vérifier le sens de l'image sur le mur.
- **Plein écran** (barres système masquées), **luminosité au maximum**, **écran toujours allumé**,
  paysage verrouillé (dans la boîte, le capteur d'orientation tournerait l'image au hasard).
- **Zoom** (pincer ou boutons + / −), **déplacement** (glisser), **double toucher** pour recentrer.
- **Correction trapèze** (±30°) quand la boîte vise un peu vers le haut ou le bas.
- Vidéo en boucle avec lecture / pause. Les réglages et le dernier fichier sont mémorisés.

## Fabriquer la boîte

1. Une boîte à chaussures, noire à l'intérieur (peinture ou papier noir).
2. Un trou à une extrémité, avec une loupe ou une lentille de Fresnel format A4 collée devant.
3. Verrouiller la rotation, poser le téléphone en paysage, écran vers la lentille, sur un support
   qui coulisse (une petite boîte, des pinces…).
4. Pièce dans le noir complet, mur blanc à 1–2 m.
5. Ouvrir la **mire** et avancer / reculer le téléphone jusqu'à ce que les traits soient nets.

Si les mots de la mire sont à l'envers sur le mur, changer « Retourner haut / bas » ou « Miroir ».

## Récupérer l'APK

Onglet **Actions** du dépôt → « Build Mini Projector APK » → artefact `mini-projector-apk`
→ `MiniProjector.apk`. Ou compiler : `cd projector && ./gradlew assembleDebug`
(Android Studio / SDK requis).

## Développement

| Fichier | Rôle |
|---|---|
| `MainActivity` | Écran d'accueil : choix du fichier, réglages de la lentille, notice de fabrication. |
| `ProjectionActivity` | Plein écran : image (`ImageView`) ou vidéo (`MediaPlayer` + `TextureView`), miroir, zoom, trapèze, gestes. |
| `FocusPatternView` | Mire de mise au point dessinée à l'écran. |
| `ProjectionMath` | Calculs testés : miroir / zoom, ajustement des proportions de la vidéo, sous-échantillonnage des grandes photos. |
| `ProjectorSettings` | Réglages mémorisés. |

- Kotlin, aucune dépendance en dehors du SDK Android. `minSdk 26`, `targetSdk 35`.
- Tests unitaires : `cd projector && ./gradlew testDebugUnitTest`.
- Interface en français sur un téléphone en français, en anglais sinon.
