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
import java.awt.Robot;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
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
        JFrame floating = new JFrame("Floating Slack regression fixture");
        var ended = new CountDownLatch(1);
        try {
            // Own profile and native runtime; never open or copy the user's Slack profile.
            var profile = Files.createTempDirectory(scratch, "profile-");
            var builder = new CefAppBuilder();
            var runtime = scratch.resolve(CefRuntime.INSTANCE.getDirectoryName());
            builder.setInstallDir(runtime.toFile());
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
            builder.install();
            CefRuntime.INSTANCE.verify(runtime);
            builder.setSkipInstallation(true);
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
            evaluate(browser, "window.fixtureIdentity = crypto.randomUUID(); " +
                "document.querySelector('.p-view_contents--primary').innerHTML = '<input id=transfer-draft value=Draft style=margin:40px;width:200px;height:40px>'; true");
            var identity = evaluate(browser, "window.fixtureIdentity");
            var robot = new Robot();
            for (int cycle = 0; cycle < 4; cycle++) {
                final JFrame destination = cycle % 2 == 0 ? floating : frame;
                SwingUtilities.invokeAndWait(() -> {
                    destination.add(browser.getUIComponent());
                    destination.setSize(900 + (destination == floating ? 80 : 0), 650);
                    destination.setVisible(true);
                    destination.toFront();
                    destination.validate();
                    browser.setFocus(true);
                });
                robot.delay(500);
                if (!evaluate(browser, "window.fixtureIdentity").equals(identity)) throw new AssertionError("Browser page was replaced during transfer");
                // Real pointer and keyboard input checks the native child, not only its Java wrapper.
                var bounds = JsonParser.parseString(evaluate(browser,
                    "JSON.stringify((() => {const r = document.querySelector('#transfer-draft').getBoundingClientRect(); return {x:r.x+20,y:r.y+20};})())")).getAsJsonObject();
                final java.awt.Point point = new java.awt.Point();
                SwingUtilities.invokeAndWait(() -> point.setLocation(browser.getUIComponent().getLocationOnScreen()));
                robot.mouseMove(point.x + bounds.get("x").getAsInt(), point.y + bounds.get("y").getAsInt());
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                robot.keyPress(KeyEvent.VK_END);
                robot.keyRelease(KeyEvent.VK_END);
                robot.keyPress(KeyEvent.VK_X);
                robot.keyRelease(KeyEvent.VK_X);
                robot.delay(200);
                String expected = "Draft" + "x".repeat(cycle + 1);
                if (!evaluate(browser, "document.querySelector('#transfer-draft').value").equals(expected)) {
                    throw new AssertionError("Draft or keyboard focus lost on browser transfer " + cycle);
                }
                System.out.println("SLACK_TRANSFER " + cycle + " draft and native focus preserved");
            }
        } finally {
            if (client != null) client.dispose();
            SwingUtilities.invokeAndWait(frame::dispose);
            SwingUtilities.invokeAndWait(floating::dispose);
            server.stop(0);
            if (app != null) {
                app.dispose();
                ended.await(5, TimeUnit.SECONDS);
            }
        }
    }

    private static String evaluate(CefBrowser browser, String expression) throws Exception {
        var response = browser.getDevToolsClient().executeDevToolsMethod("Runtime.evaluate",
            "{\"expression\":" + new com.google.gson.Gson().toJson(expression) + ",\"returnByValue\":true}").get(5, TimeUnit.SECONDS);
        return JsonParser.parseString(response).getAsJsonObject().getAsJsonObject("result").get("value").getAsString();
    }
}
