#!/usr/bin/env python3
"""Print the update manifest of the release before this one, for update-manifest.py's --previous.

    scripts/previous-update-manifest.py <owner/repo> <this tag> <platform> > previous.json

The newest published release other than <this tag> that carries a manifest for <platform>
(update-<platform>.json, or update.json for linux-x64 in releases from before there was one per
platform). Prints nothing, and still exits 0, when there is none: a release without a previous
one simply has no delta. Uses $GITHUB_TOKEN when set, which GitHub's rate limits want in CI.
"""
import json
import os
import sys
import urllib.request


def get(url):
    request = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json", "User-Agent": "nop-release"})
    token = os.environ.get("GITHUB_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def main(argv):
    if len(argv) != 4:
        sys.exit(__doc__.strip().splitlines()[2].strip())
    repo, tag, platform = argv[1:]
    names = [f"update-{platform}.json"] + (["update.json"] if platform == "linux-x64" else [])
    try:
        releases = json.loads(get(f"https://api.github.com/repos/{repo}/releases?per_page=20"))
    except Exception as e:  # no previous release to diff against is not a reason to fail one
        print(f"previous-update-manifest: could not list releases: {e}", file=sys.stderr)
        return
    for release in releases:
        if release.get("draft") or release.get("tag_name") == tag:
            continue
        assets = {a["name"]: a["browser_download_url"] for a in release.get("assets", [])}
        for name in names:
            if name in assets:
                sys.stdout.buffer.write(get(assets[name]))
                return


if __name__ == "__main__":
    main(sys.argv)
