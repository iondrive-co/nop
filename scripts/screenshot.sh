#!/usr/bin/env bash
# Capture screenshots of the running nop window and embed the latest one in the README.
#
# Designed to be invoked from inside nop (via the ▶ launcher). It targets the running
# nop process so we get a real picture of the current state, instead of spawning a
# fresh instance that wouldn't have the user's open tabs.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SHOT_DIR="$ROOT_DIR/docs/screenshots"
README="$ROOT_DIR/README.md"
DISPLAY_SPEC="${DISPLAY:-:0}"
LATEST_LINK="latest.png"
README_MARKER="<!-- screenshot -->"

mkdir -p "$SHOT_DIR"

for cmd in wmctrl xdotool import; do
    if ! command -v "$cmd" >/dev/null; then
        echo "missing required tool: $cmd" >&2
        exit 1
    fi
done

# Pick the nop window. The shell-launched run is the one that spawned us, but we don't
# rely on that — we just take whatever window the user can see right now.
wid=$(DISPLAY="$DISPLAY_SPEC" wmctrl -l | awk '/nop — / {print $1; exit}')
if [ -z "$wid" ]; then
    echo "no nop window found via wmctrl" >&2
    exit 2
fi

# Set the stage: open a diff so the README shot shows nop's main feature instead
# of the empty launcher-output tab the screenshot button itself just activated.
# We synthesize a click on the first row of the bottom commit panel, computed
# from the current window geometry (the layout uses a 0.55 vertical split with
# the commit panel below, and a 0.22 horizontal split with the file tree on the
# left — see App.kt's rememberSplitLayoutState calls).
eval "$(DISPLAY="$DISPLAY_SPEC" xdotool getwindowgeometry --shell "$wid")"
DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$wid"
# Inside the commit panel, the first change row sits a few lines below the
# panel header + message box. ~69% down and ~30% across reliably hits the
# first row at default split ratios; if the user has dragged the splitters
# this may need adjusting.
click_x=$((X + WIDTH * 30 / 100))
click_y=$((Y + HEIGHT * 69 / 100))
DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$click_x" "$click_y" click 1
# Park the cursor off the visible area so the pointer doesn't end up in the shot.
DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$X" "$Y"
# Give Compose a frame to render the freshly-opened Diff tab.
sleep 0.4

ts=$(date +%Y%m%d-%H%M%S)
out="$SHOT_DIR/$ts.png"
DISPLAY="$DISPLAY_SPEC" import -window "$wid" "$out"
ln -sf "$(basename "$out")" "$SHOT_DIR/$LATEST_LINK"
echo "wrote $out"

# Insert / replace a screenshot block in the README so the latest capture shows up
# inline. The marker lets us update in-place without growing the file each run.
rel="docs/screenshots/$LATEST_LINK"
block="$README_MARKER
![nop screenshot]($rel)
*Captured $(date '+%Y-%m-%d %H:%M:%S')*
$README_MARKER"

if grep -q "$README_MARKER" "$README"; then
    # Replace the existing block (between two markers, inclusive).
    awk -v block="$block" -v marker="$README_MARKER" '
        $0 ~ marker && !seen { print block; seen = 1; in_block = 1; next }
        in_block && $0 ~ marker { in_block = 0; next }
        !in_block { print }
    ' "$README" > "$README.tmp"
    mv "$README.tmp" "$README"
else
    printf '\n%s\n' "$block" >> "$README"
fi

echo "README updated with $rel"
