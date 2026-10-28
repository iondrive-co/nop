package iondrive.nop.agent

/** Names work from the user's request, skipping context injected ahead of it by the CLI. */
internal object SessionTitle {
    fun fromPrompt(text: String): String? {
        var prompt = text.trimStart()
        while (prompt.startsWith("<environment_context>")) {
            val end = prompt.indexOf("</environment_context>")
            if (end < 0) return null
            prompt = prompt.substring(end + "</environment_context>".length).trimStart()
        }
        return prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120)
    }
}
