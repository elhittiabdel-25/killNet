# Coupe-Net

Application Android qui coupe internet à des applications précises, choisies dans une liste. **Sans Shizuku.**

Deux modes, au choix en haut de l'écran :

| | Mode simple (VPN local) | Mode pare-feu système |
|---|---|---|
| Configuration | Aucune (accepter l'autorisation VPN) | Appairage unique avec le « Débogage sans fil » |
| Wi-Fi, 4G/5G | ✅ | ✅ |
| En même temps que votre VPN | ❌ (Android : un seul VPN à la fois) | ✅ |
| Android | 8+ | 11+ (pare-feu par app : en général Android 13+) |

### Mode simple (VPN local)
Un VPN local qui ne reçoit **que** le trafic des applications bloquées et le jette. Les autres applications ne passent pas par lui. Aucune donnée ne quitte le téléphone. Astuce : Paramètres › VPN › ⚙ Coupe-Net › « VPN permanent » pour qu'il ne s'arrête jamais.

### Mode pare-feu système (compatible VPN)
Utilise le pare-feu intégré d'Android (`cmd connectivity set-package-networking-enabled`), qui coupe l'application elle-même : Wi-Fi, 4G/5G, partage de connexion **et** VPN actif.

Pour avoir le droit d'utiliser ce pare-feu, Coupe-Net contient son **propre client ADB** : il s'appaire lui-même au « Débogage sans fil » d'Android, comme le ferait un ordinateur. Pas de Shizuku, pas d'ordinateur. (Avec un téléphone rooté, il utilise root directement.)

Après l'appairage, Coupe-Net s'accorde la permission d'allumer le Débogage sans fil lui-même : il l'allume quelques secondes quand il doit changer une règle, puis le rééteint. **Après un redémarrage** (Android efface les règles), il remet les blocages tout seul dès que le téléphone est en Wi-Fi. Sans Wi-Fi, un VPN de secours garde les applications bloquées en attendant.

## Obtenir l'APK
L'onglet **Actions** du dépôt GitHub compile l'APK à chaque envoi (« Construire l'APK », 3 à 5 min). Ouvrez la compilation terminée et téléchargez **Coupe-Net-apk** (zip contenant `Coupe-Net.apk`).

Ou dans Android Studio : *Build › Build APK(s)*.

> Si une ancienne version de Coupe-Net est installée, désinstallez-la d'abord (signature différente).

## Appairage (mode pare-feu système), une seule fois
1. Le téléphone doit être en **Wi-Fi** (n'importe lequel, même le partage de connexion d'un autre téléphone).
2. Activez les Options pour les développeurs : Paramètres › À propos › touchez 7 fois « Numéro de build ». Xiaomi/Redmi/POCO : activez aussi « Débogage USB (paramètres de sécurité) ».
3. Dans Coupe-Net, choisissez « Mode pare-feu système » puis **Appairer**.
4. Dans Options pour les développeurs › **Débogage sans fil** : activez-le, puis « Associer l'appareil avec un code d'association ».
5. Une notification Coupe-Net apparaît : **Saisir le code**, tapez les 6 chiffres, validez (sans fermer la fenêtre des Paramètres).
   Sinon : « Appairer en saisissant port et code ici », avec l'écran partagé.
6. Activez l'interrupteur des applications à couper.

## Mode 4G (changer les blocages sans Wi-Fi)
Le Débogage sans fil ne marche qu'en Wi-Fi. Coupe-Net demande donc à adbd d'ouvrir aussi un port local `127.0.0.1:5555` (comme `adb tcpip 5555`), qui reste disponible en 4G/5G, même avec Proton VPN, jusqu'au prochain redémarrage.
- Activé automatiquement après l'appairage, ou bouton « Activer le mode 4G » (en Wi-Fi).
- La première fois, Android demande « Autoriser le débogage ? » : cocher « Toujours autoriser », puis Autoriser.
- Le « Débogage USB » doit rester activé.
- Après un redémarrage : se reconnecter une fois au Wi-Fi, Coupe-Net remet les blocages et rouvre le mode 4G tout seul.

## Bon à savoir
- Une application déjà ouverte peut garder en mémoire ce qu'elle a déjà chargé : fermez-la et rouvrez-la.
- Les notifications push qui passent par les services Google peuvent encore arriver.
- Menu ⋮ : apps système, réappliquer, tout débloquer, options du Débogage sans fil, VPN de secours, refaire l'appairage.
