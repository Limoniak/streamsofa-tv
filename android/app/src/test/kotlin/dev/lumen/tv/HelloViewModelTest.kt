package dev.lumen.tv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HelloViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val ok = CoreDiagnostics("0.1.0", asyncOk = true, secretStoreOk = true, clientIdConfigured = false, elapsedMs = 12)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loading then ready when the core answers`() = runTest(dispatcher) {
        val vm = HelloViewModel(FakeGateway { ok })
        assertEquals(HelloUiState.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(HelloUiState.Ready(ok), vm.state.value)
    }

    @Test
    fun `a missing native library becomes a failed state, not a crash`() = runTest(dispatcher) {
        val vm = HelloViewModel(FakeGateway { throw UnsatisfiedLinkError("liblumen_core.so introuvable") })
        advanceUntilIdle()
        assertEquals(HelloUiState.Failed("liblumen_core.so introuvable"), vm.state.value)
    }

    @Test
    fun `retry discards the result of the previous run`() = runTest(dispatcher) {
        val first = CompletableDeferred<CoreDiagnostics>()
        var calls = 0
        val vm = HelloViewModel(FakeGateway { if (++calls == 1) first.await() else ok.copy(elapsedMs = 99) })
        advanceUntilIdle()
        vm.runDiagnostics()
        advanceUntilIdle()
        first.complete(ok)
        advanceUntilIdle()
        assertEquals(HelloUiState.Ready(ok.copy(elapsedMs = 99)), vm.state.value)
    }

    private class FakeGateway(private val answer: suspend () -> CoreDiagnostics) : CoreGateway {
        override suspend fun diagnostics() = answer()
    }
}
