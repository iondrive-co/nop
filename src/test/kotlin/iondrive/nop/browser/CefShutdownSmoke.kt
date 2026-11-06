package iondrive.nop.browser

import iondrive.nop.RestartInPlace
import iondrive.nop.ipc.Restart
import iondrive.nop.terminal.Posix
import org.cef.CefApp
import org.cef.callback.CefCookieVisitor
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Takes the real [SlackBrowsers] through a quit and through a restart in place, on blank pages with a
 * scratch profile. CEF allows one runtime per process and a failure here can be a native crash, so
 * every case runs in a JVM of its own, and passes only by saying so and exiting 0.
 */
object CefShutdownSmoke {
    private const val PASSED = "CEF_SHUTDOWN_PASSED"
    private const val CASE_TIMEOUT_SECONDS = 120L
    /** A visit answers in milliseconds; this is for a loaded CI runner. */
    private const val COOKIE_WAIT_SECONDS = 30L
    private const val LOG_LINES_SHOWN = 60
    private const val COOKIE_URL = "https://cookie.test/"
    private const val COOKIE_NAME = "kept"

    private val cases = listOf(
        "quit-page-never-shown", "quit-page-shown", "quit-after-its-window-closed", "quit-one-page-shown-one-not",
        "quit-after-reopening", "quit-while-opening", "restart", "restart-after-its-window-closed",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        when {
            args.size == 1 -> runAll(Path.of(args[0]))
            args[0] == "second-half" -> caseEnded { restartSecondHalf(Path.of(args[1]), args.drop(2).map { it.toLong() }) }
            else -> caseEnded { runCase(args[0], Path.of(args[1])) }
        }
    }

    private fun caseEnded(body: () -> Unit) {
        val code = try {
            body()
            println(PASSED)
            0
        } catch (failure: Throwable) {
            failure.printStackTrace()
            1
        }
        exitProcess(code)
    }

    private fun runAll(scratch: Path) {
        val data = scratch.resolve("data")
        val failed = cases.filterNot { name ->
            // The runtime stays between cases, to be unpacked once; the profile does not.
            data.resolve("nop/browser/slack").toFile().deleteRecursively()
            val project = scratch.resolve("project").also(Files::createDirectories)
            val output = scratch.resolve("$name.log")
            val process = ProcessBuilder(
                ProcessHandle.current().info().command().get(),
                "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=${scratch.resolve("$name-hs_err_%p.log")}",
                "-cp", System.getProperty("java.class.path"), CefShutdownSmoke::class.java.name, name, project.toString(),
            ).apply {
                environment().apply {
                    put("XDG_DATA_HOME", data.toString())
                    put("XDG_CONFIG_HOME", scratch.resolve("config").toString())
                }
                redirectErrorStream(true)
                redirectOutput(output.toFile())
            }.start()
            val finished = process.waitFor(CASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            val passed = finished && process.exitValue() == 0 && Files.readString(output).lines().contains(PASSED)
            println("$name: ${if (passed) "passed" else "FAILED (${if (finished) "exit ${process.exitValue()}" else "timed out"}), see $output"}")
            // A CI runner's files are gone with it, so what went wrong has to be in the build's output.
            if (!passed) Files.readAllBytes(output).decodeToString().lines().takeLast(LOG_LINES_SHOWN).forEach { println("    $it") }
            passed
        }
        if (failed.isNotEmpty()) {
            System.err.println("Failed: $failed")
            exitProcess(1)
        }
    }

    private fun runCase(name: String, project: Path) {
        when (name) {
            "quit-page-never-shown" -> {
                SlackBrowsers.connect(project, "about:blank")
                browserOf(project)
                Thread.sleep(1_000)
            }
            "quit-page-shown" -> showPage(project)
            "quit-after-its-window-closed" -> {
                showPage(project)
                closeWindowsAWhileBeforeTheBrowsers()
            }
            "quit-one-page-shown-one-not" -> {
                showPage(project)
                val other = project.resolveSibling("other-project").also(Files::createDirectories)
                SlackBrowsers.connect(other, "about:blank")
                browserOf(other)
                Thread.sleep(1_000)
            }
            "quit-after-reopening" -> {
                val frame = showPage(project)
                SlackBrowsers.connect(project, "about:blank")
                val next = browserOf(project)
                SwingUtilities.invokeAndWait {
                    frame.contentPane.add(next.uiComponent)
                    frame.revalidate()
                }
                awaitLoaded(project)
            }
            "quit-while-opening" -> {
                SlackBrowsers.connect(project, "about:blank")
                val browser = browserOf(project)
                SwingUtilities.invokeAndWait { frameHolding(browser.uiComponent) }
            }
            "restart" -> return restartFirstHalf(project, windowsGoFirst = false)
            "restart-after-its-window-closed" -> return restartFirstHalf(project, windowsGoFirst = true)
            else -> error("no case called $name")
        }
        quit()
    }

    private fun closeWindowsAWhileBeforeTheBrowsers() {
        SwingUtilities.invokeAndWait { java.awt.Window.getWindows().forEach { it.dispose() } }
        Thread.sleep(2_000)
    }

    /** What nop's windows closing does at a quit: they go first, and Chromium after them. */
    private fun quit() {
        SwingUtilities.invokeAndWait {
            java.awt.Window.getWindows().forEach { it.dispose() }
            SlackBrowsers.disposeAll()
        }
        check(SlackBrowsers.awaitShutdown()) { "Chromium did not terminate" }
        check(CefApp.getState() == CefApp.CefAppState.TERMINATED) { "Chromium is ${CefApp.getState()}" }
        // Whatever was queued behind the native shutdown has had its turn when the process is still here.
        Thread.sleep(2_000)
    }

    private fun restartFirstHalf(project: Path, windowsGoFirst: Boolean) {
        showPage(project)
        check(CefCookieManager.getGlobalManager().setCookie(COOKIE_URL, CefCookie(
            COOKIE_NAME, "1", "cookie.test", "/", true, false,
            Date(), Date(), true, Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)),
        ))) { "the cookie was refused" }
        // setCookie only queues the cookie. A page's cookies are in the store long before a restart, so
        // the restart starts once this one is.
        check(cookieIsSet()) { "the cookie was never set" }
        // RestartInPlace.prepare, then the windows closing as they do on the way to the exec.
        SwingUtilities.invokeAndWait { SlackBrowsers.leaveForExec() }
        if (windowsGoFirst) closeWindowsAWhileBeforeTheBrowsers()
        SwingUtilities.invokeAndWait {
            java.awt.Window.getWindows().forEach { it.dispose() }
            SlackBrowsers.disposeAll()
        }
        check(SlackBrowsers.awaitShutdown()) { "Chromium's cookies were not written" }
        check(CefApp.getState() == CefApp.CefAppState.INITIALIZED) { "a restart must not shut Chromium down, but it is ${CefApp.getState()}" }

