package iondrive.nop.index

import java.io.File
import java.nio.file.Files

/**
 * The rule the project walks share about symbolic links: a link to a directory is not walked, and a
 * link to a file is listed unless it points out of the project.
 *
 * A link to a directory inside the project names files the walk reaches anyway, at the path where
 * they live, so walking it lists them again under a second name. Tools that stage work by linking
 * a tree's directories back into a scratch copy of it (`stage/data -> ../../data`, one stage per
 * job) turn that into the whole tree again for every stage: a project of two hundred thousand files
 * walked as millions of paths, more than a heap holds. A link to a directory outside the project
 * says the thing it names lives somewhere else, often a network share of bulk data, where every
 * refresh would pay a round trip per file for data no index or search is going to use.
 *
 * A link to a file costs one entry, so it is listed, except one leading out of the project, which is
 * not followed onto someone else's disk either. Its target is resolved from the link's own text,
 * without touching it: asking the filesystem would mean a round trip to exactly the share the rule is
 * there to stay off.
 */
internal object ProjectWalk {
    /** True when a walk of [root] should not go through [f]. */
    fun skips(root: File, f: File): Boolean {
        val path = f.toPath()
        if (!Files.isSymbolicLink(path)) return false
        return leavesProject(root, f) || Files.isDirectory(path)
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
