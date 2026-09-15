package iondrive.nop.agent

import java.io.File
import java.nio.file.Path

/**
 * Finds the vendor CLI binaries, and says how to install one that is missing.
 *
 * nop does not install them behind the user's back. Both are npm packages that pull a few hundred
 * megabytes and occasionally prompt; doing that silently from a click labelled "launch" is the
 * wrong trade. [installCommand] hands back the command instead, which the picker offers to run in
 * a terminal where the user can watch it.
 */
object CliTools {

    /**
     * The binary for [provider], or null when it isn't installed.
     *
     * PATH is searched first, then the directories these CLIs actually land in, because nop is
     * usually started from a desktop launcher: the shell's PATH — where `~/.local/bin` and any
     * node version manager's shims live — is precisely what a `.desktop` Exec= does not inherit.
     */
    fun locate(provider: Provider): Path? {
        val name = provider.binary
        val onPath = System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { File(it, name) }
            .firstOrNull { it.canExecute() }
        if (onPath != null) return onPath.toPath()

        val home = System.getProperty("user.home")
        return EXTRA_BIN_DIRS
            .asSequence()
            .map { File(home, it).resolve(name) }
            .firstOrNull { it.canExecute() }
            ?.toPath()
    }

    /** [locate]'s result, or the bare name — so a spawn still tries, and fails with a real error. */
    fun resolve(provider: Provider): String = locate(provider)?.toString() ?: provider.binary

    /** The shell command that installs [provider]'s CLI, for the picker to offer. */
    fun installCommand(provider: Provider): String = when (provider) {
        Provider.Anthropic -> "npm install -g @anthropic-ai/claude-code"
        Provider.OpenAI -> "npm install -g @openai/codex"
    }

    /**
     * Where these CLIs install outside PATH: npm's default global prefix, a user-local prefix, and
     * the managed tools directory chad kept its own copies in.
     */
    private val EXTRA_BIN_DIRS = listOf(
        ".local/bin",
        ".npm-global/bin",
        ".npm-packages/bin",
        "node_modules/.bin",
        ".chad/tools/bin",
        ".chad/tools/node_modules/.bin",
    )
}
