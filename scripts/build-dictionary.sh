#!/usr/bin/env bash
# Regenerates the spellchecker's bundled word list from the aspell dictionaries installed on this
# machine (Debian: aspell aspell-en). The three English variants are merged so that neither
# "colour" nor "color" is a typo — nop has no per-project locale and an editor that underlines
# half the spellings its user writes is worse than no spellchecker at all.
#
# Only lowercase ASCII words of three letters or more are kept: the checker lowercases before
# lookup, skips anything shorter than four letters, and strips possessives itself, so accented
# forms and "cat's" entries would just be weight in the jar.
#
# Output is gzipped with -n so the bytes depend on the word list alone and re-running this without
# a dictionary upgrade leaves the file (and the git index) untouched.
#
# Usage: scripts/build-dictionary.sh
set -euo pipefail

out="$(dirname "$0")/../src/main/resources/dictionary/words.txt.gz"
mkdir -p "$(dirname "$out")"

for dict in en_US en_GB en_AU; do
    aspell -d "$dict" dump master
done |
    tr 'A-Z' 'a-z' |
    grep -x '[a-z]\{3,\}' |
    LC_ALL=C sort -u |
    gzip -n -9 >"$out"

printf '%s: %s words, %s bytes\n' \
    "$out" \
    "$(gzip -cd "$out" | wc -l)" \
    "$(wc -c <"$out")"
