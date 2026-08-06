#!/usr/bin/env bash
# Capture the two README screenshots, each from its own freshly-spawned, fully isolated nop
# instance pointed at synthetic state — so the shots are curated and reproducible instead of
# depending on whatever the user happens to have open:
#
#   - A diff-view shot: a repo with several committed-then-modified files across a few directories,
#     so the tool panel on the right lists the changes grouped by directory (source dirs, tests,
#     config, docs), with one file opened to its side-by-side diff in the editor beside it. The
#     opened file is long enough to scroll past the window bottom, with its hunks spread far
#     apart, so the change stripe beside the diff shows separate blocks marking where they are.
#   - A workspace/preview shot: several synthetic project tabs grouped by named separators in the
#     left rail, with a few editor tabs open in the active project. NOTHING from the user's real
#     workspace appears.
#
# The two shots are set up in opposite themes so the README demonstrates both. Output is
# quantised to a 256-colour palette before saving, which trims the PNGs by ~3x with no
# visible difference vs. the truecolor capture.
#
# Designed to be invoked from inside nop (via the ▶ launcher). For local testing the output
# locations can be redirected with NOP_SHOT_DIR / NOP_SHOT_README so a dry run doesn't touch
# the checked-in screenshots or README.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SHOT_DIR="${NOP_SHOT_DIR:-$ROOT_DIR/docs/screenshots}"
README="${NOP_SHOT_README:-$ROOT_DIR/README.md}"
DISPLAY_SPEC="${DISPLAY:-:0}"
README_MARKER="<!-- screenshot -->"
STATE_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/nop/state"

# When testing on a headless Xvfb, give the screen comfortable margin over these dimensions
# (e.g. 2100x1200): the WM places the window at an offset, and any part hanging past the screen
# edge is silently missing from `import` captures — the shot comes out cropped, not failed.
# Wide window + slim side panels: the editor is the subject of both shots, so it gets the width —
# each half of the side-by-side diff must fit its code lines untruncated.
SHOT_WIDTH=1720
SHOT_HEIGHT=900
# Project-pane width as a fraction of total width (~290px): enough for the demo tree's deepest
# filenames without wrapping, no more.
SHOT_H_RATIO=0.17

mkdir -p "$SHOT_DIR"

# Only the two `latest-*.png` are checked in and referenced by the README; older runs used to leave
# a timestamped pair behind on every invocation. Sweep any such leftovers so the directory doesn't
# accumulate (the `[0-9]*` prefix matches the YYYYMMDD-HHMMSS names without touching `latest-*`).
find "$SHOT_DIR" -maxdepth 1 -type f \
    \( -name '[0-9]*-diff.png' -o -name '[0-9]*-preview.png' \) -delete 2>/dev/null || true

for cmd in wmctrl xdotool xwininfo import convert awk git mktemp sha1sum; do
    if ! command -v "$cmd" >/dev/null; then
        echo "missing required tool: $cmd" >&2
        exit 1
    fi
done

# Locate a nop binary to launch. Prefer the gradle-built distributable (kept in place by
# ./scripts/install.sh), fall back to PATH for users who installed via DMG/MSI.
NOP_BIN=""
for candidate in \
    "$ROOT_DIR/build/compose/binaries/main/app/nop/bin/nop" \
    "$(command -v nop || true)"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
        NOP_BIN="$candidate"
        break
    fi
done
if [ -z "$NOP_BIN" ]; then
    echo "couldn't find a nop binary to launch — run ./scripts/install.sh first" >&2
    exit 3
fi

# Opposite theme from the user's, so the two final screenshots together demonstrate both.
user_theme=$(awk -F= '/^theme=/ {v=$2} END {print (v=="" ? "dark" : v)}' "$STATE_FILE" 2>/dev/null || echo "dark")
opposite_theme=$([ "$user_theme" = "light" ] && echo "dark" || echo "light")

