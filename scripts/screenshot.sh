#!/usr/bin/env bash
# Capture the README screenshots from freshly-spawned, fully isolated nop instances pointed at
# synthetic state — so the shots are curated and reproducible instead of depending on whatever the
# user happens to have open — and write them into the README under a heading and a caption each:
#
#   - An agents shot: a Claude Code session running in the agent pane, with the usage strip along
#     the bottom holding several made-up accounts across all three providers.
#   - A diff shot: a file opened from the commit panel to its side-by-side diff. The file is long
#     enough to scroll past the window bottom, with its hunks spread far apart, so the change
#     stripe beside the diff shows separate blocks marking where they are.
#   - An accounts shot: the Agent accounts dialog for those same accounts.
#   - A workspace shot: a named window holding several synthetic project tabs along the top.
#
# NOTHING from the user's real workspace or accounts appears. Every account, home, model and usage
# figure is invented: the readings come from a fixture file (see UsageFixture.kt), so no provider is
# asked anything, and the session is a stand-in `claude` that draws a made-up conversation — no
# real CLI runs.
#
# Each shot is cropped to the part of the window it is about, and no wider than ~840px: GitHub
# draws README images at most as wide as its text column (~840px) and scales anything wider down,
# which is what left whole-window shots unreadable. At that width they are shown pixel for pixel.
#
# The shots use both themes so the README demonstrates both. Output is quantised to a 256-colour
# palette before saving, which trims the PNGs by ~3x with no visible difference vs. the truecolor
# capture.
#
# Designed to be invoked from inside nop (via the ▶ launcher). For local testing the output
# locations can be redirected with NOP_SHOT_DIR / NOP_SHOT_README so a dry run doesn't touch
# the checked-in screenshots or README, and NOP_SHOT_BIN names the nop binary to launch.

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
# Every window is this size; what each shot keeps of it is decided by the ratios its scene seeds.
SHOT_WIDTH=1720
SHOT_HEIGHT=900
# Project-pane width as a fraction of total width (~290px): enough for the demo tree's deepest
# filenames without wrapping, no more.
SHOT_H_RATIO=0.17
# The window's client area at 1x, which the pixel offsets below are measured against: a window
# that comes out wider is rendering at a larger scale, and they are scaled to match.
SHOT_CLIENT_WIDTH=1710

mkdir -p "$SHOT_DIR"

# Only the two `latest-*.png` are checked in and referenced by the README; older runs used to leave
# a timestamped pair behind on every invocation. Sweep any such leftovers so the directory doesn't
# accumulate (the `[0-9]*` prefix matches the YYYYMMDD-HHMMSS names without touching `latest-*`).
find "$SHOT_DIR" -maxdepth 1 -type f \
    \( -name '[0-9]*-diff.png' -o -name '[0-9]*-preview.png' \) -delete 2>/dev/null || true

for cmd in wmctrl xdotool xwininfo xprop import convert awk git mktemp sha1sum; do
    if ! command -v "$cmd" >/dev/null; then
        echo "missing required tool: $cmd" >&2
        exit 1
    fi
done

# Locate a nop binary to launch. Prefer the gradle-built distributable (kept in place by
# ./scripts/install.sh), fall back to PATH for users who installed via DMG/MSI.
NOP_BIN=""
for candidate in \
    "${NOP_SHOT_BIN:-}" \
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

# Stand-ins for the vendor CLIs, first on every demo instance's PATH. The agent picker marks an
# account whose CLI can't be found as "not installed", which would make the agents shot depend on
# what this machine has installed; and nothing started against a made-up account may reach the
# real CLI. codex and agy exit straight away.
STUB_BIN="$TMP_PARENT/bin"
mkdir -p "$STUB_BIN"
for cli in codex agy; do
    printf '#!/bin/sh\nexit 0\n' > "$STUB_BIN/$cli"
    chmod +x "$STUB_BIN/$cli"
done
# claude is the one the agents shot opens a session on, so its stand-in draws one: a made-up
# conversation about the demo project, laid out like the real TUI, redrawn whenever the terminal is
# resized and then left on screen. It reads nothing, writes nothing and sends nothing. The files and
# line counts it mentions match what scene 3 puts on disk.
cat > "$STUB_BIN/claude" <<'STUB'
#!/usr/bin/env bash
stty -echo -icanon 2>/dev/null

e=$'\e'
off="$e[0m"; bold="$e[1m"; dim="$e[38;5;244m"; grey="$e[38;5;250m"
green="$e[38;5;114m"; pink="$e[38;5;211m"; frame="$e[38;5;240m"
del="$e[48;2;92;38;44m"; add="$e[48;2;36;78;48m"

prompt() { printf '%s> %s%s\n' "$grey" "$1" "$off"; }
asked()  { printf '%s  %s%s\n' "$grey" "$1" "$off"; }
said()   { printf '● %s\n' "$1"; }
more()   { printf '  %s\n' "$1"; }
tool()   { printf '%s●%s %s%s%s(%s)\n' "$green" "$off" "$bold" "$1" "$off" "$2"; }
result() { printf '  %s└  %s%s\n' "$dim" "$1" "$off"; }
change() {
    local bg=""
    case "$2" in -) bg="$del" ;; +) bg="$add" ;; esac
    printf '      %s%s%3s%s%s %s %-68s%s\n' "$bg" "$dim" "$1" "$off" "$bg" "$2" "$3" "$off"
}

