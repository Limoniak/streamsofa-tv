package dev.lumen.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun core(store: SecretStore, clientId: String = "") =
        Core(CoreConfig(clientId = clientId, dataDir = "", appVersion = "test"), store)

    @Test
    fun `self test round-trips through a Kotlin secret store`() = runBlocking {
        val store = MemoryStore()
        core(store).use { core ->
            val result = core.selfTest()
            assertTrue(result.asyncOk)
            assertTrue(result.secretStoreOk)
            assertEquals(coreVersion(), result.coreVersion)
            assertTrue(store.entries.isEmpty())
        }
    }

    @Test
    fun `an exception thrown by the Kotlin store surfaces as a typed core error`() = runBlocking {
        val failing = object : SecretStore {
            override fun load(key: String): ByteArray? = null
            override fun save(key: String, blob: ByteArray): Unit = throw IllegalStateException("keystore verrouillé")
            override fun delete(key: String) = Unit
        }
        core(failing).use { core ->
            try {
                core.selfTest()
                fail("une CoreException.SecretStore était attendue")
            } catch (e: CoreException.SecretStore) {
                assertTrue(e.reason.contains("keystore verrouillé"))
            }
        }
    }

    @Test
    fun `client id presence is reported`() {
        core(MemoryStore()).use { assertFalse(it.hasClientId()) }
        core(MemoryStore(), clientId = "abc123").use { assertTrue(it.hasClientId()) }
    }
}
