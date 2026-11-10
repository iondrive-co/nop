// nop serves this page itself at https://app.slack.com/nop/tidy-drafts, once, before Slack's client
// starts (SlackDraftTidy.kt). Slack's client keeps its drafts in localStorage. A draft its server
// never accepted (an empty one, say) is never dropped from there, and still counts for the draft
// pencil beside its channel. This finds those, sends them to nop, and removes them only once nop has
// kept their text; then it goes on to Slack. None of Slack's code runs on this page, so Slack cannot
// write its old list back over the new one.
(() => {
  "use strict";
  const home = "https://app.slack.com/client";
  let next = home;
  try {
    const wanted = new URL(new URLSearchParams(location.search).get("next") || home);
    if (wanted.origin === location.origin && !wanted.pathname.startsWith("/nop/")) next = wanted.href;
  } catch (_) {}
  const report = { stores: 0, kept: 0, removed: [], errors: [] };
  const writes = [];
  const words = (draft) => (Array.isArray(draft.ops) ? draft.ops : []).map((op) => {
    const insert = op && op.insert;
    if (typeof insert === "string") return insert;
    if (insert && typeof insert === "object") return "[" + (Object.keys(insert)[0] || "embed") + "]";
    return "";
  }).join("").trim();
  const keys = [];
  for (let i = 0; i < localStorage.length; i++) keys.push(localStorage.key(i));
  for (const key of keys) {
    // Slack's own key for one signed-in user's drafts: persist-v1::<team>::<user>::drafts.
    if (!/^persist-v1::[^:]+::[^:]+::drafts$/.test(key)) continue;
    try {
      const state = JSON.parse(localStorage.getItem(key));
      const drafts = state && state.unifiedDrafts;
      if (!drafts || typeof drafts !== "object" || Array.isArray(drafts)) continue;
      report.stores++;
      // A draft Slack's server has saved carries the server's id, and Slack drops it at startup once
      // the server no longer lists it. A scheduled draft is left alone regardless.
      const gone = Object.keys(drafts).filter((id) => {
        const draft = drafts[id];
        return draft && typeof draft === "object" && !draft.id && !draft.date_scheduled;
      });
      report.kept += Object.keys(drafts).length - gone.length;
      if (!gone.length) continue;
      for (const id of gone) {
        const draft = drafts[id];
        const to = (Array.isArray(draft.destinations) ? draft.destinations : [])[0] || {};
        report.removed.push({
          channel: typeof to.channel_id === "string" ? to.channel_id : "",
          thread: !!to.thread_ts,
          people: Array.isArray(to.user_ids) ? to.user_ids.length : 0,
          composer: !!draft.is_from_composer,
          created: Number(draft.date_created) || 0,
          files: Array.isArray(draft.file_ids) ? draft.file_ids.length : 0,
          text: words(draft),
        });
        delete drafts[id];
      }
      // What Slack's own "clear drafts" does to the rest of its drafts state.
      const without = (list) => (Array.isArray(list) ? list.filter((id) => !gone.includes(id)) : list);
      state.channelIds = without(state.channelIds);
      state.withheldDraftIds = without(state.withheldDraftIds);
      state.selectedDraftIds = without(state.selectedDraftIds);
      if (gone.includes(state.scheduledOrDlpDraftToEditInThread)) state.scheduledOrDlpDraftToEditInThread = null;
      writes.push([key, JSON.stringify(state)]);
      // Saves Slack put off while offline, so it does not try them again for drafts now gone.
      const offlineKey = key.slice(0, -"drafts".length) + "draftOffline";
      const offline = JSON.parse(localStorage.getItem(offlineKey) || "null");
      if (offline && typeof offline === "object") {
        const named = (entry) => [entry, entry && entry.draftClientId, entry && entry.client_draft_id,
          entry && entry.draft && entry.draft.client_draft_id].some((id) => typeof id === "string" && gone.includes(id));
        let changed = false;
        for (const name of ["pendingCreates", "pendingUpdates"]) {
          if (!Array.isArray(offline[name])) continue;
          const left = offline[name].filter((entry) => !named(entry));
          if (left.length !== offline[name].length) {
            offline[name] = left;
            changed = true;
          }
        }
        if (changed) writes.push([offlineKey, JSON.stringify(offline)]);
      }
    } catch (failure) {
      report.errors.push(String((failure && failure.message) || failure).slice(0, 200));
    }
  }
  let left = false;
  const go = () => {
    if (left) return;
    left = true;
    location.replace(next);
  };
  fetch("/nop/tidy-drafts/report", { method: "POST", body: JSON.stringify(report), headers: { "Content-Type": "application/json" } })
    .then((response) => {
      // nop answers 204 once it has kept the text; otherwise the drafts stay for the next start.
      if (response.status === 204 && !left) for (const [key, value] of writes) localStorage.setItem(key, value);
    })
    .catch(() => {})
    .finally(go);
  setTimeout(go, 10000);
})();
