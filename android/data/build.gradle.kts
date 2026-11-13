plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.lumen.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    // Les types du cœur (Account, CoreException…) sont le modèle de domaine : pas de recopie.
    api(project(":core-bridge"))
    implementation(libs.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
