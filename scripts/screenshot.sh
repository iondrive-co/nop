#!/usr/bin/env bash
# Capture two screenshots of the running nop window — one of the diff view and one of
# the README rendered-markdown preview, in opposite themes — and embed them in the README.
#
# Designed to be invoked from inside nop (via the ▶ launcher). It targets the running
# nop process so we get a real picture of the current state, instead of spawning a
# fresh instance that wouldn't have the user's open tabs.
#
# Output is quantised to a 256-colour palette before saving, which trims the PNGs by
# ~3x with no visible difference vs. the truecolor capture.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SHOT_DIR="$ROOT_DIR/docs/screenshots"
README="$ROOT_DIR/README.md"
DISPLAY_SPEC="${DISPLAY:-:0}"
README_MARKER="<!-- screenshot -->"

mkdir -p "$SHOT_DIR"

for cmd in wmctrl xdotool import convert; do
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

eval "$(DISPLAY="$DISPLAY_SPEC" xdotool getwindowgeometry --shell "$wid")"
DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$wid"

# Park the cursor off-window between actions so the pointer doesn't leak into the shot.
park_cursor() {
    DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$X" "$Y"
}

# Header icons run along the top of the tree panel at fixed offsets, irrespective of
# window size: Project label, then folder / history / theme buttons spaced ~30px apart.
# If you reorder ProjectTreePanel's header buttons, update theme_x here.
icon_y=$((Y + 22))
theme_x=$((X + 140))

click_theme_toggle() {
    DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$theme_x" "$icon_y" click 1
    park_cursor
    # The IntUi theme swap involves a recomposition + style cache rebuild — give Skia a
    # few frames to settle so we don't snap a half-repainted intermediate.
    sleep 0.8
}

# Capture, palette-quantise, and write to $1.
capture_to() {
    local out="$1"
    local raw
    raw="$(mktemp --suffix=.png)"
    DISPLAY="$DISPLAY_SPEC" import -window "$wid" "$raw"
    # 256 colours + max zlib compression. Strip metadata; +dither keeps text crisp.
    convert "$raw" -strip -colors 256 -dither None \
        -define png:compression-level=9 -define png:compression-filter=5 "$out"
    rm -f "$raw"
}

ts=$(date +%Y%m%d-%H%M%S)
diff_out="$SHOT_DIR/${ts}-diff.png"
preview_out="$SHOT_DIR/${ts}-preview.png"

# --- Screenshot 1: diff view ---
# Synthesize a click on the first row of the bottom commit panel. The layout uses a
# 0.55 vertical split with the commit panel below — see App.kt's rememberSplitLayoutState.
# Inside the panel: header row + message box (64dp) + button strip + paddings stack up
# to ~126px before the change list. Add half a row to centre on the first change.
panel_top=$((Y + HEIGHT * 55 / 100))
diff_x=$((X + WIDTH * 30 / 100))
diff_y=$((panel_top + 126))
DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$diff_x" "$diff_y" click 1
park_cursor
sleep 0.4
capture_to "$diff_out"

# --- Theme toggle so the second shot lands in the opposite mode ---
click_theme_toggle

# --- Screenshot 2: README rendered-markdown preview ---
# Tree rows are ~24px tall. With the nop repo open, the layout is: root (y=54), four
# ignored-aware directories (docs, gradle, scripts, src at y=78,102,126,150), then files
# alphabetically — build.gradle.kts, gradle.properties, gradlew, gradlew.bat, README.md
# (the 5th file, y=270). Adjust readme_y if you reshape the repo or test with a different
# project; this script is tuned for capturing nop's own README in the nop repo.
tree_x=$((X + 80))
readme_y=$((Y + 270))
DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$tree_x" "$readme_y" click 1
park_cursor
sleep 0.6
capture_to "$preview_out"

# Restore the user's original theme so the script doesn't surprise them later.
click_theme_toggle

# Symlinkish "latest" pointers — copies, since GitHub's markdown serves the link target
# text as the blob for symlinks rather than following them.
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
*Captured $(date '+%Y-%m-%d %H:%M:%S') — light & dark mode toggled via the header button*
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
