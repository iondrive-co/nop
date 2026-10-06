package iondrive.nop.update

import iondrive.nop.ipc.Restart
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Whether this nop may update itself from a release, and if not, why.
 *
 * Only a nop installed from a release may. One built from a checkout (`installDesktopEntry` points
 * the desktop at `build/compose/binaries/main/app/nop`) is updated by rebuilding, which the restart
 * button already picks up; a release dropped over it would quietly replace the developer's own build
 * with an older or different one. One with no launcher at all (`./gradlew run`, a bare `java`) has
 * nothing a release could replace.
 */
sealed interface Installation {

    /** A release install, run from [launcher]; [version] is what it reports itself as. */
    data class Release(val launcher: Path, val version: Version) : Installation

    /** Not updated from releases, for [reason], which is said to the user as is. */
    data class NotUpdatable(val reason: String) : Installation

    companion object {

        /** This running nop's installation. */
        fun current(): Installation = of(Restart.launcher(), BuildInfo.version)

        internal fun of(launcher: Path?, version: Version?): Installation {
            launcher ?: return NotUpdatable("nop is not running from an installed launcher")
            checkoutAbove(launcher)?.let { return NotUpdatable("nop is built from the checkout at $it; rebuild it to update") }
            version ?: return NotUpdatable("this build does not say which version it is")
            return Release(launcher, version)
        }

        /**
         * The git checkout [path] lies inside, or null. A `.git` directory marks a checkout and a
         * `.git` file a linked worktree; either means the build is someone's own. A directory counts
         * only when it holds `HEAD`, as it must for git itself to see a repository there — an empty
         * `.git` left in a shared directory such as `/tmp` is not one.
         */
        internal fun checkoutAbove(path: Path): Path? {
            var dir: Path? = path.toAbsolutePath().normalize().parent
            while (dir != null) {
                val git = dir.resolve(".git")
                if (Files.isRegularFile(git, LinkOption.NOFOLLOW_LINKS) || Files.exists(git.resolve("HEAD"))) return dir
                dir = dir.parent
            }
            return null
        }
    }
}
