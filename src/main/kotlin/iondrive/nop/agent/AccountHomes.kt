package iondrive.nop.agent

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Moving an account's home into nop's own data folder.
 *
 * An account's home can be anywhere — [Account.home] is whatever it was set to — and one outside
 * nop's folder is a set of logins and conversations that someone tidying up can delete without
 * knowing nop depends on it. The CLIs' own default homes are the exception: `~/.claude`, and the
 * home directory a plain `codex` uses, are where the user's shell keeps its login too, and moving
 * them would sign that out.
 */
object AccountHomes {

    /** Whether [account]'s home is somewhere nop should offer to move it from. */
    fun offersMove(
        account: Account,
        homesRoot: Path = Accounts.dataRoot().resolve("homes"),
        userHome: Path = Path.of(System.getProperty("user.home")),
    ): Boolean {
        val home = account.homePath.toAbsolutePath().normalize()
        val user = userHome.toAbsolutePath().normalize()
        return !home.startsWith(homesRoot.toAbsolutePath().normalize()) &&
            home != user &&
            home != user.resolve(".claude")
    }

    /**
     * Moves [account]'s home to `<homesRoot>/<name>` and returns the account pointing there.
     *
     * A rename, so only within one filesystem: copying a directory a CLI may be writing to across
     * disks is not something to do behind a button. The old path is left as a link to the new one,
     * because a CLI already running was started with it and goes on writing through it; it can be
     * deleted once nothing runs from there. A home whose directory no longer exists is re-pointed
     * at a fresh one.
     */
    fun moveIntoNop(account: Account, homesRoot: Path = Accounts.dataRoot().resolve("homes")): Account {
        val from = account.homePath.toAbsolutePath().normalize()
        val to = homesRoot.toAbsolutePath().normalize().resolve(account.name)
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("$to already exists")
        OwnerOnly.directory(to.parent)
        if (!Files.exists(from)) {
            OwnerOnly.directory(to)
            return account.copy(home = to.toString())
        }
        // Where the directory really is: the configured path may itself be a link to it.
        val real = from.toRealPath()
        if (real.startsWith(homesRoot.toAbsolutePath().normalize())) return account.copy(home = real.toString())
        try {
            Files.move(real, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            throw IllegalStateException("$real is on a different disk from nop's folder; move it by hand")
        }
        Files.createSymbolicLink(real, to)
        return account.copy(home = to.toString())
    }
}
