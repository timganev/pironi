# 🔩 Pironi

Pironi is a small Java 25 coding-agent harness for Ollama and
OpenAI-compatible chat-completions APIs. It provides a bounded agent loop,
workspace-scoped tools, explicit mutation approvals, and JSONL traces without
requiring Python or a separate runtime.

This is an early implementation. Use it in a disposable workspace until the
tool and approval behavior has been tested for your use case.

## Harness baseline: Pironi vs Hermes

The following single-run baseline was measured on 2026-07-24 on the same
machine and against the same direct provider endpoint. Both processes started
a new one-shot session with the exact prompt `хей`, no tool calls, and no project or personal context requested.
Wall time includes process startup, request latency, generation, parsing, and
shutdown.

| Metric | Pironi | Hermes |
| --- | ---: | ---: |
| Harness | Pironi | Hermes |
| Result | `Привет! Чем могу помочь?` | `Хей, Тим. Какво има?` |
| Wall time | 4.85 s | 6.36 s |
| Uncached input | 472 | 16,317 |
| Cache read | not reported | 5,888 |
| Effective prompt | 472 | 22,205 |
| Output | 245 | 61 |
| Total tokens | 717 | 22,266 |
| API calls | 1 | 1 |

For this deliberately tiny request, Pironi was 1.51 seconds (about 24%)
faster and sent about 47 times fewer effective prompt tokens. The result
measures harness overhead, not general model quality. Hermes supplied a much
larger built-in prompt/tool environment and produced the more appropriate
Bulgarian response. The context-free Pironi run incorrectly interpreted
`хей` as Russian. Output-token accounting may also include provider-side
reasoning differently, so input-token overhead is the more useful comparison.

The measured software footprints differ substantially:

| Metric                   |                                                 Pironi |                                                                              Hermes |
| ------------------------ | -----------------------------------------------------: | ----------------------------------------------------------------------------------: |
| Harness                  |                                Pironi `0.1.0-SNAPSHOT` |                                                                     Hermes `0.17.0` |
| Implementation           |                                                Java 25 |                                      Python 3.11 core with TypeScript UI components |
| Measured local footprint |                                      3.8 MB shaded JAR |                                                                 7.4 GB installation |
| Source-only footprint    | about 584 KB, excluding `target`, IDE data, and traces | about 137 MB, excluding `.git`, `venv`, `node_modules`, build, and generated output |

The footprint figures are not perfectly symmetrical. Pironi's JAR does not
bundle the Java runtime, while the measured Hermes checkout contains a 5.4 GB
Python virtual environment and 1.2 GB `node_modules` directory. Hermes also
implements gateways, messaging integrations, plugins, skills, browser and
desktop UI features that Pironi intentionally does not provide.

Commands used for the baseline:

```bash
# Hermes: safe mode disables custom rules, memory, plugins, and MCP servers.
hermes --safe-mode \
  --provider PROVIDER \
  --model MODEL \
  -z 'хей'

# Pironi: empty workspace, no personal context, no TUI/status output.
java -jar target/pironi-0.1.0-SNAPSHOT.jar \
  --provider PROVIDER \
  --model MODEL \
  --no-interactive \
  --workspace /tmp/empty-pironi-workspace \
  --approval read-only \
  --status never \
  --personal-context deny \
  --max-turns 2 \
  --task 'хей'
```

Hermes usage came from session `20260724_115036_d0984a` in its local session
database. Pironi usage came from the `model_response` event in
`/tmp/pironi-benchmark-b9ArG9/trace.jsonl`. Minion was intentionally excluded
from the final comparison at the user's request.

## Requirements

- OpenJDK 25
- Maven 3.9+
- Ollama for local models, or an OpenAI-compatible API endpoint

## Build and test

```bash
mvn clean verify
java -jar target/pironi-0.1.0-SNAPSHOT.jar --help
```

The shaded executable JAR is written to:

```text
target/pironi-0.1.0-SNAPSHOT.jar
```

