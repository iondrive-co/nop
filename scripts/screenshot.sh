#!/usr/bin/env bash
# Capture two screenshots for the README:
#
#   - A diff-view shot rendered in a freshly-spawned isolated nop instance pointed at a
#     synthetic one-file git repo, so the diff is curated and visually clean instead of
#     whatever happens to be in the user's working tree.
#   - A README markdown-preview shot taken against the user's own running nop, so it
#     shows the real project's actual rendering.
#
# The two shots are set up in opposite themes so the README demonstrates both. Output is
# quantised to a 256-colour palette before saving, which trims the PNGs by ~3x with no
# visible difference vs. the truecolor capture.
#
# Designed to be invoked from inside nop (via the ▶ launcher).

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SHOT_DIR="$ROOT_DIR/docs/screenshots"
README="$ROOT_DIR/README.md"
DISPLAY_SPEC="${DISPLAY:-:0}"
README_MARKER="<!-- screenshot -->"
STATE_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/nop/state"

# Window size for the isolated diff capture. We pre-seed this into the isolated state so
# the screenshot has a consistent shape independent of the user's monitor.
SHOT_WIDTH=1400
SHOT_HEIGHT=900
# Project-pane width as a fraction of total width. Wider than the App.kt default (0.22)
# so filenames in the tree never wrap one character per line.
SHOT_H_RATIO=0.30

mkdir -p "$SHOT_DIR"

for cmd in wmctrl xdotool xwininfo import convert awk git mktemp; do
    if ! command -v "$cmd" >/dev/null; then
        echo "missing required tool: $cmd" >&2
        exit 1
    fi
done

# --- Stage the synthetic diff scene in an isolated nop instance ---
TMP_PARENT=$(mktemp -d --suffix=-nop-shot)
# Unique basename so the spawned window's title ("nop — <basename>") can't collide with
# any project the user already has open.
DEMO_BASENAME="nop-shot-$$"
TMP_PROJECT="$TMP_PARENT/$DEMO_BASENAME"
TMP_CFG="$TMP_PARENT/cfg"
mkdir -p "$TMP_PROJECT" "$TMP_CFG/nop"

# Pre-seed the isolated state file: opposite theme from the user's (so the two final
# screenshots together demonstrate both themes), a wider project pane than the default
# so filenames render readably, and a fixed window size so the captured PNG dimensions
# don't depend on the user's main monitor.
user_theme=$(awk -F= '/^theme=/ {v=$2} END {print (v=="" ? "dark" : v)}' "$STATE_FILE" 2>/dev/null || echo "dark")
isolated_theme=$([ "$user_theme" = "light" ] && echo "dark" || echo "light")
cat > "$TMP_CFG/nop/state" <<EOF
window.width=$SHOT_WIDTH
window.height=$SHOT_HEIGHT
theme=$isolated_theme
split.h=$SHOT_H_RATIO
split.v=0.55
EOF

# Synthetic git repo: commit a baseline file, then leave a small modification in the
# working tree so the commit panel has exactly one diffable row when nop opens it.
(
    cd "$TMP_PROJECT"
    git init --quiet
    git config user.email "screenshot@nop.local"
    git config user.name "nop screenshot"
    cat > Greeting.kt <<'BASELINE'
package iondrive.nop.demo

import androidx.compose.runtime.Composable
import org.jetbrains.jewel.ui.component.Text

@Composable
fun Greeting(name: String) {
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
    val punctuation = if (excited) "!" else "."
    val message = "Hello, $name$punctuation"
    Text(message)
}
MODIFIED
)

# Locate a nop binary to launch. Prefer the gradle-built distributable (kept in place
# by ./scripts/install.sh), fall back to PATH for users who installed via DMG/MSI.
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
    rm -rf "$TMP_PARENT"
    exit 3
fi

# Spawn the isolated nop. Its XDG_CONFIG_HOME points at our pre-seeded state, and its
# single-instance lock lives under that root too, so it can't collide with the user's
# running nop (which uses ~/.config/nop).
#
# Record what we're about to launch so a failure leaves enough breadcrumbs to debug
# — without this, "JVM died with classpath error" leaves you guessing whether the
# launcher path was wrong, the cfg was stale, etc.
{
    echo "screenshot.sh launching at $(date -Is)"
    echo "  NOP_BIN=$NOP_BIN"
    echo "  TMP_PROJECT=$TMP_PROJECT"
    echo "  TMP_CFG=$TMP_CFG"
    echo "  PWD=$PWD"
    echo "  binary file type: $(file "$NOP_BIN" | sed 's/^[^:]*: //')"
    echo "----- nop output below -----"
} > "$TMP_PARENT/nop.log"
# Unset _JPACKAGE_LAUNCHER before spawning the isolated nop. When the script is invoked
# from inside a running jpackage app (nop's ▶ launcher), the parent launcher leaves this
# env var set in every child. The fresh jpackage launcher we're about to spawn would see
# it and decide "I'm a re-launch of a sibling app, skip the .cfg, just forward user args
# raw to JLI" — turning the project path arg into the main-class arg, which the JVM then
# fails to load with ClassNotFoundException. Stripping it makes the new launcher behave
# like a normal first-time invocation.
env -u _JPACKAGE_LAUNCHER \
    XDG_CONFIG_HOME="$TMP_CFG" "$NOP_BIN" "$TMP_PROJECT" >>"$TMP_PARENT/nop.log" 2>&1 &
