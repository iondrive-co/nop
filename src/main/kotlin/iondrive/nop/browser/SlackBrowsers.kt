package iondrive.nop.browser

import iondrive.nop.Log
import iondrive.nop.Settings
import iondrive.nop.terminal.openUrlInBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import me.friwi.jcefmaven.CefAppBuilder
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.callback.CefSchemeRegistrar
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefDownloadHandler
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler.ErrorCode
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

internal const val SLACK_HOME = "https://app.slack.com/client"

private val slackLayoutScript: String by lazy {
    checkNotNull(SlackBrowsers::class.java.getResource("/browser/slack-layout.js")).readText()
}

/** Tabs belong to projects; only projects visible in a window keep their browser pages alive. */
object SlackBrowsers {
    private data class Key(val project: Path, val workspace: String?)
    private val sessions = mutableMapOf<Key, SlackBrowser>()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableKnownWorkspaces = MutableStateFlow(loadWorkspaces())
    val knownWorkspaces = mutableKnownWorkspaces.asStateFlow()
    private val projectStore = SlackProjectStore(Settings::projectDataDir)
    private class ProjectState(var tabs: SlackWorkspaceTabs) {
        val workspaces = MutableStateFlow(tabs.open)
        val attention = MutableStateFlow<Map<String, SlackAttention>>(emptyMap())
    }
    private val projects = mutableMapOf<Path, ProjectState>()
    private val visibleProjects = mutableMapOf<Path, Int>()

    private fun projectState(project: Path): ProjectState = projects.getOrPut(project.toAbsolutePath().normalize()) {
        ProjectState(SlackWorkspaceTabs(mutableKnownWorkspaces.value, projectStore.load(project)))
    }

    @Synchronized
    fun workspaces(project: Path) = projectState(project).workspaces.asStateFlow()

    @Synchronized
    fun attention(project: Path) = projectState(project).attention.asStateFlow()

    /** A project can be visible in more than one nop window. Release pages after its last view. */
    @Synchronized
    fun retainProjectView(project: Path): AutoCloseable {
        val path = project.toAbsolutePath().normalize()
        visibleProjects[path] = (visibleProjects[path] ?: 0) + 1
        var released = false
        return AutoCloseable {
            synchronized(this) {
                if (!released) {
                    released = true
                    val remaining = (visibleProjects[path] ?: 1) - 1
                    if (remaining > 0) visibleProjects[path] = remaining
                    else {
                        visibleProjects.remove(path)
                        closeProjectBrowsers(path)
                    }
                }
            }
        }
    }

    private fun closeProjectBrowsers(project: Path) {
        sessions.keys.filter { it.project == project }.forEach { sessions.remove(it)?.close() }
        projects[project]?.attention?.value = emptyMap()
    }

    private val runtimeLock = Any()
    private val terminated = CountDownLatch(1)
    @Volatile private var app: CefApp? = null
    @Volatile private var stopping = false
    @Volatile private var runtimeStarted = false

    @Synchronized
    fun of(project: Path, workspaceId: String? = null): SlackBrowser {
        require(workspaceId == null || SlackWorkspaceNames.validId(workspaceId))
        val normalized = project.toAbsolutePath().normalize()
        val session = sessions.getOrPut(Key(normalized, workspaceId)) {
            SlackBrowser(normalized, workspaceId?.let { "https://app.slack.com/client/$it" } ?: SLACK_HOME)
        }
        val current = session.state.value.workspace
        if (workspaceId != null && current != null && current.id != workspaceId) {
            session.navigate("https://app.slack.com/client/$workspaceId")
        }
        return session
    }

    @Synchronized
    fun connect(project: Path) {
        val normalized = project.toAbsolutePath().normalize()
        sessions.remove(Key(normalized, null))?.close()
        sessions[Key(normalized, null)] = SlackBrowser(normalized, "https://app.slack.com/workspace-signin")
    }

    @Synchronized
    fun reopen(project: Path, workspaceId: String) {
        val state = projectState(project)
        val next = state.tabs.reopen(workspaceId)
        if (next == state.tabs) return
        state.tabs = next
        state.workspaces.value = next.open
        projectStore.save(project, next.opened)
    }

    @Synchronized
    fun close(project: Path, workspaceId: String?) {
        val path = project.toAbsolutePath().normalize()
        sessions.remove(Key(path, workspaceId))?.close()
        if (workspaceId != null) {
            val state = projectState(path)
            state.tabs = state.tabs.close(workspaceId)
            state.workspaces.value = state.tabs.open
            state.attention.update { it - workspaceId }
            projectStore.save(path, state.tabs.opened)
        }
    }

