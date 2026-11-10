package iondrive.nop.worker

import iondrive.nop.Log
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The project worker's side: `nop --project-worker` reads [WorkRequest]s, one JSON line each, from
 * its input and writes a [WorkReply] for each to its output, until its input closes.
 *
 * Requests run side by side on a few threads, so a status walk is not held up behind a long index
 * build; replies carry the request's id and come back in the order they finish. Its output is the
 * protocol and nothing else: anything else the process prints goes to stderr.
 *
 * Running out of memory ends the process once the reply saying so is written. The heap is then in no
 * state to be trusted, and nop starts a fresh worker for the next request.
 */
internal object WorkerProcess {
    const val FLAG = "--project-worker"

    private const val THREADS = 3

    internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** Serves requests from [input] until it closes, then finishes the ones running. Returns the exit status. */
    fun serve(input: InputStream, output: OutputStream): Int {
        val replies = PrintStream(output, false, Charsets.UTF_8)
        System.setOut(System.err)
        val pool = Executors.newFixedThreadPool(THREADS) { r -> Thread(r, "project-worker").apply { isDaemon = true } }
        val lock = Any()
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val request = runCatching { json.decodeFromString(WorkRequest.serializer(), line) }.getOrElse {
                    Log.warn("project worker: unreadable request: ${line.take(200)}")
                    continue
                }
                pool.execute {
                    val reply = handle(request)
                    synchronized(lock) {
                        replies.println(json.encodeToString(WorkReply.serializer(), reply))
                        replies.flush()
                    }
                    if (reply.outOfMemory) {
                        Log.warn("project worker ran out of memory on ${request.op} of ${request.root}; ending it")
                        Runtime.getRuntime().halt(EXIT_OUT_OF_MEMORY)
                    }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.HOURS)
        return 0
    }

    internal fun handle(request: WorkRequest): WorkReply = try {
        val root = Path.of(request.root)
        when (request.op) {
            WorkRequest.STATUS -> ProjectWork.status(root).toReply(request.id)
            WorkRequest.DIRTY -> WorkReply(request.id, dirty = ProjectWork.dirty(root))
            WorkRequest.INDEX -> {
                val dataDir = Path.of(requireNotNull(request.dataDir) { "an index request needs dataDir" })
                when (val done = ProjectWork.index(root, dataDir, request.checkFresh, request.reusePrevious, request.since, request.cachedFiles)) {
                    ProjectWork.Indexed.Fresh -> WorkReply(request.id, fresh = true)
                    is ProjectWork.Indexed.Built -> WorkReply(request.id, fresh = false, files = done.files)
                }
            }
            else -> WorkReply(request.id, error = "unknown op ${request.op}")
        }
    } catch (oom: OutOfMemoryError) {
        WorkReply(request.id, error = "out of memory", outOfMemory = true)
    } catch (t: Throwable) {
        WorkReply(request.id, error = t.toString())
    }

    const val EXIT_OUT_OF_MEMORY = 3
}
