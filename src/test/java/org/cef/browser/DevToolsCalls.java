package org.cef.browser;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * DevTools method calls that always get their reply, for the Chromium smoke tests.
 *
 * <p>JCEF's own {@link CefDevToolsClient} can lose one. Its observer files a reply under the call's
 * message id, completes it, and drops it straight away. Only then, if the calling thread was slow,
 * does {@code executeDevToolsMethod} look the id up, find nothing, and file a new future that
 * nothing will complete. The call then hangs. Chromium answers a call like {@code Runtime.evaluate}
 * within a millisecond, so this happens now and then on a busy machine. The 4-CPU GitHub runner is
 * one: SlackSidebarSmoke timed out there on a plain {@code window.devicePixelRatio}. Here a reply
 * stays until its caller has taken it.
 *
 * <p>It sits in JCEF's package because the native calls it needs are package-private.
 */
public final class DevToolsCalls implements AutoCloseable {
    private final CefBrowser_N browser;
    private final Map<Integer, CompletableFuture<String>> replies = new ConcurrentHashMap<>();
    private final CefRegistration registration;

    public DevToolsCalls(CefBrowser browser) {
        this.browser = (CefBrowser_N) browser;
        registration = this.browser.addDevToolsMessageObserver(new CefDevToolsMessageObserver() {
            @Override public void onDevToolsMethodResult(CefBrowser from, int id, boolean success, String result) {
                var reply = reply(id);
                if (success) reply.complete(result);
                else reply.completeExceptionally(new CefDevToolsClient.DevToolsException("DevTools method failed", result));
            }

            @Override public void onDevToolsEvent(CefBrowser from, String method, String parameters) {
            }
        });
        if (registration == null) throw new IllegalStateException("The browser has no native browser to call yet");
    }

    private CompletableFuture<String> reply(int id) {
        return replies.computeIfAbsent(id, ignored -> new CompletableFuture<>());
    }

    /** Calls {@code method} and returns its result as JSON; {@code parameters} may be null. */
    public String call(String method, String parameters, long timeout, TimeUnit unit) throws Exception {
        int id = browser.executeDevToolsMethod(method, parameters).get(timeout, unit);
        try {
            return reply(id).get(timeout, unit);
        } finally {
            replies.remove(id);
        }
    }

    @Override public void close() {
        registration.dispose();
        replies.clear();
    }
}
