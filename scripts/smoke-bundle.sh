#!/usr/bin/env bash
# Unpacks a built bundle and runs it, which is the only check that covers the packagers
# themselves. The suite tests the code and PortableLauncherTest compares the two launcher
# scripts as text; neither of them has ever executed what a person downloads. A jlink module
# list that stops producing a working runtime, a copy step that misses a directory on one
# platform, or a README claiming to ship skills that are not in the archive all survive every
# other check in this repository and appear for the first time on someone else's machine.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 ARCHIVE EXPECTED_SKILL_COUNT" >&2
  exit 2
fi

archive=$1
expected_skills=$2
archive_name=$(basename "$archive")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

echo "== checksum =="
checksum_file="${archive}.sha256"
if [[ ! -f "$checksum_file" ]]; then
  echo "no checksum was written beside $archive_name" >&2
  exit 1
fi
declared=$(tr -s ' ' < "$checksum_file" | cut -d' ' -f1 | tr 'A-Z' 'a-z')
if command -v sha256sum >/dev/null 2>&1; then
  actual=$(sha256sum "$archive" | cut -d' ' -f1)
else
  actual=$(shasum -a 256 "$archive" | cut -d' ' -f1)
fi
if [[ "$declared" != "$actual" ]]; then
  echo "checksum does not match what shipped beside it: $declared != $actual" >&2
  exit 1
fi
echo "ok: $actual"

echo "== unpack =="
case "$archive_name" in
  *.tar.gz) tar -C "$work" -xzf "$archive" ;;
  *.zip)    unzip -q "$archive" -d "$work/bundle" ;;
  *)        echo "unknown archive type: $archive_name" >&2; exit 1 ;;
esac

# The tar keeps its top folder and the zip does not, deliberately - so find the launcher rather
# than assuming either shape.
launcher=$(find "$work" -maxdepth 2 -name 'pironi' -o -maxdepth 2 -name 'pironi.bat' | head -1)
if [[ -z "$launcher" ]]; then
  echo "no launcher in the archive; it contains:" >&2
  find "$work" -maxdepth 2 >&2
  exit 1
fi
bundle=$(dirname "$launcher")
echo "bundle: $bundle"

echo "== contents =="
for required in pironi.jar version.txt README.md; do
  [[ -f "$bundle/$required" ]] || { echo "missing from the bundle: $required" >&2; exit 1; }
done
if [[ ! -x "$bundle/runtime/bin/java" && ! -f "$bundle/runtime/bin/java.exe" ]]; then
  echo "the bundled runtime has no java executable" >&2
  exit 1
fi

shipped_skills=$(find "$bundle/skills" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | wc -l | tr -d ' ')
if [[ "$shipped_skills" != "$expected_skills" ]]; then
  echo "the archive carries $shipped_skills skills; the checkout has $expected_skills" >&2
  find "$bundle/skills" -mindepth 1 -maxdepth 1 -type d >&2 || true
  exit 1
fi
echo "ok: $shipped_skills skills, runtime present"

echo "== run it =="
chmod +x "$launcher" 2>/dev/null || true
version_output=$("$launcher" --version)
echo "$version_output"
expected_version=$(cat "$bundle/version.txt")
case "$version_output" in
  *"$expected_version"*) ;;
  *) echo "--version says '$version_output' but version.txt says '$expected_version'" >&2; exit 1 ;;
esac

help_output=$("$launcher" --help)
case "$help_output" in
  *--activity*) ;;
  *) echo "--help did not print the options screen" >&2; exit 1 ;;
esac

echo "ok: the unpacked bundle runs"
