#!/usr/bin/env bash
set -euo pipefail

runs=${BENCH_RUNS:-5}
provider=${BENCH_PROVIDER:-deepseek}
model=${BENCH_MODEL:-deepseek-v4-flash}
prompt=${BENCH_PROMPT:-'Отговори на български само с: Здравей'}
pironi_jar=${PIRONI_JAR:-target/pironi-0.1.0-SNAPSHOT.jar}
hermes_bin=${HERMES_BIN:-hermes}
output_root=${BENCH_OUTPUT_DIR:-build/benchmarks}

if ! [[ "$runs" =~ ^[1-9][0-9]*$ ]]; then
  echo "BENCH_RUNS must be a positive integer" >&2
  exit 2
fi
if [[ ! -f "$pironi_jar" ]]; then
  echo "Pironi JAR not found: $pironi_jar (run mvn clean package first)" >&2
  exit 2
fi
if ! command -v /usr/bin/time >/dev/null 2>&1; then
  echo "/usr/bin/time is required" >&2
  exit 2
fi
if ! command -v "$hermes_bin" >/dev/null 2>&1; then
  echo "Hermes executable not found: $hermes_bin" >&2
  exit 2
fi

repo_root=$(pwd -P)
if [[ "$pironi_jar" != /* ]]; then
  pironi_jar="$repo_root/$pironi_jar"
fi
if [[ "$output_root" != /* ]]; then
  output_root="$repo_root/$output_root"
fi
hermes_bin=$(command -v "$hermes_bin")

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
result_dir="$output_root/$timestamp"
mkdir -p "$result_dir"
summary="$result_dir/summary.csv"
printf 'run,order,harness,exit_code,wall_seconds,peak_rss_kib,stdout_file,stderr_file,usage_file,trace_file\n' > "$summary"

git_commit=$(git rev-parse HEAD 2>/dev/null || printf 'not-a-git-checkout')
git_describe=$(git describe --always --dirty 2>/dev/null || printf 'unknown')
java_version=$(java -version 2>&1 | sed -n '1p')
hermes_version=$($hermes_bin --version 2>&1 | sed -n '1p')
{
  printf 'timestamp_utc=%s\n' "$timestamp"
  printf 'runs=%s\n' "$runs"
  printf 'provider=%s\n' "$provider"
  printf 'model=%s\n' "$model"
  printf 'prompt=%s\n' "$prompt"
  printf 'pironi_jar=%s\n' "$pironi_jar"
  printf 'pironi_git_commit=%s\n' "$git_commit"
  printf 'pironi_git_describe=%s\n' "$git_describe"
  printf 'java_version=%s\n' "$java_version"
  printf 'hermes_version=%s\n' "$hermes_version"
  printf 'host=%s\n' "$(uname -a)"
} > "$result_dir/metadata.txt"

run_one() {
  local iteration=$1
  local order=$2
  local harness=$3
  local run_dir="$result_dir/run-$iteration-$order-$harness"
  local workspace="$run_dir/workspace"
  local pironi_home="$run_dir/pironi-home"
  local stdout_file="$run_dir/stdout.txt"
  local stderr_file="$run_dir/stderr.txt"
  local time_file="$run_dir/time.txt"
  local usage_file="$run_dir/usage.json"
  local trace_file="$workspace/trace.jsonl"
  local exit_code wall_seconds peak_rss

  mkdir -p "$workspace" "$pironi_home"
  set +e
  if [[ "$harness" == pironi ]]; then
    /usr/bin/time -f '%e,%M' -o "$time_file" \
      java -jar "$pironi_jar" \
      --provider "$provider" \
      --model "$model" \
      --no-interactive \
      --workspace "$workspace" \
      --pironi-home "$pironi_home" \
      --trace "$trace_file" \
      --approval read-only \
      --status never \
      --personal-context deny \
      --max-turns 2 \
      --task "$prompt" >"$stdout_file" 2>"$stderr_file"
  else
    (
      cd "$workspace"
      /usr/bin/time -f '%e,%M' -o "$time_file" \
        "$hermes_bin" --safe-mode \
        --provider "$provider" \
        --model "$model" \
        --usage-file "$usage_file" \
        -z "$prompt" >"$stdout_file" 2>"$stderr_file"
    )
  fi
  exit_code=$?
  set -e

  if [[ -s "$time_file" ]]; then
    IFS=, read -r wall_seconds peak_rss < "$time_file"
  else
    wall_seconds=
    peak_rss=
  fi
  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$iteration" "$order" "$harness" "$exit_code" "$wall_seconds" "$peak_rss" \
    "$stdout_file" "$stderr_file" \
    "$([[ -f "$usage_file" ]] && printf '%s' "$usage_file")" \
    "$([[ -f "$trace_file" ]] && printf '%s' "$trace_file")" >> "$summary"
}

for ((iteration = 1; iteration <= runs; iteration++)); do
  if ((iteration % 2 == 1)); then
    run_one "$iteration" 1 pironi
    run_one "$iteration" 2 hermes
  else
    run_one "$iteration" 1 hermes
    run_one "$iteration" 2 pironi
  fi
done

echo "Benchmark results: $result_dir"
echo "Summary: $summary"