    @Synchronized
    internal fun report(project: Path, session: SlackBrowser, metadata: SlackMetadata) {
        if (session !in sessions.values) return
        val incoming = metadata.workspaces + listOfNotNull(metadata.current)
        val merged = SlackWorkspaceNames.merge(mutableKnownWorkspaces.value, incoming)
        if (merged != mutableKnownWorkspaces.value) {
            mutableKnownWorkspaces.value = merged
            projects.values.forEach { state ->
                state.tabs = state.tabs.discover(merged)
                state.workspaces.value = state.tabs.open
            }
            runCatching {
                val file = dataDirectory().resolve("workspaces.json")
                val temporary = file.resolveSibling("workspaces.json.tmp")
                Files.writeString(temporary, json.encodeToString(merged))
                Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }.onFailure { Log.warn("Could not save Slack workspace names: ${it.message}") }
        }
        val current = metadata.current?.takeIf { SlackWorkspaceNames.validId(it.id) } ?: return
        // Other authenticated teams in Slack's metadata are available in the add menu, but
        // only the workspace actually opened here becomes a tab in this project.
        reopen(project, current.id)
        val state = projectState(project)
        state.attention.update { it + (current.id to metadata.attention.sanitized()) }
        val bootstrap = Key(project, null)
        val workspace = Key(project, current.id)
        if (sessions[bootstrap] === session && !sessions.containsKey(workspace)) {
            sessions.remove(bootstrap)
            sessions[workspace] = session
        }
    }

    private fun dataDirectory(): Path {
        val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".local", "share")
        return data.resolve("nop/browser")
    }

    private fun loadWorkspaces(): List<SlackWorkspace> = runCatching {
        val file = dataDirectory().resolve("workspaces.json")
        if (!Files.isRegularFile(file) || Files.size(file) > 65_536) return@runCatching emptyList()
        SlackWorkspaceNames.merge(emptyList(), json.decodeFromString<List<SlackWorkspace>>(Files.readString(file)))
    }.getOrDefault(emptyList())

    internal fun runtime(progress: (String) -> Unit): CefApp = synchronized(runtimeLock) {
        check(!stopping) { "Browser is shutting down" }
        app?.let { return@synchronized it }
        val requestedRoot = dataDirectory()
        privateDirectory(requestedRoot)
        val root = requestedRoot.toRealPath()
        val profile = root.resolve("slack")
        privateDirectory(profile)
        // CEF resolves the root before checking that the profile cache lives below it.
        val profilePath = profile.toRealPath()
        val builder = CefAppBuilder().apply {
            setInstallDir(root.resolve("runtime-152.0.6").toFile())
            setProgressHandler { state, percent ->
                progress("Preparing browser: ${state.name.lowercase().replace('_', ' ')}" +
                    if (percent >= 0) " (${percent.toInt()}%)" else "")
            }
            cefSettings.windowless_rendering_enabled = false
            cefSettings.root_cache_path = profilePath.toString()
            cefSettings.cache_path = profilePath.resolve("Default").toString()
            cefSettings.persist_session_cookies = true
            addJcefArgs("--disable-gpu", "--no-first-run", "--no-default-browser-check")
            // Chromium logs can contain navigation URLs, including sign-in parameters.
            cefSettings.log_severity = org.cef.CefSettings.LogSeverity.LOGSEVERITY_DISABLE
            setAppHandler(object : MavenCefAppHandlerAdapter() {
                override fun onRegisterCustomSchemes(registrar: CefSchemeRegistrar) {
                    SlackSchemeHandler.register(registrar)
                }
                override fun onContextInitialized() {
                    SlackSchemeHandler.install(CefApp.getInstance())
                }
                override fun stateHasChanged(state: CefApp.CefAppState) {
                    if (state == CefApp.CefAppState.TERMINATED) terminated.countDown()
                }
            })
        }
        builder.build().also {
            runtimeStarted = true
            if (stopping) {
                it.dispose()
                error("Browser is shutting down")
            }
            app = it
        }
    }

    @Synchronized
    fun retain(openProjects: Collection<Path>) {
        val open = openProjects.map { it.toAbsolutePath().normalize() }.toSet()
        sessions.keys.filter { it.project !in open }.map { it.project }.distinct().forEach(::closeProjectBrowsers)
        projects.keys.retainAll(open)
    }

    @Synchronized
    fun disposeAll() {
        stopping = true
        sessions.values.forEach { it.close() }
        sessions.clear()
        visibleProjects.clear()
        projects.clear()
        app?.dispose()
        app = null
    }

    /** Called after the window loop, with the AWT thread free to finish closing the profile. */
    fun awaitShutdown() {
        if (!runtimeStarted) return
        try {
            if (!terminated.await(10, TimeUnit.SECONDS)) Log.warn("Chromium did not finish shutting down")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun privateDirectory(path: Path) {
        Files.createDirectories(path)
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        }
    }
}

