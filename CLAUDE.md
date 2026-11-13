# Lumen — contexte pour Claude Code

Client Twitch non officiel pour Android TV / Google TV, alternative moderne à S0undTV.
Distribution en APK sideload via GitHub Releases. Aucune affiliation avec Twitch.
Priorités, dans l'ordre : performance, connexion Twitch fiable, multi-compte, design 10-foot.
L'utilisateur parle français ; l'UI, les commentaires et les messages de commit sont en français.

## Architecture

- `rust/core` (`lumen-core`) : cœur métier, exposé à Kotlin via UniFFI (proc-macros, async tokio).
  `api.rs` est la seule surface publique. Modules : `auth`, `accounts`, `helix`, `store` (SQLite), `qr`, `secret`.
- `android/core-bridge` : compile le `.so` (cargo-ndk, 3 ABI) et génère les bindings à chaque build.
- `android/data` : Hilt, `CoreHolder`, `AccountsRepository`, `KeystoreSecretStore`.
- `android/feature/*` : un module par fonctionnalité (`auth`, `home`, `player`).
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
- Faible latence V1 : segments standards + live offset serré ; parser prefetch en option expérimentale plus tard.
- Aperçus animés : un ExoPlayer partagé en 160p muet après 800 ms de focus, désactivé si `isLowRamDevice`.
- TLS : rustls + ring + webpki-roots (pas d'OpenSSL, pas d'init JNI). DI : Hilt. Préférences : SQLite côté Rust.
- Replay de chat VOD : pagination par offset uniquement, repli sur « VOD sans chat ».
- Nom « Lumen » et `applicationId` `dev.lumen.tv` provisoires, à confirmer par l'utilisateur.

## Jalons

- [x] J1 : squelette Gradle + Rust + UniFFI, 3 ABI.
- [x] J2 : auth Device Code, multi-compte, Keystore. Validé contre le vrai Twitch sur l'émulateur Google TV
  (connexion, persistance après redémarrage, jeton chiffré et absent des logs, invité, boîte de retrait).
  Pas encore testé sur une vraie box.
- [x] J3 : accueil + suivis + lecteur live (Media3 HLS), shell avec barre latérale, test D-pad.
  Validé sur l'émulateur : catalogue Helix et invité (GQL), lecture, qualité fixe, zapping, reprise au direct.
  Les pubs Twitch insérées dans le flux passent normalement.
- [ ] J4 : chat (lecture puis envoi), batching côté Rust toutes les ~100 ms, buffer borné.
- [ ] J5 : VOD + reprise + replay du chat.
- [ ] J6 : multi-view (2 à 4 flux, sonde `MediaCodecList` + repli à l'exécution).
- [ ] J7 : paramètres, thèmes, mise à jour auto via GitHub Releases, CI, Baseline Profiles, keystore de release.

Schéma SQLite prévu pour la suite (migrations dans `store/mod.rs`, clé `scope` = `global` | `guest` | id de compte) :
`settings(scope,key,value)`, `chat_filters(scope,kind,value)`,
`vod_progress(scope,video_id,…,position_s,finished,updated_at)`, `meta_cache(key,body,fetched_at,ttl_s)`.

## Commandes

```bash
cd rust && cargo test
cd android && ./gradlew :core-bridge:testDebugUnitTest :feature:auth:testDebugUnitTest
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
