package iondrive.nop.agent

/** Names work from the user's request, skipping context injected ahead of it by the CLI. */
internal object SessionTitle {
    fun fromPrompt(text: String): String? {
        var prompt = text.trimStart()
        while (true) {
            val closing = contextClosingTag(prompt) ?: break
            val end = prompt.indexOf(closing)
            if (end < 0) return null
            prompt = prompt.substring(end + closing.length).trimStart()
        }
        return prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(120)
    }

    /** Default labels and truncated context blocks do not describe the work. */
    fun isPlaceholder(title: String): Boolean = title.trim().let {
        it.isEmpty() || it == "Agent" || it == "Untitled session" || isInjected(it)
    }

    fun isInjected(title: String): Boolean = contextClosingTag(title.trimStart()) != null

    private fun contextClosingTag(text: String): String? = when {
        text.startsWith("# AGENTS.md instructions for ") || text.startsWith("<INSTRUCTIONS>") ->
            "</INSTRUCTIONS>"
        text.startsWith("<environment_context>") -> "</environment_context>"
        text.startsWith("<image>") || text.startsWith("<image ") -> "</image>"
        else -> null
    }
}
