import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.android.library)
}

// --- Cœur Rust --------------------------------------------------------------------------
// Deux produits, tous deux générés dans build/ (rien de généré n'est versionné) :
//   1. liblumen_core.so par ABI, via cargo-ndk        -> build/rustJniLibs
//   2. les bindings Kotlin UniFFI, via uniffi-bindgen -> build/generated/uniffi

val rustDir = rootProject.layout.projectDirectory.dir("../rust")
val abis = providers.gradleProperty("lumen.abis").get().split(",").map { it.trim() }
val rustProfile = providers.gradleProperty("lumen.rustProfile").get()
val minSdkVersion = 26
val pinnedNdk = "29.0.14206865"

val jniLibsDir = layout.buildDirectory.dir("rustJniLibs")
val bindingsDir = layout.buildDirectory.dir("generated/uniffi")
val ndkDir = androidComponents.sdkComponents.sdkDirectory.map { it.dir("ndk/$pinnedNdk") }

val rustSources = fileTree(rustDir) {
    include("Cargo.toml", "Cargo.lock", "core/**", "uniffi-bindgen/**")
}

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "rust"
    description = "Compile liblumen_core.so pour chaque ABI Android."
    inputs.files(rustSources)
    inputs.property("abis", abis)
    inputs.property("profile", rustProfile)
    outputs.dir(jniLibsDir)
    workingDir = rustDir.asFile
    val ndk = ndkDir
    val out = jniLibsDir
    val args = buildList {
        addAll(listOf("cargo", "ndk"))
        abis.forEach { addAll(listOf("-t", it)) }
        addAll(listOf("--platform", minSdkVersion.toString()))
        addAll(listOf("-o", out.get().asFile.absolutePath))
        addAll(listOf("build", "-p", "lumen-core", "--locked"))
        if (rustProfile == "release") add("--release")
    }
    commandLine(args)
    doFirst { environment("ANDROID_NDK_HOME", ndk.get().asFile.absolutePath) }
}

// Les bindings sont lus dans les métadonnées d'une build hôte : pas besoin du NDK.
val hostLibName = OperatingSystem.current().let {
    when {
        it.isWindows -> "lumen_core.dll"
        it.isMacOsX -> "liblumen_core.dylib"
        else -> "liblumen_core.so"
    }
}

val cargoHostBuild = tasks.register<Exec>("cargoHostBuild") {
    group = "rust"
    inputs.files(rustSources)
    outputs.file(rustDir.file("target/debug/$hostLibName"))
    workingDir = rustDir.asFile
    commandLine("cargo", "build", "-p", "lumen-core", "--locked")
}

val generateUniffiBindings = tasks.register<Exec>("generateUniffiBindings") {
    group = "rust"
    description = "Génère les bindings Kotlin du crate core."
    dependsOn(cargoHostBuild)
    inputs.files(rustSources)
    outputs.dir(bindingsDir)
    workingDir = rustDir.asFile
    commandLine(
        "cargo", "run", "--quiet", "--locked", "-p", "uniffi-bindgen", "--",
        "generate", "target/debug/$hostLibName",
        "--language", "kotlin", "--no-format",
        "--out-dir", bindingsDir.get().asFile.absolutePath,
    )
}

android {
    namespace = "dev.lumen.core.bridge"
    compileSdk = 37
    ndkVersion = pinnedNdk

    defaultConfig {
        minSdk = minSdkVersion
        consumerProguardFiles("consumer-rules.pro")
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories.add(jniLibsDir.get().asFile.absolutePath)
            kotlin.directories.add(bindingsDir.get().asFile.absolutePath)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild, generateUniffiBindings)
}

dependencies {
    // UniFFI charge la lib native via JNA ; la variante AAR embarque libjnidispatch par ABI.
    api(libs.jna) { artifact { type = "aar" } }
    api(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Le JAR (et non l'AAR) de JNA contient libjnidispatch pour la machine de build.
    testRuntimeOnly(libs.jna)
}

// Les tests JVM chargent la build hôte du cœur : ils exercent le vrai pont Kotlin <-> Rust
// (appels async, callbacks, erreurs) sans appareil ni émulateur.
tasks.withType<Test>().configureEach {
    dependsOn(cargoHostBuild)
    inputs.files(rustSources)
    systemProperty("jna.library.path", rustDir.dir("target/debug").asFile.absolutePath)
}
