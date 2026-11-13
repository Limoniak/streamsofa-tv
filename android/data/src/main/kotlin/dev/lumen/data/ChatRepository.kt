package dev.lumen.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.lumen.core.ChatEvent
import dev.lumen.core.ChatListener
import dev.lumen.core.PickerEmote
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

interface ChatRepository {
    /** Lots d'événements du salon de [login] ; la connexion vit tant que le flux est collecté. */
    fun events(login: String): Flow<List<ChatEvent>>

    /** Envoie avec le compte actif, en réponse à [replyTo] si donné ; lève `ChatRejected` avec la raison de Twitch. */
    suspend fun send(broadcasterId: String, text: String, replyTo: String? = null)

    /** Émotes que le compte actif peut envoyer dans la chaîne, celles de la chaîne en tête. */
    suspend fun emotes(broadcasterId: String): List<PickerEmote>
}

@Singleton
internal class RustChatRepository @Inject constructor(
    private val coreHolder: CoreHolder,
) : ChatRepository {
    override fun events(login: String): Flow<List<ChatEvent>> = callbackFlow {
        val session = coreHolder.get().joinChat(
            login,
            object : ChatListener {
                override fun onEvents(events: List<ChatEvent>) {
                    trySend(events)
                }
            },
        )
        // Libérer l'objet Rust ferme la socket.
        awaitClose { session.destroy() }
    }.buffer(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override suspend fun send(broadcasterId: String, text: String, replyTo: String?) =
        coreHolder.get().sendChat(broadcasterId, text, replyTo)

    override suspend fun emotes(broadcasterId: String) = coreHolder.get().chatEmotes(broadcasterId)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ChatModule {
    @Binds
    abstract fun chatRepository(impl: RustChatRepository): ChatRepository
}
