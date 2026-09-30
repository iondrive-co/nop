package iondrive.nop.git

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.StoredConfig
import org.eclipse.jgit.lib.UserConfig
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.SystemReader
import java.io.File

/**
 * Who a commit is made as: git's `user.name` and `user.email`, which a commit records as both its
 * author and its committer.
 */
data class CommitIdentity(val name: String, val email: String) {
    override fun toString() = "$name <$email>"
}

/**
 * An identity the commit panel offers, and [source]: where it was found, in the words the panel
 * shows beside it — one of the constants below, or the path of an included config file.
 */
data class IdentityChoice(val identity: CommitIdentity, val source: String) {
    companion object {
        /** Set in the repository's own config, so every commit made there uses it. */
        const val REPO = "this repo"
        const val GLOBAL = "global"
        const val ENVIRONMENT = "environment"
        /** Made up by git from the login and host name, because nothing sets one. */
        const val UNSET = "not configured"
        const val HISTORY = "history"
        /** Typed into the commit panel rather than found anywhere. */
        const val ENTERED = "entered"
    }
}

/**
 * The identities [repository] can commit as, each once: the one a commit gets when nothing is
 * picked comes first, then the global one, then the ones in config files that [userConfig] or the
 * repository's own config pull in with `includeIf`, then the authors of the last [historyDepth]
 * commits, at most [MAX_HISTORY_IDENTITIES] of them.
 *
 * An included file is offered whatever its condition says, because JGit does not evaluate
 * `includeIf`: the identity a terminal's git takes from one is not what a commit gets here unless
 * it is picked, or saved into the repository's config.
 */
internal fun identityChoices(
    repository: Repository,
    userConfig: StoredConfig = SystemReader.getInstance().userConfig,
    historyDepth: Int = IDENTITY_HISTORY_DEPTH,
): List<IdentityChoice> {
    val choices = LinkedHashMap<CommitIdentity, String>()
    fun offer(identity: CommitIdentity?, source: String): Boolean {
        if (identity == null || identity.name.isBlank() || identity.email.isBlank()) return false
        return choices.putIfAbsent(identity, source) == null
    }

    val local = localConfig(repository)
    // The committer, because that is what a JGit commit falls back to for the author as well.
    val ident = PersonIdent(repository)
    offer(CommitIdentity(ident.name, ident.emailAddress), defaultSource(repository, local))
    offer(identityIn(userConfig), IdentityChoice.GLOBAL)

    val home = repository.fs.userHome()
    for (config in listOfNotNull(userConfig, local)) {
        val dir = (config as? FileBasedConfig)?.file?.parentFile
        for (condition in config.getSubsections(INCLUDE_IF)) {
            for (path in config.getStringList(INCLUDE_IF, condition, PATH)) {
                val file = includedFile(path, dir, home) ?: continue
                val included = runCatching { FileBasedConfig(null, file, repository.fs).apply { load() } }
                    .getOrNull() ?: continue
                // A file that sets only an email, as a per-employer one often does, commits under
                // the name the including config gives.
                val email = included.getString(USER, null, EMAIL) ?: continue
                val name = included.getString(USER, null, NAME) ?: config.getString(USER, null, NAME) ?: continue
                offer(CommitIdentity(name, email), shortPath(path, home))
            }
        }
    }

    val head = runCatching { repository.resolve(Constants.HEAD) }.getOrNull()
    if (head != null) {
        RevWalk(repository).use { walk ->
            walk.markStart(walk.parseCommit(head))
            var added = 0
            for ((walked, commit) in walk.withIndex()) {
                if (walked >= historyDepth || added >= MAX_HISTORY_IDENTITIES) break
                val author = commit.authorIdent ?: continue
                if (offer(CommitIdentity(author.name, author.emailAddress), IdentityChoice.HISTORY)) added++
            }
        }
    }
    return choices.map { (identity, source) -> IdentityChoice(identity, source) }
}

/** Writes [identity] into [repository]'s own config, where every later commit made there finds it. */
internal fun writeIdentity(repository: Repository, identity: CommitIdentity) {
    // Only the repository's own file is written: the global and system configs are this one's
    // base, which setString never touches and save never writes.
    val config = repository.config
    config.setString(USER, null, NAME, identity.name)
    config.setString(USER, null, EMAIL, identity.email)
    config.save()
}

/** Where git found [repository]'s default identity: see [IdentityChoice]'s constants. */
private fun defaultSource(repository: Repository, local: Config?): String {
    val env = SystemReader.getInstance()
    val user = repository.config.get(UserConfig.KEY)
    return when {
        env.getenv(Constants.GIT_COMMITTER_NAME_KEY) != null ||
            env.getenv(Constants.GIT_COMMITTER_EMAIL_KEY) != null -> IdentityChoice.ENVIRONMENT
        local != null && (local.getString(USER, null, NAME) != null || local.getString(USER, null, EMAIL) != null) ->
            IdentityChoice.REPO
        user.isCommitterNameImplicit || user.isCommitterEmailImplicit -> IdentityChoice.UNSET
        else -> IdentityChoice.GLOBAL
    }
}

/**
 * The repository's own config file read on its own. [Repository.getConfig] answers through the
 * global and system configs beneath it, so it cannot say which of them a value came from.
 */
private fun localConfig(repository: Repository): FileBasedConfig? {
    val file = (repository.config as? FileBasedConfig)?.file ?: return null
    return runCatching { FileBasedConfig(null, file, repository.fs).apply { load() } }.getOrNull()
}

private fun identityIn(config: Config): CommitIdentity? {
    val name = config.getString(USER, null, NAME) ?: return null
    val email = config.getString(USER, null, EMAIL) ?: return null
    return CommitIdentity(name, email)
}

/** [path] as git resolves an include: `~/` from the home directory, a relative one from [dir]. */
internal fun includedFile(path: String, dir: File?, home: File?): File? {
    val file = when {
        path.startsWith("~/") -> home?.let { File(it, path.removePrefix("~/")) }
        File(path).isAbsolute -> File(path)
        else -> dir?.let { File(it, path) }
    }
    return file?.takeIf { it.isFile }
}

/** [path] with the home directory written as `~`, which is how the commit panel names it. */
internal fun shortPath(path: String, home: File?): String {
    val prefix = home?.path?.trimEnd('/') ?: return path
    return if (path.startsWith("$prefix/")) "~" + path.removePrefix(prefix) else path
}

/** How far back the commit panel looks for identities people have committed as. */
internal const val IDENTITY_HISTORY_DEPTH = 200

/** Most authors from the history offered: a team's repository would otherwise list the whole team. */
internal const val MAX_HISTORY_IDENTITIES = 8

private const val USER = ConfigConstants.CONFIG_USER_SECTION
private const val NAME = ConfigConstants.CONFIG_KEY_NAME
private const val EMAIL = ConfigConstants.CONFIG_KEY_EMAIL
private const val INCLUDE_IF = "includeIf"
private const val PATH = "path"
