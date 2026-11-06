package iondrive.nop.browser;

import com.google.gson.JsonParser;
import com.sun.jna.Native;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.DevToolsCalls;
import org.cef.handler.CefLoadHandlerAdapter;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Force native memory dumps in an isolated JVM; no real Slack page or profile. */
public class CefMemorySmoke {
    public static void main(String[] args) {
        try {
            run(Path.of(args[0]), java.util.Arrays.asList(args).contains("--original-runtime"),
                    java.util.Arrays.asList(args).contains("--probe-trace"));
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(Path scratch, boolean original, boolean probe) throws Exception {
        Files.createDirectories(scratch);
        var profile = Files.createTempDirectory(scratch, "profile-");
        var builder = new CefAppBuilder();
        Object verifier = null;
        Class<?> verifierClass = null;
        String directory = "original-runtime";
        if (!original) {
            verifierClass = Class.forName("iondrive.nop.browser.CefRuntime");
            verifier = verifierClass.getField("INSTANCE").get(null);
            directory = (String) verifierClass.getMethod("getDirectoryName").invoke(verifier);
        }
        var runtime = scratch.resolve(directory);
        builder.setInstallDir(runtime.toFile());
        builder.getCefSettings().windowless_rendering_enabled = false;
        builder.getCefSettings().root_cache_path = profile.toString();
        builder.getCefSettings().cache_path = profile.resolve("Default").toString();
        builder.getCefSettings().log_severity = CefSettings.LogSeverity.LOGSEVERITY_DISABLE;
        builder.addJcefArgs("--disable-gpu", "--no-first-run", "--disable-background-networking", "--disable-breakpad");
        var ended = new CountDownLatch(1);
        builder.setAppHandler(new MavenCefAppHandlerAdapter() {
            @Override public void stateHasChanged(CefApp.CefAppState state) {
                if (state == CefApp.CefAppState.TERMINATED) ended.countDown();
            }
        });
        builder.install();
        if (!original) verifierClass.getMethod("verify", Path.class).invoke(verifier, runtime);
        builder.setSkipInstallation(true);
        var app = builder.build();
        var client = app.createClient();
        var loaded = new CountDownLatch(1);
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override public void onLoadingStateChange(CefBrowser browser, boolean loading, boolean back, boolean forward) {
                if (!loading) loaded.countDown();
            }
        });
        var browser = client.createBrowser("about:blank", false, false);
        var frame = new JFrame("Native allocator regression");
        DevToolsCalls calls = null;
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame.add(browser.getUIComponent());
                frame.setSize(640, 400);
                frame.setVisible(true);
            });
            if (!loaded.await(30, TimeUnit.SECONDS)) throw new AssertionError("Browser did not load");
            // Events come through JCEF's client; method calls through DevToolsCalls, which never loses a reply.
            var devtools = browser.getDevToolsClient();
            calls = new DevToolsCalls(browser);
            var traceComplete = new CountDownLatch(1);
            var streamHandle = new java.util.concurrent.atomic.AtomicReference<String>();
            devtools.addEventListener((method, json) -> {
                if (!method.equals("Tracing.tracingComplete")) return;
                var data = JsonParser.parseString(json).getAsJsonObject();
                if (data.has("stream")) streamHandle.set(data.get("stream").getAsString());
                traceComplete.countDown();
            });
            calls.call("Tracing.start",
                    "{\"categories\":\"disabled-by-default-memory-infra\",\"transferMode\":\"ReturnAsStream\",\"streamFormat\":\"json\"}",
                    30, TimeUnit.SECONDS);
            long[] sizes = probe ? new long[]{64L << 20} : new long[]{2560L << 20, 4608L << 20};
            for (long size : sizes) {
                long allocation = Native.malloc(size);
                if (allocation == 0) throw new AssertionError("Native allocation failed");
                try {
                    System.out.println("Forcing CEF memory dump above " + size + " bytes");
                    String reply = calls.call("Tracing.requestMemoryDump",
                            "{\"levelOfDetail\":\"detailed\",\"deterministic\":true}", 30, TimeUnit.SECONDS);
                    var result = JsonParser.parseString(reply).getAsJsonObject();
                    if (result == null || !result.get("success").getAsBoolean()) {
                        throw new AssertionError("Native memory dump failed: " + reply);
                    }
                    System.out.println("CEF memory dump passed: " + size);
                } finally {
                    Native.free(allocation);
                }
            }
            calls.call("Tracing.end", null, 30, TimeUnit.SECONDS);
            if (!traceComplete.await(30, TimeUnit.SECONDS)) throw new AssertionError("Trace did not complete");
            if (streamHandle.get() == null) throw new AssertionError("Trace stream was not returned");
            var trace = new StringBuilder();
            boolean eof = false;
            while (!eof) {
                var params = new com.google.gson.JsonObject();
                params.addProperty("handle", streamHandle.get());
                params.addProperty("size", 1048576);
                var chunk = JsonParser.parseString(calls.call("IO.read", params.toString(), 30, TimeUnit.SECONDS))
                        .getAsJsonObject();
                String data = chunk.get("data").getAsString();
                if (chunk.has("base64Encoded") && chunk.get("base64Encoded").getAsBoolean()) {
                    data = new String(java.util.Base64.getDecoder().decode(data), java.nio.charset.StandardCharsets.UTF_8);
                }
                trace.append(data);
                eof = chunk.has("eof") && chunk.get("eof").getAsBoolean();
            }
            int mallocDumps = 0;
            int otherDumps = 0;
            var processes = new java.util.HashSet<Integer>();
            long largestVirtualSize = 0;
            for (var item : JsonParser.parseString(trace.toString()).getAsJsonObject().getAsJsonArray("traceEvents")) {
                var event = item.getAsJsonObject();
                if (!event.has("args")) continue;
                var values = event.getAsJsonObject("args");
                if (!values.has("dumps")) continue;
                var dumps = values.getAsJsonObject("dumps");
                if (!dumps.has("allocators")) continue;
                var allocators = dumps.getAsJsonObject("allocators");
                if (allocators.keySet().stream().anyMatch(key -> !key.startsWith("malloc"))) {
                    otherDumps++;
                    processes.add(event.get("pid").getAsInt());
                }
                if (!allocators.has("malloc")) continue;
                var attrs = allocators.getAsJsonObject("malloc").getAsJsonObject("attrs");
                if (attrs != null && attrs.has("virtual_size")) {
                    mallocDumps++;
                    var value = attrs.getAsJsonObject("virtual_size").get("value").getAsString();
                    largestVirtualSize = Math.max(largestVirtualSize, Long.parseUnsignedLong(value, 16));
                }
            }
            if (otherDumps == 0 || processes.size() < 2) {
                throw new AssertionError("Memory dumps must still run in the browser and its child processes");
            }
            System.out.println("Other provider dumps=" + otherDumps + ", processes=" + processes.size());
            System.out.println("malloc dumps=" + mallocDumps + ", largest virtual size=" + largestVirtualSize);
            if (original) {
                if (mallocDumps == 0 || largestVirtualSize < sizes[sizes.length - 1]) {
                    throw new AssertionError("The enabled malloc sampler did not report the full native allocation");
                }
            } else if (mallocDumps != 0) {
                throw new AssertionError("Malloc diagnostics must be disabled during normal nop use");
            }
        } finally {
            if (calls != null) calls.close();
            client.dispose();
            SwingUtilities.invokeAndWait(frame::dispose);
            app.dispose();
            ended.await(5, TimeUnit.SECONDS);
        }
    }
}
