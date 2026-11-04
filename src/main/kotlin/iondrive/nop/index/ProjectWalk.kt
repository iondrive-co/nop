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
 *
 * A link back to a directory the walk came through is not followed either, the check `find -L`
 * makes. The walk would go round until the kernel gave up at 40 links in a path, listing the tree
 * under that directory forty times over, at ever longer paths. A link from `var/dev/job/stage/var`
 * to `var` turned a hundred thousand files into four million paths, and those filled a 2 GB heap.
 */
internal object ProjectWalk {
    /** True when a walk of [root] should not go through [f]. */
    fun skips(root: File, f: File): Boolean = leavesProject(root, f) || loopsBack(root, f)

    /**
     * True when [f] is a symbolic link to a directory on the way from [root] down to it. Compared by
     * real path, because a loop made of several links shows only once they are resolved; that costs
     * nothing on the share [leavesProject] stays off, since [skips] has ruled out links to it first.
     */
    private fun loopsBack(root: File, f: File): Boolean {
        val path = f.toPath()
        if (!Files.isSymbolicLink(path) || !Files.isDirectory(path)) return false
        val target = runCatching { path.toRealPath() }.getOrNull() ?: return false
        val top = root.toPath()
        var dir = path.parent
        while (dir != null) {
            if (runCatching { dir.toRealPath() }.getOrNull() == target) return true
            if (dir == top) return false
            dir = dir.parent
        }
        return false
    }

    /** True when [f] is a symbolic link whose target lies outside [root]. */
    fun leavesProject(root: File, f: File): Boolean {
        val path = f.toPath()
        if (!Files.isSymbolicLink(path)) return false
        val target = runCatching { Files.readSymbolicLink(path) }.getOrNull() ?: return false
        val resolved = (path.parent ?: path).resolve(target).toAbsolutePath().normalize()
        return !resolved.startsWith(root.toPath().toAbsolutePath().normalize())
    }
}