data class SlackBrowserState(
    val preparing: String? = "Preparing browser…",
    val browser: CefBrowser? = null,
    val url: String = SLACK_HOME,
    val loading: Boolean = true,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val error: String? = null,
    val workspace: SlackWorkspace? = null,
)

class SlackBrowser internal constructor(private val project: Path, private val initialUrl: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(SlackBrowserState())
    val state = mutableState.asStateFlow()
    private var client: CefClient? = null
    @Volatile private var closed = false
    @Volatile private var starting = false
    @Volatile private var appearance = SlackAppearance()
    private val json = Json { ignoreUnknownKeys = true }

    init { start() }

    @Synchronized
    fun start() {
        if (starting || closed || client != null) return
        starting = true
        mutableState.value = SlackBrowserState()
        scope.launch {
            try {
                val app = SlackBrowsers.runtime { progress -> mutableState.update { it.copy(preparing = progress) } }
                withContext(Dispatchers.Swing) {
                    if (!closed) {
                        val next = app.createClient()
                        configure(next)
                        val browser = try {
                            next.createBrowser(initialUrl, false, false)
                        } catch (failure: Throwable) {
                            next.dispose()
                            throw failure
                        }
                        client = next
                        mutableState.update { it.copy(preparing = null, browser = browser) }
                        watchMetadata(browser)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.error("Could not start the Slack browser", failure)
                mutableState.update { it.copy(preparing = null, loading = false, error = failure.message ?: "Could not start browser") }
            } catch (failure: LinkageError) {
                Log.error("Could not load the Slack browser", failure)
                mutableState.update { it.copy(preparing = null, loading = false, error = "Could not load Chromium: ${failure.message}") }
            } finally {
                starting = false
            }
        }
    }

    private fun watchMetadata(browser: CefBrowser) {
        scope.launch {
            while (!closed) {
                delay(1_500)
                if (!SlackNavigation.isAppUrl(mutableState.value.url)) continue
                try {
                    val response = browser.devToolsClient.executeDevToolsMethod("Runtime.evaluate",
                        """{"expression":"window.__nopSlackLayout?.metadata() ?? null","returnByValue":true,"timeout":500}""")
                        .get(2, TimeUnit.SECONDS)
                    if (response.length > 65_536) continue
                    val value = json.parseToJsonElement(response).jsonObject["result"]?.jsonObject?.get("value") ?: continue
                    if (value == kotlinx.serialization.json.JsonNull) continue
                    val metadata = json.decodeFromJsonElement<SlackMetadata>(value)
                    SlackBrowsers.report(project, this@SlackBrowser, metadata)
                    val workspace = metadata.current?.let { SlackWorkspaceNames.merge(emptyList(), listOf(it)).firstOrNull() }
                    mutableState.update { it.copy(workspace = workspace) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Navigations can replace the JavaScript context between the origin check and evaluation.
                }
            }
        }
    }

    fun navigate(url: String) {
        mutableState.value.browser?.loadURL(url)
    }

    fun setAppearance(next: SlackAppearance) {
        if (appearance == next) return
        appearance = next
        val state = mutableState.value
        if (SlackNavigation.isAppUrl(state.url)) state.browser?.executeJavaScript(themeScript(), state.url, 0)
    }

    private fun themeScript(): String = "window.__nopSlackLayout?.setTheme(${json.encodeToString(appearance)});"

    private fun configure(client: CefClient) {
        client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onAddressChange(browser: CefBrowser, frame: CefFrame, url: String) {
                if (frame.isMain && !browser.isPopup) mutableState.update { it.copy(url = url) }
            }
            override fun onConsoleMessage(browser: CefBrowser, level: org.cef.CefSettings.LogSeverity,
                                          message: String, source: String, line: Int): Boolean = true
        })
        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain && !browser.isPopup && SlackNavigation.isAppUrl(frame.url)) {
                    browser.executeJavaScript(slackLayoutScript + "\n" + themeScript(), frame.url, 0)
                }
            }
            override fun onLoadingStateChange(browser: CefBrowser, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
                if (!browser.isPopup) mutableState.update {
                    it.copy(loading = isLoading, canGoBack = canGoBack, canGoForward = canGoForward,
                        error = if (isLoading) null else it.error)
                }
            }
            override fun onLoadError(browser: CefBrowser, frame: CefFrame, errorCode: ErrorCode, errorText: String, failedUrl: String) {
                if (frame.isMain && !browser.isPopup && errorCode != ErrorCode.ERR_ABORTED) {
                    mutableState.update { it.copy(loading = false, error = "Slack could not load: $errorText") }
                }
            }
        })
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(browser: CefBrowser, frame: CefFrame, targetUrl: String, targetFrameName: String): Boolean {
                if (handleDesktopLink(targetUrl)) return true
                if (SlackNavigation.externalLink(browser.url, targetUrl)) {
                    openUrlInBrowser(targetUrl)
                    return true
                }
                // Authentication popups keep their opener and share the browser's cookie profile.
                return targetUrl.isNotEmpty() && !SlackNavigation.isWebUrl(targetUrl) && targetUrl != "about:blank"
            }
        })
        client.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(browser: CefBrowser, frame: CefFrame, request: CefRequest,
                                        userGesture: Boolean, isRedirect: Boolean): Boolean {
                val url = request.url
                if (frame.isMain && handleDesktopLink(url)) return true
                if (frame.isMain && userGesture && !isRedirect && SlackNavigation.externalLink(browser.url, url)) {
                    openUrlInBrowser(url)
                    return true
                }
                if (SlackNavigation.isWebUrl(url) || url == "about:blank") return false
                return true
            }
            override fun onOpenURLFromTab(browser: CefBrowser, frame: CefFrame, targetUrl: String, userGesture: Boolean): Boolean {
                if (handleDesktopLink(targetUrl)) return true
                if (SlackNavigation.externalLink(browser.url, targetUrl)) {
                    openUrlInBrowser(targetUrl)
                    return true
                }
                return false
            }
        })
        client.addDownloadHandler(object : CefDownloadHandler {
            override fun onBeforeDownload(browser: CefBrowser, item: CefDownloadItem, suggestedName: String,
                                          callback: CefBeforeDownloadCallback): Boolean {
                callback.Continue("", true)
                return true
            }
            override fun onDownloadUpdated(browser: CefBrowser, item: CefDownloadItem, callback: CefDownloadItemCallback) {}
        })
    }

    private fun handleDesktopLink(url: String): Boolean {
        val web = SlackNavigation.webDeepLink(url) ?: return false
        SwingUtilities.invokeLater { if (!closed) mutableState.value.browser?.loadURL(web) }
        return true
    }

    fun close() {
        closed = true
        scope.cancel()
        SwingUtilities.invokeLater {
            mutableState.value.browser?.uiComponent?.let { component ->
                component.parent?.let { parent -> parent.remove(component); parent.revalidate(); parent.repaint() }
            }
            client?.dispose()
            client = null
        }
    }
}

