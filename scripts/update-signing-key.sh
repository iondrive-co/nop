#!/usr/bin/env bash
# Make the key nop's releases are signed with, for its self-updater. Run once, by a maintainer.
#
#     scripts/update-signing-key.sh <dir-outside-the-repo>
#
# Writes an Ed25519 key pair into <dir> and prints what to do with each half:
#   - the private key goes into the repository secret NOP_UPDATE_SIGNING_KEY, which
#     .github/workflows/release.yml signs each release's update.json with;
#   - the public key goes into src/main/resources/iondrive/nop/update/update-signing-key.pem,
#     which nop checks that signature against before it installs anything.
# A nop only takes updates signed by the key it was built with, so replacing the key means one
# release signed with the old key that carries the new public key, before the switch.
set -euo pipefail

DIR="${1:?usage: $0 <dir-outside-the-repo>}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$DIR"
DIR="$(cd "$DIR" && pwd)"
case "$DIR/" in
    "$REPO_ROOT"/*) echo "Refusing to write a private key inside the repository ($DIR)." >&2; exit 1 ;;
esac

PRIV="$DIR/nop-update-signing.key"
PUB="$DIR/nop-update-signing.pub.pem"
if [[ -e "$PRIV" ]]; then
    echo "$PRIV already exists; not replacing a signing key." >&2
    exit 1
fi

umask 077
openssl genpkey -algorithm ed25519 -out "$PRIV"
openssl pkey -in "$PRIV" -pubout -out "$PUB"

cat <<EOF
Wrote $PRIV (private) and $PUB (public).

  gh secret set NOP_UPDATE_SIGNING_KEY --repo iondrive-co/nop < "$PRIV"
  mkdir -p "$REPO_ROOT/src/main/resources/iondrive/nop/update"
  cp "$PUB" "$REPO_ROOT/src/main/resources/iondrive/nop/update/update-signing-key.pem"

Then keep $PRIV somewhere safe and offline; anyone holding it can ship code to every nop.
EOF
