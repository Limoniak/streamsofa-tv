# Lumen — contexte pour Claude Code

Client Twitch non officiel pour Android TV / Google TV, alternative moderne à S0undTV.
Distribution en APK sideload via GitHub Releases. Aucune affiliation avec Twitch.
Priorités, dans l'ordre : performance, connexion Twitch fiable, multi-compte, design 10-foot.
L'utilisateur parle français ; l'UI, les commentaires et les messages de commit sont en français.

## Architecture

- `rust/core` (`lumen-core`) : cœur métier, exposé à Kotlin via UniFFI (proc-macros, async tokio).
  `api.rs` est la seule surface publique. Modules : `auth`, `accounts`, `helix`, `gql`, `emotes` (7TV/BTTV/FFZ), `hls`, `catalog`, `chat` (IRC WebSocket), `store` (SQLite), `qr`, `secret`, `update` (GitHub Releases).
- `android/core-bridge` : compile le `.so` (cargo-ndk, 3 ABI) et génère les bindings à chaque build.
- `android/data` : Hilt, `CoreHolder`, `AccountsRepository`, `KeystoreSecretStore`.
- `android/feature/*` : un module par fonctionnalité (`auth`, `home`, `player`, `settings`).
- `android/baselineprofile` : générateur du Baseline Profile de `:app`.
- `android/designsystem` : thème, couleurs, marges overscan.
- `android/app` : Activity unique, NavHost.

## Règles du projet

- Les jetons ne sont jamais écrits en clair ni journalisés : type `Secret` côté Rust, chiffrement
  Keystore côté Kotlin via le trait `SecretStore`.
- Le code GQL (API non officielle : lecture, replay de chat, catalogue invité) reste isolé dans un
  module `gql` dédié, facile à corriger.
- Ne jamais contourner les pubs ni chercher à obtenir les avantages Turbo/abonnement : la lecture est anonyme.
- Aucun appel réseau ni accès base sur le thread principal.
- Un champ d'erreur UniFFI ne peut pas s'appeler `message` (conflit avec `Throwable`).
- Si un choix technique a un impact important, demander à l'utilisateur avec options et recommandation.
- À chaque jalon : résumé court, comment tester (`adb install`…), ce qui reste.

## Décisions prises

- Catalogue : Helix quand un compte est connecté, GQL en mode invité, derrière un même trait.
- Scopes : `user:read:follows chat:read chat:edit user:write:chat user:read:emotes user:read:blocked_users`
  (en changer force tous les comptes à se reconnecter).
- Envoi de chat via Helix (raison des refus), lecture via IRC WebSocket.
- Faible latence : segments standards + live offset 5 s par défaut. Option expérimentale « Faible latence » :
  les `#EXT-X-TWITCH-PREFETCH` deviennent des segments (`LowLatency.kt`) et la cible passe à 2 s
  (mesuré : 3,7 s contre 7,2 s). Seules certaines chaînes en annoncent ; sans prefetch, aucun effet ni coupure.
- Aperçus animés : un ExoPlayer partagé en 160p muet après 800 ms de focus, désactivé si `isLowRamDevice`.
- TLS : rustls + ring + webpki-roots (pas d'OpenSSL, pas d'init JNI). DI : Hilt. Préférences : SQLite côté Rust.
- Replay de chat VOD : pagination par offset uniquement, repli sur « VOD sans chat ».
  Le chat n'est chargé que s'il est affiché ; après un saut, on montre les 30 s précédentes.
- Nom « Lumen » et `applicationId` `dev.lumen.tv` provisoires, à confirmer par l'utilisateur.

## Jalons

- [x] J1 : squelette Gradle + Rust + UniFFI, 3 ABI.
- [x] J2 : auth Device Code, multi-compte, Keystore. Validé contre le vrai Twitch sur l'émulateur Google TV
  (connexion, persistance après redémarrage, jeton chiffré et absent des logs, invité, boîte de retrait).
  Pas encore testé sur une vraie box.
- [x] J3 : accueil + suivis + lecteur live (Media3 HLS), shell avec barre latérale, test D-pad.
  Validé sur l'émulateur : catalogue Helix et invité (GQL), lecture, qualité fixe, zapping, reprise au direct.
  Les pubs Twitch insérées dans le flux passent normalement.
- [x] J4 : chat (lecture puis envoi), batching côté Rust toutes les ~100 ms, buffer borné.
  Lecture validée sur l'émulateur (IRC anonyme justinfan, émotes Twitch, couleurs, modération, 150 lignes).
  Envoi Helix validé par l'utilisateur. Chat masqué par défaut dans le lecteur (Bas l'affiche).
  Badges (GQL, globaux en cache), émotes tierces 7TV/BTTV/FFZ (module `emotes`, désactivables), mots et pseudos
  masqués + utilisateurs bloqués du compte (Helix) : `chat/decor.rs`, chargés en parallèle de la connexion IRC.
  Le replay VOD applique les filtres, sans badges (vides en anonyme) ni émotes tierces.
  Émotes animées (Coil `AnimatedImageDecoder`, désactivées si `isLowRamDevice`) ; largeur réelle apprise au
  premier affichage (`emoteRatios` dans `ChatPanel`).
