package iondrive.nop.agent

import iondrive.nop.terminal.TerminalSession
import java.io.File
import java.nio.file.Files

/**
 * Signing an account in, by running the vendor's own login.
 *
 * nop never asks for a password and never handles a token. All three CLIs authenticate through a
 * browser OAuth flow they drive themselves; all nop does is start the right command with the
 * account's home in its environment, so whatever the flow writes lands in that account's directory
 * and not in the machine owner's.
 *
 * The login runs in a normal nop terminal tab rather than inside the accounts dialog. That is
 * partly because these flows need a real TTY — which nop's PTY terminals are and a Compose dialog
 * is not — and partly because Compose Desktop composites every popup *below* the heavyweight AWT
 * component a terminal is, so a terminal drawn inside a dialog would be invisible. Watching it in a
 * tab also leaves the output there afterwards, which is the only thing that explains a login that
 * didn't take.
 */
object Login {

    /**
     * A terminal session running [account]'s login flow.
     *
     * Creates the home first: claude and codex fail outright on a directory that isn't there, and
     * an account that has never been signed in doesn't have one yet. agy makes its own — but with
     * the umask, which is the other reason this happens here rather than being left to the CLI.
     */
    fun session(account: Account): TerminalSession {
        // Owner-only. The CLI writes its own credential file at 0600, but everything else it leaves
        // in here — caches, its own transcripts of the code it read — inherits the directory, and a
        // world-readable home undoes the point of giving each account one.
        runCatching { OwnerOnly.directory(credentialParent(account)) }
        // Where agy puts a login is a decision it makes at startup, between the OS keyring every
        // account would share and a file inside this home. It has to be settled before the flow
        // runs, because afterwards the token is already wherever it went. See [Antigravity].
        if (account.provider == Provider.Antigravity) Antigravity.prepareHome(account)
        return TerminalSession.agent(
            command = command(account),
            env = env(account),
            dir = File(System.getProperty("user.home")),
            title = "login ${account.name}",
        )
    }

    /**
     * The login argv.
     *
     * `claude auth login` rather than bare `claude`: a dead token left in the config dir still
     * makes the CLI report itself logged in as whoever wrote it, so bare `claude` drops the user
     * into a coding session as another account with no way to sign in. `codex logout` before
     * `codex login` for the same reason — a half-finished session makes the next login a no-op.
     *
     * `agy` has no login subcommand at all: started in a home with no token it runs the browser
     * flow itself and writes what comes back into that home, which is the whole flow. Started in a
     * home that *has* one it opens a coding session instead — the same trap as bare `claude` — so
     * the token file goes first, in the command rather than before it, because "Sign in again" on
     * a signed-in account must not leave the old login in place if the new one is abandoned.
     */
    fun command(account: Account): List<String> = when (account.provider) {
        Provider.Anthropic -> listOf(CliTools.resolve(Provider.Anthropic), "auth", "login")
        Provider.OpenAI -> listOf(
            "sh", "-c",
            "${shellQuote(CliTools.resolve(Provider.OpenAI))} logout >/dev/null 2>&1; " +
                "exec ${shellQuote(CliTools.resolve(Provider.OpenAI))} login",
        )
        Provider.Antigravity -> listOf(
            "sh", "-c",
            "rm -f ${shellQuote(Antigravity.tokenFile(account.homePath).toString())}; " +
                "exec ${shellQuote(CliTools.resolve(Provider.Antigravity))}",
        )
    }

    /** The same isolating environment a run gets — this is what files the login under the account. */
    fun env(account: Account): Map<String, String> = when (account.provider) {
        Provider.Anthropic -> mapOf("CLAUDE_CONFIG_DIR" to account.home)
        Provider.OpenAI, Provider.Antigravity -> mapOf("HOME" to account.home)
    }

    /** Signs the account out by deleting its credential file — no CLI, so it works when one is missing. */
    fun logOut(account: Account) {
        runCatching { Files.deleteIfExists(account.credentialFile) }
    }

    /** Whether the account has a credential file at all. Not proof it still works — see [Usage]. */
    fun hasCredentials(account: Account): Boolean = Files.isRegularFile(account.credentialFile)

    private fun credentialParent(account: Account) = account.credentialFile.parent

    private fun shellQuote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
