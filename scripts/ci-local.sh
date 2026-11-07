#!/usr/bin/env bash
# Run the Linux checks that GitHub runs (.github/workflows/ci.yml, and the ubuntu job of
# release.yml) on this machine, before a release tag sends the code there.
#
# `./gradlew test` in your checkout is not that check. Your checkout has a warm build/ and files git
# ignores, and its tests run as you. Java takes user.home from the password file, not from $HOME, so
# they see your ~/.gitconfig and agent logins. They also see the agent CLIs on your PATH, and your
# time zone. A GitHub runner has none of these.
#
# So this builds a fresh clone, in a scratch directory under ~/.cache/nop-ci. In it:
#   - every JVM's user.home is an empty home, as is $HOME;
#   - PATH holds only the system directories and JDK 21 (Temurin, as setup-java installs);
#   - TZ=UTC, C.UTF-8 and CI=true are set;
#   - the build has 4 CPUs (taskset), as many as a hosted runner;
#   - the display is Xvfb.
# Gradle gets its own home under ~/.cache/nop-ci, which reads your dependency cache but never
# writes to ~/.gradle. The steps are the workflows' own commands, run in the same order.
#
#   scripts/ci-local.sh                  the working tree, uncommitted and new files included
#   scripts/ci-local.sh --commit REV     a commit instead (scripts/release.sh checks HEAD)
#   scripts/ci-local.sh --steps test,cef only some steps: test, cef, deb, bundle (default: all)
#   scripts/ci-local.sh --version 1.2.3  build as that packageVersion, as the release will
#   scripts/ci-local.sh --keep           keep the scratch clone even when everything passes
#
# NOP_CI_CPUS changes the CPU count (0 = no limit). NOP_CI_JAVA_HOME picks the JDK.
# The exit status is 0 only when every step passed. A failed run keeps its clone and logs, and says
# where they are.
set -uo pipefail

# All in one function, called on the last line: bash reads the whole file before running any of
# it, so editing or pulling this script while a run is under way can't change what that run does.
main() {

repo="$(cd "$(dirname "$0")/.." && pwd)"
commit=""
steps="test,cef,deb,bundle"
version=""
keep=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --commit) commit="${2:?--commit needs a revision}"; shift 2 ;;
        --steps) steps="${2:?--steps needs a list}"; shift 2 ;;
        --version) version="${2:?--version needs a version}"; shift 2 ;;
        --keep) keep=1; shift ;;
        -h|--help) sed -n '2,/^set -uo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown argument: $1 (see --help)" >&2; exit 2 ;;
    esac
done

fail() { echo "ci-local: $*" >&2; exit 1; }
[[ "$(uname -s)" == Linux ]] || fail "this runs the Linux jobs, so it needs Linux"
for tool in git python3 xvfb-run xauth taskset tar openssl; do
    command -v "$tool" >/dev/null || fail "$tool is not installed"
done

# The commands the workflows run. If a workflow changes them, this must change too, so check that
# each one is still there, word for word.
ci_test='xvfb-run --auto-servernum ./gradlew test --no-daemon'
release_cef='xvfb-run -a ./gradlew testCefPackaging testCefMemory testCefShutdown testSlackSidebar --no-daemon'
release_package='./gradlew ${{ matrix.task }} --no-daemon'
grep -qF "$ci_test" "$repo/.github/workflows/ci.yml" \
    || fail ".github/workflows/ci.yml no longer runs '$ci_test': update scripts/ci-local.sh to match it"
for command in "$release_cef" "$release_package" 'Self-update bundle (Linux)' 'task: packageDeb'; do
    grep -qF "$command" "$repo/.github/workflows/release.yml" \
        || fail ".github/workflows/release.yml no longer has '$command': update scripts/ci-local.sh to match it"
done

