package iondrive.nop.worker

import iondrive.nop.Log
import iondrive.nop.ipc.Restart
import java.io.BufferedWriter
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Runs [ProjectWork] in a process of its own, so that a project tree too big for the heap ends that
 * process and not nop with every terminal in it.
 *
 * The process is nop's own launcher started with [WorkerProcess.FLAG]: started on the first request,
 * left to exit after [IDLE_MS] without one, and started afresh after it dies. A request it cannot
 * answer — it died, or is not there — throws [Unavailable], and the caller carries on as it does when
 * a walk fails; it is not run here instead, since the walk that killed the worker would kill nop.
 * A nop with no launcher to start (run from a classpath, as in tests) does the work in place.
 *
 * Every call blocks: callers are already off the UI thread.
 */
internal object ProjectWorker {
    class Unavailable(message: String) : IllegalStateException(message)

    /** How to start a worker, or null to do the work in this process. */
    @Volatile internal var command: () -> List<String>? = {
        Restart.launcher()?.let { listOf(it.toString(), WorkerProcess.FLAG) }
    }

    /** The environment a worker starts with: nop's own, less what only nop's own process should carry. */
    @Volatile internal var workerEnvironment: () -> Map<String, String> = {
        boundedToWorkerCpus(Restart.successorEnvironment(System.getenv()), Runtime.getRuntime().availableProcessors())
    }

    /**
     * The worker told it has a fraction of the machine's processors. One worker serves every project
     * and session of this nop, and the JVM sizes its garbage collector, its compilers and the Java
     * parser's thread pool from this one number: by default a large index build would take most of a
     * machine that is also running the agents the index is for.
     *
     * Set through the environment because the worker is started by nop's launcher, which reads its
     * JVM options from the application's own configuration; the JVM's notice that it honoured this
     * goes to the worker's stderr, which is discarded.
     */
    internal fun boundedToWorkerCpus(env: Map<String, String>, processors: Int): Map<String, String> {
        val cpus = (processors / 4).coerceIn(MIN_WORKER_CPUS, MAX_WORKER_CPUS)
        val options = listOfNotNull(env[JVM_OPTIONS]?.takeIf { it.isNotBlank() }, "-XX:ActiveProcessorCount=$cpus")
        return env + (JVM_OPTIONS to options.joinToString(" "))
    }

    private const val JVM_OPTIONS = "JAVA_TOOL_OPTIONS"
    private const val MIN_WORKER_CPUS = 2
    private const val MAX_WORKER_CPUS = 4

    internal var idleMs: Long = IDLE_MS