        val helpers = ProcessHandle.current().descendants().map { it.pid() }.toList()
        check(helpers.isNotEmpty()) { "Chromium has no helper processes to watch" }
        val java = ProcessHandle.current().info().command().get()
        Posix.keepOnlyAcrossExec(emptyList())
        val errno = Posix.exec(
            java,
            listOf(java, "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=${project.resolveSibling("second-half-hs_err_%p.log")}",
                "-cp", System.getProperty("java.class.path"), CefShutdownSmoke::class.java.name,
                "second-half", project.toString()) + helpers.map { it.toString() },
            Restart.successorEnvironment(System.getenv()),
        )
        error("exec failed with errno $errno")
    }

    /** In the same process, now running the next program: nothing of the first Chromium is left in the way. */
    private fun restartSecondHalf(project: Path, helpers: List<Long>) {
        RestartInPlace.reapStrays(ProcessHandle.current().children().map { it.pid() }.toList())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (helpers.any(::running)) {
            check(System.nanoTime() < deadline) { "Chromium's helpers outlived the exec: ${helpers.filter(::running)}" }
            Thread.sleep(100)
        }
        showPage(project)
        check(cookieIsSet()) { "the cookie did not survive the restart" }
        quit()
    }

    /**
     * Whether the store holds the test's cookie, asking again each second: a visit made just behind
     * setCookie can miss the cookie, and a visit that finds nothing never calls its visitor at all.
     */
    private fun cookieIsSet(): Boolean {
        val found = CountDownLatch(1)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COOKIE_WAIT_SECONDS)
        while (true) {
            check(CefCookieManager.getGlobalManager().visitUrlCookies(COOKIE_URL, false, CefCookieVisitor { cookie, _, _, _ ->
                if (cookie.name == COOKIE_NAME) found.countDown()
                true
            })) { "the cookies could not be read" }
            if (found.await(1, TimeUnit.SECONDS)) return true
            if (System.nanoTime() > deadline) return false
        }
    }

    private fun running(pid: Long): Boolean {
        val stat = runCatching { Files.readString(Path.of("/proc/$pid/stat")) }.getOrNull() ?: return false
        return stat.substringAfterLast(") ").firstOrNull() != 'Z'
    }

    private fun showPage(project: Path): JFrame {
        SlackBrowsers.connect(project, "about:blank")
        val browser = browserOf(project)
        lateinit var frame: JFrame
        SwingUtilities.invokeAndWait { frame = frameHolding(browser.uiComponent) }
        awaitLoaded(project)
        return frame
    }

    private fun frameHolding(component: java.awt.Component) = JFrame("CEF shutdown").apply {
        contentPane.add(component)
        setSize(480, 320)
        isVisible = true
    }

    private fun browserOf(project: Path): org.cef.browser.CefBrowser {
        val session = SlackBrowsers.of(project)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (true) {
            val state = session.state.value
            check(state.error == null) { "the browser did not start: ${state.error}" }
            state.browser?.let { return it }
            check(System.nanoTime() < deadline) { "the browser did not start: ${state.preparing}" }
            Thread.sleep(50)
        }
    }

    private fun awaitLoaded(project: Path) {
        val session = SlackBrowsers.of(project)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (session.state.value.loading) {
            check(System.nanoTime() < deadline) { "the page did not load" }
            Thread.sleep(50)
        }
    }
}