session() {
    prompt "Expired discount codes are still applied at checkout. Skip any code past its expiry"
    asked "date, and add a test for it."
    echo
    tool Read src/DiscountCodes.kt
    result "Read 18 lines"
    echo
    said "DiscountCodes.apply never checks expiresOn, so I'll filter there."
    echo
    tool Update src/DiscountCodes.kt
    result "Updated src/DiscountCodes.kt with 6 additions and 2 removals"
    change 15 - "class DiscountCodes(private val codes: List<DiscountCode>) {"
    change 15 + "class DiscountCodes("
    change 16 + "    private val codes: List<DiscountCode>,"
    change 17 + "    private val today: () -> LocalDate = LocalDate::now,"
    change 18 + ") {"
    change 19 " " "    fun apply(subtotal: BigDecimal): BigDecimal ="
    change 17 - "        codes.fold(subtotal) { sum, code -> code.applyTo(sum) }"
    change 20 + "        codes.filterNot { it.expiresOn.isBefore(today()) }"
    change 21 + "            .fold(subtotal) { sum, code -> code.applyTo(sum) }"
    change 22 " " "}"
    echo
    tool Write test/DiscountCodesTest.kt
    result "Wrote 22 lines to test/DiscountCodesTest.kt"
    echo
    tool Bash "gradle test --tests 'store.DiscountCodesTest'"
    result "BUILD SUCCESSFUL in 6s"
    echo
    said "Expired codes are now skipped: DiscountCodes drops any code whose expiresOn is before"
    more "today, and DiscountCodesTest checks an expired code next to one expiring today."
}