## Ollama

Start Ollama and make sure the model is available:

```bash
sudo systemctl enable --now ollama
ollama pull MODEL
```

Run Pironi in its default interactive mode:

```bash
java -jar target/pironi-0.1.0-SNAPSHOT.jar \
  --provider ollama \
  --model MODEL
```

The local defaults are:

- workspace: `/home/tim/repos/pironi`;
- approval: `read-only`;
- status: `always`;
- interactive mode enabled.

At the `›` prompt, enter `/` to show the command menu. `/model` opens a
two-stage provider/model picker with arrow-key navigation, Enter to select,
Back, Cancel and Esc. Ollama models come from the local `/api/tags` endpoint,
DeepSeek and OpenRouter use their live `/models` catalogs. `/model MODEL`
remains a shortcut and automatically switches the
provider for recognized DeepSeek IDs and OpenRouter vendor/model slugs. A model switch
clears the bounded conversation history and is saved as part of the
last-session profile. `/help`, `/context`, `/clear`, and `/exit` are also
available.

`/approval` shows the live approval policy. Change it without restarting:

```text
/approval ask
/approval auto
/approval read-only
```

The change takes effect immediately and is stored in the last-session profile.
Pironi also injects the live provider, model, workspace, approval, context and
status values into every agent task as authoritative runtime metadata, so the
model does not need to inspect source or configuration files to answer those
questions.

After a successful configured start, Pironi stores the effective non-secret
settings in `~/.pironi/last-session.properties`. Starting the JAR without
arguments restores that provider, model, endpoint, workspace, approval and
generation profile and opens interactive mode:

```bash
java -jar target/pironi-0.1.0-SNAPSHOT.jar
```

An explicit invocation replaces the saved profile. Tasks, conversation
history and API keys are never stored in this file.
Interactive memory keeps at most four bounded user/final-answer exchanges.
Detailed tool output is scoped to one task and is not carried into the next.

Use one-shot mode for scripts:

```bash
java -jar target/pironi-0.1.0-SNAPSHOT.jar \
  --model MODEL \
  --no-interactive \
  --workspace /path/to/project \
  --approval ask \
  --task "Inspect the project and report the most important build problem."
```

### Empirically tuned large-context Ollama run

The following profile was tuned on 2026-07-28/29 for log-heavy automation
with `qwen3.6:35b-a3b`:

```bash
java -jar /path/to/pironi/target/pironi-0.1.0-SNAPSHOT.jar \
  --workspace "$repo_root" \
  --model qwen3.6:35b-a3b \
  --context 131072 \
  --max-output-tokens 16384 \
  --max-turns 30 \
  --approval auto \
  --deny-tools read_file,list_files \
  --no-interactive \
  --status never \
  --personal-context deny \
  --trace "$trace" \
  --task "$(cat "$prompt_file")"
```

`--context 131072` is the critical setting: measured prompts peaked around
82–84k tokens and a single large tool-output pull was about 8k. The profile
also disables interactive input, status rendering and personal instructions,
and permits unattended mutating tool calls. Use `--approval auto` only in a
workspace where that risk is acceptable.

`--deny-tools` removes the named tools from the registry and model prompt.
Unknown names fail startup, and the setting is stored in the last-session
profile. It is not a general shell sandbox: an enabled `run_command` can still
read workspace files. Deny `run_command` too when shell access is not required.

A wrapper may expose these defaults:

```bash
PIRONI_MODEL=qwen3.6:35b-a3b
PIRONI_CONTEXT=131072
PIRONI_MAX_TURNS=30
PIRONI_MAX_OUTPUT_TOKENS=16384
```

The measured 131k profile needs OpenJDK 25, Maven 3.9+, Ollama with
`qwen3.6:35b-a3b`, and roughly 21–24 GB of free GPU memory. The associated log
wrapper additionally needs `jq`, `tools/allure-digest`, `tools/kibana-logs`,
and Kibana credentials in `~/.config/kibana_ui_cred`.

