package iondrive.nop.browser

import org.cef.CefApp
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeRegistrar
import org.cef.handler.CefResourceHandlerAdapter
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Native authentication popups have no Java CefBrowser, so per-tab handlers cannot catch them. */
internal object SlackSchemeHandler {
    fun register(registrar: CefSchemeRegistrar) {
        registrar.addCustomScheme("slack", true, false, false, true, false, false, false)
    }

    fun install(app: CefApp) {
        check(app.registerSchemeHandlerFactory("slack", "") { _, frame, _, request ->
            // Keep only the canonical web destination; the desktop login credential stays out
            // of the redirect, response body, and application state.
            val web = SlackNavigation.webDeepLink(request.url)
            if (web != null && frame?.isMain == true) {
                // The frame is available even when the native popup has no Java browser.
                // Return the completed sign-in to its opener and close the authentication
                // window. The HTTP redirect below also works when a provider removed opener.
                val destination = Json.encodeToString(web)
                frame.executeJavaScript("""
                    try {
                        if (window.opener && !window.opener.closed) {
                            window.opener.location.href = $destination;
                            window.close();
                        }
                    } catch (_) {}
                """.trimIndent(), "nop:slack-navigation", 0)
            }
            object : CefResourceHandlerAdapter() {
                override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
                    callback.Continue()
                    return true
                }

                override fun getResponseHeaders(response: CefResponse, length: IntRef, redirect: StringRef) {
                    response.status = if (web == null) 400 else 302
                    response.statusText = if (web == null) "Bad Request" else "Found"
                    response.mimeType = "text/plain"
                    response.setHeaderMap(mapOf("Cache-Control" to "no-store", "Referrer-Policy" to "no-referrer"))
                    length.set(0)
                    if (web != null) redirect.set(web)
                }

                override fun readResponse(data: ByteArray, count: Int, read: IntRef, callback: CefCallback): Boolean {
                    read.set(0)
                    return false
                }
            }
        }) { "Could not register Slack browser navigation" }
    }
}
