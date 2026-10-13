#!/usr/bin/env python3
"""Describe a release's app image for nop's self-updater.

    scripts/update-manifest.py <app-image-dir> <archive> <version> <platform> \
        [--previous <last release's manifest> --delta <delta archive> --mtime <epoch>] > update-<platform>.json

<app-image-dir> is what createDistributable builds (build/compose/binaries/main/app/nop) and
<archive> the tarball of it that the release carries. The manifest lists every file in the image
with its sha256, size and whether it is executable, so a nop updating itself can reuse the files it
already has (jar names carry content hashes, and the runtime rarely changes) and download only the
rest, falling back to the whole archive. Links are listed with their target.

With --previous, the files that differ from that release's manifest also go into <delta archive>, a
tarball of just those under the same root, which the manifest names; a nop that has the rest takes
it from its own install by hash and downloads only the delta.

The release workflow signs the manifest (update-<platform>.json.sig); the archive, the delta and every file are trusted
through their hashes in it, never on their own.
"""
import hashlib
import json
import os
import stat
import subprocess
import sys

FORMAT = 1


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def entries(root):
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames.sort()
        for name in sorted(filenames + [d for d in dirnames if os.path.islink(os.path.join(dirpath, d))]):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            st = os.lstat(full)
            if stat.S_ISLNK(st.st_mode):
                out.append({"path": rel, "link": os.readlink(full)})
            elif stat.S_ISREG(st.st_mode):
                out.append({
                    "path": rel,
                    "sha256": sha256(full),
                    "size": st.st_size,
                    "executable": bool(st.st_mode & stat.S_IXUSR),
                })
            else:
                sys.exit(f"{full}: neither a file nor a link; the updater would not know how to place it")
    return out


def delta(image, files, previous, out, mtime):
    """Packs the files that differ from <previous>'s into <out>; returns the manifest's delta entry."""
    with open(previous) as f:
        before = json.load(f)
    known = {e["path"]: e.get("sha256") or ("link:" + e.get("link", "")) for e in before.get("files", [])}
    changed = [e["path"] for e in files
               if known.get(e["path"]) != (e.get("sha256") or ("link:" + e.get("link", "")))]
    if not changed:
        return None
    root = os.path.basename(os.path.normpath(image))
    members = "".join(f"{root}/{p}\0" for p in changed)
    subprocess.run(
        ["tar", "--sort=name", "--owner=0", "--group=0", "--numeric-owner", f"--mtime=@{mtime}",
         "-C", os.path.dirname(os.path.normpath(image)) or ".", "--null", "-T", "-", "-czf", out],
        input=members.encode(), check=True,
    )
    return {
        "from": before["version"],
        "name": os.path.basename(out),
        "sha256": sha256(out),
        "size": os.path.getsize(out),
        "root": root,
    }


def main(argv):
    args, options = [], {}
    rest = list(argv[1:])
    while rest:
        arg = rest.pop(0)
        if arg in ("--previous", "--delta", "--mtime"):
            if not rest:
                sys.exit(f"{arg} needs a value")
            options[arg] = rest.pop(0)
        else:
            args.append(arg)
    if len(args) != 4 or (("--previous" in options) != ("--delta" in options)):
        sys.exit(__doc__.strip().splitlines()[2].strip())
    image, archive, version, platform = args
    if not any(os.path.exists(os.path.join(image, p)) for p in ("bin/nop", "Contents", "nop.exe")):
        sys.exit(f"{image} does not look like a nop app image")
    files = entries(image)
    manifest = {
        "format": FORMAT,
        "version": version.removeprefix("v"),
        "platform": platform,
        "archive": {
            "name": os.path.basename(archive),
            "sha256": sha256(archive),
            "size": os.path.getsize(archive),
            # The archive's single top-level directory, which holds the image.
            "root": os.path.basename(os.path.normpath(image)),
        },
        "files": files,
    }
    if "--previous" in options:
        d = delta(image, files, options["--previous"], options["--delta"], options.get("--mtime", "0"))
        if d is not None:
            manifest["delta"] = d
    json.dump(manifest, sys.stdout, indent=1, sort_keys=False)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main(sys.argv)
