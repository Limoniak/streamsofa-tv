import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

fun loadProperties(name: String) = Properties().apply {
    val file = rootProject.file(name)
    if (file.exists()) file.inputStream().use(::load)
}

val localProperties = loadProperties("local.properties")
// keystore.properties (non versionné) : storeFile, storePassword, keyAlias, keyPassword.
val keystoreProperties = loadProperties("keystore.properties")

// Version publiée : la CI la passe depuis le tag (`-Plumen.versionName=1.2.3`).
val appVersionName = providers.gradleProperty("lumen.versionName").getOrElse("0.1.0")
// 1.2.3 -> 10203 : toujours croissant tant que mineur et correctif restent sous 100.
val appVersionCode = appVersionName.substringBefore('-').split('.').map { it.toInt() }
    .let { (it + listOf(0, 0, 0)).take(3) }
    .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

android {
    namespace = "dev.lumen.tv"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.lumen.tv"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField(
            "String",
            "TWITCH_CLIENT_ID",
            "\"${localProperties.getProperty("twitch.clientId", "").trim()}\"",
        )
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sans keystore de release configuré, on signe avec la clé de debug pour que
            // l'APK reste installable en local. La CI (J7) fournira la vraie clé.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // Un APK par ABI (léger, pour les box) + un APK universel.
    splits {
        abi {
            isEnable = true
            reset()
            include(*providers.gradleProperty("lumen.abis").get().split(",").map { it.trim() }.toTypedArray())
            isUniversalApk = true
        }
    }

    // JNA embarque libjnidispatch pour des ABI sans cœur Rust : sur un appareil x86 32 bits,
    // l'APK universel s'installerait puis planterait. On ne livre que les ABI réellement compilées.
    packaging {
        jniLibs {
            excludes += listOf("armeabi", "mips", "mips64", "x86").map { "lib/$it/**" }
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":data"))
    implementation(project(":designsystem"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:home"))
    implementation(project(":feature:player"))
    implementation(project(":feature:settings"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    // Installe le Baseline Profile au premier lancement d'un APK sideloadé (pas de Play Store).
    implementation(libs.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
