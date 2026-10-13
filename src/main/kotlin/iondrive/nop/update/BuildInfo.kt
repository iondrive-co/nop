package iondrive.nop.update

import java.util.Properties

/**
 * What this build of nop is, as the build stamped it (`generateBuildInfo` in build.gradle.kts, from
 * `packageVersion`). The self-updater compares [version] with the latest release.
 */
object BuildInfo {

    /** This build's version, `MAJOR.MINOR.PATCH`; null when the stamp is missing or unreadable. */
    val version: Version? by lazy { load()?.getProperty("version")?.let(Version::parse) }

    /** The handover this build adopts; see [handoverOf]. */
    val handover: Int by lazy { handoverOf(load() ?: Properties()) }

    /**
     * The handover a build stamped with [stamp] adopts — what [iondrive.nop.ipc.Handover] calls its
     * VERSION. A stamp without the field adopts version 1: no build that leaves it out knows another.
     */
    fun handoverOf(stamp: Properties): Int = stamp.getProperty("handover")?.trim()?.toIntOrNull() ?: 1

    private fun load(): Properties? = runCatching {
        BuildInfo::class.java.getResourceAsStream(RESOURCE)?.use { s -> Properties().apply { load(s) } }
    }.getOrNull()

    /** Where the stamp sits, in the jar and in the sources it is generated into. */
    const val RESOURCE_PATH = "iondrive/nop/update/nop-build.properties"

    private const val RESOURCE = "nop-build.properties"
}

/**
 * A release version as nop numbers them: `MAJOR.MINOR.PATCH` (scripts/release.sh refuses anything
 * else), optionally written with the tag's leading `v`.
 */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {

    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""v?(\d{1,9})\.(\d{1,9})\.(\d{1,9})""")

        /** [text] as a version, or null when it is not exactly one. */
        fun parse(text: String): Version? =
            PATTERN.matchEntire(text.trim())?.destructured?.let { (a, b, c) -> Version(a.toInt(), b.toInt(), c.toInt()) }
    }
}
