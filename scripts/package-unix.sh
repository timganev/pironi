#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "Usage: $0 JAR OUTPUT_DIR VERSION PLATFORM" >&2
  exit 2
fi

jar_path=$1
output_dir=$2
version=$3
platform=$4
bundle_name="pironi-${version}-${platform}"
bundle_dir="${output_dir}/${bundle_name}"

if [[ ! -f "$jar_path" ]]; then
  echo "JAR not found: $jar_path" >&2
  exit 1
fi

rm -rf "$bundle_dir"
mkdir -p "$bundle_dir"
jlink --add-modules java.se,jdk.crypto.ec,jdk.management \
  --strip-debug --no-header-files --no-man-pages \
  --output "$bundle_dir/runtime"
cp "$jar_path" "$bundle_dir/pironi.jar"
# Records which build this is, so a reported problem can be tied to a version.
printf '%s' "$version" > "$bundle_dir/version.txt"
cp dist/unix/pironi "$bundle_dir/pironi"
cp README.md "$bundle_dir/README.md"
# The examples ship beside the README so a person can see what a persona looks like without
# going to the repository for it.
for example in SOUL.example.md USER.example.md; do
  [ -f "$example" ] && cp "$example" "$bundle_dir/$example"
done
# Every directory under skills/ ships. Naming them one at a time meant a new skill had to be
# added here and in package-windows.ps1, and one missing from either shipped on a single platform.
if [ -d skills ]; then
  mkdir -p "$bundle_dir/skills"
  for skill in skills/*/; do
    [ -d "$skill" ] || continue
    # Without stripping the trailing slash this copies the directory on Linux and its *contents*
    # on macOS - GNU cp and BSD cp disagree about what "cp -R dir/ dest/" means. Every macOS
    # release shipped seven SKILL.md files overwriting each other directly in skills/, leaving one
    # orphan and no skill directories at all: a bundle whose skills silently did not exist. Linux
    # was correct throughout, which is why nothing ever looked wrong.
    cp -R "${skill%/}" "$bundle_dir/skills/"
  done
  echo "bundled skills: $(find "$bundle_dir/skills" -mindepth 1 -maxdepth 1 -type d | wc -l)"
fi
chmod +x "$bundle_dir/pironi"

archive="${output_dir}/${bundle_name}.tar.gz"
tar -C "$output_dir" -czf "$archive" "$bundle_name"
if command -v sha256sum >/dev/null 2>&1; then
  (
    cd "$output_dir"
    sha256sum "$(basename "$archive")"
  ) > "${archive}.sha256"
else
  (
    cd "$output_dir"
    shasum -a 256 "$(basename "$archive")"
  ) > "${archive}.sha256"
fi
