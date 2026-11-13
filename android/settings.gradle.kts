pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            // Le marqueur de plugin de Hilt 2.60.1 n'est publié nulle part : on vise l'artefact directement.
            if (requested.id.id == "com.google.dagger.hilt.android") {
                useModule("com.google.dagger:hilt-android-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lumen"

include(
    ":app",
    ":baselineprofile",
    ":core-bridge",
    ":data",
    ":designsystem",
    ":feature:auth",
    ":feature:home",
    ":feature:player",
    ":feature:settings",
)
