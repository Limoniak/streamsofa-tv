# État des lieux des performances (base de mesure)

Relevé le 16 novembre 2026 sur le commit `ff43ea8` (0.5.1 + optimisations d'interface du 15 novembre).
Tout est mesuré dans le conteneur de build (x86_64, 4 cœurs, 15 Go) : aucune mesure sur appareil.
Les temps CPU sont donc ceux d'un PC ; sur une box ARM modeste, compter 5 à 10 fois plus.

## 1. Structure

| Élément | État |
| --- | --- |
| Workspace Cargo | `rust/` : `core` (`lumen-core`, 7 200 lignes) et `uniffi-bindgen` (outil de build) |
| Pont Rust ↔ Android | UniFFI 0.32 (proc-macros), chargé par JNA côté Kotlin ; `api.rs` seule surface publique |
| Runtime async | tokio multi-thread, créé une seule fois par UniFFI (`async_runtime = "tokio"`, via `async-compat`) |
| Réseau | un seul `reqwest::Client` partagé (pool, keep-alive, HTTP/2), rustls + ring ; WebSocket `tokio-tungstenite` |
| Stockage | `rusqlite` avec SQLite embarqué (`bundled`), WAL, `synchronous = NORMAL`, une connexion derrière un `Mutex` |
| Journalisation | aucune (`log`/`tracing` absents) |
| Build | `cargo-ndk` 4.1.2, NDK 29, 3 ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`), lancé par Gradle (`:core-bridge`) |
| Android | R8 (`isMinifyEnabled`), `isShrinkResources`, un APK par ABI + un universel, 6 langues (`localeFilters`) |

Profil release actuel (`rust/Cargo.toml`) :

```toml
[profile.release]
opt-level = "s"
lto = "thin"
codegen-units = 1
strip = true
```

`panic = "unwind"` (valeur par défaut) est gardé exprès : UniFFI rattrape un panic (`catch_unwind`) et le
transforme en exception Kotlin au lieu de tuer l'application.

## 2. Mesures

### Taille

| Fichier | arm64-v8a | armeabi-v7a | x86_64 |
| --- | ---: | ---: | ---: |
| `liblumen_core.so` | 6 501 944 | 4 559 080 | 7 064 688 |
| `liblumen_core.so` compressé (gzip -9) | 2 879 444 | 2 505 984 | 3 069 696 |
| APK release | 9 530 841 | 7 538 835 | 10 044 424 |

APK universel : 21 464 560 octets.

Composition de l'APK arm64 (octets dans l'archive) :

| Contenu | Taille | Part |
| --- | ---: | ---: |
| `liblumen_core.so` (stocké non compressé) | 6 501 936 | 68 % |
| Code Kotlin/Java (dex, après R8) | 2 525 147 | 26 % |
| `libjnidispatch.so` (JNA) | 165 992 | 2 % |
| `resources.arsc` + `res/` + `assets/` | 185 190 | 2 % |
| Reste (`META-INF`, manifeste, profil) | 95 395 | 1 % |

Sections de `liblumen_core.so` arm64 :

| Section | Taille | Rôle |
| --- | ---: | --- |
| `.text` | 4 125 460 | code |
| `.rodata` | 724 876 | constantes (dont 176 Ko de tables de courbes elliptiques de ring) |
| `.eh_frame` + `.eh_frame_hdr` + `.gcc_except_table` | 1 139 140 | tables de déroulement de pile (panics) |
| `.rela.dyn` + `.data.rel.ro` + `.data` | 467 784 | relocations et données |

Répartition du code par crate (arm64, symboles d'une build non strippée ; `cargo bloat` refuse un crate
`["cdylib", "lib"]`, le relevé est fait avec `llvm-nm`) :

| Crate | Taille | Part |
| --- | ---: | ---: |
| SQLite (C) | 430 593 | 9,4 % |
| `core` + `alloc` + `std` | 837 400 | 18,3 % |
| `serde_json` (décodage générique de chaque réponse) | 374 872 | 8,2 % |
| ring (C/asm + Rust) | 292 155 | 6,4 % |
| `rustls` + `webpki` | 264 970 | 5,8 % |
| `h2` | 214 240 | 4,7 % |
| `lumen_core` (notre code) | 170 952 | 3,7 % |
| `tokio` | 158 100 | 3,5 % |
| `async_compat` (un futur par fonction exportée) | 155 992 | 3,4 % |
| `uniffi_core` | 116 208 | 2,5 % |
| `jni` (tiré par `rustls-platform-verifier`, jamais appelé) | 97 300 | 2,1 % |
| `hyper` + `hyper_util` + `http` + `reqwest` | 270 076 | 5,9 % |
| Autres (symboles C non attribués, petits crates) | ≈ 1 180 000 | 26 % |

### Temps de compilation

| Mesure | Durée |
| --- | ---: |
| `cargo ndk` release, 3 ABI, cible vide | 244 s |
| `./gradlew :app:assembleRelease` complet (Rust en cache partiel) | 325 s |

### Benchmarks criterion (`cargo bench -p lumen-core --features bench --bench hot_paths`)

Profil `bench` = profil release livré. Valeur médiane.

| Benchmark | Temps | Ce que ça représente |
| --- | ---: | --- |
| `chat/trame_20_messages` | 113,0 µs | 20 messages IRC → événements (5,7 µs par message) |
| `chat/trame_20_messages_emotes_tierces` | 128,3 µs | idem avec 400 émotes 7TV/BTTV/FFZ et badges |
| `catalogue/helix_100_lives` | 149,9 µs | décodage JSON d'une page de 100 lives (compte connecté) |
| `catalogue/gql_100_lives` | 178,4 µs | idem en invité (GQL) |
| `hls/playlist_maitresse` | 8,8 µs | playlist maîtresse d'un live (6 qualités) |
| `base/lire_preferences_40` | 18,1 µs | toutes les préférences d'un profil |
| `base/ecrire_preference` | 17,5 µs | une préférence |
| `base/reprise_100_vod` | 194,1 µs | positions de reprise de 100 VOD (une requête par VOD) |
| `base/enregistrer_reprise` | 19,8 µs | position d'une VOD (toutes les quelques secondes en lecture) |
| `base/cache_emotes_400` | 71,8 µs | lecture du cache d'émotes d'une chaîne + décodage |
| `demarrage/core_new` | 160,6 µs | création du cœur : client HTTP, TLS, ouverture de la base |

## 3. Ce que disent les chiffres

- **Le cœur Rust n'est pas un point chaud CPU.** Un salon à 100 messages par seconde coûte 0,6 ms de calcul
  par seconde ici, soit quelques millisecondes sur une box : moins de 1 % d'un cœur. Le démarrage du cœur
  (0,16 ms) est invisible à côté du lancement de l'activité. Le réseau domine partout ailleurs.
- **Le cœur Rust est le poids de l'APK** : 68 % de l'APK arm64. C'est le levier principal côté Rust.
- **La fluidité se joue côté Compose** (mesure de l'utilisateur sur Mi Box 4 : 26 ms par image au défilement),
  pas côté Rust ; voir la section Performance de `ROADMAP.md`.
- Déjà en place, rien à faire : client HTTP unique, runtime unique, aucun `block_on`, aucun log, R8, APK par ABI,
  ressources filtrées, cœur créé paresseusement hors du thread principal (`CoreHolder`).

## 4. Pistes, par priorité

Règle : un gain inférieur à 3 % ou non mesurable est annulé.

| # | Piste | Gain estimé | Risque | Effort |
| --- | --- | --- | --- | --- |
| 1 | Profil release : comparer `opt-level` `"s"` / `"z"` / `3` et `lto` `thin` / `fat` (taille et benchmarks) | −5 à −15 % sur le `.so` | Faible (réglage, tests inchangés) | Faible |
| 2 | Reprise des VOD : une requête SQL pour toute la page au lieu d'une par VOD | −50 % ou plus sur `reprise_100_vod` | Faible | Faible |
| 3 | Chat : lignes IRC sans `HashMap<String, String>` par message (tags empruntés à la ligne) | −30 % ou plus sur `chat/*` | Faible (parseur couvert par les tests) | Moyen |
| 4 | Playlist HLS : attributs empruntés au lieu de `HashMap<String, String>` par ligne | −30 % sur `hls/*` (8,8 µs, une fois par zapping) | Faible | Faible |
| 5 | SQLite embarqué : retirer à la compilation les modules inutilisés (`SQLITE_OMIT_*`) | −50 à −150 Ko | Moyen (options de compilation de SQLite) | Moyen |
| 6 | Dépendances : features par défaut inutiles (`tokio`, `rusqlite`, `qrcode`), doublons (`getrandom`) | −0 à −100 Ko | Faible | Faible |
| 7 | Décodage JSON du catalogue : champs empruntés (`&str`) avant conversion | −10 à −20 % sur `catalogue/*` | Faible | Moyen |

Pistes écartées dès maintenant :

| Piste | Pourquoi |
| --- | --- |
| `panic = "abort"` (−1,1 Mo, soit −17 % du `.so`) | Change le comportement : UniFFI s'appuie sur `catch_unwind` pour transformer un panic en exception Kotlin ; avec `abort`, un panic tue l'application. |
| Retirer `rustls-platform-verifier` et `jni` (~130 Ko, jamais appelés) | Dépendance non optionnelle de reqwest 0.13 dès que rustls est activé ; il faudrait remplacer reqwest. |
| Retirer HTTP/2 (`h2`, 214 Ko) | Change le comportement réseau : plus de connexions vers Helix et GQL, chargement de l'accueil plus lent. |
| Compresser les `.so` dans l'APK (`useLegacyPackaging`) | APK plus léger à télécharger (−3,6 Mo) mais +2,9 Mo occupés sur la box après installation, et chargement plus lent. |
| Nombre de threads tokio | Les threads inactifs sont endormis ; aucun coût mesurable ici, et le runtime appartient à UniFFI. |
| `opt-level = 3` pour la vitesse | À mesurer dans la piste 1, mais le CPU n'est pas le facteur limitant : seule une forte hausse des benchmarks sans hausse de taille le justifierait. |
| Niveaux de log compilés hors release | Le cœur ne journalise rien. |

## 5. Profilage sur appareil

Non fait : aucune box n'est reliée à cet environnement. Les commandes sont dans `PERF_REPORT.md` (livrable final)
pour qui voudra vérifier sur un vrai appareil.
