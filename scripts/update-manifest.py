#!/usr/bin/env python3
"""Describe a release's app image for nop's self-updater.

    scripts/update-manifest.py <app-image-dir> <archive> <version> <platform> > update.json

<app-image-dir> is what createDistributable builds (build/compose/binaries/main/app/nop) and
<archive> the tarball of it that the release carries. The manifest lists every file in the image
with its sha256, size and whether it is executable, so a nop updating itself can reuse the files it
already has (jar names carry content hashes, and the runtime rarely changes) and download only the
rest, falling back to the whole archive. Links are listed with their target.

The release workflow signs the manifest (update.json.sig); the archive and every file are trusted
through their hashes in it, never on their own.
"""
import hashlib
import json
import os
import stat
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


def main(argv):
    if len(argv) != 5:
        sys.exit(__doc__.strip().splitlines()[2].strip())
    image, archive, version, platform = argv[1:]
    if not os.path.isfile(os.path.join(image, "bin", "nop")) and not os.path.isdir(os.path.join(image, "Contents")):
        sys.exit(f"{image} does not look like a nop app image")
    manifest = {
        "format": FORMAT,
        "version": version.removeprefix("v"),
        "platform": platform,
        "archive": {
            "name": os.path.basename(archive),
            "sha256": sha256(archive),
            "size": os.path.getsize(archive),
            # The archive's single top-level directory, which holds the image.
            "root": "nop",
        },
        "files": entries(image),
    }
    json.dump(manifest, sys.stdout, indent=1, sort_keys=False)
    sys.stdout.write("\n")


if __name__ == "__main__":
    main(sys.argv)
