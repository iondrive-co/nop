package iondrive.nop.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A named launch choice or a physical model-picker selection, independent of vendor telemetry. */
internal class ModelAuthorization(initial: ConversationModel?) {
    private var acceptedSelectionAt = 0L
    private var acceptedSelectionModel: String? = null
    var choice: ConversationModel? by mutableStateOf(initial)
        private set

    data class Change(val expected: ConversationModel, val observed: ConversationModel, val restored: Boolean = false)

    var change: Change? by mutableStateOf(null)
        private set

    /** True when the observation establishes a choice that should be saved. */
    fun observe(observed: ConversationModel, at: Long, selectedAt: Long): Boolean {
        val selected = selectedAt > 0 && at - selectedAt in 0..5_000 &&
            (selectedAt != acceptedSelectionAt || observed.model == acceptedSelectionModel)
        if (choice == null || selected) {
            if (selected) { acceptedSelectionAt = selectedAt; acceptedSelectionModel = observed.model }
            val changed = choice != observed
            choice = observed
            change = null
            return changed
        }
        val expected = choice!!
        if (observed.model != expected.model ||
            (expected.reasoning != null && observed.reasoning != null && expected.reasoning != observed.reasoning)
        ) {
            if (change?.observed != observed || change?.expected != expected) change = Change(expected, observed)
        } else if (expected.reasoning == null && observed.reasoning != null) {
            choice = expected.copy(reasoning = observed.reasoning)
            return true
        }
        return false
    }

    fun restored(expected: ConversationModel) {
        change?.takeIf { it.expected == expected }?.let { change = it.copy(restored = true) }
    }

    fun dismiss() { change = null }
}
