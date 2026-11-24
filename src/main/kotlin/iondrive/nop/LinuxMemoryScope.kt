package iondrive.nop

import iondrive.nop.ipc.Handover
import iondrive.nop.ipc.Restart
import iondrive.nop.terminal.Posix
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Keep the editor and embedded browser responsive when other desktop workloads are swapping. */
internal object LinuxMemoryScope {
    private const val MEMORY_LOW = "3221225472" // 3 GiB of best-effort reclaim protection.
    private const val SYSTEMD = "org.freedesktop.systemd1"
    private const val MANAGER = "$SYSTEMD.Manager"
    private const val BUSCTL = "/usr/bin/busctl"
    private val cgroupRoot = Path.of("/sys/fs/cgroup")
    private val pid = ProcessHandle.current().pid().toString()
    private val nopScope = "app-nop-$pid.scope"
    private val workloadScope = "app-nop-workloads-$pid.scope"

    // Chromium registers with the XDG portal under this PID-specific scope. Creating it before
    // CEF starts keeps its registration in the same cgroup as the JVM's existing allocations.
    // See components/dbus/xdg/systemd.cc and base/version_info/nix/version_extra_utils.cc.
    private val chromiumScope = "app-org.chromium.Chromium" + when (System.getenv("CHROME_VERSION_EXTRA")) {
        "beta" -> ".beta"
        "canary" -> ".canary"
        "unstable" -> ".unstable"
        else -> ""
    } + "-$pid.scope"

    @Volatile private var managed = false

    /** Called only for the UI process, before it starts taking up terminals or loading projects. */
    fun start(args: Array<String>, adopting: Boolean) {
        if (!Handover.supported || !Files.isExecutable(Path.of(BUSCTL))) return
        val launcher = Restart.launcher() ?: return
        val original = currentCgroup() ?: return
        if (original == cgroupRoot || !Files.exists(cgroupRoot.resolve("cgroup.controllers"))) return

        if (!owns(original) || !hasLimits(original)) {
            // An in-place upgrade may inherit Chromium's scope with running agents in it. Leave
            // that scope alone and move only our PID when systemd says the requested name is in use.
            val chromiumError = create(chromiumScope)
            val unit = if (chromiumError == null) chromiumScope else nopScope
            val error = if (chromiumError == null) null else create(unit)
            val cgroup = if (error == null) awaitCgroup(unit) else null
            if (cgroup == null) {
                Log.warn("memory: could not enter a private user scope: ${error ?: unit}")
                return
            }
            if (!ensureLimits(cgroup)) return
            // Linux charges memory where it was allocated. Re-exec before restoring the UI to
            // charge the JVM itself here, keeping the inherited PTY masters on a restart.
            if (cgroup != original) {
                val keep = if (adopting) Posix.openMasters() else emptyList()
                val errno = Restart.execNow(launcher, args.toList(), keep)
                Log.warn("memory: could not re-exec in $unit (errno $errno)")
            }
        }
        managed = true
        Log.info("memory: using ${currentCgroup()?.fileName} with swap disabled and 3 GiB MemoryLow")
        watch()
    }

    /** PTY commands and project tooling share a sibling scope with the user's workload limits. */
    fun workload(command: List<String>): List<String> = if (!managed) command else
        listOf("/bin/sh", "-c", workloadScript, "nop-workload", workloadScope) + command