- [x] J5 : VOD + reprise + replay du chat. Validé sur l'émulateur : page chaîne (appui long sur une carte de live,
  ou « Chaînes suivies » dans Suivis), grille de VOD avec barre de reprise, lecteur VOD (OK pause, ◀ ▶ ±10 s,
  ±1 min en maintenant, ▼ chat), reprise par profil (table `vod_progress`), replay du chat synchronisé.
  Une VOD réservée aux abonnés est refusée avec un message, jamais contournée.
  En invité, une seule page de VOD (100) : la pagination GQL exige un jeton d'intégrité, non fourni.
- [x] J6 : multi-view (2 à 4 flux). Puce « Multi-view » dans le panneau du lecteur live (si la sonde
  `MediaCodecList` autorise > 1 flux, jamais sur `isLowRamDevice`), choix parmi les suivis en live.
  Un ExoPlayer par tuile, qualité fixe (720p à 2, 480p à 3-4), son sur la tuile focalisée ; erreur décodeur
  → qualité inférieure puis « l'appareil ne peut pas ». OK = plein écran, appui long OK = retirer.
  Validé sur l'émulateur avec 4 flux.
- [x] J7 : onglet Paramètres (chat au lancement, qualité au lancement d'un live, couleur d'accent,
  recherche de mise à jour au démarrage). Préférences globales dans `settings` (migration 3), lues par
  `SettingsRepository`. Accent = état Compose (`LumenColors.accent`), appliqué sans redémarrer.
  Mise à jour : release GitHub `Limoniak/lumen-tv` plus récente → APK de l'ABI de l'appareil
  (`lumen-<version>-<abi>.apk`, sinon universel), téléchargé par Rust, installé via FileProvider.
  Jamais proposée automatiquement sur une build de debug (signature différente).
  CI GitHub Actions : tests Rust + Android à chaque push, release signée sur tag `vX.Y.Z`
  (version issue du tag ; secrets décrits dans le README). Baseline Profile : module `:baselineprofile`
  + profileinstaller. Keystore de release : à créer par l'utilisateur (README), jamais généré ici.
- [x] J8 : parité avec l'app officielle. Validé sur l'émulateur sauf les raids (aucun raid déclenchable).
  Recherche (onglet en tête de barre latérale ; Helix connecté, GQL `searchFor` en invité). Recommandations
  (connecté) : top 3 des catégories des suivis en live, dans leur langue dominante, hors suivis
  (`recommendation_seeds`). Filtre de langue des catégories (préférence `stream_language`). Page chaîne :
  planning à venir, clips (semaine puis tout temps, lecteur MP4 `clip/{slug}`), panneaux « À propos ».
  Raids : EventSub WebSocket `channel.raid` (compte connecté seulement), bandeau 10 s, OK = suivre, Retour = rester.
  Intégration TV : liens `lumen://` et twitch.tv (`singleTask`, `addOnNewIntentListener`), « Continuer à regarder »
  via `TvContract.WatchNextPrograms` (publié en quittant une VOD, retiré une fois finie, jamais remis si l'utilisateur
  l'a retiré), recherche globale/vocale via `SearchProvider` + `searchable.xml`. Google TV peut ignorer les deux
  pour une app hors Play Store. Suivre/ne plus suivre : impossible, Twitch a retiré les endpoints de l'API (27 juillet 2021) ;
  le contourner via GQL exigerait un jeton web et une vérification d'intégrité, exclu.

Schéma SQLite (migrations dans `store/mod.rs`, clé `scope` = `global` | `guest` | id de compte) :
`vod_progress` (migration 2), `settings` (migration 3, seule la portée `global` est utilisée ; filtres du chat dans `chat_hidden_words`/`chat_hidden_users`), `meta_cache(key,body,fetched_at,ttl_s)` (migration 4 :
badges et émotes, 24 h pour les globaux, 1 h par chaîne).

## Commandes

```bash
cd rust && cargo test
cd android && ./gradlew :core-bridge:testDebugUnitTest :data:testDebugUnitTest :feature:auth:testDebugUnitTest :feature:player:testDebugUnitTest
cd android && ./gradlew :app:assembleDebug          # -Plumen.abis=arm64-v8a pour itérer vite
adb install -r android/app/build/outputs/apk/debug/app-universal-debug.apk
```

Prérequis et configuration (`local.properties`, Client ID Twitch) : voir `README.md`.

## Poste de dev (Windows)

- `JAVA_HOME` système pointe vers un JRE : lancer Gradle avec le JDK d'Android Studio
  (`$env:JAVA_HOME = "$env:ProgramFiles\Android\Android Studio\jbr"`).
- Émulateur de test : AVD `Lumen_GoogleTV` (Google TV, Android 16, 1080p, x86_64, WHPX).
  Itérer avec `-Plumen.abis=x86_64` et `adb -s emulator-5554 ...` (un téléphone est souvent branché aussi).
- Captures d'écran : `adb shell screencap -p /sdcard/s.png` puis `adb pull` (la redirection `>` de
  PowerShell 5.1 corrompt les PNG).
