package iondrive.nop.agent

import java.io.File
import java.nio.file.Path

/**
 * Finds the vendor CLI binaries, and says how to install one that is missing.
 *
 * nop does not install them behind the user's back. claude and codex are npm packages that pull a
 * few hundred megabytes and occasionally prompt; doing that silently from a click labelled "launch"
 * is the wrong trade. [installCommand] hands back the command instead, which the picker offers to
 * run in a terminal where the user can watch it.
 *
 * `agy` has no such command. It is a signed 200 MB binary served from Google's own release service,
 * one manifest per platform naming the build, its URL and its checksum — a downloader, not a
 * one-liner, and not one worth writing blind into a string the user is told to paste. So that arm
 * answers null and the picker says where to get it instead.
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

    /** The shell command that installs [provider]'s CLI, or null when there is no one-line install. */
    fun installCommand(provider: Provider): String? = when (provider) {
        Provider.Anthropic -> "npm install -g @anthropic-ai/claude-code"
        Provider.OpenAI -> "npm install -g @openai/codex"
        Provider.Antigravity -> null
    }

    /** Where to get [provider]'s CLI when [installCommand] has no command to offer. */
    fun installPage(provider: Provider): String = when (provider) {
        Provider.Antigravity -> "https://antigravity.google/docs/cli"
        Provider.Anthropic, Provider.OpenAI -> "https://www.npmjs.com/"
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
