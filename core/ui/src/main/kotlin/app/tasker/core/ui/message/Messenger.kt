package app.tasker.core.ui.message

import app.tasker.core.model.BatchId
import app.tasker.core.ui.text.UiText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** A message for the app-wide snackbar (§14.3: one undo snackbar for all commands). */
sealed interface UserMessage {
    val text: UiText

    /** The command wrote batch [batchId]; the snackbar offers "Undo" for it (EXC-7, AUT-1). */
    data class Undoable(override val text: UiText, val batchId: BatchId) : UserMessage

    data class Info(override val text: UiText) : UserMessage
}

/**
 * Queue of snackbar messages. ViewModels post, the app shell shows them one at a time and runs the undo. Messages
 * wait in the queue while no screen collects them (e.g. during a configuration change).
 */
@Singleton
class Messenger @Inject constructor() {
    private val queue = Channel<UserMessage>(capacity = QUEUE_SIZE)

    val messages: Flow<UserMessage> = queue.receiveAsFlow()

    fun post(message: UserMessage) {
        queue.trySend(message)
    }

    /** Offers undo when the command changed something; a no-op command (no batch) shows nothing. */
    fun undoable(text: UiText, batchId: BatchId?) {
        if (batchId != null) post(UserMessage.Undoable(text, batchId))
    }

    fun info(text: UiText) = post(UserMessage.Info(text))

    private companion object {
        const val QUEUE_SIZE = 16
    }
}