    // Enter the workload scope before exec so a terminal keeps its PID, PTY and exit status.
    // Several children can race to create it when the previous workload scope has emptied.
    private val workloadScript = """
        unit=$1
        shift
        manager() {
            $BUSCTL --user --quiet --timeout=2s --allow-interactive-authorization=no call \
                $SYSTEMD /org/freedesktop/systemd1 $MANAGER "$@"
        }
        for attempt in 1 2 3 4; do
            manager AttachProcessesToUnit ssau "${'$'}unit" '' 1 "$$" 2>/dev/null ||
                manager StartTransientUnit 'ssa(sv)a(sa(sv))' "${'$'}unit" fail 5 \
                    Description s 'nop terminal and project workloads' Slice s app.slice \
                    Delegate b true CollectMode s inactive-or-failed PIDs au 1 "$$" 0 2>/dev/null
            for check in 1 2 3 4; do
                IFS= read -r cgroup < /proc/self/cgroup
                if [ "${'$'}{cgroup##*/}" = "${'$'}unit" ]; then exec "$@"; fi
                /bin/sleep 0.05
            done
        done
        echo "nop: could not enter workload scope ${'$'}unit" >&2
        exec "$@"
    """.trimIndent()

    private fun create(unit: String): String? = call(
        "StartTransientUnit", "ssa(sv)a(sa(sv))", unit, "fail", "5",
        "Description", "s", "nop editor (PID $pid)",
        "Slice", "s", "app.slice",
        "PIDs", "au", "1", pid,
        "MemorySwapMax", "t", "0",
        "MemoryLow", "t", MEMORY_LOW,
        "0",
    )

    private fun awaitCgroup(unit: String): Path? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        do {
            currentCgroup()?.let { if (it.fileName.toString() == unit) return it }
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
        return null
    }

    private fun watch() = thread(name = "nop-memory-scope", isDaemon = true) {
        // A manager reload can bring back a generic app scope drop-in. Also, after an in-place
        // upgrade the old Chromium scope can empty before the embedded browser first opens.
        // If CEF then creates it, it belongs to this UI PID and its new workload scopes stay separate.
        while (true) {
            Thread.sleep(5_000)
            currentCgroup()?.takeIf(::owns)?.let(::ensureLimits)
        }
    }

    private fun owns(cgroup: Path): Boolean {
        val unit = cgroup.fileName.toString()
        return unit == nopScope || unit == chromiumScope
    }

    private fun currentCgroup(): Path? = runCatching {
        Files.readAllLines(Path.of("/proc/self/cgroup"))
            .firstOrNull { it.startsWith("0::/") }
            ?.let { cgroupRoot.resolve(it.removePrefix("0::/")) }
    }.getOrNull()

    private fun read(cgroup: Path, name: String): String? =
        runCatching { Files.readString(cgroup.resolve(name)).trim() }.getOrNull()

    private fun hasLimits(cgroup: Path): Boolean =
        read(cgroup, "memory.swap.max") == "0" && read(cgroup, "memory.low") == MEMORY_LOW

    private fun ensureLimits(cgroup: Path): Boolean {
        if (hasLimits(cgroup)) return true
        // Generic app scope drop-ins can override transient creation properties. Set the
        // properties on this running unit and check the effective kernel values.
        val unit = cgroup.fileName.toString()
        val error = call("SetUnitProperties", "sba(sv)", unit, "true", "2",
            "MemorySwapMax", "t", "0", "MemoryLow", "t", MEMORY_LOW)
        if (error == null && hasLimits(cgroup)) return true
        Log.warn("memory: could not protect $unit: ${error ?: "memory controller did not apply the limits"}")
        return false
    }

    private fun call(vararg args: String): String? = runCatching {
        val child = ProcessBuilder(
            BUSCTL, "--user", "--quiet", "--timeout=2s", "--allow-interactive-authorization=no",
            "call", SYSTEMD, "/org/freedesktop/systemd1", MANAGER, *args,
        ).redirectErrorStream(true).start()
        child.inputStream.use { output ->
            if (!child.waitFor(3, TimeUnit.SECONDS)) {
                child.destroyForcibly()
                "systemd user manager timed out"
            } else if (child.exitValue() == 0) {
                null
            } else {
                output.readNBytes(1024).decodeToString().trim().ifBlank { "busctl exited ${child.exitValue()}" }
            }
        }
    }.getOrElse { it.message ?: it.javaClass.simpleName }
}
