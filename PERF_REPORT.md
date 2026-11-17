# Rapport d'optimisation du cœur Rust et de l'APK

Travail des 16 et 17 novembre 2026, de `174c051` (état des lieux, `PERF_BASELINE.md`) à `06550ce`.
Tout est mesuré dans le conteneur de build (x86_64, 4 cœurs) : **rien n'a été mesuré sur un appareil**.
Les temps sont ceux d'un PC ; les écarts relatifs sont le résultat utile, pas les valeurs absolues.

Le comportement et l'API publique sont inchangés : 95 tests Rust, `cargo clippy -- -D warnings`, et les
tests Android du pont (`:core-bridge`, `:data`, `:feature:player`) passent sur le dernier commit.

## Avant / après

### Taille

| Fichier | Avant | Après | Écart |
| --- | ---: | ---: | ---: |
| `liblumen_core.so` arm64-v8a | 6 501 944 | 6 219 912 | −4,3 % |
| `liblumen_core.so` armeabi-v7a | 4 559 080 | 4 150 568 | −9,0 % |
| `liblumen_core.so` x86_64 | 7 064 688 | 6 976 864 | −1,2 % |
| APK arm64-v8a | 9 530 841 | 9 248 809 | −3,0 % |
| APK armeabi-v7a (Mi Box et box 32 bits) | 7 538 835 | 7 130 323 | −5,4 % |
| APK x86_64 (émulateurs) | 10 044 424 | 9 956 600 | −0,9 % |
| APK universel | 21 464 560 | 20 688 608 | −3,6 % |

### Temps CPU (criterion, médianes, les deux séries sur la même machine)

« Avant » = commit `45b6aed` (ancien profil, ancien code). Le bruit entre deux passes est d'environ ±5 %.

| Benchmark | Avant | Après | Écart |
| --- | ---: | ---: | ---: |
| `chat/trame_20_messages` | 76,5 µs | 32,1 µs | −58 % |
| `chat/trame_20_messages_emotes_tierces` | 89,8 µs | 42,7 µs | −52 % |
| `catalogue/helix_100_lives` | 104,3 µs | 87,5 µs | −16 % |
| `catalogue/gql_100_lives` | 123,9 µs | 101,8 µs | −18 % |
| `hls/playlist_maitresse` | 5,98 µs | 5,43 µs | −9 % |
| `base/reprise_100_vod` | 132,1 µs | 49,7 µs | −62 % |
| `base/cache_emotes_400` | 51,8 µs | 47,9 µs | −8 % |
| `demarrage/core_new` | 125,1 µs | 86,5 µs | −31 % |
| `reseau/top_streams_local` | 343,7 µs | 295,6 µs | −14 % |
| `base/lire_preferences_40` | 12,2 µs | 12,0 µs | dans le bruit |
| `base/ecrire_preference` | 11,7 µs | 12,3 µs | dans le bruit |
| `base/enregistrer_reprise` | 14,1 µs | 15,2 µs | dans le bruit |

Les deux écritures SQLite ont été comparées à part, deux passes par réglage : aucun écart stable.

### Compilation

| Mesure | Avant | Après |
| --- | ---: | ---: |
| `cargo ndk` release, 3 ABI, cible vide | 244 s | 262 s (+7 %) |

## Ce que ça change pour l'utilisateur

- **L'APK est plus léger**, surtout sur les box 32 bits : −408 Ko pour l'APK armv7.
- **Rien de visible sur la fluidité.** Le cœur Rust n'était pas un point chaud : un salon à 100 messages
  par seconde lui coûtait moins de 1 % d'un cœur avant, et moitié moins maintenant. Les gains CPU sont réels
  mais portent sur des microsecondes. La fluidité se joue dans l'interface Compose (section Performance de
  `ROADMAP.md`), pas ici.

## Commits

| Commit | Changement | Gain mesuré |
| --- | --- | --- |
| `ff43ea8`, `174c051`, `45b6aed` | Benchmarks criterion (chat, catalogue, HLS, SQLite, démarrage, réseau local) et état des lieux | outil de mesure |
| `5f3a2c7` | Profil release : notre code en `opt-level = 3`, les dépendances en `"z"` | décodage −8 à −23 %, appel réseau −9 %, taille arm64 −0,2 %, armv7 −3,1 % |
| `d8ccd9e` | Reprise des VOD : une requête SQL pour toute la page au lieu d'une par VOD | `reprise_100_vod` −64 % |
| `f6c01da` | Chat : lignes IRC lues sans copie (tags empruntés à la trame) | `chat/*` −52 à −60 % |
| `06550ce` | SQLite sans FTS3, FTS5, R-Tree, dbstat (`rust/.cargo/config.toml`) | `.so` −3,8 à −6,0 %, compilation −64 s |

