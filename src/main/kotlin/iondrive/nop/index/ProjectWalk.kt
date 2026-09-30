package iondrive.nop.index

import java.io.File
import java.nio.file.Files

/**
 * The rule the project walks share about symbolic links: one that points out of the project is
 * not followed.
 *
 * A link out of the tree says the thing it names lives somewhere else, and the somewhere else can
 * be anything — often a network share of bulk data, where every refresh's staleness walk and every
 * rebuild would pay a round trip per file for data no index or search is going to use. A link that
 * stays inside the project is followed; what it names is part of the project either way.
 *
 * The target is resolved from the link's own text, without touching it. Asking the filesystem
 * would mean a round trip to exactly the share the rule is there to stay off. The one thing this
 * misses is a link inside the project that points at a second link which then leaves; that one is
 * followed.
 */
internal object ProjectWalk {
    /** True when [f] is a symbolic link whose target lies outside [root]. */
    fun leavesProject(root: File, f: File): Boolean {
        val path = f.toPath()
        if (!Files.isSymbolicLink(path)) return false
        val target = runCatching { Files.readSymbolicLink(path) }.getOrNull() ?: return false
        val resolved = (path.parent ?: path).resolve(target).toAbsolutePath().normalize()
        return !resolved.startsWith(root.toPath().toAbsolutePath().normalize())
    }
}
