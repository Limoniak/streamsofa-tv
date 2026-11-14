# StreamSofa — contexte pour Claude Code

Client Twitch non officiel pour Android TV / Google TV, alternative moderne à S0undTV.
Distribution en APK sideload via GitHub Releases, licence GPL-3.0-or-later. Aucune affiliation avec Twitch.
Priorités, dans l'ordre : performance, connexion Twitch fiable, multi-compte, design 10-foot.
L'utilisateur parle français ; les commentaires et les messages de commit sont en français.
L'UI suit la langue du système : anglais dans `values/strings.xml` (défaut), puis `values-fr/`, `-es/`, `-de/`, `-it/`, `-pt-rBR/` ;
toute chaîne ajoutée l'est dans les six (`TranslationsTest` dans `:data` échoue sinon), et une langue nouvelle va aussi dans `locales_config.xml`.
Un nombre suivi d'un mot passe par `<plurals>` (`pluralStringResource`) : `one` et `other` partout, plus `many` en fr/es/it/pt (lint l'exige).
Exceptions : `README.md`, `CHANGELOG.md` et `ROADMAP.md` sont en anglais (chaque changement visible va dans `Unreleased`).

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
- Envoi de chat via Helix (raison des refus), lecture via IRC WebSocket. Réponses : tags IRC `reply-parent-*` affichés
  au-dessus du message, envoi avec `reply_parent_message_id` (choix parmi les 30 derniers messages, figés à l'ouverture).
  Sélecteur d'émotes : Helix `chat/emotes/user` (celles de la chaîne d'abord) + émotes tierces de la chaîne (cache `emotes:<id>`).
