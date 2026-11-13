package dev.lumen.feature.auth

import dev.lumen.core.Account
import dev.lumen.core.AccountState
import dev.lumen.core.AccountsSnapshot
import dev.lumen.core.DeviceLogin
import dev.lumen.core.ProfileLock
import dev.lumen.core.QrMatrix
import dev.lumen.data.AccountsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

fun account(id: String, state: AccountState = AccountState.OK) =
    Account(id = id, login = "login$id", displayName = "Name$id", avatarUrl = "", state = state)

class FakeAccountsRepository(accounts: List<Account> = emptyList(), activeId: String? = null) : AccountsRepository {
    override val snapshot = MutableStateFlow<AccountsSnapshot?>(AccountsSnapshot(accounts, activeId, emptyList()))
    val pins = mutableMapOf<String?, String>()

    var startResults = ArrayDeque<Result<DeviceLogin>>()
    var completion = CompletableDeferred<Account>()
    var completedCodes = mutableListOf<String>()

    override suspend fun startDeviceLogin(): DeviceLogin = startResults.removeFirst().getOrThrow()

    override suspend fun completeDeviceLogin(userCode: String): Account {
        completedCodes += userCode
        return completion.await()
    }

    override suspend fun setActive(accountId: String?) {
        val current = snapshot.value!!
        check(accountId == null || current.accounts.any { it.id == accountId })
        snapshot.value = current.copy(activeId = accountId)
    }

    override suspend fun remove(accountId: String) {
        val current = snapshot.value!!
        snapshot.value = AccountsSnapshot(
            accounts = current.accounts.filterNot { it.id == accountId },
            activeId = current.activeId.takeIf { it != accountId },
            locks = current.locks.filterNot { it.profileId == accountId },
        )
    }

    override suspend fun setPin(profileId: String?, pin: String?) {
        if (pin == null) pins.remove(profileId) else pins[profileId] = pin
        updateLock(profileId) { it.copy(hasPin = pin != null) }
    }

    override suspend fun checkPin(profileId: String?, pin: String) = pins[profileId]?.let { it == pin } ?: true

    override suspend fun setKids(profileId: String?, kids: Boolean) = updateLock(profileId) { it.copy(kids = kids) }

    override suspend fun resetGuest() {
        pins.remove(null)
        updateLock(null) { ProfileLock(null, hasPin = false, kids = false) }
    }

    private fun updateLock(profileId: String?, change: (ProfileLock) -> ProfileLock) {
        val current = snapshot.value!!
        val lock = change(current.locks.firstOrNull { it.profileId == profileId } ?: ProfileLock(profileId, hasPin = false, kids = false))
        val others = current.locks.filterNot { it.profileId == profileId }
        snapshot.value = current.copy(locks = if (lock.hasPin || lock.kids) others + lock else others)
    }
}

fun deviceLogin(code: String) = DeviceLogin(
    userCode = code,
    verificationUri = "https://www.twitch.tv/activate?device-code=$code",
    expiresInS = 1800u,
    qr = QrMatrix(size = 1u, modules = listOf(true)),
)
