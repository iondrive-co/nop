#!/usr/bin/env bash
# Build nop and wire it into the system app launcher.
#
# Linux: runs `./gradlew createDistributable installDesktopEntry`, which builds the
# distributable under build/compose/binaries/main/app/nop and writes a .desktop file
# at ~/.local/share/applications/io.iondrive.nop.desktop. After this completes, "nop"
# should show up in the application menu under Development. Re-running rebuilds in
# place — the menu entry keeps pointing at the same binary path.
#
# macOS and Windows are not handled here; see README for the equivalent gradle tasks.

set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

case "$(uname -s)" in
    Linux*) ;;
    *)
        echo "install.sh only handles Linux. For macOS/Windows see the README." >&2
        exit 1
        ;;
esac

./gradlew --console=plain createDistributable installDesktopEntry

cat <<'EOF'

Done. Launch nop from your desktop application menu (look under "Development"),
or run build/compose/binaries/main/app/nop/bin/nop directly.
EOF