- Sondages et prédictions : GQL anonyme (`user.viewablePoll`, `channel.activePredictionEvents`), relus toutes les 15 s
  seulement quand le chat est affiché (PubSub n'existe plus, EventSub exige les scopes du diffuseur). Lecture seule.
- Apparence du chat (`chat_text_size`, `chat_width`, `chat_overlay`) : `ChatLook`, lu à l'ouverture du lecteur.
- Profils : préférences par profil (portée = id du compte ou `guest`), sauf `check_updates`, `beta_updates`,
  `crash_log` (portée `global`) ; `set_setting` aiguille selon la clé. PIN : 4 chiffres, PBKDF2-SHA256 (ring) dans
  `pin_hash`, jamais renvoyé par `settings()` ; PIN oublié = retirer le profil (ou réinitialiser l'invité).
  Mode enfants (`kids_profile`) : GQL `users { broadcastSettings.isMature, channel.contentClassificationLabels }`,
  on ne garde que les chaînes non matures et sans étiquette, en échouant fermé ; lecture refusée (`Unavailable`
  `kids_profile`), pas de chat. Seul le PIN d'un profil non enfant lève le mode (libre si aucun n'existe).
- Rangée launcher « Chaînes suivies en live » : chaîne d'aperçu `TvContract` (`LauncherChannel`), réécrite à chaque
  `followedStreams()` et par `LauncherRefreshJob` (JobScheduler, 30 min, sans dépendance). Vide en invité.
- Clips : catégorie = GQL `game.clips` (semaine, filtre de langue) en tête de la grille ; suivis = clips de la semaine
  de 12 chaînes (en live d'abord, puis derniers suivis), une requête GQL chacune, triés par vues.
- Faible latence : segments standards + live offset 5 s par défaut. Option expérimentale « Faible latence » :
  les `#EXT-X-TWITCH-PREFETCH` deviennent des segments (`LowLatency.kt`) et la cible passe à 2 s
  (mesuré : 3,7 s contre 7,2 s). Seules certaines chaînes en annoncent ; sans prefetch, aucun effet ni coupure.
- Aperçus animés : un ExoPlayer partagé en 160p muet après 800 ms de focus, désactivé si `isLowRamDevice`.
- TLS : rustls + ring + webpki-roots (pas d'OpenSSL, pas d'init JNI). DI : Hilt. Préférences : SQLite côté Rust.
- Replay de chat VOD : pagination par offset uniquement, repli sur « VOD sans chat ».
  Le chat n'est chargé que s'il est affiché ; après un saut, on montre les 30 s précédentes.
- Nom : **StreamSofa**, `applicationId` `dev.streamsofa.tv`, liens internes `streamsofa://`. Le code garde
  l'ancien nom interne « Lumen » (`lumen-core`, packages `dev.lumen.*`, `namespace`, `lumen.db`, alias Keystore,
  secrets CI `LUMEN_*`) : invisible pour l'utilisateur, ne pas renommer. Identité (piste « E1 ») : logotype « stream » crème + « sofa » pêche
  assis dans un canapé lavande, Fraunces SOFT 100 / 750 (OFL) converti en tracés ; icône = le canapé avec un « s ».
  Fichiers générés : `banner.xml`, `ic_launcher.xml`, `docs/logo.svg` (script hors dépôt). Palette : fonds prune
  `#130F19`/`#1B1622`, texte crème `#F4EEE8`, accent lavande `#B79CFF`, pêche `#FFB38A` (clé d'accent `orange` conservée).
- Télécommande du lecteur live, style télé (choix de l'utilisateur) : ▲ ▼ zappent entre les suivis en live (sinon les lives populaires), ◀ ouvre la
  liste des lives (`LiveList`, suivis sinon top), ▶ affiche le chat puis va sur son bouton « Écrire »
  (compte connecté ; ◀ revient à la vidéo, ▶ masque le chat), OK ouvre la barre d'actions (Chat, Qualité, Son seul, Mise en veille, Multi-view, Incrustation si `FEATURE_PICTURE_IN_PICTURE`,
  Page de la chaîne, Statistiques). Qualité choisie retenue par chaîne (`quality:<login>` dans `settings`). L'aide des touches reste affichée dans le panneau d'infos. La saisie ne s'ouvre que par OK sur « Écrire ».

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
  Envoi Helix validé par l'utilisateur. Chat masqué par défaut dans le lecteur.
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
  Mise à jour : release GitHub `Limoniak/streamsofa-tv` plus récente → APK de l'ABI de l'appareil
  (`streamsofa-<version>-<abi>.apk`, sinon universel ; seul le suffixe `-<abi>.apk` compte), téléchargé par Rust, installé via FileProvider.
  Jamais proposée automatiquement sur une build de debug (signature différente). Option « Versions bêta » : liste des
  releases avec pré-versions (ordre semver, tag `vX.Y.Z-beta.N` publié en pré-version par la CI).
  Journal de crash local en option (`CrashLog`, `filesDir/crash.log`, 256 Ko), export MediaStore dans Téléchargements (API 29+).
  CI GitHub Actions : tests Rust + Android à chaque push, release signée sur tag `vX.Y.Z`
  (version issue du tag ; secrets décrits dans le README). Baseline Profile : module `:baselineprofile`
  + profileinstaller. Keystore de release : à créer par l'utilisateur (README), jamais généré ici.
- [x] J8 : parité avec l'app officielle. Validé sur l'émulateur sauf les raids (aucun raid déclenchable).
  Recherche (onglet en tête de barre latérale ; Helix connecté, GQL `searchFor` en invité). Recommandations
  (connecté) : top 3 des catégories des suivis en live, dans leur langue dominante, hors suivis
  (`recommendation_seeds`). Filtre de langue des catégories (préférence `stream_language`). Page chaîne :
  planning à venir, clips (semaine puis tout temps, lecteur MP4 `clip/{slug}`), panneaux « À propos ».
  Raids : EventSub WebSocket `channel.raid` (compte connecté seulement), bandeau 10 s, OK = suivre, Retour = rester.
  Intégration TV : liens `streamsofa://` et twitch.tv (`singleTask`, `addOnNewIntentListener`), « Continuer à regarder »
  via `TvContract.WatchNextPrograms` (publié en quittant une VOD, retiré une fois finie, jamais remis si l'utilisateur
  l'a retiré), recherche globale/vocale via `SearchProvider` + `searchable.xml`. Google TV peut ignorer les deux
  pour une app hors Play Store. Suivre/ne plus suivre : impossible, Twitch a retiré les endpoints de l'API (27 juillet 2021) ;
  le contourner via GQL exigerait un jeton web et une vérification d'intégrité, exclu.

- Lecteur VOD : ▲ fait défiler la vitesse, chapitres GQL `moments(VIDEO_CHAPTER_MARKERS)` marqués sur la barre.
  Live terminé ou chaîne hors ligne (`LoadError.Offline`) : écran de fin (raid ignoré, live suivant, dernière VOD).
  Multi-view : appui long OK = menu Remplacer / Retirer / Chat (lecture seule, suit la tuile qui a le son ; jamais en enfants).

- Tests à la télécommande : `PlayerScreen`, `VodPlayerScreen`, `MultiViewScreen` (sans ViewModel ni ExoPlayer,
  actions derrière `PlayerActions` / `VodActions` / `MultiActions`, vidéo passée en lambda) montés sous Robolectric dans
  `PlayerRemoteTest`, `VodRemoteTest`, `MultiViewRemoteTest` ; tournent avec les tests unitaires, sans émulateur.
  Un dialogue (menu de tuile) est une seconde fenêtre : y envoyer les touches via `onAllNodes(isRoot()).onLast()`.
  Benchmarks (`:baselineprofile`, `Benchmarks.kt`) et régénération du Baseline Profile : workflow `benchmarks.yml`
  (émulateur Android TV x86_64, lundi ou à la demande, PR depuis `ci/baseline-profile`). Jamais exécuté avant le premier run en CI.

- Épinglées (`pinned_channels`), regardées récemment (`recent_channels`, 20 max, écrit quand un live démarre),
  catégories masquées (`hidden_categories`, lignes `id\tnom`) : préférences du profil, listes séparées par `\n`.
  L'ordre « épinglées d'abord » est appliqué une seule fois, dans `RustCatalogRepository.followed` (accueil, Suivis,
  zapping, liste ◀, multi-view, rangée launcher). « Regardé récemment » : 10 `liveStream` en parallèle à chaque
  chargement de l'accueil, hors suivis en live. Une catégorie masquée retire aussi ses lives de Populaires et
  Recommandés, jamais des suivis.
- Accueil personnalisable : `HomeLayout.kt` (`HomeRow`, préférence `home_rows` = `followed,-recent,…`, `-` = masquée ;
  clé inconnue ignorée, rangée nouvelle ajoutée à la fin, visible). Seules les rangées visibles sont chargées.
  Catégories favorites (`favorite_categories`, `id\tnom`, appui long sur une catégorie → Ajouter / Masquer), une rangée
  chacune (`categoryStreams` + filtre de langue). « Continuer à regarder » : `continue_watching` (Rust, `vod_progress`
  non finies, 10 au plus). « Pourrait vous plaire » : 2 dernières `recent_categories` (écrites par le lecteur), dans la
  langue de l'interface, hors suivis et regardés. « Les plus regardés dans votre langue » : `language_streams` (Helix
  `language`, GQL `streams(options: {languages})`). Paramètres > Accueil : OK = afficher/masquer, ◀ ▶ = déplacer.
- Temps d'écran enfants : `kids_daily_minutes` et `kids_extra` (`jour\tminutes`) protégés comme `kids_profile`
  (`set_kids_limit`, `grant_kids_time`, refusés par `set_setting`), réglés derrière le PIN parent dans les options du profil.
  Consommé : `screen_time` (`jour\tsecondes`), +30 s par tranche jouée (`ScreenTimeGuard`, lecteurs live et VOD ; pas les clips).
- Volume constant (`constant_volume`, désactivé par défaut) : `AudioLeveler`, `DynamicsProcessing` (API 28+) sur la session
  audio du lecteur live, une bande de compression (−28 dB, 4:1, +9 dB) et un limiteur ; refusé par l'appareil = sans effet.
  Non écouté sur un vrai appareil.
- Bandeau « X est en live » : le lecteur live relit `followedStreams()` toutes les 2 min (compte connecté),
  `newlyLive()` compare avec le relevé précédent ; 10 s, OK = y aller, Retour = rester, un raid passe avant.
- « Depuis le début » : GQL `user.stream.archiveVideo` (`live_archive`), lu après le démarrage ; ouvre le lecteur VOD.
  L'archive en cours est une playlist HLS `EVENT` : départ explicite à 0 et vitesse figée à 1× (`LiveConfiguration`).
- Zapping rapide : `PlaybackCache` (`Prefetch.kt`) garde le jeton des chaînes de ▲ et ▼, préparé après `STATE_READY`,
  servi une seule fois, valable jusqu'au plus tôt de 5 min et de l'expiration du jeton (`Playback.expires_at`, champ
  `expires` du JSON signé, 0 si absent) moins 1 min ; renouvelé à mi-vie (30 s à 4 min). Une relance (`retried`) en redemande un.
  Tampon du live : 1,5 s avant la première image, 3 s après une coupure (`liveLoadControl`).
- Chat : `LazyColumn` à clés (id, doublons écartés) + `requestScrollToItem(0)` à chaque nouveau message (`ChatPanelTest`).
- Sauvegarde : `allowBackup=false` + `data_extraction_rules.xml` (rien en cloud ni en transfert d'appareil, Android 12+).
  `SearchProvider` protégé par `readPermission` `GLOBAL_SEARCH`.
- Clippy : zéro remarque, `-D warnings` en CI (`ChatEvent` garde `allow(large_enum_variant)` : pas de `Box` en UniFFI).
- Lint : zéro erreur dans tous les modules, vérifié en CI (étape « Lint Android ») ; API Media3 instable derrière
  `@OptIn(UnstableApi::class)` (`newLivePlayer`, `liveLoadControl`), textes lus au clic via `LocalResources`.
- APK : `localeFilters` = les six langues (écrites comme les dossiers, `pt-rBR`) ; `TranslationsTest` vérifie l'accord
  avec `locales_config.xml`.

Schéma SQLite (migrations dans `store/mod.rs`, clé `scope` = `global` | `guest` | id de compte) :
`vod_progress` (migration 2), `settings` (migration 3, portée `global` pour les réglages de l'appareil, sinon le profil ; filtres du chat dans `chat_hidden_words`/`chat_hidden_users`), `meta_cache(key,body,fetched_at,ttl_s)` (migration 4 :
badges et émotes, 24 h pour les globaux, 1 h par chaîne). Migration 5 : réglages copiés de `global` vers chaque profil.

## Commandes

```bash
cd rust && cargo test
cd android && ./gradlew :core-bridge:testDebugUnitTest :data:testDebugUnitTest :feature:auth:testDebugUnitTest :feature:home:testDebugUnitTest :feature:player:testDebugUnitTest :designsystem:testDebugUnitTest
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
