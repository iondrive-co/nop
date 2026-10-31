package iondrive.nop.browser;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Run with testSlackSidebar in a separate JVM: CEF cannot be reinitialized after shutdown. */
public class SlackSidebarSmoke {
    public static void main(String[] args) {
        int exitCode = 0;
        try {
            run(Path.of(args[0]));
        } catch (Throwable failure) {
            failure.printStackTrace();
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    private static String resource(String name) throws Exception {
        try (var stream = SlackSidebarSmoke.class.getResourceAsStream(name)) {
            if (stream == null) throw new AssertionError("Missing resource: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void run(Path scratch) throws Exception {
        Files.createDirectories(scratch);
        var results = new LinkedBlockingQueue<String>();
        var page = resource("/browser/slack-sidebar.html").getBytes(StandardCharsets.UTF_8);
        var guard = "if (location.protocol !== \"https:\" || location.hostname !== \"app.slack.com\") return;";
        var original = resource("/browser/slack-layout.js");
        if (!original.contains(guard)) throw new AssertionError("Slack origin guard changed; update the test adapter");
        // Adapt only the origin guard for our loopback fixture. Everything else is the shipped script.
        var script = original.replace(guard, "if (location.hostname !== \"127.0.0.1\") return;");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                if (exchange.getRequestURI().getPath().equals("/result")) {
                    results.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    exchange.sendResponseHeaders(204, -1);
                } else {
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, page.length);
                    exchange.getResponseBody().write(page);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();

        CefApp app = null;
        org.cef.CefClient client = null;
        JFrame frame = new JFrame("Slack sidebar regression fixture");
        var ended = new CountDownLatch(1);
        try {
            // Own profile and native runtime; never open or copy the user's Slack profile.
            var profile = Files.createTempDirectory(scratch, "profile-");
            var builder = new CefAppBuilder();
            builder.setInstallDir(scratch.resolve("runtime").toFile());
            builder.getCefSettings().windowless_rendering_enabled = false;
            builder.getCefSettings().root_cache_path = profile.toString();
            builder.getCefSettings().cache_path = profile.resolve("Default").toString();
            builder.getCefSettings().log_severity = CefSettings.LogSeverity.LOGSEVERITY_DISABLE;
            builder.addJcefArgs("--disable-gpu", "--no-first-run", "--no-default-browser-check");
            builder.setAppHandler(new MavenCefAppHandlerAdapter() {
                @Override public void stateHasChanged(CefApp.CefAppState state) {
                    if (state == CefApp.CefAppState.TERMINATED) ended.countDown();
                }
            });
            app = builder.build();
            client = app.createClient();
            client.addLoadHandler(new CefLoadHandlerAdapter() {
                @Override public void onLoadEnd(CefBrowser browser, CefFrame loadedFrame, int status) {
                    if (!loadedFrame.isMain()) return;
                    var wrapped = loadedFrame.getURL().contains("wrapped=1");
                    browser.executeJavaScript((wrapped ? script : "") + "\nwindow.runFixture();", loadedFrame.getURL(), 0);
                }
            });
            String origin = "http://127.0.0.1:" + server.getAddress().getPort() + "/client/TEXAMPLE";
            var browser = client.createBrowser(origin + "?mode=transform", false, false);
            SwingUtilities.invokeAndWait(() -> {
                frame.add(browser.getUIComponent());
                frame.setSize(900, 650);
                frame.setVisible(true);
            });
            // Baseline proves that the fixture works before applying nop's script.
            for (String test : new String[]{"baseline", "transform", "top"}) {
                if (!test.equals("baseline")) browser.loadURL(origin + "?mode=" + test + "&wrapped=1");
                String result = results.poll(45, TimeUnit.SECONDS);
                if (result == null) throw new AssertionError("Sidebar test timed out: " + test);
                System.out.println("SLACK_SIDEBAR " + test + " " + result);
                if (!JsonParser.parseString(result).getAsJsonObject().get("passed").getAsBoolean()) {
                    throw new AssertionError("Sidebar regression: " + test);
                }
            }
        } finally {
            if (client != null) client.dispose();
            SwingUtilities.invokeAndWait(frame::dispose);
            server.stop(0);
            if (app != null) {
                app.dispose();
                ended.await(5, TimeUnit.SECONDS);
            }
        }
    }
}
