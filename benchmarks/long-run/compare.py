#!/usr/bin/env python3
"""compare.py A_GLOB B_GLOB -- compares two sets of long-run results (n >= 4 each, as run.py writes them).

    python3 benchmarks/long-run/compare.py 'results/base-38-*.json' 'results/hist-38-*.json'

Prints the median [min-max] of each summary metric for A and B and the change of the medians, then the
pass count of every step. The criterion is on passes only: B must not pass fewer tasks than A (median of
the totals), and no step may lose two or more runs out of n. Time and tokens are reported, not judged:
with n = 4 their spread is shown next to the change so a reader can tell noise from effect.
"""
import glob
import json
import statistics
import sys

METRICS = [("passed", "passed"), ("wall", "time s"), ("turns", "turns"), ("requests", "requests"),
           ("prompt_tokens", "prompt tok"), ("cache_hit", "cache hit"), ("gen_tokens", "gen tok"),
           ("max_prompt", "max prompt"), ("read_s", "read s"), ("write_s", "write s"),
           ("thinking_share", "thinking"), ("truncations", "truncations"), ("compressions", "compressions"),
           ("protocol_errors", "protocol err"), ("ledger_share", "ledger share")]
STEP_METRICS = [("wall", "s"), ("cache_hit", "cache"), ("read_s", "read"), ("max_prompt", "maxP")]


def load(pattern):
    runs = [json.load(open(p)) for p in sorted(glob.glob(pattern))]
    if not runs:
        sys.exit(f"no results match {pattern}")
    return runs


def spread(values):
    v = [x for x in values if x is not None]
    if not v:
        return None, "-"
    m = statistics.median(v)
    fmt = (lambda x: f"{x:.3f}") if any(isinstance(x, float) and x < 1 for x in v) else (lambda x: f"{x:g}")
    return m, f"{fmt(m)} [{fmt(min(v))}-{fmt(max(v))}]"


def change(a, b):
    if a is None or b is None:
        return "-"
    d = b - a
    return f"{d:+.3g}" + (f" ({d / a * 100:+.1f}%)" if a else "")


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    A, B = load(sys.argv[1]), load(sys.argv[2])
    print(f"A: {sys.argv[1]} (n={len(A)})   B: {sys.argv[2]} (n={len(B)})")
    if len(A) < 4 or len(B) < 4:
        print("note: fewer than 4 runs on a side; the medians are weak")
    print(f"\n{'metric':14} {'A median [min-max]':>26} {'B median [min-max]':>26}  change of medians")
    for key, title in METRICS:
        ma, sa = spread([r["summary"].get(key) for r in A])
        mb, sb = spread([r["summary"].get(key) for r in B])
        print(f"{title:14} {sa:>26} {sb:>26}  {change(ma, mb)}")

    names = {s["step"]: s["name"] for r in A + B for s in r["steps"]}
    print(f"\n{'#':>2} {'step':9} {'A ok':>5} {'B ok':>5}  " + "  ".join(f"{t + ' A→B':>16}" for _, t in STEP_METRICS))
    worse = []
    for step in sorted(names):
        sa = [s for r in A for s in r["steps"] if s["step"] == step]
        sb = [s for r in B for s in r["steps"] if s["step"] == step]
        oa, ob = sum(s["ok"] for s in sa), sum(s["ok"] for s in sb)
        # compare as shares, in case the two sides ran a different number of times
        if sa and sb and (oa / len(sa) - ob / len(sb)) * max(len(sa), len(sb)) >= 2:
            worse.append(names[step])
        cells = []
        for key, _ in STEP_METRICS:
            ma, _ = spread([s.get(key) for s in sa])
            mb, _ = spread([s.get(key) for s in sb])
            cells.append(f"{'-' if ma is None else f'{ma:g}':>7}→{'-' if mb is None else f'{mb:g}':<8}")
        print(f"{step:2d} {names[step]:9} {oa:>2}/{len(sa):<2} {ob:>2}/{len(sb):<2}  " + "  ".join(cells))

    pa, _ = spread([r["summary"]["passed"] for r in A])
    pb, _ = spread([r["summary"]["passed"] for r in B])
    ok = pb >= pa and not worse
    print(f"\nverdict: {'PASSES HOLD' if ok else 'PASSES DROPPED'} (median passed {pa:g} → {pb:g}"
          + (f"; steps losing 2+ runs: {', '.join(worse)}" if worse else "") + ")")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
