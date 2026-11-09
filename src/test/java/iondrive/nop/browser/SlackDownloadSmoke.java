package iondrive.nop.browser;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import kotlin.Unit;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.DevToolsCalls;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Real Chromium saving downloads the way Slack starts them, into a scratch folder: Slack's Download
 * button (fetch the file, click a blob: link to it) and a file link (what a popup is turned into).
 */
final class SlackDownloadSmoke {
    /** The fixture server answers every path under this with the same attachment. */
    static final String FILES = "/files/";
    static final String BODY = "invented file body\n";

    static void verify(CefBrowser browser, DevToolsCalls calls, CefClient client, Path scratch, String origin) throws Exception {
        browser.setZoomLevel(0);
        var folder = Files.createTempDirectory(scratch, "downloads-");
        var reports = new LinkedBlockingQueue<SlackDownload>();
        client.addDownloadHandler(new SlackDownloads(() -> folder, download -> {
            reports.add(download);
            return Unit.INSTANCE;
        }));
        try {
            evaluate(calls, "document.querySelector('.p-view_contents--primary').innerHTML = " + new Gson().toJson(
                "<button id='download-button' style='margin:40px;width:200px;height:40px'>Download</button>") + "; " +
                "document.querySelector('#download-button').onclick = () => fetch('" + FILES + "slack.txt', {credentials: 'include'})" +
                ".then(r => r.blob()).then(b => {const url = URL.createObjectURL(b), a = document.createElement('a'); " +
                "a.href = url; a.setAttribute('download', 'report.txt'); document.body.appendChild(a); a.click(); " +
                "setTimeout(() => {a.remove(); URL.revokeObjectURL(url);}, 0);}); true");
            for (String expected : List.of("report.txt", "report (1).txt")) {
                click(calls, "download-button");
                awaitSaved(reports, folder, expected, "Slack's Download button");
            }
            if (!Files.readString(folder.resolve("report.txt")).equals(BODY)) throw new AssertionError("Second download overwrote the first");
            // What a popup or navigation to one of Slack's file links becomes.
            SwingUtilities.invokeAndWait(() -> browser.startDownload(origin + FILES + "link"));
            awaitSaved(reports, folder, "report (2).txt", "a file link");
            System.out.println("SLACK_DOWNLOAD blob button, repeated name and file link saved");
        } finally {
            client.removeDownloadHandler();
        }
    }

    private static void awaitSaved(LinkedBlockingQueue<SlackDownload> reports, Path folder, String name, String how) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var report = reports.poll(100, TimeUnit.MILLISECONDS);
            if (report == null) continue;
            if (report.getStatus() == SlackDownload.Status.Failed) throw new AssertionError("Download failed from " + how + ": " + report);
            if (report.getStatus() != SlackDownload.Status.Saved) continue;
            if (!report.getFile().equals(folder.resolve(name))) throw new AssertionError("Saved from " + how + " as " + report.getFile() + ", not " + name);
            if (!Files.readString(report.getFile()).equals(BODY)) throw new AssertionError("Wrong contents saved from " + how);
            return;
        }
        throw new AssertionError("Nothing was saved from " + how + "; files: " + List.of(folder.toFile().list()));
    }

    private static void click(DevToolsCalls calls, String id) throws Exception {
        var point = JsonParser.parseString(evaluate(calls, "JSON.stringify((() => {const r = document.querySelector('#" + id +
            "').getBoundingClientRect(); return {x: r.x + r.width / 2, y: r.y + r.height / 2};})())")).getAsJsonObject();
        String position = "\"x\":" + point.get("x") + ",\"y\":" + point.get("y") + ",\"button\":\"left\",\"clickCount\":1";
        for (String type : List.of("mousePressed", "mouseReleased")) {
            calls.call("Input.dispatchMouseEvent", "{\"type\":\"" + type + "\"," + position + "}", 5, TimeUnit.SECONDS);
        }
    }

    private static String evaluate(DevToolsCalls calls, String expression) throws Exception {
        var response = calls.call("Runtime.evaluate", "{\"expression\":" + new Gson().toJson(expression) +
            ",\"returnByValue\":true}", 5, TimeUnit.SECONDS);
        var result = JsonParser.parseString(response).getAsJsonObject();
        if (result.has("exceptionDetails")) throw new AssertionError(result);
        return result.getAsJsonObject("result").get("value").getAsString();
    }
}
