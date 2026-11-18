# Coupe-Net

Application Android qui coupe internet à des applications précises, que vous choisissez dans une liste.

Le blocage passe par le pare-feu intégré d'Android (la « chain3 » du service de connectivité) : il agit sur l'application elle-même, donc il fonctionne **en Wi-Fi, en 4G/5G, en partage de connexion et même quand un VPN est actif**. Coupe-Net n'utilise pas de VPN, vous gardez donc votre VPN habituel.

## Ce qu'il faut

- Android 11 ou plus récent
- L'application gratuite **Shizuku** (pas besoin de root ni d'ordinateur), ou un téléphone rooté

## Obtenir l'APK

### Option A : GitHub (sans rien installer)
1. Créez un dépôt sur github.com (il peut être privé) et envoyez-y le contenu de ce dossier (bouton « Add file › Upload files », en gardant le dossier `.github`).
2. Ouvrez l'onglet **Actions** : la compilation « Construire l'APK » démarre seule (3 à 5 minutes).
3. Cliquez sur la compilation terminée et téléchargez **Coupe-Net-apk** en bas de la page. Le zip contient `Coupe-Net.apk`.

### Option B : Android Studio
Ouvrez le dossier dans Android Studio, puis faites *Build › Build APK(s)*.

## Installation et configuration
1. Installez `Coupe-Net.apk` (autorisez « Sources inconnues » si Android le demande).
2. Installez Shizuku (Play Store ou https://shizuku.rikka.app).
3. Activez les Options pour les développeurs (Paramètres › À propos › appuyez 7 fois sur « Numéro de build »), puis activez **Débogage sans fil**.
4. Dans Shizuku : **Associer** (avec le code de débogage sans fil), puis **Démarrer**.
5. Ouvrez Coupe-Net et appuyez sur **Autoriser Coupe-Net dans Shizuku**.
6. Activez l'interrupteur à côté des applications auxquelles vous voulez couper internet.

## Bon à savoir
- **Redémarrage** : Android efface ces blocages à chaque redémarrage. Relancez Shizuku (bouton Démarrer) : Coupe-Net remet automatiquement vos blocages. Avec root, c'est fait au démarrage sans action de votre part.
- Une application déjà ouverte peut garder en cache ce qu'elle a déjà chargé. Fermez-la et rouvrez-la pour voir l'effet.
- Les notifications push qui passent par les services Google (Firebase) ne viennent pas de l'application elle-même : elles peuvent encore arriver.
- Menu ⋮ : afficher les apps système, réappliquer les blocages, tout débloquer.
