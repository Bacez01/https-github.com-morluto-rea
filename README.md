# Instant Converter — Convertisseur Instantané

Application Android qui affiche une **bulle flottante** dans un coin de l'écran.
Sélectionnez un montant en **euros** ou en **dollars** sur une page web : la bulle
s'agrandit légèrement et affiche le montant converti en **francs CFA (FCFA / XOF)**.
Touchez n'importe où en dehors de la bulle : elle se referme et se range dans son coin.

## Fonctionnement

| Élément | Rôle |
|---|---|
| `BubbleService` | Service au premier plan qui dessine la bulle par-dessus les autres applis. Glisser-déposer, aimantation au coin le plus proche, agrandissement animé, fermeture au toucher extérieur (`FLAG_WATCH_OUTSIDE_TOUCH`), appui long pour copier le résultat. |
| `SelectionAccessibilityService` | Détecte automatiquement le texte sélectionné (Chrome, Firefox, etc.) et envoie le montant à la bulle. |
| `ProcessTextActivity` | Ajoute « Convertir en FCFA » dans le menu de sélection de texte de toutes les applis (solution de secours sans accessibilité). |
| `AmountParser` | Reconnaît `49,99 €`, `€49.99`, `1 299,00 EUR`, `$1,299.99`, `USD 15`, `12€50`, `1.234,56 €`, `2,5 M€`, `$3k`, `15 euros`, `20 dollars`… |
| `RateRepository` | EUR → XOF : parité fixe **655,957**. USD → XOF : téléchargé depuis `open.er-api.com` (gratuit, sans clé), mis en cache, actualisé toutes les 6 h. |
| `BootReceiver` | Réaffiche la bulle au démarrage du téléphone (désactivable). |

La bulle s'affiche dès l'ouverture de l'application.

## Installation

1. Récupérer l'APK : onglet **Actions** du dépôt → dernier build « Build APK » → artefact `instant-converter-apk`.
   Ou compiler soi-même : `./gradlew assembleDebug` (Android Studio / SDK requis) → `app/build/outputs/apk/debug/app-debug.apk`.
2. Ouvrir l'appli et :
   1. autoriser **l'affichage par-dessus les autres applis** ;
   2. activer **Convertisseur Instantané** dans *Paramètres › Accessibilité* (détection automatique).
      Sur Android 13+, pour un APK installé à la main, il faut d'abord autoriser les
      *paramètres restreints* : *Infos sur l'appli › ⋮ › Autoriser les paramètres restreints* ;
   3. (Android 13+) autoriser les notifications.

Sans le service d'accessibilité, sélectionnez le montant puis choisissez
**Convertir en FCFA** dans le menu de sélection (⋮ dans Chrome).

## Développement

- Kotlin, aucune dépendance en dehors du SDK Android. `minSdk 26`, `targetSdk 35`.
- Tests unitaires : `./gradlew testDebugUnitTest`.
- L'interface est en français sur un téléphone en français, en anglais sinon.
