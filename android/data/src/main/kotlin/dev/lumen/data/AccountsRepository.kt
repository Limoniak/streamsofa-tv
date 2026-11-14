package dev.lumen.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.lumen.core.Account
import dev.lumen.core.AccountsListener
import dev.lumen.core.AccountsSnapshot
import dev.lumen.core.DeviceLogin
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface AccountsRepository {
    /** `null` tant que le cœur n'a pas fini de démarrer. */
    val snapshot: StateFlow<AccountsSnapshot?>

    suspend fun startDeviceLogin(): DeviceLogin

    /** Suspend jusqu'à la validation du code ; annuler la coroutine arrête l'attente. */
    suspend fun completeDeviceLogin(userCode: String): Account

    /** `null` = mode invité. */
    suspend fun setActive(accountId: String?)

    suspend fun remove(accountId: String)

    // Profils (`null` = l'invité) : PIN à 4 chiffres et mode enfants.
    suspend fun setPin(profileId: String?, pin: String?)

    suspend fun checkPin(profileId: String?, pin: String): Boolean

    suspend fun setKids(profileId: String?, kids: Boolean)

    suspend fun resetGuest()

    /** Temps d'écran quotidien d'un profil enfants, en minutes ; 0 = sans limite. */
    suspend fun kidsLimit(profileId: String?): Int
    suspend fun setKidsLimit(profileId: String?, minutes: Int)
    /** Temps en plus pour aujourd'hui. */
    suspend fun grantKidsTime(profileId: String?, minutes: Int)
}

@Singleton
internal class RustAccountsRepository @Inject constructor(
    private val coreHolder: CoreHolder,
) : AccountsRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _snapshot = MutableStateFlow<AccountsSnapshot?>(null)
    override val snapshot: StateFlow<AccountsSnapshot?> = _snapshot.asStateFlow()

    init {
        scope.launch {
            val core = try {
                coreHolder.get()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Cœur indisponible (.so absent, base illisible) : l'app reste utilisable en invité.
                _snapshot.value = AccountsSnapshot(emptyList(), null, emptyList())
                return@launch
            }
            core.setAccountsListener(object : AccountsListener {
                override fun onAccountsChanged(snapshot: AccountsSnapshot) {
                    _snapshot.value = snapshot
                }
            })
            _snapshot.value = withContext(Dispatchers.IO) { core.accounts() }
            // Twitch demande de valider les jetons au démarrage puis toutes les heures.
            while (true) {
                runCatching { core.validateAccounts() }
                delay(1.hours)
            }
        }
    }

    override suspend fun startDeviceLogin(): DeviceLogin = coreHolder.get().startDeviceLogin()

    override suspend fun completeDeviceLogin(userCode: String): Account =
        coreHolder.get().completeDeviceLogin(userCode)

    override suspend fun setActive(accountId: String?) = withContext(Dispatchers.IO) {
        coreHolder.get().setActiveAccount(accountId)
    }

    override suspend fun remove(accountId: String) = coreHolder.get().removeAccount(accountId)

    // PBKDF2 et SQLite : hors du thread principal.
    override suspend fun setPin(profileId: String?, pin: String?) = withContext(Dispatchers.IO) {
        coreHolder.get().setProfilePin(profileId, pin)
    }

    override suspend fun checkPin(profileId: String?, pin: String) = withContext(Dispatchers.IO) {
        coreHolder.get().checkProfilePin(profileId, pin)
    }

    override suspend fun setKids(profileId: String?, kids: Boolean) = withContext(Dispatchers.IO) {
        coreHolder.get().setKidsProfile(profileId, kids)
    }

    override suspend fun resetGuest() = withContext(Dispatchers.IO) { coreHolder.get().resetGuestProfile() }

    override suspend fun kidsLimit(profileId: String?) = withContext(Dispatchers.IO) { coreHolder.get().kidsLimit(profileId).toInt() }

    override suspend fun setKidsLimit(profileId: String?, minutes: Int) = withContext(Dispatchers.IO) {
        coreHolder.get().setKidsLimit(profileId, minutes.coerceAtLeast(0).toUInt())
    }

    override suspend fun grantKidsTime(profileId: String?, minutes: Int) = withContext(Dispatchers.IO) {
        coreHolder.get().grantKidsTime(profileId, today(), minutes.coerceAtLeast(0).toUInt())
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun accountsRepository(impl: RustAccountsRepository): AccountsRepository
}
