package dev.lumen.tv

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lumen.data.AccountsRepository
import dev.lumen.feature.auth.DeviceLoginRoute
import dev.lumen.feature.auth.ProfilesRoute
import dev.lumen.feature.home.CategoryRoute
import dev.lumen.feature.home.ChannelRoute
import dev.lumen.feature.home.FollowingRoute
import dev.lumen.feature.home.HomeRoute
import dev.lumen.feature.player.MultiViewRoute
import dev.lumen.feature.player.PlayerRoute
import dev.lumen.feature.player.VodPlayerRoute
import dev.lumen.feature.settings.SettingsRoute
import dev.lumen.feature.settings.UpdatePrompt
import javax.inject.Inject

private object Routes {
    const val PROFILES = "profiles"
    const val LOGIN = "login"
    const val HOME = "home"
    const val FOLLOWING = "following"
    const val CATEGORY = "category/{id}?name={name}"
    const val LIVE = "live/{login}"
    const val CHANNEL = "channel/{login}"
    const val VOD = "vod/{id}"
    const val MULTI = "multi/{logins}"
    const val SETTINGS = "settings"

    fun category(id: String, name: String) = "category/${Uri.encode(id)}?name=${Uri.encode(name)}"
    fun live(login: String) = "live/${Uri.encode(login)}"
    fun channel(login: String) = "channel/${Uri.encode(login)}"
    fun vod(id: String) = "vod/${Uri.encode(id)}"
    fun multi(logins: List<String>) = "multi/${Uri.encode(logins.joinToString(","))}"
}

@HiltViewModel
class SessionViewModel @Inject constructor(accounts: AccountsRepository) : ViewModel() {
    val snapshot = accounts.snapshot
}

@Composable
fun LumenNavHost(session: SessionViewModel = hiltViewModel()) {
    val snapshot by session.snapshot.collectAsStateWithLifecycle()
    val current = snapshot
    if (current == null) {
        // Le cœur démarre (quelques dizaines de ms) : même fond que la fenêtre, aucun flash.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    val navController = rememberNavController()
    // Décidé une seule fois : un compte actif entre directement, sinon on choisit un profil.
    val start = remember { if (current.activeId != null) Routes.HOME else Routes.PROFILES }

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.PROFILES) {
            ProfilesRoute(
                onEnter = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
                onAddAccount = { navController.navigate(Routes.LOGIN) },
            )
        }
        composable(Routes.LOGIN) {
            DeviceLoginRoute(
                onDone = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(navController.graph.id) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.HOME) {
            Shell(ShellTab.Home, onTab = navController::openTab) {
                HomeRoute(
                    onOpenStream = navController::openLive,
                    onOpenChannel = navController::openChannel,
                    onOpenCategory = { id, name -> navController.navigate(Routes.category(id, name)) },
                    onReconnect = { navController.navigate(Routes.LOGIN) },
                )
            }
            UpdatePrompt()
        }
        composable(Routes.SETTINGS) {
            Shell(ShellTab.Settings, onTab = navController::openTab) { SettingsRoute() }
        }
        composable(Routes.FOLLOWING) {
            Shell(ShellTab.Following, onTab = navController::openTab) {
                FollowingRoute(
                    onOpenStream = navController::openLive,
                    onOpenChannel = navController::openChannel,
                    onSignIn = { navController.navigate(Routes.PROFILES) },
                )
            }
        }
        composable(Routes.CATEGORY) {
            Shell(selected = null, onTab = navController::openTab) {
                CategoryRoute(onOpenStream = navController::openLive, onOpenChannel = navController::openChannel)
            }
        }
        composable(Routes.CHANNEL) {
            Shell(selected = null, onTab = navController::openTab) {
                ChannelRoute(
                    onOpenStream = navController::openLive,
                    onOpenVideo = { navController.navigate(Routes.vod(it)) },
                )
            }
        }
        composable(Routes.LIVE) {
            PlayerRoute(onMultiView = { navController.navigate(Routes.multi(it)) })
        }
        composable(Routes.MULTI) {
            MultiViewRoute(
                // Plein écran : la tuile remplace le multi-view et le lecteur d'où il a été lancé.
                onOpenSingle = { login ->
                    navController.navigate(Routes.live(login)) {
                        popUpTo(Routes.LIVE) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.VOD) { VodPlayerRoute() }
    }
}

private fun NavController.openLive(login: String) = navigate(Routes.live(login))

private fun NavController.openChannel(login: String) = navigate(Routes.channel(login))

/** Les onglets ne s'empilent pas : Retour depuis « Suivis » ramène à l'accueil. */
private fun NavController.openTab(tab: ShellTab) {
    when (tab) {
        ShellTab.Profiles -> navigate(Routes.PROFILES)
        ShellTab.Home -> navigate(Routes.HOME) {
            popUpTo(Routes.HOME) { inclusive = false }
            launchSingleTop = true
        }
        ShellTab.Following -> navigate(Routes.FOLLOWING) {
            popUpTo(Routes.HOME)
            launchSingleTop = true
        }
        ShellTab.Settings -> navigate(Routes.SETTINGS) {
            popUpTo(Routes.HOME)
            launchSingleTop = true
        }
    }
}
