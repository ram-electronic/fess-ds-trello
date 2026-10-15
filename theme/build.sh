#!/bin/bash
# Builds <out>/fess-theme-ram.zip (default out: target): Fess's bootstrap
# theme at FESS_COMMIT, with bootstrap.patch applied and trello.js and
# trello.css added. README.md's "Search theme" says how to move it to a
# new Fess release.
#
# Usage: theme/build.sh [out-dir]

set -euo pipefail

# Tag fess-15.9.0, pinned by commit so a moved tag can't change a release.
FESS_COMMIT=dc5a07c14b9db9ec48129e881feccc4226e520c3

theme_dir=$(cd "$(dirname "$0")" && pwd)
out=${1:-target}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

git init -q "$work/fess"
git -C "$work/fess" remote add origin https://github.com/codelibs/fess.git
git -C "$work/fess" sparse-checkout set --no-cone /src/main/webapp/themes/bootstrap/
git -C "$work/fess" fetch -q --depth 1 --filter=blob:none origin "$FESS_COMMIT"
git -C "$work/fess" checkout -q FETCH_HEAD

cp -r "$work/fess/src/main/webapp/themes/bootstrap" "$work/ram"
# No fuzz: a hunk that no longer matches upstream fails the build.
patch -s -p1 -F0 --no-backup-if-mismatch -d "$work/ram" < "$theme_dir/bootstrap.patch"
cp "$theme_dir/trello.js" "$theme_dir/trello.css" "$work/ram/assets/"

mkdir -p "$out"
zip=$(cd "$out" && pwd)/fess-theme-ram.zip
rm -f "$zip"
# theme.yml at the zip's top level, as Fess's theme installer expects.
(cd "$work/ram" && python3 -m zipfile -c "$zip" ./*)
echo "$zip"
