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
cp dist/unix/pironi "$bundle_dir/pironi"
cp README.md "$bundle_dir/README.md"
mkdir -p "$bundle_dir/.pironi/skills/team-lead"
cp skills/team-lead/SKILL.md "$bundle_dir/.pironi/skills/team-lead/SKILL.md"
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
