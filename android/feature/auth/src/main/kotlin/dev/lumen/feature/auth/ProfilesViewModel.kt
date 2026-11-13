package dev.lumen.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lumen.core.Account
import dev.lumen.core.AccountState
import dev.lumen.core.AccountsSnapshot
import dev.lumen.data.AccountsRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ProfilesEvent {
    /** Un profil utilisable (ou l'invité) est actif : on peut entrer dans l'app. */
    data object Enter : ProfilesEvent

    /** Le compte choisi doit d'abord repasser par la connexion. */
    data object Reconnect : ProfilesEvent
}

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val accounts: AccountsRepository,
) : ViewModel() {
    /** `null` pendant le démarrage du cœur. */
    val snapshot: StateFlow<AccountsSnapshot?> =
        accounts.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), accounts.snapshot.value)

    private val _events = Channel<ProfilesEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun select(account: Account) = launchSafely {
        accounts.setActive(account.id)
        _events.send(if (account.state == AccountState.OK) ProfilesEvent.Enter else ProfilesEvent.Reconnect)
    }

    fun continueAsGuest() = launchSafely {
        accounts.setActive(null)
        _events.send(ProfilesEvent.Enter)
    }

    fun remove(account: Account) = launchSafely { accounts.remove(account.id) }

    // --- PIN et mode enfants (`profileId` `null` = l'invité) ------------------------------

    /** Un échec de lecture vaut un PIN refusé. */
    suspend fun checkPin(profileId: String?, pin: String): Boolean =
        runCatching { accounts.checkPin(profileId, pin) }.getOrDefault(false)

    /** PIN d'un profil adulte (protégé et hors mode enfants) : lui seul lève le mode enfants. */
    suspend fun checkParentPin(pin: String): Boolean =
        snapshot.value?.locks.orEmpty().filter { it.hasPin && !it.kids }.any { checkPin(it.profileId, pin) }

    fun setPin(profileId: String?, pin: String?) = launchSafely { accounts.setPin(profileId, pin) }

    fun setKids(profileId: String?, kids: Boolean) = launchSafely { accounts.setKids(profileId, kids) }

    fun resetGuest() = launchSafely { accounts.resetGuest() }

    // Un échec ici (compte retiré entre-temps, base occupée) laisse simplement l'écran tel quel :
    // la liste affichée vient du dépôt et reste la vérité.
    private fun launchSafely(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }
}