# Gradle's default home follows Java's user.home, which may differ from a CLI's isolated HOME.
gradle_source_home="${GRADLE_USER_HOME:-$(python3 -I -c 'import os, pwd; print(pwd.getpwuid(os.getuid()).pw_dir)')/.gradle}"
java_home="${NOP_CI_JAVA_HOME:-}"
if [[ -z "$java_home" ]]; then
    for candidate in "$gradle_source_home"/jdks/eclipse_adoptium-21-* /usr/lib/jvm/temurin-21-* /usr/lib/jvm/*-21-*; do
        if [[ -x "$candidate/bin/java" && -x "$candidate/bin/javac" ]]; then java_home="$candidate"; break; fi
    done
fi
[[ -n "$java_home" && -x "$java_home/bin/java" && -x "$java_home/bin/javac" ]] \
    || fail "no full JDK 21 found: set NOP_CI_JAVA_HOME (the workflows use Temurin 21)"

cache="${XDG_CACHE_HOME:-$HOME/.cache}/nop-ci"
mkdir -p "$cache"
# One run at a time: two would share the Gradle home and the CPUs.
exec 9>"$cache/lock"
flock -n 9 || fail "another scripts/ci-local.sh is running (lock: $cache/lock)"
# Earlier runs' clones. Kept after a failure to look at; gone at the next run.
rm -rf "$cache"/run-*
run="$cache/run-$(date +%Y%m%d-%H%M%S)"
src="$run/nop"
home="$run/home"
logs="$run/logs"
mkdir -p "$home" "$logs" "$run/runtime" "$run/runner-temp"
chmod 700 "$run/runtime"

if [[ -n "$commit" ]]; then
    sha="$(git -C "$repo" rev-parse --verify --quiet "$commit^{commit}")" || fail "no such commit: $commit"
    git clone --quiet --no-checkout "$repo" "$src" && git -C "$src" checkout --quiet --detach "$sha" \
        || fail "could not check out $commit"
    what="commit $(git -C "$repo" log -1 --format='%h %s' "$sha")"
else
    git clone --quiet "$repo" "$src" || fail "could not clone $repo"
    # What `git add -A` would commit: tracked files as they are now, new files git doesn't ignore,
    # and deletions.
    (cd "$repo" && git ls-files -z --cached --others --exclude-standard \
        | tar --null --files-from=- --ignore-failed-read -cf - 2>/dev/null) | tar -xf - -C "$src" \
        || fail "could not copy the working tree"
    (cd "$repo" && git ls-files -z --deleted) | (cd "$src" && xargs -0 -r rm -f --)
    what="the working tree of $repo ($(git -C "$repo" status --porcelain | wc -l | tr -d ' ') changed paths)"
fi
if [[ -n "$version" ]]; then
    sed -i -E "0,/^([[:space:]]*packageVersion[[:space:]]*=[[:space:]]*\")[^\"]+(\")/s//\1$version\2/" \
        "$src/build.gradle.kts"
fi
version="$(grep -E '^[[:space:]]*packageVersion[[:space:]]*=' "$src/build.gradle.kts" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"

cpus="${NOP_CI_CPUS:-4}"
pin=()
if [[ "$cpus" != 0 ]]; then
    # The last N CPUs this process may use.
    allowed="$(python3 -I -c 'import os; print(",".join(map(str, sorted(os.sched_getaffinity(0)))))')"
    IFS=, read -ra all <<<"$allowed"
    if (( ${#all[@]} > cpus )); then
        list="$(IFS=,; echo "${all[*]: -$cpus}")"
        pin=(taskset -c "$list")
    fi
fi

gradle_home="$cache/gradle"
mkdir -p "$gradle_home"
# --no-daemon still leaves a daemon now and then; this one exits after 10 idle minutes, not 3 hours.
[[ -f "$gradle_home/gradle.properties" ]] || echo "org.gradle.daemon.idletimeout=600000" >"$gradle_home/gradle.properties"
# A runner's environment, not yours: nothing is inherited but what is listed here.
runner_env=(
    env -i
    HOME="$home" USER="${USER:-runner}" LOGNAME="${USER:-runner}"
    PATH="$java_home/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    JAVA_HOME="$java_home"
    # Java reads user.home from the password file, so $HOME alone would leave it at your real home.
    JAVA_TOOL_OPTIONS="-Duser.home=$home"
    GRADLE_USER_HOME="$gradle_home"
    XDG_RUNTIME_DIR="$run/runtime"
    LANG=C.UTF-8 LC_ALL=C.UTF-8 TZ=UTC
    CI=true GITHUB_ACTIONS=true
    RUNNER_TEMP="$run/runner-temp"
)
if [[ -d "$gradle_source_home/caches" ]]; then
    runner_env+=(GRADLE_RO_DEP_CACHE="$gradle_source_home/caches")
fi

echo "ci-local: checking $what"
echo "ci-local: packageVersion $version, JDK $java_home, ${pin[*]:-all CPUs}"
echo "ci-local: clone, home and logs in $run"

failed=()
step() {
    local name="$1"; shift
    local log="$logs/$name.log" started=$SECONDS
    echo "ci-local: [$name] $*"
    (cd "$src" && nice -n 10 "${pin[@]}" "${runner_env[@]}" "$@") >"$log" 2>&1
    local status=$?
    if [[ $status -eq 0 ]]; then
        echo "ci-local: [$name] passed in $(( (SECONDS - started) / 60 ))m$(( (SECONDS - started) % 60 ))s"
        return 0
    fi
    failed+=("$name")
    echo "ci-local: [$name] FAILED (exit $status) after $(( (SECONDS - started) / 60 ))m, log: $log" >&2
    # The tests that failed, by name, from Gradle's XML reports.
    [[ "$name" == test ]] && python3 -I - "$src/build/test-results" <<'PY' >&2
import pathlib, sys, xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1])
for report in sorted(root.glob("*/*.xml")) if root.is_dir() else []:
    try:
        suite = ET.parse(report).getroot()
    except ET.ParseError:
        continue
    for case in suite.iter("testcase"):
        problem = case.find("failure")
        if problem is None:
            problem = case.find("error")
        if problem is not None:
            message = (problem.get("message") or problem.text or "").strip().splitlines()
            print(f"  failed: {case.get('classname')} > {case.get('name')}: {message[0][:300] if message else ''}")
