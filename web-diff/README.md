# nop-diff

A standalone, **client-side** diff viewer. It takes a `git diff` (or already-parsed diff data) and
renders nop's side-by-side review view — gutters, per-kind tints, inline word-level highlights, and
per-language syntax colouring — entirely in the browser. **No server, no backend, no network.**

It is built by transpiling nop's pure diff/highlight core to JavaScript (Kotlin/JS), then rendering
to real DOM (not a canvas) so it deploys as static files and embeds cleanly inside another web app.

| | |
|---|---|
| Output | static `nop-diff.js` (~85 KiB min) + `index.html` |
| Rendering | real DOM in a Shadow-DOM web component (`<nop-diff>`) |
| Input | a unified-diff string **or** a structured `FileDiff[]` (chad's shape) |
| Reused from nop | `DiffModel`, the 7 language tokenizers (`SyntaxHighlight`), the diff row model |
| Net-new | unified-diff parser, word-level inline diff (replaces java-diff-utils), DOM renderer, web component |

This module lives in the nop repo but is an independent Gradle subproject — it does **not** touch the
desktop app's build or dependencies.

## Build

```bash
./gradlew :web-diff:jsBrowserDistribution   # production bundle  -> web-diff/build/dist/js/productionExecutable/
./gradlew :web-diff:jsTest                  # run the unit tests (parser, word-diff, tokenizers) on Node
```

Output (`web-diff/build/dist/js/productionExecutable/`):

```
nop-diff.js        # the whole widget — element registration + API, self-contained
index.html         # standalone demo (renders a sample git diff; theme toggle)
```

Open `index.html` directly, or serve the directory.

## Deploy to Cloudflare Pages

The dist directory is a complete static site.

```bash
./gradlew :web-diff:jsBrowserDistribution
npx wrangler pages deploy web-diff/build/dist/js/productionExecutable --project-name nop-diff
```

Or point a Pages project at this repo with **build command** `./gradlew :web-diff:jsBrowserDistribution`
and **output directory** `web-diff/build/dist/js/productionExecutable`.

## Usage

The bundle registers a `<nop-diff>` custom element and exposes `window.NopDiff`. Three ways to use it:

### 1. Custom element (set `.diff` or `.files`)

```html
<script src="/nop-diff.js"></script>
<nop-diff id="v" theme="auto"></nop-diff>
<script>
  document.getElementById('v').diff = unifiedDiffString;   // a `git diff` string
  // or, if you already have parsed diff data:
  // document.getElementById('v').files = fileDiffArray;
</script>
```

`theme` = `light` | `dark` | `auto` (auto follows `prefers-color-scheme`).

### 2. Imperative API

```js
NopDiff.renderUnifiedDiff(targetEl, unifiedDiffString, 'dark');
NopDiff.renderStructured(targetEl, fileDiffArray, 'auto');
```

### 3. Structured input shape

`renderStructured` / `.files` accept an array of files (snake_case fields; missing fields tolerated):

```ts
{
  old_path, new_path, is_new, is_deleted, is_rename, is_binary,
  hunks: [{
    old_start, old_count, new_start, new_count,
    lines: [{ type: 'context'|'add'|'delete', content, old_line, new_line }]
  }]
}
```

This is **deliberately identical to chad's `FileDiff[]`**, so chad's parsed diff data feeds straight
in with no transformation.

## Integrating with chad's web view

chad (React + Vite) already fetches structured diffs from its backend (`api.getFullDiff` →
`DiffFull.files: FileDiff[]`) and renders them with a plain `DiffViewer.tsx` that has **no syntax
highlighting and no intra-line word diff**. nop-diff is a drop-in upgrade that consumes the *same*
`FileDiff[]` and adds both — with zero backend changes.

It is CSP-safe under chad's policy (`script-src 'self'`, `style-src 'self' 'unsafe-inline'`): the
bundle is local (no CDN), there is no `eval`/`Function`, and styles are scoped inside the Shadow DOM.

**Step 1 — vendor the bundle.** Build it and copy the JS into chad:

```bash
./gradlew :web-diff:jsBrowserDistribution
cp web-diff/build/dist/js/productionExecutable/nop-diff.js  ~/chad/ui/src/vendor/nop-diff.js
```

**Step 2 — a thin React wrapper** (`~/chad/ui/src/components/NopDiffView.tsx`). The side-effect import
registers `<nop-diff>`; a ref hands chad's `FileDiff[]` to the element's `.files` property:

```tsx
import { useEffect, useRef } from "react";
import "../vendor/nop-diff.js";              // registers <nop-diff>, defines window.NopDiff
import type { FileDiff } from "chad-client";

// Tell TS/JSX about the custom element.
declare global {
  namespace JSX {
    interface IntrinsicElements {
      "nop-diff": React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement> & {
        theme?: "auto" | "light" | "dark";
      };
    }
  }
}

export function NopDiffView({ files, theme = "auto" }: { files: FileDiff[]; theme?: "auto" | "light" | "dark" }) {
  const ref = useRef<(HTMLElement & { files?: FileDiff[] }) | null>(null);
  useEffect(() => { if (ref.current) ref.current.files = files; }, [files]);
  return <nop-diff ref={ref} theme={theme} />;
}
```

**Step 3 — use it** where `DiffViewer` is used today (e.g. in `MergePanel.tsx`):

```tsx
// was: <DiffViewer files={diff.files} />
<NopDiffView files={diff.files} />
```

**Theming.** The widget reads the host's CSS variables when present, so it adopts chad's theme
automatically: `--bg`, `--text`, `--font-mono`, `--diff-add-bg`, `--diff-delete-bg`. Override any
widget colour explicitly with the `--nd-*` variables (see `Styles.kt`), e.g.:

```css
nop-diff { --nd-change-bg: rgba(120,120,255,0.12); }
```

## Limitations (v1)

- **Side-by-side only.** Unified (inline) view is not implemented yet.
- **Per-line syntax highlighting.** Multi-line constructs (block comments, fenced code, triple-quoted
  strings) aren't detected across line boundaries — single-line tokens (keywords, strings, numbers,
  line comments) highlight correctly.
- Word-level alignment is skipped on pathologically long lines (token product > 40k) — the whole line
  is marked changed instead.
- Languages with syntax highlighting: Kotlin, Go, JSON, Markdown, JS/TS, Shell, YAML/Ansible/Jinja.
  Other extensions render as plain (uncoloured) text.
```
