package iondrive.nopdiff

/**
 * Shadow-DOM stylesheet for the diff view. Colours come from the desktop palettes (Darcula dark /
 * IntelliJ light syntax, nop's green/red/blue diff tints). Every colour is a CSS custom property so
 * a host app can retheme by setting `--nd-*`, and the most common ones fall back to the host's own
 * variables (chad's `--bg`, `--text`, `--font-mono`, `--diff-add-bg`, `--diff-delete-bg`) so the
 * widget adopts the surrounding theme automatically.
 *
 * Theme: `theme="light"` / `theme="dark"` force a scheme; absent or `theme="auto"` follows the host
 * via prefers-color-scheme.
 */
internal val CSS = """
:host {
  /* light defaults — overridable by the host's own variables */
  --nd-bg: var(--bg, #ffffff);
  --nd-fg: var(--text, #1f2329);
  --nd-gutter-fg: #9aa0aa;
  --nd-header-bg: var(--bg-secondary, #f3f4f6);
  --nd-header-fg: var(--text-muted, #57606a);
  --nd-border: var(--border, #d8dee4);
  --nd-hunk-fg: #8250df;
  --nd-add-bg: var(--diff-add-bg, rgba(70,169,89,0.16));
  --nd-del-bg: var(--diff-delete-bg, rgba(203,73,73,0.16));
  --nd-change-bg: rgba(84,123,157,0.13);
  --nd-empty-bg: rgba(0,0,0,0.035);
  --nd-word-add-bg: rgba(70,169,89,0.40);
  --nd-word-del-bg: rgba(203,73,73,0.40);
  --nd-font: var(--font-mono, ui-monospace, "JetBrains Mono", SFMono-Regular, Menlo, Consolas, monospace);

  --nd-tok-keyword: #0033b3;
  --nd-tok-string: #067d17;
  --nd-tok-comment: #8c8c8c;
  --nd-tok-number: #1750eb;
  --nd-tok-literal: #0033b3;
  --nd-tok-punct: #1f2329;
  --nd-tok-heading: #8a4a00;
  --nd-tok-emphasis: #6f2da8;

  display: block;
  background: var(--nd-bg);
  color: var(--nd-fg);
  font-family: var(--nd-font);
  font-size: 12px;
  line-height: 1.5;
  -webkit-font-smoothing: antialiased;
}

/* ---- dark scheme: explicit, or auto via the host ---- */
:host([theme="dark"]) {
  --nd-bg: var(--bg, #1e1f22);
  --nd-fg: var(--text, #a9b7c6);
  --nd-gutter-fg: #6b7785;
  --nd-header-bg: var(--bg-secondary, #2b2d30);
  --nd-header-fg: var(--text-muted, #9aa7b0);
  --nd-border: var(--border, #303338);
  --nd-hunk-fg: #c9a26d;
  --nd-add-bg: var(--diff-add-bg, rgba(98,151,85,0.20));
  --nd-del-bg: var(--diff-delete-bg, rgba(179,94,94,0.20));
  --nd-change-bg: rgba(84,123,157,0.20);
  --nd-empty-bg: rgba(255,255,255,0.035);
  --nd-word-add-bg: rgba(98,151,85,0.45);
  --nd-word-del-bg: rgba(179,94,94,0.45);
  --nd-tok-keyword: #cc7832;
  --nd-tok-string: #6a8759;
  --nd-tok-comment: #808080;
  --nd-tok-number: #6897bb;
  --nd-tok-literal: #cc7832;
  --nd-tok-punct: #a9b7c6;
  --nd-tok-heading: #ffc66d;
  --nd-tok-emphasis: #9876aa;
}
@media (prefers-color-scheme: dark) {
  :host([theme="auto"]), :host(:not([theme])) {
    --nd-bg: var(--bg, #1e1f22);
    --nd-fg: var(--text, #a9b7c6);
    --nd-gutter-fg: #6b7785;
    --nd-header-bg: var(--bg-secondary, #2b2d30);
    --nd-header-fg: var(--text-muted, #9aa7b0);
    --nd-border: var(--border, #303338);
    --nd-hunk-fg: #c9a26d;
    --nd-add-bg: var(--diff-add-bg, rgba(98,151,85,0.20));
    --nd-del-bg: var(--diff-delete-bg, rgba(179,94,94,0.20));
    --nd-change-bg: rgba(84,123,157,0.20);
    --nd-empty-bg: rgba(255,255,255,0.035);
    --nd-word-add-bg: rgba(98,151,85,0.45);
    --nd-word-del-bg: rgba(179,94,94,0.45);
    --nd-tok-keyword: #cc7832;
    --nd-tok-string: #6a8759;
    --nd-tok-comment: #808080;
    --nd-tok-number: #6897bb;
    --nd-tok-literal: #cc7832;
    --nd-tok-punct: #a9b7c6;
    --nd-tok-heading: #ffc66d;
    --nd-tok-emphasis: #9876aa;
  }
}

.nd-container { width: 100%; box-sizing: border-box; }

.nd-file { border: 1px solid var(--nd-border); border-radius: 6px; margin: 0 0 12px 0; overflow: hidden; }
.nd-file:last-child { margin-bottom: 0; }

.nd-file-header {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 10px; background: var(--nd-header-bg); color: var(--nd-header-fg);
  border-bottom: 1px solid var(--nd-border); font-size: 12px;
}
.nd-path { font-weight: 600; word-break: break-all; }
.nd-badge { font-size: 10px; text-transform: uppercase; letter-spacing: .04em; padding: 1px 6px; border-radius: 10px; border: 1px solid var(--nd-border); }
.nd-badge-new { color: #2ea043; border-color: #2ea043; }
.nd-badge-del { color: #cf222e; border-color: #cf222e; }
.nd-badge-ren { color: #8250df; border-color: #8250df; }
.nd-badge-bin { color: var(--nd-header-fg); }
.nd-stats { margin-left: auto; display: flex; gap: 8px; font-variant-numeric: tabular-nums; }
.nd-stat-add { color: #2ea043; }
.nd-stat-del { color: #cf222e; }

.nd-note { padding: 10px 12px; color: var(--nd-header-fg); font-style: italic; }

.nd-scroll { overflow-x: auto; }
.nd-table { border-collapse: collapse; width: 100%; table-layout: auto; }
.nd-table td { height: 1.5em; vertical-align: top; padding: 0; }

.nd-hunk td {
  padding: 2px 10px; color: var(--nd-hunk-fg);
  background: var(--nd-header-bg); border-top: 1px solid var(--nd-border); border-bottom: 1px solid var(--nd-border);
  white-space: pre; user-select: none;
}

.nd-gutter {
  width: 1%; white-space: nowrap; text-align: right; padding: 0 8px;
  color: var(--nd-gutter-fg); user-select: none; -webkit-user-select: none;
  border-right: 1px solid var(--nd-border);
}
.nd-code {
  white-space: pre; padding: 0 8px 0 8px; tab-size: 4; -moz-tab-size: 4;
}
.nd-code.nd-old { border-right: 1px solid var(--nd-border); }

/* per-side row tints */
.nd-change .nd-old, .nd-change .nd-new { background: var(--nd-change-bg); }
.nd-insert .nd-new { background: var(--nd-add-bg); }
.nd-insert .nd-old { background: var(--nd-empty-bg); }
.nd-delete .nd-old { background: var(--nd-del-bg); }
.nd-delete .nd-new { background: var(--nd-empty-bg); }

/* inline word-level highlight */
.nd-old .nd-word { background: var(--nd-word-del-bg); border-radius: 2px; }
.nd-new .nd-word { background: var(--nd-word-add-bg); border-radius: 2px; }

/* syntax tokens */
.tok-keyword { color: var(--nd-tok-keyword); }
.tok-string  { color: var(--nd-tok-string); }
.tok-comment { color: var(--nd-tok-comment); font-style: italic; }
.tok-number  { color: var(--nd-tok-number); }
.tok-literal { color: var(--nd-tok-literal); }
.tok-punct   { color: var(--nd-tok-punct); }
.tok-heading { color: var(--nd-tok-heading); font-weight: 600; }
.tok-emphasis{ color: var(--nd-tok-emphasis); }
""".trimIndent()