PY
    grep -E 'FAILED|Exception|Error:|What went wrong' "$log" | grep -v '^w: ' | head -n 25 | sed 's/^/  | /' >&2
    return $status
}

has() { [[ ",$steps," == *",$1,"* ]]; }

# ci.yml, job test.
has test && step test bash -c "$ci_test"
# release.yml, job ubuntu-latest, in its order: Chromium checks, then the .deb, then the bundle.
has cef && step cef bash -c "$release_cef"
if has deb; then
    step deb bash -c './gradlew packageDeb --no-daemon && ls build/compose/binaries/main/deb/*.deb'
fi
if has bundle; then
    # The workflow's own script, read out of release.yml, signed with a key made for this run only.
    python3 -I - "$repo/.github/workflows/release.yml" >"$run/bundle.sh" <<'PY'
import sys, textwrap
lines = open(sys.argv[1]).read().splitlines()
start = next(i for i, line in enumerate(lines) if "name: Self-update bundle (Linux)" in line)
run = next(i for i in range(start, len(lines)) if lines[i].strip() == "run: |")
body = []
for line in lines[run + 1:]:
    if line.strip() and len(line) - len(line.lstrip()) <= len(lines[run]) - len(lines[run].lstrip()):
        break
    body.append(line)
print(textwrap.dedent("\n".join(body)))
PY
    key="$run/signing.key"
    (umask 077; openssl genpkey -algorithm ed25519 -out "$key" 2>/dev/null) || fail "could not make a signing key"
    mkdir -p "$src/release"
    # The key goes in by file, so it never shows in the step line or the log.
    step bundle env GITHUB_REF="refs/tags/v$version" GITHUB_REF_NAME="v$version" GITHUB_REPOSITORY=iondrive-co/nop \
        bash -c 'SIGNING_KEY="$(cat "$1")" exec bash --noprofile --norc -eo pipefail "$2"' bundle "$key" "$run/bundle.sh"
    rm -f "$key"
fi

if [[ ${#failed[@]} -gt 0 ]]; then
    echo "ci-local: FAILED: ${failed[*]}. The clone, its build/reports and the logs are in $run" >&2
    exit 1
fi
if [[ $keep -eq 1 ]]; then
    echo "ci-local: all passed. Kept $run"
else
    rm -rf "$run"
    echo "ci-local: all passed"
fi
}

main "$@"
