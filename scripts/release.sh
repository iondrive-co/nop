#!/usr/bin/env bash
# Cut a release. No arguments: reads the current packageVersion from build.gradle.kts,
# auto-bumps the patch (0-9) rolling over into minor (1.1.0 -> 1.1.1 ... 1.1.9 -> 1.2.0),
# commits, tags v<version>, and pushes. The tag push triggers .github/workflows/release.yml,
# which builds .deb/.msi/.dmg on Linux/Windows/macOS runners and attaches them to a fresh
# GitHub release.
#
# First it runs the Linux checks of ci.yml and release.yml here, with scripts/ci-local.sh, and
# releases nothing if any of them fails. NOP_RELEASE_SKIP_LOCAL_CI=1 skips them.
#
# Pass an explicit version to override the auto-bump:
#     scripts/release.sh           # auto-bump (e.g. 1.1.9 -> 1.2.0)
#     scripts/release.sh 2.0.0     # force a specific version
set -euo pipefail

# All in one function, called on the last line: bash reads the whole file before running any of
# it, so a change to this script during the long checks can't change what this release does.
main() {

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"
GRADLE_FILE="build.gradle.kts"

CURRENT="$(grep -E '^[[:space:]]*packageVersion[[:space:]]*=' "$GRADLE_FILE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
if [[ -z "$CURRENT" ]]; then
    echo "Could not find packageVersion in $GRADLE_FILE" >&2
    exit 1
fi

if [[ $# -ge 1 ]]; then
    VERSION="$1"
else
    # Auto-bump: single-digit patch (0..9) rolls over into minor, and minor (0..9) into major.
    IFS=. read -r MAJ MIN PATCH <<<"$CURRENT"
    if ! [[ "$MAJ" =~ ^[0-9]+$ && "$MIN" =~ ^[0-9]+$ && "$PATCH" =~ ^[0-9]+$ ]]; then
        echo "Current version '$CURRENT' is not MAJOR.MINOR.PATCH; pass an explicit version." >&2
        exit 1
    fi
    if [[ "$PATCH" -ge 9 ]]; then
        if [[ "$MIN" -ge 9 ]]; then
            VERSION="$((MAJ + 1)).0.0"
        else
            VERSION="${MAJ}.$((MIN + 1)).0"
        fi
    else
        VERSION="${MAJ}.${MIN}.$((PATCH + 1))"
    fi
fi

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Version must look like MAJOR.MINOR.PATCH (got: $VERSION)" >&2
    exit 2
fi

TAG="v$VERSION"

if [[ -n "$(git status --porcelain)" ]]; then
    echo "Working tree is not clean. Commit or stash changes first." >&2
    git status --short >&2
    exit 1
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$BRANCH" != "main" ]]; then
    echo "Refusing to release from branch '$BRANCH' (expected 'main')." >&2
    exit 1
fi

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "Tag $TAG already exists." >&2
    exit 1
fi

# GitHub runs its checks only once the tag is pushed, and a release whose Linux job fails still
# publishes, just without the .deb or the Linux self-update. So run the same Linux checks here
# first, on the commit being released, and stop if any fails.
if [[ "${NOP_RELEASE_SKIP_LOCAL_CI:-}" == 1 ]]; then
    echo "Skipping the local Linux checks (NOP_RELEASE_SKIP_LOCAL_CI=1)."
else
    HEAD_BEFORE="$(git rev-parse HEAD)"
    echo "Running GitHub's Linux checks here first (scripts/ci-local.sh, around 20 minutes)."
    if ! "$REPO_ROOT/scripts/ci-local.sh" --commit "$HEAD_BEFORE" --version "$VERSION"; then
        echo "Not releasing $TAG: the Linux checks failed here, so they would fail on GitHub too." >&2
        exit 1
    fi
    if [[ "$(git rev-parse HEAD)" != "$HEAD_BEFORE" || -n "$(git status --porcelain)" ]]; then
        echo "Not releasing $TAG: the checkout changed while the checks ran. Run scripts/release.sh again." >&2
        exit 1
    fi
fi

echo "Bumping packageVersion: $CURRENT -> $VERSION"
# Only rewrite the top-level packageVersion; OS targets inherit it.
python3 - "$GRADLE_FILE" "$CURRENT" "$VERSION" <<'PY'
import sys, re, pathlib
path, current, new = sys.argv[1], sys.argv[2], sys.argv[3]
text = pathlib.Path(path).read_text()
pattern = re.compile(r'^([ \t]*packageVersion[ \t]*=[ \t]*")' + re.escape(current) + r'(")', re.MULTILINE)
new_text, n = pattern.subn(r'\g<1>' + new + r'\g<2>', text, count=1)
if n != 1:
    sys.exit(f"Could not locate top-level packageVersion = \"{current}\" in {path}")
pathlib.Path(path).write_text(new_text)
PY

git add "$GRADLE_FILE"
if ! git diff --cached --quiet; then
    git commit -m "Release $TAG"
fi
git tag -a "$TAG" -m "Release $TAG"
git push origin "$BRANCH"
git push origin "$TAG"

echo
echo "Released $TAG. Watch the build: https://github.com/iondrive-co/nop/actions"
}

main "$@"
