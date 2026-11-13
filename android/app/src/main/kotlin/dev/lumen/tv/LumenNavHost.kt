package dev.lumen.tv

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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lumen.data.AccountsRepository
import dev.lumen.feature.auth.DeviceLoginRoute
import dev.lumen.feature.auth.ProfilesRoute
import javax.inject.Inject

private object Routes {
    const val PROFILES = "profiles"
    const val LOGIN = "login"
    const val HOME = "home"
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
            // Lu ici, et non capturé depuis l'extérieur : le graphe de navigation ne doit pas
            // être reconstruit à chaque changement de compte.
            val latest by session.snapshot.collectAsStateWithLifecycle()
            HomePlaceholder(
                snapshot = latest ?: return@composable,
                onSwitchProfile = { navController.navigate(Routes.PROFILES) },
                onReconnect = { navController.navigate(Routes.LOGIN) },
            )
        }
    }
}
