# Long-run benchmark

Sixteen tasks in two resumed Pironi sessions, each checked automatically. It is the measure for changes
to the turn loop: history handling, compression, ledgers and prompts.

| Steps | Session | What it covers |
|---|---|---|
| 1-11 | 1 | The long test used since 25.10.2026: explain, search, move, bug fix, new code, log counting, refactor, script, memory, git |
| 12-13 | 2 | Read a stock file (deleted by the harness afterwards), write a short plan |
| 14 | 2 | Third task of a resumed session, 14+ turns: follow a trail of 14 files; the output format is only in the task |
| 15 | 2 | 22+ turns: follow a trail of 22 files |
| 16 | 2 | Answer from an early tool result only: the file is gone |

Each run gets its own home (`-Duser.home`): the SOUL and USER files are `SOUL.example.md` and
`USER.example.md`, CLAUDE.md is `context/CLAUDE.md`, and nothing from the user's `~/.pironi` is used.
The read tools are limited to the workspace and the trace is kept outside the run's home, so the model
cannot answer a memory question by reading its own trace. The shell keeps the user scope Pironi is used
with: the workspace scope has no `run_command`, and the log, script and git steps need it.

Pironi talks to the server through a recording proxy started by `run.py`; it counts prompt, cached and
generated tokens, the longest prompt, the thinking share, truncations (a request with fewer messages than
the one before, with no compression between) and the share of the findings ledger in tool results.
Read and write seconds come from vLLM's `/metrics`.

```bash
mvn clean verify
python3 benchmarks/long-run/run.py --model qwen38 --label base-38-1     # one run, ~13 min on a B70
python3 benchmarks/long-run/compare.py 'benchmarks/long-run/results/base-38-*.json' \
                                       'benchmarks/long-run/results/new-38-*.json'
```

Compare n >= 4 runs per side. `compare.py` judges only passes (no fewer in total, no step losing two or
more runs); time and tokens are reported with their spread.

The task texts are in Bulgarian, as the agent is used; the fixture is deterministic (`fixture.py`).
