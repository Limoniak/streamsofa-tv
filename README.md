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

## Publier une version

Les APK sont publiés sur les GitHub Releases ; l'app les propose en mise à jour (Paramètres).
La CI (`.github/workflows/ci.yml`) teste chaque push et publie une release signée pour chaque tag `vX.Y.Z`.

1. Créer une seule fois la clé de release, **à conserver précieusement** : sans elle, plus aucune
   mise à jour ne pourra s'installer par-dessus les versions déjà distribuées.

   ```bash
   keytool -genkeypair -v -keystore lumen-release.jks -alias lumen -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Renseigner les secrets du dépôt (Settings → Secrets and variables → Actions) :
   `TWITCH_CLIENT_ID`, `LUMEN_KEYSTORE_BASE64` (sortie de `base64 -w0 lumen-release.jks`),
   `LUMEN_KEYSTORE_PASSWORD`, `LUMEN_KEY_ALIAS`, `LUMEN_KEY_PASSWORD`.
3. Taguer : `git tag v0.2.0 && git push origin v0.2.0`. La version de l'app vient du tag.

En local, un `android/keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`)
signe `assembleRelease` avec la même clé ; sans lui, la release est signée avec la clé de debug.

Baseline Profile (démarrage et défilement plus fluides), à régénérer quand l'UI change beaucoup,
émulateur ou box branché. Attention : l'app est désinstallée à la fin (comptes et reprises perdus
sur cet appareil). Avec plusieurs appareils branchés, choisir la cible via `ANDROID_SERIAL`.

```bash
cd android && ./gradlew :app:generateBaselineProfile -Plumen.abis=x86_64
```