# The conversation, then the input box under it. A terminal too short for all of it shows the end,
# as a scrolled session would.
draw() {
    local rows cols first=0
    read -r rows cols < <(stty size 2>/dev/null)
    rows=${rows:-40}; cols=${cols:-100}
    local -a lines
    mapfile -t lines < <(session)
    local room=$(( rows - 5 ))
    if (( room < 1 )); then room=1; fi
    if (( ${#lines[@]} > room )); then first=$(( ${#lines[@]} - room )); fi
    local rule
    rule=$(printf '─%.0s' $(seq $(( cols - 4 ))))
    printf '%s[2J%s[3J%s[H' "$e" "$e" "$e"
    printf '%s\n' "${lines[@]:first}"
    printf '\n%s╭%s╮%s\n' "$frame" "$rule" "$off"
    printf '%s│%s > %*s%s│%s\n' "$frame" "$off" $(( cols - 7 )) "" "$frame" "$off"
    printf '%s╰%s╯%s\n' "$frame" "$rule" "$off"
    printf '  %s▸▸ bypass permissions on%s %s(shift+tab to cycle)%s' "$pink" "$off" "$dim" "$off"
    # The cursor waits in the input box, where the real one would.
    printf '%s[%d;5H' "$e" $(( ${#lines[@]} - first + 3 ))
}

trap draw WINCH
sleep 0.3
draw
# Short sleeps so a resize is redrawn promptly; bounded, so a stray one can't outlive the run.
for _ in $(seq 3600); do sleep 0.25; done
STUB
chmod +x "$STUB_BIN/claude"

# Spawn an isolated nop. $1=XDG config dir, $2=exact window title to wait for, $3=optional project
# arg (empty → restore the seeded window layout). A nop window is titled by the name its window was
# given, or by the project it is showing when it has no name. Sets the global LAST_WID to its id.
# (Sets a global rather than echoing, so the PID it records for cleanup survives in this shell
# rather than a command-substitution subshell.)
LAST_WID=""
launch_isolated() {
    local cfg="$1" want_title="$2" arg="${3:-}"
    local log="$cfg/nop.log"
    {
        echo "launching at $(date -Is): NOP_BIN=$NOP_BIN cfg=$cfg arg=$arg"
        echo "----- nop output -----"
    } > "$log"
    # A scene that seeds no agent accounts gets an explicitly empty list. With no agent.json at all
    # nop seeds one from the user's real vendor logins (Accounts.discover), which would put their
    # account names — and live usage polls for them — into the shot.
    [ -f "$cfg/nop/agent.json" ] || echo '{ "accounts": [] }' > "$cfg/nop/agent.json"
    # The rest of the agent state is fenced off too: session logs under a private XDG_DATA_HOME,
    # Claude's default store (past sessions "outside nop") pointed at an empty directory, the stub
    # CLIs first on PATH, and usage always read from the scene's fixture — a file that doesn't
    # exist just reads as no usage, so no scene can ever send a provider a request.
    local -a demo_env=(
        XDG_CONFIG_HOME="$cfg"
        XDG_DATA_HOME="$cfg/data"
        CLAUDE_CONFIG_DIR="$cfg/claude"
        PATH="$STUB_BIN:$PATH"
        NOP_USAGE_FIXTURE="$cfg/usage.json"
    )
    # Strip _JPACKAGE_LAUNCHER so the fresh jpackage launcher treats this as a first-time start
    # (see the long-form note in install.sh) rather than forwarding raw args to JLI.
    if [ -n "$arg" ]; then
        env -u _JPACKAGE_LAUNCHER "${demo_env[@]}" "$NOP_BIN" "$arg" >>"$log" 2>&1 &
    else
        env -u _JPACKAGE_LAUNCHER "${demo_env[@]}" "$NOP_BIN" >>"$log" 2>&1 &
    fi
    local pid=$!
    DEMO_PIDS+=("$pid")
    # Out of the shell's job table, so the instances killed between scenes don't print "Killed".
    disown "$pid" 2>/dev/null || true

    local wid=""
    for _ in $(seq 60); do
        wid=$(DISPLAY="$DISPLAY_SPEC" wmctrl -l 2>/dev/null | awk -v t=" $want_title$" '$0 ~ t {print $1; exit}' || true)
        [ -n "$wid" ] && break
        if ! kill -0 "$pid" 2>/dev/null; then
            echo "isolated nop exited before showing a window; log at $log" >&2
            tail -n 40 "$log" >&2 || true
            exit 5
        fi
        sleep 0.5
    done
    if [ -z "$wid" ]; then
        echo "isolated nop window ('$want_title') never appeared; log at $log" >&2
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

# Saves [image] to [out], cropped to "WxH+X+Y" when a geometry is given, quantised as described at
# the top.
save_png() {
    local image="$1" out="$2" crop="${3:-}"
    local -a cropping=()
    [ -n "$crop" ] && cropping=(-crop "$crop" +repage)
    convert "$image" ${cropping[@]+"${cropping[@]}"} -strip -colors 256 -dither None \
        -define png:compression-level=9 -define png:compression-filter=5 "$out"
}

capture_to() {
    local out="$1" wid="$2" crop="${3:-}"
    local raw; raw="$(mktemp --suffix=.png)"
    DISPLAY="$DISPLAY_SPEC" import -window "$wid" "$raw"
    save_png "$raw" "$out" "$crop"
    rm -f "$raw"
}

# A length measured at 1x, scaled to a window whose client area is $2 pixels wide.
scaled_to() {
    awk -v v="$1" -v w="$2" -v base="$SHOT_CLIENT_WIDTH" 'BEGIN {printf "%d", v * w / base}'
}

# The x at which a split at ratio $3 of the area right of the project tree falls, in a window $1
# wide whose tree takes ratio $2: the editor's right edge, and the tool region's left one.
split_x() {
    awk -v w="$1" -v h="$2" -v t="$3" 'BEGIN {printf "%d", w * (h + (1 - h) * t)}'
}

# Nop draws a 4px divider between its panes, and a 30px project bar along the top of the window.
PANE_DIVIDER=4
PROJECT_BAR=30

# Visual "ink" of the editor pane's top band: high when a diff (gutter + coloured code) is showing,
# near-zero for the empty "click a file…" placeholder. Used to pick the click offset that actually
# opened the diff, so the shot doesn't silently capture a blank pane if the layout shifted. The
# crop must stay inside the editor: right of the project tree (ends at SHOT_H_RATIO) and left of
# the tool region (starts at ~66% width with the split.tools seeded below) — the agent pane's text
# and the panel's change list are both full of ink and would mask a missed click.
pane_ink() {
    local img="$1" w="$2" h="$3"
    local cw=$(( w * 38 / 100 )) ch=$(( h * 22 / 100 ))
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

# The shot is the editor alone, so split.tools makes the editor ~840px — the width the README shows
# pixel for pixel — and gives the tool region the rest. The tools are unfolded (a first run starts
# them folded away) so the commit panel is there to click, and split.session=0 pins the agent pane
# beside it at its minimum width, leaving the panel room for its rows.
DIFF_TOOLS_RATIO=0.592
cat > "$DIFF_CFG/nop/state" <<EOF
window.width=$SHOT_WIDTH
window.height=$SHOT_HEIGHT
theme=$opposite_theme
split.h=$SHOT_H_RATIO
split.tools=$DIFF_TOOLS_RATIO
split.session=0
tools.collapsed=0
EOF

# Several files spread across a few directories so the tool panel groups them by directory:
# a "ui" source group (2 files), a "model" group, then tests / config / docs. Each file is
# committed as a baseline, then edited, so every one shows up as a modification with a real diff.
# Greeting.kt — the file the shot opens — is long enough to scroll past the window bottom, with
# its edits spread far apart, so the diff's change stripe shows several separate blocks. Its lines
# are kept to 44 characters, which is what each half of an ~840px side-by-side diff has room for.
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
 * Builds the greeting lines shown in
 * the demo UI.
 *
 * Deliberately small: each function
 * does one thing, and the companion
 * holds the banner settings.
 */
class GreetingService(
    private val locale: String,
) {

    /** Greets one user by name. */
    fun greet(name: String): String {
        val message = "Hello, $name"
        return decorate(message)
    }

    /** Greets several users at once. */
    fun greetAll(names: List<String>) =
        names.map(::greet).joinToString()

    /**
     * Says goodbye. Mirrors [greet] so
     * the two read alike when called.
     */
    fun farewell(name: String): String {
        val message = "Goodbye, $name"
        return decorate(message)
    }

    /** Title-cases a raw name. */
    fun formatName(raw: String) = raw
        .trim()
        .split(" ")
        .map(::capitalize)
        .joinToString(" ")

    /** True when [name] can be greeted. */
    fun canGreet(name: String) =
        name.isNotBlank()

    /**
     * Adds the locale's decoration. Plain
     * adds nothing; every other locale
     * ends in a full stop.
     */
    private fun decorate(text: String) =
        when (locale) {
            "plain" -> text
            else -> "$text."
        }

    private fun capitalize(w: String) =
        w.replaceFirstChar(Char::uppercase)

    /**
     * Sums up how many users were greeted,
     * for the demo window's status bar.
     */
    fun summary(count: Int) = when (count) {
        0 -> "Nobody greeted yet"
        1 -> "Greeted one user"
        else -> "Greeted $count users"
    }

    /** Whether [name] fits the banner. */
    fun fitsBanner(name: String) =
        name.length <= BANNER_WIDTH

    /** Pads [name] to the banner width. */
    fun padForBanner(name: String) =
        name.padEnd(BANNER_WIDTH)

    companion object {
        /** Banner width, in characters. */
        const val BANNER_WIDTH = 42

        /** Locales with translations. */
        val SUPPORTED =
            listOf("en", "plain")
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
 * Builds the greeting lines shown in
 * the demo UI.
 *
 * Deliberately small: each function
 * does one thing, and the companion
 * holds the banner settings.
 */
class GreetingService(
    private val locale: String,
) {

    /** Greets one user, maybe excitedly. */
    fun greet(
        name: String,
        excited: Boolean = false,
    ): String {
        val mark = if (excited) "!" else ""
        val message = "Hello, $name$mark"
        return decorate(message)
    }

    /** Greets several users at once. */
    fun greetAll(names: List<String>) =
        names.map(::greet).joinToString()

    /**
     * Says goodbye. Mirrors [greet] so
     * the two read alike when called.
     */
    fun farewell(name: String): String {
        val message = "See you later, $name"
        return decorate(message)
    }

    /** Title-cases a raw name. */
    fun formatName(raw: String) = raw
        .trim()
        .split(" ")
        .map(::capitalize)
        .joinToString(" ")

    /** True when [name] can be greeted. */
    fun canGreet(name: String) =
        name.isNotBlank()

    /**
     * Adds the locale's decoration. Plain
     * adds nothing, shout upper-cases the
     * line; others end in a full stop.
     */
    private fun decorate(text: String) =
        when (locale) {
            "plain" -> text
            "shout" -> text.uppercase()
            else -> "$text."
        }

    private fun capitalize(w: String) =
        w.replaceFirstChar(Char::uppercase)

    /**
     * Sums up how many users were greeted,
     * for the demo window's status bar.
     */
    fun summary(count: Int) = when (count) {
        0 -> "No greetings sent yet"
        1 -> "Greeted one user"
        else -> "Greeted $count users"
    }

    /** Whether [name] fits the banner. */
    fun fitsBanner(name: String) =
        name.length <= BANNER_WIDTH

    /** Pads [name] to the banner width. */
    fun padForBanner(name: String) =
        name.padEnd(BANNER_WIDTH)

    companion object {
        /** Banner width, in characters. */
        const val BANNER_WIDTH = 48

        /** Locales with translations. */
        val SUPPORTED =
            listOf("en", "plain", "shout")
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
# ~78% with the ratios seeded above). That first row sits ~309px below the window top (project
# tabs, tool tabs, header, buttons, recent-messages dropdown, message box, group header); the exact
# Y depends on render scale, so the offsets are that row's position at 1x, 1.25x and 1.5x — and at
# 1x the later two fall in the empty space under a group rather than on another file. Each is only
# tried until one lands: the ink probe stops the loop at the first capture whose editor pane
# clearly shows a diff, because a later offset could hit a DIFFERENT row and put the wrong file in
# the shot.
diff_row_x=$(( DX + DW * 92 / 100 ))
# What is kept: the editor, between the tree's divider and the tool region's, below the project bar.
diff_left=$(( $(split_x "$DW" "$SHOT_H_RATIO" 0) + PANE_DIVIDER ))
diff_right=$(( $(split_x "$DW" "$SHOT_H_RATIO" "$DIFF_TOOLS_RATIO") - 2 ))
diff_top=$(scaled_to "$PROJECT_BAR" "$DW")
diff_crop="$(( diff_right - diff_left ))x$(( DH - diff_top ))+${diff_left}+${diff_top}"
best_ink="-1"
for off in 309 386 463; do
    DISPLAY="$DISPLAY_SPEC" xdotool windowraise "$diff_wid" || true
    DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$diff_wid"
    sleep 0.2
    DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$diff_row_x" $(( DY + off ))
    sleep 0.15
    DISPLAY="$DISPLAY_SPEC" xdotool click 1
    # Park the cursor over the editor (not the bar's "+" button) so no tooltip is in the shot.
    DISPLAY="$DISPLAY_SPEC" xdotool mousemove $(( DX + DW * 65 / 100 )) $(( DY + DH * 28 / 100 ))
    sleep 0.9
    cand="$(mktemp --suffix=.png)"
    DISPLAY="$DISPLAY_SPEC" import -window "$diff_wid" "$cand"
    ink=$(pane_ink "$cand" "$DW" "$DH")
    keep=$(awk -v a="$ink" -v b="$best_ink" 'BEGIN{print (a>b)?1:0}')
    if [ "$keep" = "1" ]; then
        best_ink="$ink"
        save_png "$cand" "$diff_out" "$diff_crop"
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
# The name the preview shot's window goes by — its title, and how launch_isolated finds it.
PREV_WINDOW="WORK"
PREV_CFG="$TMP_PARENT/prev-cfg"
mkdir -p "$PREV_CFG/nop"

# Plain names, because the project tabs they label are what the shot is of. The window is found by
# its name rather than by these, so they don't have to be unique on the screen.
WEBAPP="$TMP_PARENT/projects/webapp"
API="$TMP_PARENT/projects/api-server"
BLOG="$TMP_PARENT/projects/blog"
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
# The other two project tabs aren't opened in the shot, but they need a file each so their repo has
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

# One window, named, holding the three demo project tabs — that name is the window's title, and the
# tabs are the project bar along the top of the shot. `ws.N` is the window layout nop restores on a
# no-arg launch (Settings.loadWorkspaces).
cat > "$PREV_CFG/nop/state" <<EOF
theme=$user_theme
split.h=$SHOT_H_RATIO
split.tools=0.80
active=$WEBAPP
ws.0.name=$PREV_WINDOW
ws.0.open=true
ws.0.active=$WEBAPP
ws.0.geom=$SHOT_WIDTH,$SHOT_HEIGHT
ws.0.project.0=$WEBAPP
ws.0.project.1=$API
ws.0.project.2=$BLOG
EOF

# Seed editor tabs for the active project so the top tab strip shows several tabs (App.kt selected).
PREV_DATA=$(project_data_dir "$PREV_CFG" "$WEBAPP")
mkdir -p "$PREV_DATA"
{
    printf 'file\t%s\t1\n' "$WEBAPP/src/App.kt"
    printf 'file\t%s\t0\n' "$WEBAPP/src/Theme.kt"
    printf 'file\t%s\t0\n' "$WEBAPP/README.md"
} > "$PREV_DATA/tabs.tsv"

launch_isolated "$PREV_CFG" "$PREV_WINDOW" ""
prev_wid="$LAST_WID"
read PX PY PW PH < <(geometry_of "$prev_wid")
echo "preview window $prev_wid at $PX,$PY ${PW}x${PH}"
# Park the cursor over the editor so the project bar's "+" tooltip isn't captured.
DISPLAY="$DISPLAY_SPEC" xdotool mousemove $(( PX + PW * 60 / 100 )) $(( PY + PH * 30 / 100 ))
sleep 1.0

# The top-left corner: the project tabs, the tree, and the open file's code, ~834x320 at 1x.
preview_out="$SHOT_DIR/latest-preview.png"
capture_to "$preview_out" "$prev_wid" "$(scaled_to 834 "$PW")x$(scaled_to 320 "$PW")+0+0"

# ===========================================================================================
# Scene 3 — an agent session over the usage strip, and the accounts dialog (synthetic accounts)
# ===========================================================================================
# The first two instances have been captured. Closed rather than left behind, so neither can sit
# over the dialog this scene opens wherever the window manager puts it.
cleanup
DEMO_PIDS=()

AGENT_BASENAME="acme-store"
AGENT_CFG="$TMP_PARENT/agent-cfg"
# Under a made-up home, so wherever nop names the directory a session runs in it reads as an
# ordinary checkout (ShortPath keeps the last few segments) rather than naming the temp directory.
AGENT_PROJECT="$TMP_PARENT/home/dev/projects/$AGENT_BASENAME"
mkdir -p "$AGENT_CFG/nop" "$AGENT_PROJECT/src" "$AGENT_PROJECT/test" "$AGENT_PROJECT/docs"

# Short lines and a shallow tree: the editor and the tree are both narrow in this layout, and a
# horizontal scrollbar or a clipped filename would be most of what shows around the dialog.
cat > "$AGENT_PROJECT/src/Checkout.kt" <<'EOF'
package store

import java.math.BigDecimal

/** Totals a cart, then applies any valid discount codes. */
class Checkout(val cart: Cart, val codes: DiscountCodes) {
    fun total(): BigDecimal {
        val subtotal = cart.lines.sumOf { it.lineTotal() }
        return codes.apply(subtotal)
    }
}
EOF
cat > "$AGENT_PROJECT/src/Cart.kt" <<'EOF'
package store

import java.math.BigDecimal

data class CartLine(val sku: String, val price: BigDecimal, val qty: Int) {
    fun lineTotal(): BigDecimal = price * qty.toBigDecimal()
}

data class Cart(val lines: List<CartLine>)
EOF
cat > "$AGENT_PROJECT/README.md" <<'EOF'
# acme-store

A small synthetic project used for the nop screenshot.
EOF
cat > "$AGENT_PROJECT/docs/checkout.md" <<'EOF'
# Checkout

Discount codes are applied after the subtotal.
EOF
cat > "$AGENT_PROJECT/build.gradle.kts" <<'EOF'
plugins {
    kotlin("jvm") version "2.1.0"
}
EOF
# DiscountCodes.kt as it was before the session in the claude stand-in: 18 lines, no expiry check.
cat > "$AGENT_PROJECT/src/DiscountCodes.kt" <<'EOF'
package store

import java.math.BigDecimal
import java.time.LocalDate

data class DiscountCode(
    val code: String,
    val percentOff: Int,
    val expiresOn: LocalDate,
) {
    fun applyTo(total: BigDecimal): BigDecimal =
        total * BigDecimal(100 - percentOff).movePointLeft(2)
}

class DiscountCodes(private val codes: List<DiscountCode>) {
    fun apply(subtotal: BigDecimal): BigDecimal =
        codes.fold(subtotal) { sum, code -> code.applyTo(sum) }
}
EOF
git -C "$AGENT_PROJECT" init --quiet
git -C "$AGENT_PROJECT" config user.email "screenshot@nop.local"
git -C "$AGENT_PROJECT" config user.name "nop screenshot"
git -C "$AGENT_PROJECT" add -A
git -C "$AGENT_PROJECT" commit --quiet -m "initial commit"

# And the session's work, left uncommitted so the tree shows what it touched: the edit it drew as a
# diff, and the 22-line test it says it wrote.
cat > "$AGENT_PROJECT/src/DiscountCodes.kt" <<'EOF'
package store

import java.math.BigDecimal
import java.time.LocalDate

data class DiscountCode(
    val code: String,
    val percentOff: Int,
    val expiresOn: LocalDate,
) {
    fun applyTo(total: BigDecimal): BigDecimal =
        total * BigDecimal(100 - percentOff).movePointLeft(2)
}

class DiscountCodes(
    private val codes: List<DiscountCode>,
    private val today: () -> LocalDate = LocalDate::now,
) {
    fun apply(subtotal: BigDecimal): BigDecimal =
        codes.filterNot { it.expiresOn.isBefore(today()) }
            .fold(subtotal) { sum, code -> code.applyTo(sum) }
}
EOF
cat > "$AGENT_PROJECT/test/DiscountCodesTest.kt" <<'EOF'
package store

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscountCodesTest {
    private val today = LocalDate.of(2026, 9, 18)

    @Test
    fun `expired codes are skipped`() {
        val codes = DiscountCodes(
            listOf(
                DiscountCode("SPRING", 10, today.minusDays(1)),
                DiscountCode("WELCOME", 20, today),
            ),
            today = { today },
        )
        assertEquals(BigDecimal("80.0000"), codes.apply(BigDecimal("100.00")))
    }
}
EOF

# Tools folded away, so the agent session has the tool region to itself — the region is the shot —
# and split.tools makes the region ~835px: the width the README shows pixel for pixel, and still
# wide enough for the usage strip (which spans it) to fit every account on one line.
AGENT_TOOLS_RATIO=0.41
cat > "$AGENT_CFG/nop/state" <<EOF
window.width=$SHOT_WIDTH
window.height=$SHOT_HEIGHT
theme=$opposite_theme
split.h=$SHOT_H_RATIO
split.tools=$AGENT_TOOLS_RATIO
tools.collapsed=1
EOF

AGENT_DATA=$(project_data_dir "$AGENT_CFG" "$AGENT_PROJECT")
mkdir -p "$AGENT_DATA"
{
    printf 'file\t%s\t1\n' "$AGENT_PROJECT/src/DiscountCodes.kt"
    printf 'file\t%s\t0\n' "$AGENT_PROJECT/src/Checkout.kt"
} > "$AGENT_DATA/tabs.tsv"

# Five made-up accounts across the three providers. The dialog has room for four rows, so the order
# puts one of each provider in the first three. The homes are where nop would create them for a
# user called "dev"; nothing reads them, because every reading comes from the fixture below.
AGENT_HOMES="/home/dev/.local/share/nop/agent/homes"
cat > "$AGENT_CFG/nop/agent.json" <<EOF
{
  "accounts": [
    { "name": "claude-work", "provider": "anthropic", "home": "$AGENT_HOMES/claude-work",
      "model": "claude-opus-5", "reasoning": "high", "handoverTo": "codex-work" },
    { "name": "codex-work", "provider": "openai", "home": "$AGENT_HOMES/codex-work",
      "model": "gpt-5.5-codex", "reasoning": "medium", "handoverTo": "antigravity" },
    { "name": "antigravity", "provider": "antigravity", "home": "$AGENT_HOMES/antigravity",
      "reasoning": "high" },
    { "name": "claude-side", "provider": "anthropic", "home": "$AGENT_HOMES/claude-side",
      "model": "claude-sonnet-5", "handoverTo": "claude-work" },
    { "name": "codex-lab", "provider": "openai", "home": "$AGENT_HOMES/codex-lab",
      "reasoning": "high" }
  ]
}
EOF

# The usage every account reports (see UsageFixture.kt). Spread across the strip's green / amber /
# red bands, with resets at different points in their windows so the "now" markers differ too.
cat > "$AGENT_CFG/usage.json" <<'EOF'
{
  "claude-work": {
    "session": { "percent": 72, "resetsInMinutes": 108, "windowMinutes": 300 },
    "weekly": { "percent": 41, "resetsInMinutes": 4380, "windowMinutes": 10080 },
    "models": ["claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5"]
  },
  "codex-work": {
    "session": { "percent": 35, "resetsInMinutes": 191, "windowMinutes": 300 },
    "weekly": { "percent": 57, "resetsInMinutes": 7300, "windowMinutes": 10080 }
  },
  "antigravity": {
    "session": { "percent": 8, "resetsInMinutes": 290, "windowMinutes": 300 },
    "weekly": { "percent": 19, "resetsInMinutes": 8900, "windowMinutes": 10080 }
  },
  "claude-side": {
    "session": { "percent": 14, "resetsInMinutes": 262, "windowMinutes": 300 },
    "weekly": { "percent": 88, "resetsInMinutes": 1500, "windowMinutes": 10080 },
    "models": ["claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5"]
  },
  "codex-lab": {
    "session": { "percent": 93, "resetsInMinutes": 26, "windowMinutes": 300 },
    "weekly": { "percent": 64, "resetsInMinutes": 3200, "windowMinutes": 10080 }
  }
}
EOF

launch_isolated "$AGENT_CFG" "$AGENT_BASENAME" "$AGENT_PROJECT"
agent_wid="$LAST_WID"
read AX AY AW AH < <(geometry_of "$agent_wid")
echo "agents window $agent_wid at $AX,$AY ${AW}x${AH}"

# Positions below are measured at 1x and scaled to the window. The tool region's left edge follows
# from the seeded ratios.
scaled() { scaled_to "$1" "$AW"; }
tool_left=$(split_x "$AW" "$SHOT_H_RATIO" "$AGENT_TOOLS_RATIO")
AGENT_TABS="$AGENT_DATA/agents"

# Start a session on claude-work by clicking its row in the agent picker, which fills the tool
# region at startup: the first row sits ~190px down. It runs the claude stand-in. nop writes every
# open Claude session to the project's `agents` file as soon as it opens, which is how the script
# knows the click landed. A single click, not a sweep of offsets like the diff shot's, because a
# miss here would land on another account's row and start the wrong session.
DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$agent_wid" 2>/dev/null || true
DISPLAY="$DISPLAY_SPEC" xdotool mousemove $(( AX + tool_left + $(scaled 120) )) $(( AY + $(scaled 190) ))
sleep 0.15
DISPLAY="$DISPLAY_SPEC" xdotool click 1
for _ in $(seq 20); do
    grep -q $'\tclaude-work\t' "$AGENT_TABS" 2>/dev/null && break
    sleep 0.25
done
if ! grep -q $'\tclaude-work\t' "$AGENT_TABS" 2>/dev/null; then
    echo "no claude-work session opened from the agent picker; log at $AGENT_CFG/nop.log" >&2
    exit 7
fi
# Let the terminal take its size and the stand-in draw into it.
sleep 2

# Name the tab, as a session a turn or two in would be: the stand-in writes no transcript for nop to
# take a title from, so it is renamed the way a user would, from the tab's right-click. The tab is
# the first after the terminals' "+", ~150px into the region; the rename field opens with the old
# name selected, so typing replaces it.
AGENT_TITLE="Discount expiry"
DISPLAY="$DISPLAY_SPEC" xdotool mousemove $(( AX + tool_left + $(scaled 150) )) $(( AY + $(scaled 90) ))
sleep 0.15
DISPLAY="$DISPLAY_SPEC" xdotool click 3
sleep 0.5
DISPLAY="$DISPLAY_SPEC" xdotool type --delay 15 "$AGENT_TITLE"
DISPLAY="$DISPLAY_SPEC" xdotool key Return
for _ in $(seq 20); do
    grep -qF "$AGENT_TITLE" "$AGENT_TABS" 2>/dev/null && break
    sleep 0.25
done
if ! grep -qF "$AGENT_TITLE" "$AGENT_TABS" 2>/dev/null; then
    # Not worth failing the run over: the tab keeps its default name and the shot is still right.
    echo "warning: couldn't rename the agent tab; it keeps its default name" >&2
fi

# The session shot: the tool region, from its divider to the window's right edge and from under the
# project bar to the bottom — the session's tab, the conversation, and the usage strip. Taken before
# the dialog opens, with the cursor parked over the project tree, outside it.
DISPLAY="$DISPLAY_SPEC" xdotool mousemove $(( AX + AW * 5 / 100 )) $(( AY + AH * 85 / 100 ))
sleep 1.0
agents_left=$(( tool_left + PANE_DIVIDER + 1 ))
agents_top=$(scaled "$PROJECT_BAR")
agents_out="$SHOT_DIR/latest-agents.png"
capture_to "$agents_out" "$agent_wid" "$(( AW - agents_left ))x$(( AH - agents_top ))+${agents_left}+${agents_top}"

# The dialog this instance opens: matched by being transient for its window, not by title alone,
# so an accounts dialog the user has open in their own nop can't be picked up instead.
dialog_of() {
    local parent=$(( $1 )) id
    for id in $(DISPLAY="$DISPLAY_SPEC" wmctrl -l 2>/dev/null | awk '/ Agent accounts$/ {print $1}'); do
        local owner
        owner=$(DISPLAY="$DISPLAY_SPEC" xprop -id "$id" WM_TRANSIENT_FOR 2>/dev/null | awk '/window id/ {print $NF}')
        if [ -n "$owner" ] && [ $(( owner )) -eq "$parent" ]; then
            echo "$id"
            return
        fi
    done
}

# Open the dialog by clicking the usage strip, which is one big button onto it. Its right end is
# empty strip past the last account, well clear of the bars' tooltips; the strip's vertical centre
# sits ~21px above the window bottom at 1x, and the other offsets cover larger render scales. A
# miss lands on the bottom of the session's terminal, where the stand-in ignores it.
dialog_wid=""
for off in 21 26 31 16; do
    DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$agent_wid" 2>/dev/null || true
    DISPLAY="$DISPLAY_SPEC" xdotool mousemove $(( AX + AW - 60 )) $(( AY + AH - off ))
    sleep 0.15
    DISPLAY="$DISPLAY_SPEC" xdotool click 1
    for _ in $(seq 10); do
        dialog_wid=$(dialog_of "$agent_wid")
        [ -n "$dialog_wid" ] && break
        sleep 0.3
    done
    [ -n "$dialog_wid" ] && break
done
if [ -z "$dialog_wid" ]; then
    echo "the Agent accounts dialog never opened; log at $AGENT_CFG/nop.log" >&2
    exit 6
fi
DISPLAY="$DISPLAY_SPEC" wmctrl -i -r "$dialog_wid" -b add,above 2>/dev/null || true
DISPLAY="$DISPLAY_SPEC" xdotool windowraise "$dialog_wid" 2>/dev/null || true
DISPLAY="$DISPLAY_SPEC" xdotool windowactivate --sync "$dialog_wid" 2>/dev/null || true

# The accounts shot: the dialog's own window, which is already about as wide as the README shows
# pixel for pixel. The cursor goes just outside whichever side of it has room, so nothing in it is
# hovered.
read GX GY GW GH < <(geometry_of "$dialog_wid")
if [ "$GX" -gt 40 ]; then park_x=$(( GX - 20 )); else park_x=$(( GX + GW + 20 )); fi
DISPLAY="$DISPLAY_SPEC" xdotool mousemove "$park_x" $(( GY + GH / 2 ))
sleep 1.0
accounts_out="$SHOT_DIR/latest-accounts.png"
capture_to "$accounts_out" "$dialog_wid"

cleanup
DEMO_PIDS=()
trap 'rm -rf "$TMP_PARENT"' EXIT INT TERM

for out in "$agents_out" "$diff_out" "$accounts_out" "$preview_out"; do
    echo "wrote $out ($(stat -c %s "$out") bytes)"
done

# Insert / replace the screenshot block in the README so the latest captures show up inline. Each
# shot gets a heading and a sentence, so someone skimming the README learns what it is for from the
# headings alone and what they are looking at from the caption.
block="$README_MARKER

### Run coding agents beside your code

Claude Code, Codex and Antigravity sessions run in tabs next to the editor, each on whichever of
your accounts you choose. The strip along the bottom shows every account's session and weekly
usage. Every agent shares one memory file, \`~/.local/share/nop/agent/memory/memory.md\`, so what
one session learns reaches the next, whichever account or tool it runs on.

![A Claude Code session, with every account's usage along the bottom](docs/screenshots/latest-agents.png)

### Review what changed

Every change opens as a side-by-side diff, with a revert on each hunk and a stripe marking where
the rest of the file's changes are.

![A side-by-side diff](docs/screenshots/latest-diff.png)

### Manage all your accounts in one place

Set each account's model and thinking level, sign it in or out, and choose which account takes
over its work when it runs out.

![The agent accounts settings](docs/screenshots/latest-accounts.png)

### Keep projects in tabs

Open several projects in one window, each with its own file tree and editor tabs.

![Several projects open as tabs](docs/screenshots/latest-preview.png)

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
