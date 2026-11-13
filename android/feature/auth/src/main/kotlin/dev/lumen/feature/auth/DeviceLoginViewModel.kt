package dev.lumen.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lumen.core.CoreException
import dev.lumen.core.QrMatrix
import dev.lumen.data.AccountsRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LoginError { Expired, Network, NotConfigured, Storage, Other }

sealed interface DeviceLoginUiState {
    data object Requesting : DeviceLoginUiState
    data class WaitingForUser(val userCode: String, val qr: QrMatrix) : DeviceLoginUiState
    data class Success(val displayName: String) : DeviceLoginUiState
    data class Failed(val error: LoginError) : DeviceLoginUiState
}

@HiltViewModel
class DeviceLoginViewModel @Inject constructor(
    private val accounts: AccountsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<DeviceLoginUiState>(DeviceLoginUiState.Requesting)
    val state: StateFlow<DeviceLoginUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        start()
    }

    /** Demande un code puis attend sa validation. Quitter l'écran annule l'attente côté Rust. */
    fun start() {
        job?.cancel()
        _state.value = DeviceLoginUiState.Requesting
        job = viewModelScope.launch {
            _state.value = try {
                val login = accounts.startDeviceLogin()
                _state.value = DeviceLoginUiState.WaitingForUser(login.userCode, login.qr)
                val account = accounts.completeDeviceLogin(login.userCode)
                DeviceLoginUiState.Success(account.displayName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CoreException) {
                DeviceLoginUiState.Failed(
                    when (e) {
                        is CoreException.DeviceCodeExpired -> LoginError.Expired
                        is CoreException.Network -> LoginError.Network
                        is CoreException.NotConfigured -> LoginError.NotConfigured
                        is CoreException.SecretStore, is CoreException.Storage -> LoginError.Storage
                        else -> LoginError.Other
                    },
                )
            } catch (e: Throwable) {
                // Y compris UnsatisfiedLinkError si le .so manque pour cette ABI.
                DeviceLoginUiState.Failed(LoginError.Other)
            }
        }
    }
}
