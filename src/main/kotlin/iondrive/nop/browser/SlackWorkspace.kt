package iondrive.nop.browser

import kotlinx.serialization.Serializable

@Serializable
data class SlackWorkspace(val id: String, val name: String) {
    val url: String get() = "https://app.slack.com/client/$id"
}

@Serializable
internal data class SlackMetadata(
    val current: SlackWorkspace? = null,
    val workspaces: List<SlackWorkspace> = emptyList(),
    val attention: SlackAttention = SlackAttention(),
)

@Serializable
data class SlackAttention(val unread: Boolean = false, val mentions: Int = 0) {
    fun sanitized() = SlackAttention(unread || mentions > 0, mentions.coerceIn(0, 9999))
}

internal data class SlackWorkspaceTabs(
    val known: List<SlackWorkspace> = emptyList(),
    val opened: Set<String> = emptySet(),
) {
    val open: List<SlackWorkspace> get() = known.filter { it.id in opened }
    fun discover(incoming: List<SlackWorkspace>) = copy(known = SlackWorkspaceNames.merge(known, incoming))
    fun close(id: String) = copy(opened = opened - id)
    fun reopen(id: String) = if (SlackWorkspaceNames.validId(id)) copy(opened = opened + id) else this
}

@Serializable
data class SlackAppearance(
    val dark: Boolean = true,
    val background: String = "#1e1f22",
    val panel: String = "#2b2d30",
    val foreground: String = "#dfe1e5",
    val muted: String = "#9da0a8",
    val border: String = "#43454a",
    val hover: String = "#35373b",
    val selection: String = "#2e436e",
    val accent: String = "#6b9bfa",
)

internal object SlackWorkspaceNames {
    fun validId(id: String): Boolean = id.matches(Regex("T[A-Z0-9]{1,31}"))

    fun merge(existing: List<SlackWorkspace>, incoming: List<SlackWorkspace>): List<SlackWorkspace> {
        val workspaces = existing.associateByTo(linkedMapOf()) { it.id }
        incoming.take(128).forEach { workspace ->
            val name = workspace.name.filterNot { it.isISOControl() }.trim().take(120)
            if (validId(workspace.id) && name.isNotEmpty()) workspaces[workspace.id] = workspace.copy(name = name)
        }
        return workspaces.values.take(128)
    }
}