internal object SlackNavigation {
    fun isAppUrl(url: String): Boolean = parse(url)?.let {
        it.scheme == "https" && it.host == "app.slack.com" && it.userInfo == null
    } == true

    fun isWebUrl(url: String): Boolean = parse(url)?.let {
        it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null
    } == true

    fun externalLink(current: String, target: String): Boolean {
        val from = parse(current) ?: return false
        val to = parse(target) ?: return false
        return from.host == "app.slack.com" && from.path.orEmpty().startsWith("/client/") &&
            isWebUrl(target) && to.host != "slack.com" && !to.host.orEmpty().endsWith(".slack.com")
    }

    fun webDeepLink(url: String): String? {
        val uri = parse(url)?.takeIf { it.scheme.equals("slack", ignoreCase = true) && it.userInfo == null && it.port == -1 } ?: return null
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val pair = it.split('=', limit = 2)
            if (pair.size == 2) pair[0] to pair[1] else null
        }.toMap()
        val hostTeam = uri.host?.uppercase(java.util.Locale.ROOT)?.takeIf(SlackWorkspaceNames::validId)
        if (hostTeam != null && "team" !in query) {
            // Desktop handoffs after 2FA carry a one-time credential in the path. The web
            // client already shares the authenticated cookies; never forward that credential.
            val path = uri.rawPath.orEmpty()
            if (path.startsWith("/magic-login/") && path.length > "/magic-login/".length) {
                return "https://app.slack.com/client/$hostTeam"
            }
            if (path.isEmpty() || path == "/" || path == "/open") return "https://app.slack.com/client/$hostTeam"
            return null
        }
        val team = query["team"]?.takeIf(SlackWorkspaceNames::validId) ?: return null
        val channel = query["id"]?.takeIf { it.matches(Regex("[CDG][A-Z0-9]+")) }
        return "https://app.slack.com/client/$team" + if (channel == null) "" else "/$channel"
    }

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()
}
