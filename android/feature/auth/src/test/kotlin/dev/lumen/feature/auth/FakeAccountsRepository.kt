package dev.lumen.feature.auth

import dev.lumen.core.Account
import dev.lumen.core.AccountState
import dev.lumen.core.AccountsSnapshot
import dev.lumen.core.DeviceLogin
import dev.lumen.core.QrMatrix
import dev.lumen.data.AccountsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

fun account(id: String, state: AccountState = AccountState.OK) =
    Account(id = id, login = "login$id", displayName = "Name$id", avatarUrl = "", state = state)

class FakeAccountsRepository(accounts: List<Account> = emptyList(), activeId: String? = null) : AccountsRepository {
    override val snapshot = MutableStateFlow<AccountsSnapshot?>(AccountsSnapshot(accounts, activeId))

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
        )
    }
}

fun deviceLogin(code: String) = DeviceLogin(
    userCode = code,
    verificationUri = "https://www.twitch.tv/activate?device-code=$code",
    expiresInS = 1800u,
    qr = QrMatrix(size = 1u, modules = listOf(true)),
)