## OpenAI-compatible APIs

Pironi reads API keys from environment variables. It does not accept a key as
a CLI value.

```bash
export OPENAI_API_KEY=...
java -jar target/pironi-0.1.0-SNAPSHOT.jar \
  --provider openai-compatible \
  --base-url https://provider.example/v1 \
  --api-key-env OPENAI_API_KEY \
  --model provider-model-name \
  --workspace /path/to/project \
  --task "Inspect the project."
```

A named provider is a convenience alias for a pre-configured endpoint:

```bash
export PROVIDER_API_KEY=...
java -jar target/pironi-0.1.0-SNAPSHOT.jar \
  --provider PROVIDER
```

Named providers ship with sensible defaults (base URL, model, context window,
reasoning effort) that can be overridden with `--model` or `--base-url`.
Pironi first checks the process environment for the API key. If absent, it
reads only that variable from `~/.hermes/.env`. It does not source other
variables and never stores the key in its config or trace. Provider model
names, behavior, and pricing can change; verify the current provider
documentation before using the paid API.

JSON mode can occasionally return empty assistant content. Pironi retries
only the model request up to two additional times; completed tools are not
executed again.

OpenRouter is another dedicated profile over the generic client:

```bash
export OPENROUTER_API_KEY=...
java -jar target/pironi-0.1.0-SNAPSHOT.jar \
  --provider openrouter
```

Its defaults are `https://openrouter.ai/api/v1`, model `openrouter/auto`, key
variable `OPENROUTER_API_KEY`, and 200,000 tokens for context accounting.
Pironi checks the process environment first and then reads only that variable
from `~/.hermes/.env`. Use an explicit slug such as
`--model openrouter/free` when automatic paid routing is not desired.
OpenRouter is a cloud provider, so `--personal-context auto` does not send
`SOUL.md` or `USER.md`.

## Approvals

- `--approval ask` prompts before each mutating tool call.
- `--approval auto` permits mutating tool calls without prompting.
- `--approval read-only` denies mutating tool calls.
- `--activity auto` is a convenience override for `--approval auto`, including
  when `--approval ask` also appears in the command.

Interactive sessions are persisted under `~/.pironi/sessions`. `/sessions`
lists them, `/resume [ID]` schedules a saved checkpoint for the next request,
and `/compress now` schedules semantic compression for the next request.
Model-reported prompt and output token counts drive the compression threshold.

Skills live under `~/.pironi/skills/NAME/SKILL.md`. `/skill NAME` activates a
skill for subsequent agent prompts, `/skill off` clears it, and
`/save-skill NAME` saves the last successfully completed turn as a reusable
skill.

`run_command` is considered mutating because arbitrary shell commands can
change files or external state. Prefer `ask` or `read-only` when evaluating a
new model.

## Context files and privacy

Pironi can load:

- `~/.pironi/SOUL.md` for agent identity and behavior;
- `~/.pironi/USER.md` for personal preferences;
- `WORKSPACE/CLAUDE.md` for project instructions.

For a long `CLAUDE.md`, place this marker after the instructions that must be
sent to the model on every turn:

```text
<!-- pironi-runtime-context-end -->
```

Pironi loads only the content before the marker. The rest remains available to
people and other agents as project history without consuming every Ollama
context window.

The example files (`SOUL.example.md`, `USER.example.md`) are structured,
placeholder-based templates of what a personal setup can look like. Copy them
only as a starting point and fill in the `[PLACEHOLDERS]` with your own values:

```bash
mkdir -p ~/.pironi
cp SOUL.example.md ~/.pironi/SOUL.md
cp USER.example.md ~/.pironi/USER.md
```

The example templates are tracked in the public repo, so treat them as
**sample formats, not a place for personal data**. Keep any real identity,
names, machines, employers, or projects out of them. Personal context lives
only in your local `~/.pironi/SOUL.md` and `~/.pironi/USER.md`.