TMP_PARENT=$(mktemp -d --suffix=-nop-shot)
DEMO_PIDS=()
cleanup() {
    for pid in "${DEMO_PIDS[@]:-}"; do
        [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && { kill "$pid" 2>/dev/null || true; }
    done
    sleep 0.3
    for pid in "${DEMO_PIDS[@]:-}"; do
        [ -n "$pid" ] && kill -9 "$pid" 2>/dev/null || true
    done
}
trap cleanup EXIT INT TERM

# Where nop keeps a project's restored tab strip — mirror Settings.projectDataDir: a SHA-1 of the
# absolute path (first 10 hex) suffixing the sanitised final path segment.
project_data_dir() {
    local cfg="$1" abs="$2"
    local safe short
    safe=$(basename "$abs" | sed 's/[^A-Za-z0-9_-]/_/g'); [ -z "$safe" ] && safe="project"
    short=$(printf '%s' "$abs" | sha1sum | cut -c1-10)
    echo "$cfg/nop/projects/$safe-$short"
}

# Spawn an isolated nop. $1=XDG config dir, $2=window-title basename to wait for, $3=optional
# project arg (empty → restore the seeded rail layout). Sets the global LAST_WID to the window id.
# (Sets a global rather than echoing, so the PID it records for cleanup survives in this shell
# rather than a command-substitution subshell.)
LAST_WID=""
launch_isolated() {
    local cfg="$1" want_basename="$2" arg="${3:-}"
    local log="$cfg/nop.log"
    {
        echo "launching at $(date -Is): NOP_BIN=$NOP_BIN cfg=$cfg arg=$arg"
        echo "----- nop output -----"
    } > "$log"
    # Strip _JPACKAGE_LAUNCHER so the fresh jpackage launcher treats this as a first-time start
    # (see the long-form note in install.sh) rather than forwarding raw args to JLI.
    if [ -n "$arg" ]; then
        env -u _JPACKAGE_LAUNCHER XDG_CONFIG_HOME="$cfg" "$NOP_BIN" "$arg" >>"$log" 2>&1 &
    else
        env -u _JPACKAGE_LAUNCHER XDG_CONFIG_HOME="$cfg" "$NOP_BIN" >>"$log" 2>&1 &
    fi
    local pid=$!
    DEMO_PIDS+=("$pid")

    local wid=""
    for _ in $(seq 60); do
        wid=$(DISPLAY="$DISPLAY_SPEC" wmctrl -l 2>/dev/null | awk -v t="nop — $want_basename" '$0 ~ t {print $1; exit}' || true)
        [ -n "$wid" ] && break
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "isolated nop exited before showing a window; log at $log" >&2
            tail -n 40 "$log" >&2 || true
            exit 5
        fi
        sleep 0.5
    done
    if [ -z "$wid" ]; then
        echo "isolated nop window ('nop — $want_basename') never appeared; log at $log" >&2
        tail -n 40 "$log" >&2 || true
        exit 4
    fi
    # Settle: git status loaded, first frame laid out.
    sleep 2
    DISPLAY="$DISPLAY_SPEC" wmctrl -i -r "$wid" -b add,above 2>/dev/null || true
    DISPLAY="$DISPLAY_SPEC" xdotool windowraise "$wid" 2>/dev/null || true
    DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$wid" 2>/dev/null || true
    sleep 0.4
    LAST_WID="$wid"
}

geometry_of() {
    DISPLAY="$DISPLAY_SPEC" xwininfo -id "$1" | awk '
        /Absolute upper-left X:/ {x=$NF}
        /Absolute upper-left Y:/ {y=$NF}
        /Width:/  {w=$NF}
        /Height:/ {h=$NF}
        END {print x, y, w, h}'
}

capture_to() {
    local out="$1" wid="$2"
    local raw; raw="$(mktemp --suffix=.png)"
    DISPLAY="$DISPLAY_SPEC" import -window "$wid" "$raw"
    convert "$raw" -strip -colors 256 -dither None \
        -define png:compression-level=9 -define png:compression-filter=5 "$out"
    rm -f "$raw"
}

# Visual "ink" of the editor pane's top band: high when a diff (gutter + coloured code) is showing,
# near-zero for the empty "click a file…" placeholder. Used to pick the click offset that actually
# opened the diff, so the shot doesn't silently capture a blank pane if the layout shifted. The
# crop must stay inside the editor: right of the project tree (ends at SHOT_H_RATIO) and left of
# the tool panel (starts at ~83% width with the split.tools seeded below) — the panel's change
# list is itself full of ink and would mask a missed click.
pane_ink() {
    local img="$1" w="$2" h="$3"
    local cw=$(( w * 50 / 100 )) ch=$(( h * 22 / 100 ))
    local cx=$(( w * 25 / 100 )) cy=$(( h * 2 / 100 ))
    convert "$img" -crop "${cw}x${ch}+${cx}+${cy}" +repage -colorspace Gray \
        -format '%[fx:standard_deviation]' info: 2>/dev/null || echo 0
}

# ===========================================================================================
# Scene 1 — diff view
# ===========================================================================================
DIFF_BASENAME="nop-shot-diff-$$"
DIFF_CFG="$TMP_PARENT/diff-cfg"
DIFF_PROJECT="$TMP_PARENT/$DIFF_BASENAME"
mkdir -p "$DIFF_CFG/nop" "$DIFF_PROJECT"

# split.tools gives the editor 80% of the width right of the tree; the tool panel keeps ~285px —
# slim, but still wide enough that the commit-message row's buttons don't clip.
cat > "$DIFF_CFG/nop/state" <<EOF
window.width=$SHOT_WIDTH
window.height=$SHOT_HEIGHT
theme=$opposite_theme
split.h=$SHOT_H_RATIO
split.tools=0.80
EOF

# Several files spread across a few directories so the tool panel groups them by directory:
# a "ui" source group (2 files), a "model" group, then tests / config / docs. Each file is
# committed as a baseline, then edited, so every one shows up as a modification with a real diff.
# Greeting.kt — the file the shot opens — is long enough to scroll past the window bottom, with
# its edits spread far apart, so the diff's change stripe shows several separate blocks.
(
    cd "$DIFF_PROJECT"
    git init --quiet
    git config user.email "screenshot@nop.local"
    git config user.name "nop screenshot"

    mkdir -p src/ui src/model src/test docs

    # --- baselines -------------------------------------------------------------------------
    cat > src/ui/Greeting.kt <<'EOF'
package iondrive.nop.ui

/**
 * Builds the greeting lines shown in the demo UI.
 *
 * The service is deliberately small and readable: each function
 * does one thing, and the companion holds the couple of knobs the
 * banner rendering needs.
 */
class GreetingService(private val locale: String) {

    /** Greets a single user by name. */
    fun greet(name: String): String {
        val message = "Hello, $name"
        return decorate(message)
    }

    /** Greets every user in [names], one line each. */
    fun greetAll(names: List<String>): String =
        names.joinToString("\n") { greet(it) }

    /**
     * Says goodbye. Mirrors [greet] so the two read the same way
     * in calling code.
     */
    fun farewell(name: String): String {
        val message = "Goodbye, $name"
        return decorate(message)
    }

    /** Title-cases a raw name for display. */
    fun formatName(raw: String): String =
        raw.trim().split(Regex("\\s+")).joinToString(" ") {
            it.replaceFirstChar(Char::uppercaseChar)
        }

    /** True when [name] can be greeted at all. */
    fun canGreet(name: String): Boolean =
        name.isNotBlank()

    /**
     * Wraps a message with the locale's decorations. The plain
     * locale adds nothing; every other locale gets a full stop.
     */
    private fun decorate(message: String): String {
        if (locale == "plain") return message
        return "$message."
    }

    /**
     * Summarises how many users were greeted, for the status bar
     * at the bottom of the demo window.
     */
    fun summary(count: Int): String = when (count) {
        0 -> "Nobody greeted yet"
        1 -> "Greeted one user"
        else -> "Greeted $count users"
    }

    /** Longest name that still fits the banner. */
    fun fitsBanner(name: String): Boolean =
        name.length <= BANNER_WIDTH

    /** Pads [name] so banner lines align in a mono column. */
    fun padForBanner(name: String): String =
        name.padEnd(BANNER_WIDTH)

    companion object {
        /** Banner column budget, in characters. */
        const val BANNER_WIDTH = 42

        /** Locales the demo ships translations for. */
        val SUPPORTED = listOf("en", "plain")
    }
}
EOF
    cat > src/ui/Header.kt <<'EOF'
package iondrive.nop.ui

import androidx.compose.runtime.Composable
import org.jetbrains.jewel.ui.component.Text

@Composable
fun Header(title: String) {
    Text(title)
}
EOF
    cat > src/model/User.kt <<'EOF'
package iondrive.nop.model

data class User(val name: String) {
    fun greeting(): String = "Hello, $name"
}
EOF
    cat > src/test/GreetingTest.kt <<'EOF'
package iondrive.nop.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingTest {
    @Test
    fun greets() {
        assertEquals("Hello, Ada", User("Ada").greeting())
    }
}
EOF
    cat > docs/guide.md <<'EOF'
# Guide

Greet a user by name.
EOF
    cat > build.gradle.kts <<'EOF'
plugins {
    kotlin("jvm") version "2.1.0"
}

version = "0.1.0"
EOF

    git add -A
    git commit --quiet -m "initial demo project"

    # --- modifications (each produces a real diff) -----------------------------------------
    # Greeting.kt gets four edits spread through the file (greet, farewell, decorate, and the
    # companion constants) so the diff's change stripe shows distinct, separated blocks.
    cat > src/ui/Greeting.kt <<'EOF'
package iondrive.nop.ui

/**
 * Builds the greeting lines shown in the demo UI.
 *
 * The service is deliberately small and readable: each function
 * does one thing, and the companion holds the couple of knobs the
 * banner rendering needs.
 */
class GreetingService(private val locale: String) {

    /** Greets a single user by name, optionally excitedly. */
    fun greet(name: String, excited: Boolean = false): String {
        val punctuation = if (excited) "!" else ""
        val message = "Hello, $name$punctuation"
        return decorate(message)
    }

    /** Greets every user in [names], one line each. */
    fun greetAll(names: List<String>): String =
        names.joinToString("\n") { greet(it) }

    /**
     * Says goodbye. Mirrors [greet] so the two read the same way
     * in calling code.
     */
    fun farewell(name: String): String {
        val message = "See you later, $name"
        return decorate(message)
    }

    /** Title-cases a raw name for display. */
    fun formatName(raw: String): String =
        raw.trim().split(Regex("\\s+")).joinToString(" ") {
            it.replaceFirstChar(Char::uppercaseChar)
        }

    /** True when [name] can be greeted at all. */
    fun canGreet(name: String): Boolean =
        name.isNotBlank()

    /**
     * Wraps a message with the locale's decorations. The plain
     * locale adds nothing, shout upper-cases the whole line, and
     * every other locale gets a full stop.
     */
    private fun decorate(message: String): String {
        if (locale == "plain") return message
        if (locale == "shout") return message.uppercase()
        return "$message."
    }

    /**
     * Summarises how many users were greeted, for the status bar
     * at the bottom of the demo window.
     */
    fun summary(count: Int): String = when (count) {
        0 -> "No greetings sent yet"
        1 -> "Greeted one user"
        else -> "Greeted $count users"
    }

    /** Longest name that still fits the banner. */
    fun fitsBanner(name: String): Boolean =
        name.length <= BANNER_WIDTH

    /** Pads [name] so banner lines align in a mono column. */
    fun padForBanner(name: String): String =
        name.padEnd(BANNER_WIDTH)

    companion object {
        /** Banner column budget, in characters. */
        const val BANNER_WIDTH = 48

        /** Locales the demo ships translations for. */
        val SUPPORTED = listOf("en", "plain", "shout")
    }
}
EOF
    cat > src/ui/Header.kt <<'EOF'
package iondrive.nop.ui

import androidx.compose.runtime.Composable
import org.jetbrains.jewel.ui.component.Text

@Composable
fun Header(title: String, subtitle: String? = null) {
    Text(if (subtitle != null) "$title — $subtitle" else title)
}
EOF
    cat > src/model/User.kt <<'EOF'
package iondrive.nop.model

data class User(val name: String, val excited: Boolean = false) {
    fun greeting(): String = "Hello, $name" + if (excited) "!" else "."
}
EOF
    cat > src/test/GreetingTest.kt <<'EOF'
package iondrive.nop.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class GreetingTest {
    @Test
    fun greets() {
        assertEquals("Hello, Ada.", User("Ada").greeting())
    }

    @Test
    fun greetsExcitedly() {
        assertEquals("Hello, Ada!", User("Ada", excited = true).greeting())
    }
}
EOF
    cat > docs/guide.md <<'EOF'
# Guide

Greet a user by name, optionally with excitement.
EOF
    cat > build.gradle.kts <<'EOF'
plugins {
    kotlin("jvm") version "2.1.0"
}

version = "0.2.0"
EOF
)

launch_isolated "$DIFF_CFG" "$DIFF_BASENAME" "$DIFF_PROJECT"
diff_wid="$LAST_WID"
read DX DY DW DH < <(geometry_of "$diff_wid")
echo "diff window $diff_wid at $DX,$DY ${DW}x${DH}"

diff_out="$SHOT_DIR/latest-diff.png"

# Click the FIRST change row in the tool panel on the right edge — Greeting.kt, the long file
# whose diff the shot is about. The x sits at 92% of the window width: past the row's checkbox
# and kind prefix, inside the click-to-open area that spans the rest of the row (panel starts at
# ~83% with the ratios seeded above). That first row sits ~245px below the window top (tab strip,
# header, buttons, recent-messages dropdown, message box); the exact Y depends on render scale,
# so the offsets cover the row's position at 1x through 1.5x. Each is only tried until one
# lands: the ink probe stops the loop at the first capture whose editor pane clearly shows a
# diff, because a later offset would hit a DIFFERENT row and put the wrong file in the shot.
diff_row_x=$(( DX + DW * 92 / 100 ))
best_ink="-1"
for off in 245 215 300 330 365; do
    DISPLAY="$DISPLAY_SPEC" xdotool windowraise "$diff_wid" || true
    DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$diff_wid"
    sleep 0.2
    DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$diff_row_x" $(( DY + off ))
    sleep 0.15
    DISPLAY="$DISPLAY_SPEC" xdotool click 1
    # Park the cursor over the editor (not the rail's "+" button) so no tooltip is in the shot.
    DISPLAY="$DISPLAY_SPEC" xdotool mousemove $(( DX + DW * 65 / 100 )) $(( DY + DH * 28 / 100 ))
    sleep 0.9
    cand="$(mktemp --suffix=.png)"
    DISPLAY="$DISPLAY_SPEC" import -window "$diff_wid" "$cand"
    ink=$(pane_ink "$cand" "$DW" "$DH")
    keep=$(awk -v a="$ink" -v b="$best_ink" 'BEGIN{print (a>b)?1:0}')
    if [ "$keep" = "1" ]; then
        best_ink="$ink"
        convert "$cand" -strip -colors 256 -dither None \
            -define png:compression-level=9 -define png:compression-filter=5 "$diff_out"
    fi
    rm -f "$cand"
    # A blank editor pane probes near zero and an open diff ~0.09+, so 0.05 separates them
    # cleanly. Stopping at the first hit matters: later offsets would open other rows' diffs.
    if awk -v a="$ink" 'BEGIN{exit !(a>0.05)}'; then break; fi
done
echo "diff shot ink=$best_ink"

# ===========================================================================================
# Scene 2 — workspace / preview (synthetic tabs + tab groups, NOT the user's workspace)
# ===========================================================================================
PREV_BASENAME="webapp-$$"
PREV_CFG="$TMP_PARENT/prev-cfg"
mkdir -p "$PREV_CFG/nop"

WEBAPP="$TMP_PARENT/$PREV_BASENAME"
API="$TMP_PARENT/api-server-$$"
BLOG="$TMP_PARENT/blog-$$"
mkdir -p "$WEBAPP/src" "$API" "$BLOG"

cat > "$WEBAPP/src/App.kt" <<'EOF'
package webapp

import androidx.compose.runtime.Composable

// Application entry point and top-level layout.
@Composable
fun App(state: AppState) {
    val theme = if (state.dark) Theme.Dark else Theme.Light
    Workspace(theme) {
        Sidebar(state.projects)
        Editor(state.activeFile)
    }
}
EOF
cat > "$WEBAPP/src/Theme.kt" <<'EOF'
package webapp

// Colour palettes for the two themes.
enum class Theme(val background: Long, val foreground: Long) {
    Dark(0xFF1E1F22, 0xFFA9B7C6),
    Light(0xFFFFFFFF, 0xFF1F2329),
}
EOF
cat > "$WEBAPP/README.md" <<'EOF'
# webapp

A small synthetic project used for the nop screenshot.
EOF
# The other two rail projects aren't opened in the shot, but they need a file each so their repo has
# a commit (an empty repo has no HEAD) and so their tree isn't bare if clicked.
cat > "$API/main.py" <<'EOF'
# Tiny synthetic API server for the nop screenshot.
from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")


if __name__ == "__main__":
    HTTPServer(("", 8080), Handler).serve_forever()
EOF
cat > "$BLOG/index.md" <<'EOF'
# blog

A small synthetic project used for the nop screenshot.
EOF

# Each demo project is its own git repo. nop roots the tree, editor tabs and commit panel at the
# discovered git root (App.kt: `rootPath = repo?.rootDir ?: projectPath`), climbing parents until it
# finds a `.git`. Without one of their own, these /tmp-based projects would climb to whatever `.git`
# happens to sit higher up — e.g. a stray empty /tmp/.git — and the shot would show the user's real
# /tmp instead of the synthetic files. A repo per project pins the root to the project itself.
for demo in "$WEBAPP" "$API" "$BLOG"; do
    git -C "$demo" init --quiet
    git -C "$demo" config user.email "screenshot@nop.local"
    git -C "$demo" config user.name "nop screenshot"
    git -C "$demo" add -A
    git -C "$demo" commit --quiet -m "initial commit"
done

# Rail layout: two named separators grouping three project tabs. open.N mirrors the projects so a
# no-arg launch restores the layout (Settings.loadRailLayout falls back to open.N otherwise).
cat > "$PREV_CFG/nop/state" <<EOF
window.width=$SHOT_WIDTH
window.height=$SHOT_HEIGHT
theme=$user_theme
split.h=$SHOT_H_RATIO
split.tools=0.80
active=$WEBAPP
rail.0=sep:WORK
rail.1=project:$WEBAPP
rail.2=project:$API
rail.3=sep:SIDE PROJECTS
rail.4=project:$BLOG
open.0=$WEBAPP
open.1=$API
open.2=$BLOG
EOF

# Seed editor tabs for the active project so the top tab strip shows several tabs (App.kt selected).
PREV_DATA=$(project_data_dir "$PREV_CFG" "$WEBAPP")
mkdir -p "$PREV_DATA"
{
    printf 'file\t%s\t1\n' "$WEBAPP/src/App.kt"
    printf 'file\t%s\t0\n' "$WEBAPP/src/Theme.kt"
    printf 'file\t%s\t0\n' "$WEBAPP/README.md"
} > "$PREV_DATA/tabs.tsv"

launch_isolated "$PREV_CFG" "$PREV_BASENAME" ""
prev_wid="$LAST_WID"
read PX PY PW PH < <(geometry_of "$prev_wid")
echo "preview window $prev_wid at $PX,$PY ${PW}x${PH}"
# Park the cursor over the editor so the rail's "+" tooltip isn't captured.
DISPLAY="$DISPLAY_SPEC" xdotool mousemove $(( PX + PW * 60 / 100 )) $(( PY + PH * 30 / 100 ))
sleep 1.0

preview_out="$SHOT_DIR/latest-preview.png"
capture_to "$preview_out" "$prev_wid"

cleanup
DEMO_PIDS=()
trap 'rm -rf "$TMP_PARENT"' EXIT INT TERM

echo "wrote $diff_out ($(stat -c %s "$diff_out") bytes)"
echo "wrote $preview_out ($(stat -c %s "$preview_out") bytes)"

# Insert / replace the screenshot block in the README so the latest captures show up inline.
block="$README_MARKER
![Diff view](docs/screenshots/latest-diff.png)
![Workspace preview](docs/screenshots/latest-preview.png)
$README_MARKER"

if grep -q "$README_MARKER" "$README"; then
    awk -v block="$block" -v marker="$README_MARKER" '
        $0 ~ marker && !seen { print block; seen = 1; in_block = 1; next }
        in_block && $0 ~ marker { in_block = 0; next }
        !in_block { print }
    ' "$README" > "$README.tmp"
    mv "$README.tmp" "$README"
else
    printf '\n%s\n' "$block" >> "$README"
fi

echo "README updated"