    private const val IDLE_MS = 5L * 60 * 1000
    private const val RESTART_BACKOFF_MS = 10_000L
    private const val STATUS_TIMEOUT_S = 10L * 60
    private const val INDEX_TIMEOUT_S = 60L * 60

    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<WorkReply>>()
    private var running: Running? = null
    private var diedAt = Long.MIN_VALUE / 2
    private var lastUse = 0L
    private val reaper = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "project-worker-idle").apply { isDaemon = true } }
        .also { it.scheduleWithFixedDelay(::closeIfIdle, 60, 60, TimeUnit.SECONDS) }

    private class Running(val process: Process, val input: BufferedWriter) {
        @Volatile var closing = false
    }

    fun status(root: Path): ProjectWork.Status {
        if (command() == null) return ProjectWork.status(root)
        return call(WorkRequest(ids.incrementAndGet(), WorkRequest.STATUS, root.toString()), STATUS_TIMEOUT_S).toStatus()
    }

    fun dirty(root: Path): Boolean {
        if (command() == null) return ProjectWork.dirty(root)
        return call(WorkRequest(ids.incrementAndGet(), WorkRequest.DIRTY, root.toString()), STATUS_TIMEOUT_S).dirty ?: false
    }

    /** See [ProjectWork.index]. [stop] is honoured only when the work is done in this process. */
    fun index(
        root: Path,
        dataDir: Path,
        checkFresh: Boolean,
        reusePrevious: Boolean,
        since: Long,
        cachedFiles: Int,
        stop: () -> Boolean = { false },
    ): ProjectWork.Indexed {
        if (command() == null) return ProjectWork.index(root, dataDir, checkFresh, reusePrevious, since, cachedFiles, stop)
        val reply = call(
            WorkRequest(
                ids.incrementAndGet(), WorkRequest.INDEX, root.toString(), dataDir.toString(),
                checkFresh, reusePrevious, since, cachedFiles,
            ),
            INDEX_TIMEOUT_S,
        )
        return if (reply.fresh == true) ProjectWork.Indexed.Fresh else ProjectWork.Indexed.Built(reply.files ?: 0)
    }

    private fun call(request: WorkRequest, timeoutS: Long): WorkReply {
        val answer = CompletableFuture<WorkReply>()
        pending[request.id] = answer
        try {
            send(request)
            val reply = try {
                answer.get(timeoutS, TimeUnit.SECONDS)
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
            reply.error?.let { throw IllegalStateException("project worker: $it") }
            return reply
        } finally {
            pending.remove(request.id)
            synchronized(this) { lastUse = System.currentTimeMillis() }
        }
    }

    private fun send(request: WorkRequest) = synchronized(this) {
        val worker = running ?: start()
        val line = WorkerProcess.json.encodeToString(WorkRequest.serializer(), request)
        try {
            worker.input.write(line)
            worker.input.newLine()
            worker.input.flush()
        } catch (e: java.io.IOException) {
            throw Unavailable("project worker stopped taking work: $e")
        }
        lastUse = System.currentTimeMillis()
    }

    /** Starts a worker. Called holding the lock. */
    private fun start(): Running {
        val now = System.currentTimeMillis()
        if (now - diedAt < RESTART_BACKOFF_MS) throw Unavailable("project worker died moments ago; not starting another yet")
        val command = command() ?: throw Unavailable("no launcher to start a project worker with")
        val env = workerEnvironment()
        val process = try {
            ProcessBuilder(command)
                .apply {
                    environment().clear()
                    environment().putAll(env)
                }
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e: Exception) {
            diedAt = now
            throw Unavailable("could not start a project worker: $e")
        }
        val worker = Running(process, process.outputStream.bufferedWriter(Charsets.UTF_8))
        running = worker
        thread(isDaemon = true, name = "project-worker-replies") { readReplies(worker) }
        return worker
    }

    private fun readReplies(worker: Running) {
        runCatching {
            worker.process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val reply = runCatching { WorkerProcess.json.decodeFromString(WorkReply.serializer(), line) }.getOrNull() ?: continue
                    pending[reply.id]?.complete(reply)
                }
            }
        }
        val code = runCatching { worker.process.waitFor(5, TimeUnit.SECONDS); worker.process.exitValue() }.getOrNull()
        synchronized(this) {
            if (running === worker) running = null
            if (!worker.closing) {
                diedAt = System.currentTimeMillis()
                Log.warn(
                    "project worker exited" + (code?.let { " with status $it" } ?: "") +
                        if (code == WorkerProcess.EXIT_OUT_OF_MEMORY) ", out of memory; a fresh one starts with the next request" else "",
                )
            }
        }
        val gone = Unavailable("project worker exited" + (code?.let { " with status $it" } ?: ""))
        pending.values.forEach { it.completeExceptionally(gone) }
    }

    internal fun closeIfIdle() {
        synchronized(this) {
            val worker = running ?: return
            if (pending.isNotEmpty() || System.currentTimeMillis() - lastUse < idleMs) return
            worker.closing = true
            running = null
            runCatching { worker.input.close() }
        }
    }

    /** Ends the worker, if one is running: for nop's shutdown, and for tests. */
    fun stop() {
        synchronized(this) {
            val worker = running ?: return
            worker.closing = true
            running = null
            runCatching { worker.input.close() }
            if (!worker.process.waitFor(5, TimeUnit.SECONDS)) worker.process.destroyForcibly()
        }
    }

    /** The running worker's process id, or null with none running. */
    internal fun workerPid(): Long? = synchronized(this) { running?.process?.pid() }

    internal fun resetForTest() {
        stop()
        diedAt = Long.MIN_VALUE / 2
    }
}