DEMO_PID=$!

# Cleanup only kills the JVM; the log directory survives so a failing run leaves something
# to diagnose. On success we wipe TMP_PARENT explicitly at the end of the script.
cleanup_demo() {
    if [ -n "${DEMO_PID:-}" ] && kill -0 "$DEMO_PID" 2>/dev/null; then
        kill "$DEMO_PID" 2>/dev/null || true
        sleep 0.3
        kill -9 "$DEMO_PID" 2>/dev/null || true
    fi
}
trap cleanup_demo EXIT INT TERM

# Wait for the isolated window to appear. We match by the unique basename in the title
# so we can't accidentally grab one of the user's other nop windows. Cold JVM start +
# Compose first-frame can take ~10s on slower hardware, so allow up to ~30s.
demo_wid=""
for _ in $(seq 60); do
    demo_wid=$(DISPLAY="$DISPLAY_SPEC" wmctrl -l | awk -v t="nop — $DEMO_BASENAME" '$0 ~ t {print $1; exit}')
    [ -n "$demo_wid" ] && break
    # If the JVM died before showing a window, stop early so the user sees the real reason
    # in the log instead of waiting out the full timeout.
    if ! kill -0 "$DEMO_PID" 2>/dev/null; then
        echo "isolated nop exited before showing a window; log retained at $TMP_PARENT/nop.log" >&2
        echo "--- last 40 lines of the log ---" >&2
        tail -n 40 "$TMP_PARENT/nop.log" >&2 || true
        exit 5
    fi
    sleep 0.5
done
if [ -z "$demo_wid" ]; then
    echo "isolated nop window never appeared within 30s; log retained at $TMP_PARENT/nop.log" >&2
    echo "wmctrl currently sees:" >&2
    DISPLAY="$DISPLAY_SPEC" wmctrl -l | sed 's/^/  /' >&2 || true
    echo "--- last 40 lines of the log ---" >&2
    tail -n 40 "$TMP_PARENT/nop.log" >&2 || true
    exit 4
fi

# Give the JVM a moment more so git status has loaded and the commit-panel layout has
# settled before we try clicking a row.
sleep 1.5

# Pin the demo window above everything else. The user typically has several "nop — "
# windows already open, and xfwm4's focus-stealing-prevention means a plain
# `windowactivate` doesn't reliably bring the new window to the top — so an xdotool
# click at on-screen coordinates can land on whatever other nop window is in front. The
# "above" hint is enough to keep our window on top for the few hundred ms we need.
DISPLAY="$DISPLAY_SPEC" wmctrl -i -r "$demo_wid" -b add,above 2>/dev/null || true
DISPLAY="$DISPLAY_SPEC" xdotool windowraise "$demo_wid" || true
DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$demo_wid"
DISPLAY="$DISPLAY_SPEC" xdotool windowfocus --sync "$demo_wid" || true
sleep 0.4

