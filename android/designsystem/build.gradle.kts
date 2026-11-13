plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.lumen.designsystem"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.tv.material)
    // Images des cartes. Le module réseau s'enregistre seul auprès de Coil.
    api(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)

    testImplementation(libs.junit)
}
