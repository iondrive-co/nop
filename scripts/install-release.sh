#!/usr/bin/env bash
# Install a nop release for the current user, the way nop's self-updater lays one out, so it keeps
# itself up to date from then on (src/main/kotlin/iondrive/nop/update).
#
#     scripts/install-release.sh                    # the latest release
#     scripts/install-release.sh v1.2.6             # a given one
#     scripts/install-release.sh --replace-build    # also over a build of a checkout
#
# The release goes in ~/.local/opt/nop-<version> and ~/.local/opt/nop becomes a link to it, which is
# what launchers should name (~/.local/bin/nop -> ~/.local/opt/nop/bin/nop). An ~/.local/opt/nop that
# is still a directory of its own is moved aside as the version it holds. One that is a build of a
# checkout (it has .built-from-checkout, from ./gradlew's installLocalCopy) is left alone unless
# --replace-build says otherwise; it is then kept as ~/.local/opt/nop.build, and builds stop being
# installed there. To run builds again instead of releases:
#
#     rm ~/.local/opt/nop && mv ~/.local/opt/nop.build ~/.local/opt/nop    # then ./gradlew test
#
# The release's manifest is checked against the signing key in this checkout, and the tarball
# against the manifest's hash. A running nop is not touched: restart it (the bar's restart button)
# to run the new version. NOP_OPT_DIR puts the install somewhere other than ~/.local/opt.

set -euo pipefail

replace_build=false
tag=""
for arg in "$@"; do
    case "$arg" in
        -h|--help) sed -n '2,/^$/s/^# \{0,1\}//p' "$0"; exit 0 ;;
        --replace-build) replace_build=true ;;
        v*) tag="$arg" ;;
        *) echo "install-release: unknown argument: $arg" >&2; exit 2 ;;
    esac
done

REPO=iondrive-co/nop
PLATFORM=linux-x64
KEY="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/src/main/resources/iondrive/nop/update/update-signing-key.pem"
OPT="${NOP_OPT_DIR:-$HOME/.local/opt}"
HOME_LINK="$OPT/nop"

for cmd in curl openssl python3 tar sha256sum; do
    command -v "$cmd" >/dev/null || { echo "install-release: $cmd is needed" >&2; exit 1; }
done
[ -f "$KEY" ] || { echo "install-release: no signing key at $KEY" >&2; exit 1; }
if [ -d "$HOME_LINK" ] && [ ! -L "$HOME_LINK" ] && [ -e "$HOME_LINK/.built-from-checkout" ] && ! $replace_build; then
    echo "install-release: $HOME_LINK is a build from $(cat "$HOME_LINK/.built-from-checkout"); not replacing it" >&2
    echo "(--replace-build installs the release in its place and keeps the build as $HOME_LINK.build)" >&2
    exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# The release, and its asset URLs by name.
if [ -n "$tag" ]; then api="https://api.github.com/repos/$REPO/releases/tags/$tag"; else api="https://api.github.com/repos/$REPO/releases/latest"; fi
curl -fsSL -H "Accept: application/vnd.github+json" "$api" > "$work/release.json"
asset() {
    python3 -I - "$work/release.json" "$1" <<'PY_EOF'
import json, sys
release = json.load(open(sys.argv[1]))
for a in release.get("assets", []):
    if a["name"] == sys.argv[2]:
        print(a["browser_download_url"])
PY_EOF
}
manifest_name="update-$PLATFORM.json"
[ -n "$(asset "$manifest_name")" ] || manifest_name="update.json"
[ -n "$(asset "$manifest_name")" ] && [ -n "$(asset "$manifest_name.sig")" ] || {
    echo "install-release: that release has no signed update for $PLATFORM" >&2
    exit 1
}
curl -fsSL -o "$work/manifest.json" "$(asset "$manifest_name")"
curl -fsSL -o "$work/manifest.json.sig" "$(asset "$manifest_name.sig")"
openssl pkeyutl -verify -pubin -inkey "$KEY" -rawin -in "$work/manifest.json" -sigfile "$work/manifest.json.sig" >/dev/null || {
    echo "install-release: $manifest_name is not signed with nop's update key" >&2
    exit 1
}

# What the signed manifest says: version, tarball name, its hash, the root directory inside it.
read -r version archive sha root < <(python3 -I - "$work/manifest.json" <<'PY_EOF'
import json, sys
m = json.load(open(sys.argv[1]))
a = m["archive"]
print(m["version"], a["name"], a["sha256"], a["root"])
PY_EOF
)
target="$OPT/nop-$version"
if [ -e "$target/.update-staged" ] && [ ! -e "$target/.update-failed" ]; then
    echo "nop $version is already installed at $target"
else
    echo "Downloading nop $version..."
    curl -fSL --progress-bar -o "$work/archive.tar.gz" "$(asset "$archive")"
    echo "$sha  $work/archive.tar.gz" | sha256sum -c --quiet - || {
        echo "install-release: $archive does not match the hash in the signed manifest" >&2
        exit 1
    }
    mkdir -p "$work/unpacked" "$OPT"
    tar -xzf "$work/archive.tar.gz" -C "$work/unpacked" --no-same-owner
    # What the build adopts, as the updater records it; a build older than the probe adopts 1.
    probe_dirs="$work/probe"; mkdir -p "$probe_dirs/config" "$probe_dirs/data" "$probe_dirs/run"
    answer="$(env -u _JPACKAGE_LAUNCHER -u LD_LIBRARY_PATH XDG_CONFIG_HOME="$probe_dirs/config" XDG_DATA_HOME="$probe_dirs/data" \
        XDG_RUNTIME_DIR="$probe_dirs/run" "$work/unpacked/$root/bin/nop" --update-probe 2>/dev/null </dev/null || true)"
    handover="$(sed -n 's/.* handover=\([0-9]*\).*/\1/p' <<<"$answer" | head -1)"
    adopts="$(sed -n 's/.* adopts=\([0-9,]*\).*/\1/p' <<<"$answer" | head -1)"
    printf 'version=%s\nhandover=%s\nadopts=%s\n' "$version" "${handover:-1}" "${adopts:-${handover:-1}}" > "$work/unpacked/$root/.update-staged"
    # Installed by hand and started by the user: as good as a start that came up.
    : > "$work/unpacked/$root/.update-healthy"
    rm -rf "$target"
    mv "$work/unpacked/$root" "$target"
fi

# Point the home at it. A build of a checkout goes aside as nop.build, to switch back to; any other
# home that is a directory of its own goes aside as the version it holds.
if [ -d "$HOME_LINK" ] && [ ! -L "$HOME_LINK" ] && [ -e "$HOME_LINK/.built-from-checkout" ]; then
    rm -rf "$HOME_LINK.build"
    mv "$HOME_LINK" "$HOME_LINK.build"
    echo "Kept the build that was there as $HOME_LINK.build"
elif [ -d "$HOME_LINK" ] && [ ! -L "$HOME_LINK" ]; then
    old="$(unzip -p "$HOME_LINK"/lib/app/nop-*.jar iondrive/nop/update/nop-build.properties 2>/dev/null | sed -n 's/^version=//p' | head -1 || true)"
    aside="$OPT/nop-${old:-previous}"
    [ -e "$aside" ] && aside="$OPT/nop.previous" && rm -rf "$aside"
    mv "$HOME_LINK" "$aside"
    echo "Moved the old install to $aside"
fi
ln -sfn "nop-$version" "$HOME_LINK.link"
mv -T "$HOME_LINK.link" "$HOME_LINK"
echo "Installed nop $version: $HOME_LINK -> $target"
echo "Restart nop (the restart button in its bar) to run it."
