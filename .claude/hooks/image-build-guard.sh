#!/usr/bin/env bash
# Keeps a Claude Code session from finishing while nop's app image is older than the source
# changes that session made. nop runs from build/compose/binaries/main/app/, so a fix that only
# exists in src/ hasn't reached anyone yet: tests passing is not the same as the change being there.
#
#   pre   (PreToolUse)   marks the moment a tool call that can write files starts
#   post  (PostToolUse)  if build inputs changed while it ran, records that, and when, for this
#                        session
#   stop  (Stop)         if this session changed inputs after build/nop-image.stamp, which Gradle
#                        dates to the start of the last build whose createDistributable succeeded,
#                        blocks once and says what to do. If Claude stops again without building,
#                        lets it and warns the user.
#
# Changes are attributed per session because several agents often work in one checkout. A session
# shouldn't be held until it builds another one's half-finished edits into the image, so what is
# compared with the stamp is when *this* session changed something, never a file's current mtime,
# which anyone can move.
#
# Plain bash 3.2 and POSIX tools only (stock macOS and Git Bash included), with no jq or Python:
# the few fields needed are read out of the hook's JSON with grep and sed. Anything unexpected lets
# the session through; a guard that wedges a session is worse than one that misses a build.

mode=$1
input=$(tr -d '\n')

root=${CLAUDE_PROJECT_DIR:-$PWD}
cd "$root" 2>/dev/null || exit 0
# Main checkout only. A linked worktree (.git is a file there) builds an image that is deleted with
# it and that nobody runs; see installDesktopEntry in build.gradle.kts.
[ -d .git ] || exit 0

# A top-level string field. $2 picks the occurrence: the input nests the tool's own input and
# response, so session_id is the first one and, in PostToolUse, tool_use_id the last. The same keys
# inside a string value are escaped (\"key\"), so text content can't be mistaken for them.
field() {
    printf '%s' "$input" | grep -o "\"$1\" *: *\"[^\"]*\"" | "$2" -n 1 | sed 's/^[^:]*: *"//; s/"$//'
}
session=$(field session_id head)
case $session in
    '' | */* | *..*) exit 0 ;;
esac

state=build/claude-sessions
stamp=build/nop-image.stamp
inputs="src/main build.gradle.kts settings.gradle.kts gradle.properties gradle"

case $mode in
pre)
    call=$(field tool_use_id tail)
    case $call in */* | *..*) exit 0 ;; esac
    mkdir -p "$state" 2>/dev/null && : > "$state/$session.${call:-call}.start" || exit 0
    # Linux dates files from a clock that ticks every few milliseconds, and find -newer wants
    # strictly newer: an edit in the same tick as the marker would go unseen. Wait one out.
    sleep 0.02 2>/dev/null
    ;;

post)
    call=$(field tool_use_id tail)
    case $call in */* | *..*) exit 0 ;; esac
    start="$state/$session.${call:-call}.start"
    [ -f "$start" ] || exit 0
    # Directories too: creating, deleting or renaming a file only shows up as its directory's mtime.
    # shellcheck disable=SC2086
    found=$(find $inputs -newer "$start" 2>/dev/null)
    rm -f "$start"
    # Written now, at the end of the call, so its mtime is no earlier than any change the call made.
    [ -z "$found" ] || printf '%s\n' "$found" > "$state/$session.${call:-call}.changed"
    ;;

stop)
    # Old sessions' records, so the directory doesn't grow without end.
    find "$state" -type f -mtime +7 -exec rm -f {} + 2>/dev/null
    unbuilt=$(for record in "$state/$session".*.changed; do
        [ -f "$record" ] || continue
        if [ ! -f "$stamp" ] || [ "$record" -nt "$stamp" ]; then cat "$record"; fi
    done | sort -u)
    [ -n "$unbuilt" ] || exit 0

    if printf '%s' "$input" | grep -Eq '"stop_hook_active" *: *true'; then
        printf '%s\n' '{"systemMessage": "nop was not rebuilt after this session changed its source: the app you run does not have these changes yet. Run ./gradlew test in the checkout, then restart nop."}'
        exit 0
    fi
    {
        echo "This session changed nop's build inputs, and the app image hasn't been rebuilt since:"
        printf '%s\n' "$unbuilt" | sed 's/^/  /' | head -n 20
        echo
        echo "The user runs nop from build/compose/binaries/main/app/, so none of this reaches"
        echo "them until that is rebuilt. From $root run ./gradlew test (without"
        echo "-x installDesktopEntry): installDesktopEntry finalizes it, and rebuilds the image even"
        echo "when a test fails. Check it built, then tell the user to restart nop, because a running"
        echo "JVM keeps its old classes. If the build can't succeed, or you are stopping mid-task on"
        echo "purpose (asking a question, work unfinished), say that plainly to the user and stop."
    } >&2
    exit 2
    ;;
esac
exit 0
