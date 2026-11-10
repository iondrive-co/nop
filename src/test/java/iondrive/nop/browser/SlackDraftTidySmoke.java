package iondrive.nop.browser;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.DevToolsCalls;
import org.cef.callback.CefCallback;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.handler.CefResourceHandler;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Real Chromium running nop's draft tidy page on Slack's own origin, against drafts stored the way
 * Slack's client stores them: everything is served from here, so no request leaves the machine.
 */
final class SlackDraftTidySmoke {
    private static final String FIXTURE = "https://app.slack.com/nop-test/";
    private static final String KEY = "persist-v1::TDEMO::UDEMO::drafts";
    private static final String OFFLINE = "persist-v1::TDEMO::UDEMO::draftOffline";

    static void verify(CefBrowser browser, DevToolsCalls calls, CefClient client, Path scratch) throws Exception {
        var folder = Files.createTempDirectory(scratch, "draft-tidy-");
        var unwritable = folder.resolve("not-a-folder");
        Files.writeString(unwritable, "");
        var tidy = new SlackDraftTidy(() -> folder, Instant::now);
        var broken = new SlackDraftTidy(() -> unwritable, Instant::now);
        var current = new SlackDraftTidy[]{tidy};
        client.addRequestHandler(new CefRequestHandlerAdapter() {
            @Override
            public CefResourceRequestHandler getResourceRequestHandler(CefBrowser b, CefFrame f, CefRequest request, boolean navigation,
                                                                       boolean download, String initiator, BoolRef disable) {
                var handler = current[0].resourceRequestHandler(request);
                var url = request.getURL();
                if (handler != null || !(url.startsWith(FIXTURE) || url.equals("https://app.slack.com/client"))) return handler;
                return new CefResourceRequestHandlerAdapter() {
                    @Override public CefResourceHandler getResourceHandler(CefBrowser b, CefFrame f, CefRequest r) {
                        return new Page("<!doctype html><title>fixture</title><body>Slack fixture</body>");
                    }
                };
            }
        });
        try {
            String landing = FIXTURE + "landing?team=TDEMO";
            open(browser, calls, FIXTURE + "seed", FIXTURE + "seed");
            seed(calls);
            // Backing the text up fails: the page must leave Slack's drafts as they were.
            current[0] = broken;
            String before = evaluate(calls, "localStorage.getItem('" + KEY + "') + localStorage.getItem('" + OFFLINE + "')");
            open(browser, calls, SlackDraftTidy.Companion.pageUrl(landing), landing);
            if (!evaluate(calls, "localStorage.getItem('" + KEY + "') + localStorage.getItem('" + OFFLINE + "')").equals(before)) {
                throw new AssertionError("Drafts were removed although their text could not be kept");
            }
            current[0] = tidy;
            open(browser, calls, SlackDraftTidy.Companion.pageUrl(landing), landing);
            var state = JsonParser.parseString(evaluate(calls, "localStorage.getItem('" + KEY + "')")).getAsJsonObject();
            var drafts = state.getAsJsonObject("unifiedDrafts");
            if (!drafts.keySet().equals(java.util.Set.of("CSAVED", "CLATER"))) throw new AssertionError("Drafts left: " + drafts.keySet());
            expect(state, "channelIds", "[\"CSAVED\"]");
            expect(state, "withheldDraftIds", "[]");
            if (!state.get("scheduledOrDlpDraftToEditInThread").isJsonNull()) throw new AssertionError("Removed draft still selected for editing");
            if (!state.get("lastCustomScheduledTime").getAsString().equals("1700000000")) throw new AssertionError("Unrelated drafts state changed");
            var offline = JsonParser.parseString(evaluate(calls, "localStorage.getItem('" + OFFLINE + "')")).getAsJsonObject();
            expect(offline, "pendingCreates", "[\"CLATER\"]");
            expect(offline, "pendingUpdates", "[{\"draftClientId\":\"CSAVED\"}]");
            expect(offline, "pendingDeletes", "[{\"draft\":{\"client_draft_id\":\"CEMPTY\"}}]");
            if (!evaluate(calls, "localStorage.getItem('persist-v1::TDEMO::UDEMO::gifUnfurls')").equals("{\"unfurls\":{}}")) {
                throw new AssertionError("Another part of Slack's state changed");
            }
            var kept = Files.readString(folder.resolve(SlackDraftTidy.BACKUP));
            for (String text : List.of("Channel COLD", "An old idea [emoji] here", "(1 attached file, not kept here)", "Channel CTHREAD, in a thread", "a reply")) {
                if (!kept.contains(text)) throw new AssertionError("Kept file lacks " + text + ": " + kept);
            }
            if (kept.contains("CEMPTY") || kept.contains("kept on the server")) throw new AssertionError("Kept file has drafts it should not: " + kept);
            if (!Files.exists(folder.resolve(SlackDraftTidy.MARKER))) throw new AssertionError("Tidy not marked as done");
            // Running again finds nothing to remove and keeps nothing more.
            open(browser, calls, SlackDraftTidy.Companion.pageUrl(landing), landing);
            if (!Files.readString(folder.resolve(SlackDraftTidy.BACKUP)).equals(kept)) throw new AssertionError("Second tidy kept drafts again");
            // A destination off Slack's origin, or back to the tidy page, goes to Slack's client instead.
            open(browser, calls, SlackDraftTidy.Companion.pageUrl("https://example.org/"), "https://app.slack.com/client");
            open(browser, calls, SlackDraftTidy.Companion.pageUrl(SlackDraftTidy.Companion.pageUrl(landing)), "https://app.slack.com/client");
            System.out.println("SLACK_DRAFT_TIDY unsaved drafts removed, their text kept, saved and scheduled drafts left");
        } finally {
            client.removeRequestHandler();
        }
    }

