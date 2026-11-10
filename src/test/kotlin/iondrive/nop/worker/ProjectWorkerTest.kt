package iondrive.nop.worker

import iondrive.nop.git.ChangeKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The project worker: its protocol served in this process, then the real thing, a second JVM started
 * the way nop starts it, answering through pipes.
 */
class ProjectWorkerTest {
    @TempDir
    lateinit var tmp: Path

    private val defaultCommand = ProjectWorker.command
    private val defaultEnvironment = ProjectWorker.workerEnvironment

    @AfterEach
    fun restore() {
        ProjectWorker.resetForTest()
        ProjectWorker.command = defaultCommand
        ProjectWorker.workerEnvironment = defaultEnvironment
        ProjectWorker.idleMs = 5L * 60 * 1000
    }

    private fun git(dir: Path, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "git ${args.toList()} failed" }
    }

    /** A repository with one committed file, one edit to it, and one new file. */
    private fun repo(): Path {
        val root = tmp.resolve("repo")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/a.kt"), "fun a() = 1\n")
        git(root, "init", "-q")
        git(root, "-c", "user.name=t", "-c", "user.email=t@example.invalid", "add", ".")
        git(root, "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "init")
        Files.writeString(root.resolve("src/a.kt"), "fun a() = 2\n")
        Files.writeString(root.resolve("src/b.kt"), "fun b() = 3\n")
        return root
    }

    /** Serves in this process: requests written as lines, replies read back as lines. */
    private fun serve(vararg lines: String): List<WorkReply> {
        val toWorker = PipedOutputStream()
        val input = PipedInputStream(toWorker)
        val out = ByteArrayOutputStream()
        val server = thread { WorkerProcess.serve(input, out) }
        toWorker.write(lines.joinToString("\n", postfix = "\n").toByteArray())
        toWorker.close()
        server.join(60_000)
        return out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
            .map { WorkerProcess.json.decodeFromString(WorkReply.serializer(), it) }
            .sortedBy { it.id }
    }

    private fun request(r: WorkRequest) = WorkerProcess.json.encodeToString(WorkRequest.serializer(), r)

    @Test
    fun `the worker answers status, dirty and unknown requests, and skips what it cannot read`() {
        val root = repo().toString()
        val replies = serve(
            request(WorkRequest(1, WorkRequest.STATUS, root)),
            request(WorkRequest(2, WorkRequest.DIRTY, root)),
            "not json",
            request(WorkRequest(3, "launch", root)),
        )
        assertEquals(listOf(1L, 2L, 3L), replies.map { it.id })
        val status = replies[0].toStatus().status
        assertEquals(ChangeKind.MODIFIED, status.byPath["src/a.kt"])
        assertEquals(ChangeKind.UNTRACKED, status.byPath["src/b.kt"])
        assertNotNull(replies[0].head)
        assertEquals(true, replies[1].dirty)
        assertTrue(replies[2].error!!.contains("unknown op"))
    }

    @Test
    fun `the worker writes the index where it is asked, and finds it fresh next time`() {
        val root = repo()
        val data = tmp.resolve("data")
        val built = serve(request(WorkRequest(1, WorkRequest.INDEX, root.toString(), data.toString())))
        assertEquals(false, built.single().fresh)
        assertEquals(2, built.single().files)
        assertEquals(listOf("src/a.kt", "src/b.kt"), Files.readAllLines(data.resolve(ProjectWork.FILES_FILE)))

        val since = Files.getLastModifiedTime(data.resolve(ProjectWork.FILES_FILE)).toMillis()
        root.toFile().walkTopDown().forEach { it.setLastModified(since - 60_000) }
        val again = serve(
            request(WorkRequest(2, WorkRequest.INDEX, root.toString(), data.toString(), checkFresh = true, reusePrevious = true, since = since, cachedFiles = 2)),
        )
        assertEquals(true, again.single().fresh)
    }

    @Test
    fun `with no launcher the work is done in this process`() {
        ProjectWorker.command = { null }
        val status = ProjectWorker.status(repo()).status
        assertEquals(ChangeKind.UNTRACKED, status.byPath["src/b.kt"])
    }

    /** The real thing: a JVM of its own, started with the worker flag, as nop starts its launcher. */
    @Test
    fun `a worker process answers what the work would in place, and leaves when idle`() {
        val java = ProcessHandle.current().info().command().get()
        val classpath = System.getProperty("java.class.path")
        ProjectWorker.command = { listOf(java, "-cp", classpath, "iondrive.nop.MainKt", WorkerProcess.FLAG) }
        ProjectWorker.workerEnvironment = { System.getenv() }
        val root = repo()

        assertEquals(ProjectWork.status(root).status, ProjectWorker.status(root).status)
        assertTrue(ProjectWorker.dirty(root))
        val data = tmp.resolve("data")
        val indexed = ProjectWorker.index(root, data, checkFresh = false, reusePrevious = false, since = 0L, cachedFiles = 0)
        assertEquals(ProjectWork.Indexed.Built(2), indexed)

        val worker = ProcessHandle.of(checkNotNull(ProjectWorker.workerPid()) { "no worker running" }).orElseThrow()
        ProjectWorker.idleMs = 0
        ProjectWorker.closeIfIdle()
        assertTrue(worker.onExit().get(30, TimeUnit.SECONDS) != null)
        assertFalse(worker.isAlive)
    }

    @Test
    fun `a worker that dies fails its request at once, and is not started again straight away`() {
        ProjectWorker.command = { listOf("sh", "-c", "exit 3") }
        ProjectWorker.workerEnvironment = { System.getenv() }
        val root = repo()
        val started = System.nanoTime()
        assertThrows<ProjectWorker.Unavailable> { ProjectWorker.status(root) }
        assertThrows<ProjectWorker.Unavailable> { ProjectWorker.dirty(root) }
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20), "a dead worker kept its caller waiting")
    }
}
