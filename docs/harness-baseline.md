# Harness baseline: Pironi vs Hermes

Moved out of README so the install-and-run page stays a manual. This is a dated
measurement and its reproduction script, kept beside the CSV it produced.


This baseline was repeated on 2026-08-09 with Pironi `v0.1.10` and Hermes
`0.20.0 (2026.8.3)`, using the same
machine, direct DeepSeek endpoint, and `deepseek-v4-flash`. Each
harness ran five times in alternating order with a fresh workspace and disabled
personal context. The neutral prompt was
`Отговори на български само с: Здравей`; all ten runs returned exactly
`Здравей`. Wall time includes startup, provider latency, validation,
output and shutdown.

| Metric | Pironi | Hermes |
| --- | ---: | ---: |
| Wall time, median (range) | 2.92 s (2.70–3.62) | 5.28 s (4.86–6.05) |
| Peak RSS, median (range) | 133.7 MiB (132.0–138.5) | 168.1 MiB (167.5–168.6) |
| Provider input tokens, median (range) | 4,338 (4,337–4,338) | 20,340 (20,340–23,924) |
| Provider output tokens, median (range) | 68 (53–76) | 28 (21–54) |
| API calls | 1 | 1 |
| Exact requested result | 5/5 | 5/5 |

For this deliberately tiny request, Pironi used about 79% fewer input tokens,
about 20% less peak process memory, and finished about 45% sooner at the
median. This measures harness overhead for one controlled request, not general
agent quality. Hermes intentionally includes a much broader built-in
environment, while Pironi optimizes for a small, selectively loaded harness.
Provider output accounting can include reasoning differently, so input tokens,
wall time and verified task outcomes are the most useful comparison here.

The measured software footprints differ substantially:

| Artifact measured on the benchmark host | Pironi | Hermes |
| --- | ---: | ---: |
| Standalone application | 3.9 MiB shaded JAR | n/a |
| Runnable environment | 97 MiB unpacked Linux portable | 880 MiB checkout excluding `.git` |
| Compressed portable | 34 MiB Linux archive | n/a |
| Source tree without dependency/runtime directories | below 1 MiB | 186 MiB |

The footprints are not feature-equivalent. Hermes additionally provides
gateways, messaging integrations, plugins, browser/desktop features and a much
larger bundled skill environment. Sanitized per-run measurements are committed
in `docs/benchmarks/2026-08-09-pironi-v0.1.10-vs-hermes-v0.20.0.csv`.

### Reproduce the harness comparison

On Linux, `scripts/benchmark-harnesses.sh` performs five alternating runs of
each harness. Every run receives a fresh workspace and Pironi home. The script
records exact command versions, the Pironi Git commit, host metadata, raw
stdout/stderr, `/usr/bin/time` measurements, Pironi JSONL traces, Hermes usage
JSON, and a summary CSV. It never reads or writes an API key itself; configure
both harnesses for the selected provider before running it.

```bash
mvn clean package
BENCH_PROVIDER=deepseek \
BENCH_MODEL=deepseek-v4-flash \
scripts/benchmark-harnesses.sh
```

Results are written below `build/benchmarks`, which is not a release artifact.
Compare median wall time and peak RSS together with provider-reported prompt,
cache-read, output-token, and API-call fields. Alternate ordering reduces the
effect of warm provider caches, but a five-run local sample remains a startup
and request baseline rather than a general model-quality ranking.