    private static void seed(DevToolsCalls calls) throws Exception {
        var drafts = new JsonObject();
        drafts.add("CSAVED", draft("{\"client_draft_id\":\"CSAVED\",\"id\":\"Dr0DEMO\",\"last_updated_ts\":\"1700000000.000100\"," +
            "\"destinations\":[{\"channel_id\":\"CSAVED\"}],\"ops\":[{\"insert\":\"kept on the server\\n\"}]}"));
        drafts.add("CEMPTY", draft("{\"client_draft_id\":\"CEMPTY\",\"destinations\":[{\"channel_id\":\"CEMPTY\"}],\"ops\":[{\"insert\":\"\\n\"}]," +
            "\"date_created\":1700000000000,\"temp_attached_draft_unique_id\":\"demo-1\"}"));
        drafts.add("compose-1", draft("{\"client_draft_id\":\"compose-1\",\"is_from_composer\":true,\"destinations\":[{\"channel_id\":\"COLD\"}]," +
            "\"ops\":[{\"insert\":\"An old idea \"},{\"insert\":{\"emoji\":{\"name\":\"bulb\"}}},{\"insert\":\" here\\n\"}],\"file_ids\":[\"FDEMO\"],\"date_created\":1690000000}"));
        drafts.add("CTHREAD-1700000000.000200", draft("{\"client_draft_id\":\"CTHREAD-1700000000.000200\"," +
            "\"destinations\":[{\"channel_id\":\"CTHREAD\",\"thread_ts\":\"1700000000.000200\"}],\"ops\":[{\"insert\":\"a reply\\n\"}]}"));
        drafts.add("CLATER", draft("{\"client_draft_id\":\"CLATER\",\"date_scheduled\":1900000000,\"destinations\":[{\"channel_id\":\"CLATER\"}]," +
            "\"ops\":[{\"insert\":\"later\\n\"}]}"));
        var state = JsonParser.parseString("{\"channelIds\":[\"CSAVED\",\"CEMPTY\"],\"withheldDraftIds\":[\"CEMPTY\"],\"isEditingDraftPage\":false," +
            "\"selectedDraftIds\":[],\"isSelectAllChecked\":false,\"scheduledOrDlpDraftToEditInThread\":\"CTHREAD-1700000000.000200\"," +
            "\"pendingDraftInsert\":null,\"quoteReplyPathsByDraft\":{},\"lastCustomScheduledTime\":1700000000,\"composerAutoPopulateSuppressedByKey\":{}}")
            .getAsJsonObject();
        state.add("unifiedDrafts", drafts);
        var offline = "{\"pendingCreates\":[\"CEMPTY\",\"CLATER\",{\"draftClientId\":\"compose-1\"}],\"pendingDeletes\":[{\"draft\":{\"client_draft_id\":\"CEMPTY\"}}]," +
            "\"pendingUpdates\":[{\"draftClientId\":\"CSAVED\"}]}";
        var gson = new Gson();
        evaluate(calls, "localStorage.clear(); localStorage.setItem('" + KEY + "', " + gson.toJson(state.toString()) + "); " +
            "localStorage.setItem('" + OFFLINE + "', " + gson.toJson(offline) + "); " +
            "localStorage.setItem('persist-v1::TDEMO::UDEMO::gifUnfurls', '{\"unfurls\":{}}'); 'seeded'");
    }

