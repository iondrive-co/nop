package iondrive.nop.browser;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.DevToolsCalls;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefMenuModel;
import org.cef.handler.CefContextMenuHandlerAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Real Chromium spelling and editing in invented, dynamically mounted Slack composers. Slack sets
 * spellcheck on its composer from its own "Enable spellcheck" preference, and nop must never change it.
 */
final class SlackSpellingSmoke {
    private record SpellingMenu(String word, List<String> suggestions, List<String> labels) {}

    static void verify(CefBrowser browser, DevToolsCalls calls, CefClient client) throws Exception {
        browser.setZoomLevel(0);
        var menus = new LinkedBlockingQueue<SpellingMenu>();
        client.addContextMenuHandler(new CefContextMenuHandlerAdapter() {
            @Override public void onBeforeContextMenu(CefBrowser from, CefFrame frame,
                                                       CefContextMenuParams params, CefMenuModel model) {
                var suggestions = new Vector<String>();
                params.getDictionarySuggestions(suggestions);
                var labels = new ArrayList<String>();
                for (int i = 0; i < model.getCount(); i++) labels.add(model.getLabelAt(i).replace("&", ""));
                menus.add(new SpellingMenu(params.getMisspelledWord(), List.copyOf(suggestions), labels));
                // Observe CEF's real menu, then suppress the popup so the fixture can keep editing.
                model.clear();
            }
        });
        try {
            composerLeftAlone(calls);
            for (String surface : List.of("channel", "thread", "edit")) {
                // A composer as Slack mounts it with its spelling preference on.
                evaluate(calls, "document.querySelector('.p-view_contents--primary').innerHTML = " +
                    new Gson().toJson("<div id='spelling-editor' data-qa='texty_input' contenteditable='true' " +
                        "spellcheck='true' style='font-size:20px;line-height:2;padding:20px'>" +
                        "<strong>Keep formatting</strong> <span id='spelling-word'>Draft</span> " +
                        "<span id='spelling-mention' contenteditable='false' spellcheck='false'>@Avery</span> " +
                        "<code id='spelling-code' spellcheck='false'>mispeling</code></div>") + "; true");
                evaluate(calls, "(() => {const editor = document.querySelector('#spelling-editor'); editor.focus(); " +
                    "const range = document.createRange(); " +
                    "range.selectNodeContents(document.querySelector('#spelling-word')); " +
                    "const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);})(), true");
                calls.call("Input.insertText", "{\"text\":\"mispeling \"}", 5, TimeUnit.SECONDS);
                var menu = waitForTypo(calls, menus, "spelling-editor", "mispeling");
                if (!menu.suggestions.contains("misspelling") || !menu.labels.contains("misspelling") ||
                    !menu.labels.contains("Add to dictionary")) {
                    throw new AssertionError("Native spelling actions missing on " + surface + ": " + menu);
                }
                browser.replaceMisspelling("misspelling");
                awaitText(calls, "misspelling");
                if (!evaluate(calls, "document.querySelector('#spelling-editor strong').textContent === 'Keep formatting' && " +
                    "document.querySelector('#spelling-mention').textContent === '@Avery' && " +
                    "!document.querySelector('#spelling-mention').spellcheck && " +
                    "!document.querySelector('#spelling-code').spellcheck").equals("true")) {
                    throw new AssertionError("Correction changed formatting or spelling opt-outs");
                }
                browser.getFocusedFrame().undo();
                awaitText(calls, "mispeling");
                var code = contextMenu(calls, menus, "spelling-code", "mispeling");
                if (!code.word.isEmpty()) throw new AssertionError("Code must not be spellchecked: " + code);
                System.out.println("SLACK_SPELLING " + surface + " suggestions, replacement, formatting and undo passed");
            }
            // All three bundled dictionaries initialize offline; regional English remains accepted.
            for (String word : List.of("colour", "color", "organise")) {
                evaluate(calls, "(() => {const editor = document.querySelector('#spelling-editor'); editor.focus(); " +
                    "const range = document.createRange(); range.selectNodeContents(editor); " +
                    "const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);})(), true");
                calls.call("Input.insertText", "{\"text\":" + new Gson().toJson(word + " ") + "}", 5, TimeUnit.SECONDS);
                Thread.sleep(300);
                var menu = contextMenu(calls, menus, "spelling-editor", word);
                if (!menu.word.isEmpty()) throw new AssertionError("English variant rejected: " + word + ": " + menu);
            }
        } finally {
            client.removeContextMenuHandler();
        }
    }

