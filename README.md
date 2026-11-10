# Instant Converter — Convertisseur Instantané

Application Android qui affiche une **bulle flottante** dans un coin de l'écran.
Sélectionnez un montant en **euros** ou en **dollars** sur une page web : la bulle
s'agrandit légèrement et affiche le montant converti en **francs CFA (FCFA / XOF)**.
Touchez n'importe où en dehors de la bulle : elle se referme et se range dans son coin.

## Fonctionnement

| Élément | Rôle |
|---|---|
| `BubbleService` | Service au premier plan qui dessine la bulle par-dessus les autres applis. Glisser-déposer, aimantation au coin le plus proche, agrandissement animé, fermeture au toucher extérieur (`FLAG_WATCH_OUTSIDE_TOUCH`), appui long pour copier le résultat. |
| `SelectionAccessibilityService` | Détecte le texte sélectionné. Dans les champs de saisie, Android transmet la sélection directement. Sur les pages web, les navigateurs ne la transmettent pas : dès que la barre « Copier / Partager » apparaît, le service appuie sur « Copier » et la bulle convertit le texte copié (option « Résultat immédiat », désactivable). |
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

Sans le service d'accessibilité : **copiez le montant puis touchez la bulle**, ou
sélectionnez-le et choisissez **Convertir en FCFA** dans le menu de sélection (⋮ dans Chrome).

## Développement

- Kotlin, aucune dépendance en dehors du SDK Android. `minSdk 26`, `targetSdk 35`.
- Tests unitaires : `./gradlew testDebugUnitTest`.
- L'interface est en français sur un téléphone en français, en anglais sinon.

---

# Version Windows 11

Le dossier `windows/` contient l'équivalent pour PC : une bulle flottante
toujours au premier plan, dans un coin de l'écran, avec une icône dans la zone de
notification (à côté de l'horloge).

## Utilisation

- **Sélectionnez un prix** (glisser avec la souris ou double-clic) dans Chrome, Edge,
  Firefox, Opera, Brave… : la bulle s'agrandit et affiche le montant en FCFA.
  L'appli fait elle-même un Ctrl+C sur la sélection puis **remet votre presse-papiers
  comme avant** (option « Résultat immédiat dans les navigateurs »).
- Dans les autres logiciels (Word, lecteur PDF…), la sélection est lue avec
  l'automatisation de l'interface Windows (UI Automation), quand le logiciel le permet.
- **Ctrl+C** sur un montant, n'importe où : la bulle affiche la conversion
  (option « Convertir ce que je copie »).
- **Clic n'importe où ailleurs** : la bulle se referme et se range dans son coin.
- **Glisser la bulle** : elle se range dans le coin le plus proche (sur n'importe quel écran).
- **Clic sur le résultat** : copie le montant en FCFA. **Clic droit** sur la bulle : options.
- Au premier lancement, l'appli s'ajoute au démarrage de Windows (désactivable).

Réglages et taux en cache : `%APPDATA%\InstantConverter\settings.json`.

## Récupérer le programme

Onglet **Actions** → « Build Windows app » → artefact `instant-converter-windows`
→ `InstantConverter.exe` (un seul fichier, aucune installation de .NET nécessaire).

Le programme n'est pas signé : Windows SmartScreen affiche « Windows a protégé votre
ordinateur » au premier lancement → **Informations complémentaires** → **Exécuter quand même**.

## Développement

- C# / WPF, .NET 8. Moteur de conversion partagé et testé : `windows/InstantConverter.Core`.
- Tests : `dotnet test windows/InstantConverter.Core.Tests`.
- Programme : `dotnet publish windows/InstantConverter -c Release -r win-x64 --self-contained -p:PublishSingleFile=true`.