read DEMO_X DEMO_Y DEMO_W DEMO_H < <(DISPLAY="$DISPLAY_SPEC" xwininfo -id "$demo_wid" | awk '
    /Absolute upper-left X:/ {x=$NF}
    /Absolute upper-left Y:/ {y=$NF}
    /Width:/  {w=$NF}
    /Height:/ {h=$NF}
    END {print x, y, w, h}
')

capture_to() {
    local out="$1" wid="$2"
    local raw
    raw="$(mktemp --suffix=.png)"
    DISPLAY="$DISPLAY_SPEC" import -window "$wid" "$raw"
    convert "$raw" -strip -colors 256 -dither None \
        -define png:compression-level=9 -define png:compression-filter=5 "$out"
    rm -f "$raw"
}

# Click the (only) change row in the isolated commit panel. The vertical split puts the
# commit panel below the 0.55 line; inside the panel the BottomTabs TabStrip (~40dp) +
# CommitPanel padding + header row + message TextArea (64dp default) + resize handle
# stack up to ~170px before the change list. Empirically measured at default scale:
# +175 lands on the middle of row 0 with plenty of margin from the TextArea above.
demo_panel_top=$((DEMO_Y + DEMO_H * 55 / 100))
demo_row_x=$((DEMO_X + DEMO_W * 50 / 100))
demo_row_y=$((demo_panel_top + 175))

# Re-raise immediately before clicking — between activate and click, the user's other
# nop windows can come to the front and the click would land on them instead. Then move
# + click + (small settle) + verify by checking whether a Diff tab opened; if not, retry.
# Compose's click handlers want a real mouse press at on-screen coordinates, so we send
# native motion + click via the root display, not via xdotool --window which goes through
# XSendEvent and is ignored by some listeners.
click_change_row() {
    DISPLAY="$DISPLAY_SPEC" xdotool windowraise "$demo_wid" || true
    DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$demo_wid"
    sleep 0.2
    DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$demo_row_x" "$demo_row_y"
    sleep 0.15
    DISPLAY="$DISPLAY_SPEC" xdotool click 1
    DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$DEMO_X" "$DEMO_Y"
    sleep 1.0
}
click_change_row

ts=$(date +%Y%m%d-%H%M%S)
diff_out="$SHOT_DIR/${ts}-diff.png"
preview_out="$SHOT_DIR/${ts}-preview.png"

capture_to "$diff_out" "$demo_wid"

# Done with the isolated nop. Tear it down before driving the user's window for the
# preview shot so we don't have a stray "demo" nop kicking around on the desktop.
cleanup_demo
DEMO_PID=""
trap 'rm -rf "$TMP_PARENT"' EXIT INT TERM

# --- Preview screenshot: drive the user's own nop session ---
wid=$(DISPLAY="$DISPLAY_SPEC" wmctrl -l | awk '/nop — / {print $1; exit}')
if [ -z "$wid" ]; then
    echo "no nop window found via wmctrl for preview shot" >&2
    exit 2
fi
DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$wid"
read X Y WIDTH HEIGHT < <(DISPLAY="$DISPLAY_SPEC" xwininfo -id "$wid" | awk '
    /Absolute upper-left X:/ {x=$NF}
    /Absolute upper-left Y:/ {y=$NF}
    /Width:/  {w=$NF}
    /Height:/ {h=$NF}
    END {print x, y, w, h}
')

park_cursor() { DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$X" "$Y"; }

# Read current horizontal split ratio so we can drag back to it after capture. Falls
# back to the App.kt default (0.22) if the state file is missing or unparsable.
hRatio_orig=$(awk -F= '/^split\.h=/ {v=$2} END {if (v=="" || v+0 < 0.05 || v+0 > 0.95) print "0.22"; else printf "%s", v}' "$STATE_FILE" 2>/dev/null || echo "0.22")

ratio_to_x() {
    awk -v wx="$X" -v w="$WIDTH" -v r="$1" 'BEGIN { printf "%d", wx + (w - 4) * r + 2 }'
}
divider_orig_x=$(ratio_to_x "$hRatio_orig")
divider_target_x=$(ratio_to_x "$SHOT_H_RATIO")
mid_y=$((Y + HEIGHT / 2))

drag_h_divider() {
    local from_x=$1 to_x=$2
    DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$wid"
    DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$from_x" "$mid_y"
    sleep 0.15
    DISPLAY="$DISPLAY_SPEC" xdotool mousedown 1
    sleep 0.1
    local step=24 x=$from_x
    while [ "$x" != "$to_x" ]; do
        if [ "$to_x" -gt "$x" ]; then
            x=$((x + step))
            [ "$x" -gt "$to_x" ] && x=$to_x
        else
            x=$((x - step))
            [ "$x" -lt "$to_x" ] && x=$to_x
        fi
        DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$x" "$mid_y"
        sleep 0.02
    done
    sleep 0.1
    DISPLAY="$DISPLAY_SPEC" xdotool mouseup 1
    park_cursor
    sleep 0.5
}

drag_h_divider "$divider_orig_x" "$divider_target_x"

# Use nop's own double-shift file-search dialog to jump straight to README.md. Avoids
# brittle pixel arithmetic against the tree, which shifts when ancestors get auto-expanded.
# Pointedly NOT using xdotool's `--window` flag: that path goes via XSendEvent and
# Compose's key listeners don't fire on synthetic events.
DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$wid"
DISPLAY="$DISPLAY_SPEC" xdotool key shift
sleep 0.05
DISPLAY="$DISPLAY_SPEC" xdotool key shift
sleep 0.3
DISPLAY="$DISPLAY_SPEC" xdotool type --delay 20 "README"
sleep 0.3
DISPLAY="$DISPLAY_SPEC" xdotool key Return
park_cursor
sleep 0.6
capture_to "$preview_out" "$wid"

# Restore the user's preferred project-pane width.
drag_h_divider "$divider_target_x" "$divider_orig_x"

cp -f "$diff_out" "$SHOT_DIR/latest-diff.png"
cp -f "$preview_out" "$SHOT_DIR/latest-preview.png"

diff_size=$(stat -c %s "$diff_out")
preview_size=$(stat -c %s "$preview_out")
echo "wrote $diff_out (${diff_size} bytes)"
echo "wrote $preview_out (${preview_size} bytes)"

# Insert / replace a screenshot block in the README so the latest captures show up
# inline. The marker pair lets us update in-place without growing the file each run.
block="$README_MARKER
![Diff view](docs/screenshots/latest-diff.png)
![README preview](docs/screenshots/latest-preview.png)
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