Les gains par commit ont été mesurés avant un redémarrage de la machine de build, qui est devenue plus rapide
d'environ un tiers ; le tableau « Avant / après » ci-dessus a été refait d'un bloc après ce redémarrage.

## Pistes écartées

| Piste | Mesure ou raison |
| --- | --- |
| Tout en `opt-level = "z"` | `.so` −6,2 %, mais décodage +40 à +65 % : le pire compromis. |
| `lto = "fat"` | `.so` −0,03 % : rien. |
| Tout en `opt-level = 3` | `.so` +15,9 %. |
| Dépendances en `"z"`, notre code en `"s"` | `.so` −2,6 % : sous le seuil de 3 %, avec la playlist HLS et le cache d'émotes 27 % plus lents. |
| Dépendances en `"s"`, notre code en `3` | Mêmes gains CPU que le réglage retenu, mais `.so` +2,4 %. |
| `panic = "abort"` | −1,1 Mo de tables de déroulement, mais UniFFI s'appuie sur `catch_unwind` : un panic tuerait l'application au lieu de devenir une exception Kotlin. Change le comportement. |
| Autres options de SQLite (`OMIT_DEPRECATED`, `OMIT_SHARED_CACHE`, `OMIT_PROGRESS_CALLBACK`, `OMIT_LOAD_EXTENSION`, `DEFAULT_MEMSTATUS=0`) | −0,7 % de plus : sous le seuil. |
| Retirer `rustls-platform-verifier` et `jni` (~130 Ko jamais appelés) | Dépendance non optionnelle de reqwest 0.13 avec rustls. |
| Retirer HTTP/2 (`h2`, 214 Ko) ou TLS 1.2 | Change le comportement réseau. |
| Playlist HLS et JSON du catalogue avec des champs empruntés | Mesurable, mais 6 µs une fois par zapping et 100 µs une fois par page : aucun effet réel, risque inutile. |
| Features des dépendances | Déjà minimales (`default-features = false` sur reqwest, rustls, qrcode, tungstenite, futures-util). Les doublons (`getrandom`, `syn`) viennent de dépendances transitives. |
| Threads tokio, runtime, client HTTP, logs, frontière FFI | Rien à corriger : un seul runtime (UniFFI), un seul client HTTP partagé, aucun `block_on`, aucun log, préférences en cache côté Kotlin, chat déjà groupé toutes les 100 ms. |
| Compresser les `.so` dans l'APK | APK −3,6 Mo à télécharger mais +2,9 Mo occupés sur la box une fois installé. |
| R8, ABI splits, ressources | Déjà en place (`isMinifyEnabled`, `isShrinkResources`, un APK par ABI, 6 langues). |

## Recommandations restantes

1. **Mesurer sur une vraie box** : aucun des chiffres ci-dessus ne vient d'un appareil ARM. Les commandes sont
   plus bas.
2. **Côté interface** : le code Kotlin (dex) pèse 2,5 Mo, soit 27 % de l'APK arm64, et c'est là que se joue la
   fluidité. Les pistes sont dans `ROADMAP.md` (cartes plus légères pour le GPU, recompositions au défilement).
3. **`serde_json`** représente 8 % du code natif (un décodeur généré par type de réponse). Le réduire
   demanderait de regrouper les structures GQL : gain possible de 100 à 200 Ko, effort élevé, à ne faire que
   si la taille devient une priorité.
4. **Refaire les benchmarks** après toute mise à jour de dépendance majeure :
   `cd rust && cargo bench -p lumen-core --features bench --bench hot_paths`.

## Profilage sur appareil (non fait)

Pour qui voudra vérifier sur une box, avec un APK de debug installé et `adb` connecté.

Mémoire native du processus :

```bash
adb shell dumpsys meminfo dev.streamsofa.tv
```

Trace Perfetto de 15 s (thread d'interface, threads natifs, ordonnanceur), à ouvrir sur ui.perfetto.dev :

```bash
adb shell perfetto -o /data/misc/perfetto-traces/streamsofa.pftrace -t 15s -a dev.streamsofa.tv sched freq gfx view input dalvik
```

```bash
adb pull /data/misc/perfetto-traces/streamsofa.pftrace
```

Échantillonnage CPU avec simpleperf (20 s, piles d'appels), sur une build dont la bibliothèque Rust garde ses
symboles (`CARGO_PROFILE_RELEASE_STRIP=false` au moment du build) :

```bash
adb shell simpleperf record --app dev.streamsofa.tv -g --duration 20 -o /data/local/tmp/perf.data
```

```bash
adb pull /data/local/tmp/perf.data
```

```bash
simpleperf report -i perf.data --symfs android/core-bridge/build/rustJniLibs --sort dso,symbol | head -60
```

`simpleperf` est fourni par le NDK (`ndk/<version>/simpleperf/`). `--app` exige un APK `debuggable` ou
`profileable`.
