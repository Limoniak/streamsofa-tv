package dev.lumen.feature.auth

import dev.lumen.core.AccountState
import dev.lumen.core.CoreException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
// Noms de tests courts : chacun devient un nom de fichier .class, et Windows limite les chemins.
class ViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // --- Profils ---------------------------------------------------------------------------

    @Test
    fun `select healthy account enters`() = runTest(dispatcher) {
        val repo = FakeAccountsRepository(listOf(account("1"), account("2")), activeId = "1")
        val vm = ProfilesViewModel(repo)
        vm.select(account("2"))
        assertEquals(ProfilesEvent.Enter, vm.events.first())
        assertEquals("2", repo.snapshot.value!!.activeId)
    }

    @Test
    fun `select expired account reconnects`() = runTest(dispatcher) {
        val expired = account("1", AccountState.NEEDS_REAUTH)
        val vm = ProfilesViewModel(FakeAccountsRepository(listOf(expired)))
        vm.select(expired)
        assertEquals(ProfilesEvent.Reconnect, vm.events.first())
    }

    @Test
    fun `guest keeps account list`() = runTest(dispatcher) {
        val repo = FakeAccountsRepository(listOf(account("1")), activeId = "1")
        val vm = ProfilesViewModel(repo)
        vm.continueAsGuest()
        assertEquals(ProfilesEvent.Enter, vm.events.first())
        assertEquals(null, repo.snapshot.value!!.activeId)
        assertEquals(1, repo.snapshot.value!!.accounts.size)
    }

    @Test
    fun `select removed account is ignored`() = runTest(dispatcher) {
        val repo = FakeAccountsRepository(listOf(account("1")))
        val vm = ProfilesViewModel(repo)
        vm.remove(account("1"))
        vm.select(account("1"))
        advanceUntilIdle()
        assertTrue(repo.snapshot.value!!.accounts.isEmpty())
        vm.continueAsGuest()
        assertEquals(ProfilesEvent.Enter, vm.events.first())
    }

    // --- Connexion par code ----------------------------------------------------------------

    @Test
    fun `login shows code then succeeds`() = runTest(dispatcher) {
        val repo = FakeAccountsRepository().apply { startResults += Result.success(deviceLogin("ABCD1234")) }
        val vm = DeviceLoginViewModel(repo)
        assertEquals(DeviceLoginUiState.Requesting, vm.state.value)
        advanceUntilIdle()
        assertEquals("ABCD1234", (vm.state.value as DeviceLoginUiState.WaitingForUser).userCode)

        repo.completion.complete(account("7"))
        advanceUntilIdle()
        assertEquals(DeviceLoginUiState.Success("Name7"), vm.state.value)
        assertEquals(listOf("ABCD1234"), repo.completedCodes)
    }

    @Test
    fun `core errors map to messages`() = runTest(dispatcher) {
        val cases = listOf(
            CoreException.NotConfigured() to LoginError.NotConfigured,
            CoreException.Network("dns") to LoginError.Network,
            CoreException.SecretStore("locked") to LoginError.Storage,
            CoreException.Api(500u, "boom") to LoginError.Other,
        )
        for ((exception, expected) in cases) {
            val repo = FakeAccountsRepository().apply { startResults += Result.failure(exception) }
            val vm = DeviceLoginViewModel(repo)
            advanceUntilIdle()
            assertEquals(DeviceLoginUiState.Failed(expected), vm.state.value)
        }
    }

    @Test
    fun `stale wait cannot overwrite new code`() = runTest(dispatcher) {
        val repo = FakeAccountsRepository().apply {
            startResults += Result.success(deviceLogin("OLD"))
            startResults += Result.success(deviceLogin("NEW"))
        }
        val vm = DeviceLoginViewModel(repo)
        advanceUntilIdle()
        val staleWait = repo.completion

        repo.completion = CompletableDeferred()
        vm.start()
        advanceUntilIdle()
        staleWait.completeExceptionally(CoreException.DeviceCodeExpired())
        advanceUntilIdle()

        assertEquals("NEW", (vm.state.value as DeviceLoginUiState.WaitingForUser).userCode)
    }

    @Test
    fun `expiry offers retry`() = runTest(dispatcher) {
        val repo = FakeAccountsRepository().apply { startResults += Result.success(deviceLogin("ABCD")) }
        val vm = DeviceLoginViewModel(repo)
        advanceUntilIdle()
        repo.completion.completeExceptionally(CoreException.DeviceCodeExpired())
        advanceUntilIdle()
        assertEquals(DeviceLoginUiState.Failed(LoginError.Expired), vm.state.value)
    }
}
