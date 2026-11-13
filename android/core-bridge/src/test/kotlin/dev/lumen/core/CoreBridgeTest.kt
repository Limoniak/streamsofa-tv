package dev.lumen.core

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Traverse réellement la FFI : bindings générés + liblumen_core compilée pour l'hôte. */
class CoreBridgeTest {
    private class MemoryStore : SecretStore {
        val entries = HashMap<String, ByteArray>()
        override fun load(key: String): ByteArray? = entries[key]
        override fun save(key: String, blob: ByteArray) { entries[key] = blob }
        override fun delete(key: String) { entries.remove(key) }
    }

    private fun core(clientId: String = "", dataDir: String = Files.createTempDirectory("lumen").toString()) =
        Core(CoreConfig(clientId = clientId, dataDir = dataDir, appVersion = "test"), MemoryStore())

    @Test
    fun `a new install starts with no account, in guest mode`() {
        core().use { core ->
            assertEquals(coreVersion(), core.version())
            val snapshot = core.accounts()
            assertTrue(snapshot.accounts.isEmpty())
            assertNull(snapshot.activeId)
        }
    }

    @Test
    fun `async errors cross the bridge as typed exceptions`() = runBlocking {
        core(clientId = " ").use { core ->
            assertFalse(core.hasClientId())
            try {
                core.startDeviceLogin()
                fail("CoreException.NotConfigured attendue")
            } catch (_: CoreException.NotConfigured) {
            }
        }
    }

    @Test
    fun `constructor failures are typed too`() {
        try {
            core(dataDir = "/nonexistent/lumen").close()
            fail("CoreException.Storage attendue")
        } catch (_: CoreException.Storage) {
        }
    }

    @Test
    fun `unknown accounts are rejected and listeners can be registered`() = runBlocking {
        core(clientId = "abc").use { core ->
            assertTrue(core.hasClientId())
            core.setAccountsListener(object : AccountsListener {
                override fun onAccountsChanged(snapshot: AccountsSnapshot) = Unit
            })
            try {
                core.removeAccount("404")
                fail("CoreException.UnknownAccount attendue")
            } catch (e: CoreException.UnknownAccount) {
                assertEquals("404", e.accountId)
            }
            core.validateAccounts()
        }
    }
}
