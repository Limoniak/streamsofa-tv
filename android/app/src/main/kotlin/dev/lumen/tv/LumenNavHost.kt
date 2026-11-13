package dev.lumen.tv

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.core.util.Consumer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import androidx.tv.material3.MaterialTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lumen.core.AccountState
import dev.lumen.data.AccountsRepository
import dev.lumen.feature.auth.DeviceLoginRoute
import dev.lumen.feature.auth.ProfilesRoute
import dev.lumen.feature.home.CategoryRoute
import dev.lumen.feature.home.ChannelRoute
import dev.lumen.feature.home.FollowingRoute
import dev.lumen.feature.home.HomeRoute
import dev.lumen.feature.home.SearchRoute
import dev.lumen.feature.player.ClipPlayerRoute
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
    const val SEARCH = "search?q={q}"
    const val CLIP = "clip/{slug}?title={title}"

    fun category(id: String, name: String) = "category/${Uri.encode(id)}?name=${Uri.encode(name)}"
    fun live(login: String) = "live/${Uri.encode(login)}"
    fun channel(login: String) = "channel/${Uri.encode(login)}"
    fun vod(id: String) = "vod/${Uri.encode(id)}"
    fun multi(logins: List<String>) = "multi/${Uri.encode(logins.joinToString(","))}"
    fun clip(slug: String, title: String) = "clip/${Uri.encode(slug)}?title=${Uri.encode(title)}"
}

/** Liens `streamsofa://…` (internes) et twitch.tv (partagés) vers un écran. */
private fun links(vararg patterns: String) = patterns.map { p -> navDeepLink { uriPattern = p } }

private fun twitch(path: String) = arrayOf("https://www.twitch.tv/$path", "https://twitch.tv/$path", "https://m.twitch.tv/$path")

/** La recherche du launcher arrive en ACTION_SEARCH : on la ramène à un lien que la navigation suit. */
fun Intent.asDeepLink(): Intent {
    if (action != Intent.ACTION_SEARCH) return this
    val query = getStringExtra(SearchManager.QUERY).orEmpty()
    return Intent(Intent.ACTION_VIEW, Uri.parse("streamsofa://search?q=${Uri.encode(query)}"))
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
    // App déjà ouverte (singleTask) : un lien du launcher arrive ici plutôt qu'à la création.
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(activity, navController) {
        val listener = Consumer<Intent> { navController.handleDeepLink(it.asDeepLink()) }
        activity?.addOnNewIntentListener(listener)
        onDispose { activity?.removeOnNewIntentListener(listener) }
    }

    val needsReauth = current.accounts.any { it.state == AccountState.NEEDS_REAUTH }
    CompositionLocalProvider(LocalNeedsReauth provides needsReauth) {
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
            composable(
                Routes.SEARCH,
                arguments = listOf(navArgument("q") { defaultValue = "" }),
                deepLinks = links("streamsofa://search?q={q}"),
            ) {
                Shell(ShellTab.Search, onTab = navController::openTab) {
                    SearchRoute(
                        onOpenStream = navController::openLive,
                        onOpenChannel = navController::openChannel,
                        onOpenCategory = { id, name -> navController.navigate(Routes.category(id, name)) },
                    )
                }
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
                        onOpenClip = { slug, title -> navController.navigate(Routes.clip(slug, title)) },
                    )
                }
            }
            composable(Routes.CATEGORY, deepLinks = links("streamsofa://category/{id}?name={name}")) {
                Shell(selected = null, onTab = navController::openTab) {
                    CategoryRoute(
                        onOpenStream = navController::openLive,
                        onOpenChannel = navController::openChannel,
                        onOpenClip = { slug, title -> navController.navigate(Routes.clip(slug, title)) },
                    )
                }
            }
            composable(Routes.CHANNEL, deepLinks = links("streamsofa://channel/{login}")) {
                Shell(selected = null, onTab = navController::openTab) {
                    ChannelRoute(
                        onOpenStream = navController::openLive,
                        onOpenVideo = { navController.navigate(Routes.vod(it)) },
                        onOpenClip = { slug, title -> navController.navigate(Routes.clip(slug, title)) },
                    )
                }
            }
            composable(Routes.CLIP, arguments = listOf(navArgument("title") { defaultValue = "" })) { ClipPlayerRoute() }
            composable(Routes.LIVE, deepLinks = links("streamsofa://live/{login}", *twitch("{login}"))) {
                PlayerRoute(
                    onMultiView = { navController.navigate(Routes.multi(it)) },
                    onOpenChannel = navController::openChannel,
                    onOpenVod = { navController.navigate(Routes.vod(it)) },
                )
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
            composable(Routes.VOD, deepLinks = links("streamsofa://vod/{id}", *twitch("videos/{id}"))) { VodPlayerRoute() }
        }
        }
    }

    private fun NavController.openLive(login: String) = navigate(Routes.live(login))

    private fun NavController.openChannel(login: String) = navigate(Routes.channel(login))

    /** Les onglets ne s'empilent pas : Retour depuis « Suivis » ramène à l'accueil. */
    private fun NavController.openTab(tab: ShellTab) {
        when (tab) {
            ShellTab.Profiles -> navigate(Routes.PROFILES)
            ShellTab.Search -> navigate("search") {
                popUpTo(Routes.HOME)
                launchSingleTop = true
            }
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