The default `--personal-context auto` behavior is deliberately different by
provider:

- Ollama loads `SOUL.md` and `USER.md`.
- Cloud/OpenAI-compatible providers do not load them.
- `CLAUDE.md` is loaded for every provider.

Sending personal context to a cloud provider requires the explicit
`--personal-context allow` option. Review these files before enabling it.

## Other options

```text
--interactive
--no-interactive
--task TEXT
--max-turns N
--context N
--max-output-tokens N
--timeout-seconds N
--trace PATH
--pironi-home PATH
--personal-context auto|allow|deny
--status auto|always|never
--deny-tools NAME,NAME
```

The default trace is `WORKSPACE/.pironi/trace.jsonl`. A trace can contain
prompts, model responses, tool arguments, and tool output. Treat it as
potentially sensitive and do not commit it.

## Live status

In an interactive terminal Pironi reserves the bottom terminal row for a
persistent status line on `stderr`:

```text
⠹ MODEL | project | ctx ~7% | working 18s | turn 2/8
```

`working` means that Pironi is waiting for the model; it does not imply that
the model's optional reasoning mode is enabled. The spinner and elapsed seconds
show that the process is alive. Normal output and the `pironi>` prompt scroll
above the status row. After a task, the row remains visible as `ready`.
`ctx ~7%` is an estimate based on message size because tokenization differs by
model. During tool execution the same line shows the tool name.

After each Ollama turn the status line also retains the measured generation
rate from `eval_count / eval_duration`, for example `│ 19.99 tok/s`. It updates
when the next response completes and resets after a model change. Providers
without eval timing metadata use `completion_tokens / request duration` and
mark the end-to-end estimate with `~`, for example `│ ~12.40 tok/s`.

Normal output and the ANSI status frame share one `PrintStream`; every frame is
written as one operation to prevent status fragments from being interleaved
inside model output in IDE terminals.

- `--status auto` enables the line only for an interactive console.
- `--status always` forces it on, which is useful in some IDE terminals.
- `--status never` disables it.

Status uses `stderr`; the final answer remains clean on `stdout`.
On `/exit`, Pironi clears the reserved row and restores the normal terminal
scroll region.

Ollama responses use NDJSON streaming. In interactive mode Pironi incrementally
extracts and prints only the JSON protocol's `finalAnswer`; `thought` and raw
protocol JSON stay hidden. Tool turns continue to use the status line, and the
completed answer is retained for validation, tracing and conversation memory
without being printed a second time.

Interactive conversation colors distinguish speakers: user input is cyan and
streamed agent answers are green. Status, memory and approval messages retain
their neutral UI colors. JLine generates the terminal-specific escape sequences.

## Current tool set

- `list_files`
- `read_file`
- `write_file`
- `apply_patch`
- `rollback_checkpoint`
- `run_command`

All file paths are restricted to the selected workspace. `apply_patch` requires
one exact old-text match, shows a diff before approval, creates a checkpoint,
and writes atomically. Before a final answer after a mutation, Pironi
automatically runs the configured verification command or detects Maven/Gradle.
`list_files` omits common generated/private directories such as `.git`,
`.pironi`, `.idea`, `target`, `build`, `.gradle`, and `node_modules`.
`--deny-tools` removes exact tool names from this set and rejects unknown names
at startup. It does not restrict filesystem access through `run_command`.
`run_command` still requires mutation approval, but does not trigger a second
automatic build after a successful command. Source changes must use
`apply_patch`, which does trigger automatic verification.
Commands and automatic verification inherit the Java runtime that launched
Pironi: `JAVA_HOME` is set from the active JVM and its `bin` directory is
prepended to `PATH`. Starting Pironi with Java 25 therefore also makes Maven
use Java 25, even when the parent shell still defaults to Java 17.

For Ollama, Pironi currently sends `think: false`, uses the requested context
window and caps generation with `--max-output-tokens`.
