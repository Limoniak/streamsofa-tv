# Lumen

Client Twitch non officiel pour Android TV / Google TV. Aucune affiliation avec Twitch.

- `rust/` — cœur métier (`lumen-core`), exposé à Kotlin via UniFFI.
- `android/` — application (Jetpack Compose for TV). `core-bridge` compile le cœur Rust
  et génère ses bindings à chaque build.

## Prérequis

- JDK 21, SDK Android (plateforme 37), NDK `29.0.14206865`
- Rust stable, puis :

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

```bash
cargo install cargo-ndk --locked
```

Copier `android/local.properties.example` en `android/local.properties` et le compléter.

## Compiler et installer

```bash
cd android && ./gradlew :app:assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

Pour itérer plus vite sur une seule box : `./gradlew :app:assembleDebug -Plumen.abis=arm64-v8a`.

## Tests

```bash
cd rust && cargo test
```

```bash
cd android && ./gradlew :core-bridge:testDebugUnitTest :app:testDebugUnitTest
```

Les tests de `core-bridge` chargent le cœur Rust compilé pour la machine de build : ils
vérifient le pont Kotlin ⇄ Rust sans appareil.