    /** A draft as Slack's reducer stores it: its defaults, then the fields given. */
    private static JsonObject draft(String fields) {
        var draft = JsonParser.parseString("{\"is_from_composer\":false,\"date_created\":1700000000000,\"last_updated\":1700000000000," +
            "\"destinations\":[],\"ops\":[],\"file_ids\":[],\"cursor_index\":0}").getAsJsonObject();
        JsonParser.parseString(fields).getAsJsonObject().entrySet().forEach(entry -> draft.add(entry.getKey(), entry.getValue()));
        return draft;
    }

    private static void expect(JsonObject object, String name, String json) {
        if (!object.get(name).toString().equals(json)) throw new AssertionError(name + " is " + object.get(name) + ", not " + json);
    }

    private static void open(CefBrowser browser, DevToolsCalls calls, String url, String arrival) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> browser.loadURL(url));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String seen = "";
        while (System.nanoTime() < deadline) {
            Thread.sleep(100);
            try {
                seen = evaluate(calls, "document.readyState === 'complete' ? location.href : ''");
            } catch (Exception navigating) {
                continue;
            }
            if (seen.equals(arrival)) return;
        }
        throw new AssertionError("Opening " + url + " did not arrive at " + arrival + "; last at " + seen);
    }

    private static String evaluate(DevToolsCalls calls, String expression) throws Exception {
        var response = calls.call("Runtime.evaluate", "{\"expression\":" + new Gson().toJson(expression) +
            ",\"returnByValue\":true}", 5, TimeUnit.SECONDS);
        var result = JsonParser.parseString(response).getAsJsonObject();
        if (result.has("exceptionDetails")) throw new AssertionError(result);
        return result.getAsJsonObject("result").get("value").getAsString();
    }

    private static final class Page extends CefResourceHandlerAdapter {
        private final byte[] body;
        private int offset;

        Page(String html) {
            body = html.getBytes(StandardCharsets.UTF_8);
        }

        @Override public boolean processRequest(CefRequest request, CefCallback callback) {
            callback.Continue();
            return true;
        }

        @Override public void getResponseHeaders(CefResponse response, IntRef length, StringRef redirect) {
            response.setStatus(200);
            response.setMimeType("text/html");
            length.set(body.length);
        }

        @Override public boolean readResponse(byte[] data, int count, IntRef read, CefCallback callback) {
            int size = Math.min(count, body.length - offset);
            if (size <= 0) {
                read.set(0);
                return false;
            }
            System.arraycopy(body, offset, data, 0, size);
            offset += size;
            read.set(size);
            return true;
        }
    }
}
