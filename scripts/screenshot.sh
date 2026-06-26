#!/usr/bin/env bash
# Capture the two README screenshots, each from its own freshly-spawned, fully isolated nop
# instance pointed at synthetic state — so the shots are curated and reproducible instead of
# depending on whatever the user happens to have open:
#
#   - A diff-view shot: a one-file git repo with a committed + modified source file, opened to
#     its side-by-side diff.
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

SHOT_WIDTH=1400
SHOT_HEIGHT=900
# Project-pane width as a fraction of total width. Wider than the App.kt default (0.22) so
# filenames in the tree never wrap one character per line.
SHOT_H_RATIO=0.30

mkdir -p "$SHOT_DIR"

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
# opened the diff, so the shot doesn't silently capture a blank pane if the layout shifted.
pane_ink() {
    local img="$1" w="$2" h="$3"
    local cw=$(( w * 60 / 100 )) ch=$(( h * 22 / 100 ))
    local cx=$(( w * 33 / 100 )) cy=$(( h * 2 / 100 ))
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

cat > "$DIFF_CFG/nop/state" <<EOF
window.width=$SHOT_WIDTH
window.height=$SHOT_HEIGHT
theme=$opposite_theme
split.h=$SHOT_H_RATIO
split.v=0.55
EOF
# Pin a short commit-message box so the single change row sits predictably high in the panel.
DIFF_DATA=$(project_data_dir "$DIFF_CFG" "$DIFF_PROJECT")
mkdir -p "$DIFF_DATA"
echo "40.0" > "$DIFF_DATA/commit-height"

(
    cd "$DIFF_PROJECT"
    git init --quiet
    git config user.email "screenshot@nop.local"
    git config user.name "nop screenshot"
    cat > Greeting.kt <<'BASELINE'
package iondrive.nop.demo

import androidx.compose.runtime.Composable
import org.jetbrains.jewel.ui.component.Text

@Composable
fun Greeting(name: String) {
    // Render a friendly greeting.
    val message = "Hello, $name"
    Text(message)
}
BASELINE
    git add Greeting.kt
    git commit --quiet -m "initial greeting"
    cat > Greeting.kt <<'MODIFIED'
package iondrive.nop.demo

import androidx.compose.runtime.Composable
import org.jetbrains.jewel.ui.component.Text

@Composable
fun Greeting(name: String, excited: Boolean = false) {
    // Render a friendly, optionally excited greeting.
    val punctuation = if (excited) "!" else "."
    val message = "Hello, $name$punctuation"
    Text(message)
}
MODIFIED
)

launch_isolated "$DIFF_CFG" "$DIFF_BASENAME" "$DIFF_PROJECT"
diff_wid="$LAST_WID"
read DX DY DW DH < <(geometry_of "$diff_wid")
echo "diff window $diff_wid at $DX,$DY ${DW}x${DH}"

ts=$(date +%Y%m%d-%H%M%S)
diff_out="$SHOT_DIR/${ts}-diff.png"

# Click the single change row in the commit panel to open its diff. The row's exact Y depends on
# render scale, so try a few offsets below the bottom-panel top and keep whichever capture has the
# most "ink" in the editor pane (i.e. actually opened the diff).
diff_panel_top=$(( DY + DH * 55 / 100 ))
diff_row_x=$(( DX + DW * 45 / 100 ))
best_ink="-1"
for off in 150 175 200 225 130; do
    DISPLAY="$DISPLAY_SPEC" xdotool windowraise "$diff_wid" || true
    DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$diff_wid"
    sleep 0.2
    DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$diff_row_x" $(( diff_panel_top + off ))
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
    # Clearly-a-diff already: stop early.
    if awk -v a="$ink" 'BEGIN{exit !(a>0.10)}'; then break; fi
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

# Rail layout: two named separators grouping three project tabs. open.N mirrors the projects so a
# no-arg launch restores the layout (Settings.loadRailLayout falls back to open.N otherwise).
cat > "$PREV_CFG/nop/state" <<EOF
window.width=$SHOT_WIDTH
window.height=$SHOT_HEIGHT
theme=$user_theme
split.h=$SHOT_H_RATIO
split.v=0.62
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

preview_out="$SHOT_DIR/${ts}-preview.png"
capture_to "$preview_out" "$prev_wid"

cleanup
DEMO_PIDS=()
trap 'rm -rf "$TMP_PARENT"' EXIT INT TERM

cp -f "$diff_out" "$SHOT_DIR/latest-diff.png"
cp -f "$preview_out" "$SHOT_DIR/latest-preview.png"
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