    /**
     * With Slack's spelling preference off, its composer says spellcheck="false". Focusing it and typing
     * must not make nop write any attribute there: Slack's editor observes them all, and nop turning
     * spellcheck on at focus came with Slack saving drafts nobody had typed.
     */
    private static void composerLeftAlone(DevToolsCalls calls) throws Exception {
        evaluate(calls, "document.querySelector('.p-view_contents--primary').innerHTML = " +
            new Gson().toJson("<div id='slack-composer' data-qa='texty_input' contenteditable='true' spellcheck='false' " +
                "style='font-size:20px;line-height:2;padding:20px'><p>Draft</p></div>") + "; " +
            "window.composerAttributeWrites = []; new MutationObserver(records => window.composerAttributeWrites.push(" +
            "...records.filter(r => r.type === 'attributes').map(r => r.target.id + ' ' + r.attributeName)))" +
            ".observe(document.querySelector('#slack-composer'), {attributes: true, subtree: true}); true");
        click(calls, "slack-composer");
        evaluate(calls, "(() => {const editor = document.querySelector('#slack-composer'); editor.focus(); " +
            "const range = document.createRange(); range.selectNodeContents(editor); range.collapse(false); " +
            "const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);})(), true");
        calls.call("Input.insertText", "{\"text\":\" typed\"}", 5, TimeUnit.SECONDS);
        Thread.sleep(300);
        if (!evaluate(calls, "document.querySelector('#slack-composer').textContent").endsWith("typed")) {
            throw new AssertionError("Typing did not reach the composer");
        }
        var writes = evaluate(calls, "JSON.stringify(window.composerAttributeWrites)");
        if (!writes.equals("[]")) throw new AssertionError("nop wrote to Slack's composer: " + writes);
        if (!evaluate(calls, "document.querySelector('#slack-composer').getAttribute('spellcheck')").equals("false")) {
            throw new AssertionError("nop overrode Slack's spelling preference");
        }
        System.out.println("SLACK_SPELLING composer left as Slack set it");
    }

    private static void click(DevToolsCalls calls, String id) throws Exception {
        var point = JsonParser.parseString(evaluate(calls, "JSON.stringify((() => {const r = document.querySelector('#" + id +
            "').getBoundingClientRect(); return {x: r.x + r.width - 4, y: r.y + r.height / 2};})())")).getAsJsonObject();
        String position = "\"x\":" + point.get("x") + ",\"y\":" + point.get("y") + ",\"button\":\"left\",\"clickCount\":1";
        for (String type : List.of("mousePressed", "mouseReleased")) {
            calls.call("Input.dispatchMouseEvent", "{\"type\":\"" + type + "\"," + position + "}", 5, TimeUnit.SECONDS);
        }
    }

    private static SpellingMenu waitForTypo(DevToolsCalls calls, LinkedBlockingQueue<SpellingMenu> menus,
                                            String id, String word) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        SpellingMenu menu;
        do {
            Thread.sleep(100);
            menu = contextMenu(calls, menus, id, word);
            if (menu.word.equals(word)) return menu;
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Offline spellchecker did not report " + word + ": " + menu);
    }

    private static SpellingMenu contextMenu(DevToolsCalls calls, LinkedBlockingQueue<SpellingMenu> menus,
                                            String id, String word) throws Exception {
        menus.clear();
        var point = JsonParser.parseString(evaluate(calls,
            "JSON.stringify((() => {const root = document.querySelector('#" + id + "'); " +
            "const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT); const word = " + new Gson().toJson(word) + "; " +
            "while (walker.nextNode()) {const node = walker.currentNode; const offset = node.textContent.indexOf(word); " +
            "if (offset < 0) continue; const range = document.createRange(); range.setStart(node, offset); " +
            "range.setEnd(node, offset + word.length); const r = range.getBoundingClientRect(); " +
            "return {x:r.left+Math.min(12,r.width/2), y:r.top+r.height/2};} throw Error(root.innerHTML);})())")).getAsJsonObject();
        String position = "\"x\":" + point.get("x") + ",\"y\":" + point.get("y") + ",\"button\":\"right\",\"clickCount\":1";
        for (String type : List.of("mousePressed", "mouseReleased")) {
            calls.call("Input.dispatchMouseEvent", "{\"type\":\"" + type + "\"," + position + "}", 5, TimeUnit.SECONDS);
        }
        var menu = menus.poll(5, TimeUnit.SECONDS);
        if (menu == null) throw new AssertionError("Composer did not offer Chromium's native context menu");
        return menu;
    }

    private static void awaitText(DevToolsCalls calls, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (evaluate(calls, "document.querySelector('#spelling-editor').textContent.replaceAll('\u00a0', ' ').startsWith(" +
                new Gson().toJson("Keep formatting " + expected + " ") + ")").equals("true")) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected corrected/undone text: " + expected);
    }

    private static String evaluate(DevToolsCalls calls, String expression) throws Exception {
        var response = calls.call("Runtime.evaluate", "{\"expression\":" + new Gson().toJson(expression) +
            ",\"returnByValue\":true}", 5, TimeUnit.SECONDS);
        var result = JsonParser.parseString(response).getAsJsonObject();
        if (result.has("exceptionDetails")) throw new AssertionError(result);
        return result.getAsJsonObject("result").get("value").getAsString();
    }
}
