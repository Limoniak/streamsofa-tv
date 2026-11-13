package dev.lumen.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HelloUiState {
    data object Loading : HelloUiState
    data class Ready(val diagnostics: CoreDiagnostics) : HelloUiState
    data class Failed(val message: String) : HelloUiState
}

class HelloViewModel(private val gateway: CoreGateway) : ViewModel() {
    private val _state = MutableStateFlow<HelloUiState>(HelloUiState.Loading)
    val state: StateFlow<HelloUiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        runDiagnostics()
    }

    fun runDiagnostics() {
        job?.cancel()
        _state.value = HelloUiState.Loading
        job = viewModelScope.launch {
            _state.value = try {
                HelloUiState.Ready(gateway.diagnostics())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable : un .so absent pour l'ABI lève UnsatisfiedLinkError, pas une Exception.
                HelloUiState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        fun factory(gateway: CoreGateway): ViewModelProvider.Factory = viewModelFactory {
            initializer { HelloViewModel(gateway) }
        }
    }
}
