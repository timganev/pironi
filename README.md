# 🔩 Pironi

Pironi is a small Java 25 coding-agent harness for Ollama and
OpenAI-compatible chat-completions APIs. It provides a bounded agent loop,
workspace-scoped tools, explicit mutation approvals, and JSONL traces without
requiring Python or a separate runtime.

This is an early implementation. Use it in a disposable workspace until the
tool and approval behavior has been tested for your use case.

## Download and run

The current binaries are published on the
[latest Pironi release page](https://github.com/timganev/pironi/releases/latest).
Choose the setup that matches the machine:

| Platform                       | Recommended package                          |              Admin rights | Java/Maven required         | Start command              |
| ------------------------------ | -------------------------------------------- | ------------------------: | --------------------------- | -------------------------- |
| Windows 11, locked-down laptop | Windows x64 portable ZIP                     |                        No | Neither; Java 25 is bundled | `pironi.bat ...`           |
| macOS (Apple Silicon)          | macOS ARM64 portable archive                 |                        No | None; Java 25 is bundled     | `./pironi ...`             |
| Linux x64                      | Linux x64 portable archive                   |                        No | None; Java 25 is bundled     | `./pironi ...`             |
| Development machine            | Source checkout                              |          Depends on setup | JDK 25 and Maven 3.9+       | `mvn clean verify`         |

Portable archives contain a trimmed Java 25 runtime. Maven is needed only when
building or testing Pironi from source. Each tagged release provides
`pironi-VERSION-windows-x64.zip`, `pironi-VERSION-linux-x64.tar.gz`, and
`pironi-VERSION-macos-arm64.tar.gz`, each with an adjacent `.sha256` checksum
file.

### Windows 11 without admin rights

This is the recommended setup for a managed Windows x64 laptop where software
cannot be installed:

1. Download the latest Windows x64 ZIP from the
   [Pironi releases page](https://github.com/timganev/pironi/releases/latest).
2. Extract the complete ZIP into a writable location such as
   `Documents\Pironi`. Keep `pironi.bat`, `pironi.jar`, and `runtime` together.
3. Open PowerShell in the extracted folder.
4. Set the API key for the current PowerShell window and start Pironi. Both
   commands below are intentionally single-line commands:

```powershell
$env:DEEPSEEK_API_KEY = "your-key"
.\pironi.bat --provider deepseek --model deepseek-v4-flash --context 131072 --max-output-tokens 16384 --max-turns 30 --activity auto
```

Optionally verify the downloaded archive against its adjacent checksum file
before extracting it:

```powershell
(Get-FileHash .\pironi-VERSION-windows-x64.zip -Algorithm SHA256).Hash.ToLower()
Get-Content .\pironi-VERSION-windows-x64.zip.sha256
```

The hexadecimal values must match. Replace `VERSION` with the release tag shown
in the downloaded filename, for example `v0.1.9`.

For a scripted one-shot task containing Cyrillic, quotes, or other Unicode,
save the prompt as UTF-8 and pass its path instead of putting the prompt on the
`cmd.exe` command line:

```powershell
Set-Content -Path .\task.txt -Value 'Прегледай проекта и обобщи риска. ✓' -Encoding utf8
.\pironi.bat --provider deepseek --model deepseek-v4-flash --no-interactive --task-file .\task.txt --activity auto
```

PowerShell does not search the current directory for commands, which is why
the required launcher spelling is `.\pironi.bat`. The `$env:` assignment lasts
only until that PowerShell process closes. To persist the key for the current
Windows user without admin rights, run this once and then open a new terminal:

```powershell
[Environment]::SetEnvironmentVariable("DEEPSEEK_API_KEY", "your-key", "User")
```

This stores the secret in the user environment, so use the session-only form
on a shared or managed machine. In Command Prompt (not PowerShell), the
equivalent session-only syntax is `set "DEEPSEEK_API_KEY=your-key"`, followed
by `pironi.bat --provider deepseek --model deepseek-v4-flash --context 131072 --max-output-tokens 16384 --max-turns 30 --activity auto`.

No installer, elevation, Maven, `JAVA_HOME`, or system `PATH` change is used.
`--activity auto` permits user-file changes and shell commands without
confirmation in the Windows portable default. Use it only on a backed-up user
profile and review the requested task carefully.

When no override is supplied, `pironi.bat` uses `%USERPROFILE%` as both the
writable workspace and search root (including Downloads, Desktop, and
Documents), enables user-scoped shell commands, and uses the
`.pironi` directory beside `pironi.bat` for `SOUL.md`, `USER.md`, skills, and
sessions. Personal context is layered from `%USERPROFILE%\.pironi`, through the
portable home, and then through `.pironi` directories down to the workspace;
nearer layers override conflicting instructions from broader layers. `CLAUDE.md`
files cascade from the user home down to the workspace in the same order. These
personal files are sent to the selected cloud provider; pass
`--personal-context deny` to disable that.
Arguments after `pironi.bat` override the launcher defaults, so an explicit
`--workspace` still selects a project. Pironi creates a missing final workspace
directory automatically.

#### Windows alternative: an existing unpacked JDK 25

If Java 25 is already unpacked somewhere such as
`%USERPROFILE%\Downloads\Java\jdk-25`, the smaller standalone JAR can be used
instead of the portable ZIP. No global environment variables or admin rights
are required. Set `JAVA_HOME` and prepend Java to `PATH` only for the current
Command Prompt window:

```bat
set "JAVA_HOME=%USERPROFILE%\Downloads\Java\jdk-25"
set "PATH=%JAVA_HOME%\bin;%PATH%"

java -version
```

Then set the API key and launch the downloaded JAR from Command Prompt using
single-line commands:

```bat
set "DEEPSEEK_API_KEY=your-key"
java -jar "%USERPROFILE%\Downloads\pironi.jar" --provider deepseek --model deepseek-v4-flash --workspace "%USERPROFILE%" --context 131072 --max-output-tokens 16384 --max-turns 30 --activity auto --shell-scope user
```

These `set` commands affect only the current Command Prompt process and
disappear when the window is closed. They are unnecessary when using the
Windows portable ZIP, because `pironi.bat` starts the bundled runtime directly.

### macOS

Download `pironi-VERSION-macos-arm64.tar.gz` from the
[latest release](https://github.com/timganev/pironi/releases/latest), extract it,
and run the bundled launcher. Maven, Homebrew, and a system Java are not needed:

```bash
export DEEPSEEK_API_KEY='your-key'
./pironi --provider deepseek --model deepseek-v4-flash --workspace "$HOME/Projects/project" --context 131072 --max-output-tokens 16384 --max-turns 30 --activity auto
```

The published macOS portable archive is ARM64 and targets Apple Silicon.
Before extracting, compare the hexadecimal values printed by these commands:

```bash
shasum -a 256 pironi-VERSION-macos-arm64.tar.gz
awk '{print $1}' pironi-VERSION-macos-arm64.tar.gz.sha256
```

### Linux with full administrative access

Download `pironi-VERSION-linux-x64.tar.gz` from the
[latest release](https://github.com/timganev/pironi/releases/latest), extract it,
and run its bundled launcher. No system Java or Maven is required:

```bash
export DEEPSEEK_API_KEY='your-key'
./pironi --provider deepseek --model deepseek-v4-flash --workspace /path/to/project --activity auto
```

With full administrative access, move the extracted directory under
`/opt/pironi` and symlink its `pironi` launcher into `/usr/local/bin` if desired.

Before extracting, compare the hexadecimal values printed by these commands:

```bash
sha256sum pironi-VERSION-linux-x64.tar.gz
awk '{print $1}' pironi-VERSION-linux-x64.tar.gz.sha256
```

There is currently no published Linux ARM64 portable bundle. It can be built
locally by running `scripts/package-unix.sh` with an ARM64 JDK 25; do not use an
x64 release on an ARM64 machine.

## Harness baseline: Pironi vs Hermes

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

## Requirements for building from source

- OpenJDK/JDK 25;
- Maven 3.9+;
- Ollama for local models, or an OpenAI-compatible API endpoint.

## Build and test from source

```bash
mvn clean verify
java -jar target/pironi-0.1.0-SNAPSHOT.jar --help
```

The shaded executable JAR is written to:

```text
target/pironi-0.1.0-SNAPSHOT.jar
```

Every push and pull request runs a clean Java 25 build on Ubuntu, Windows and
macOS. The Linux job also runs the PTY-based interactive terminal regression.
The workflows are in `.github/workflows/ci.yml` and
`.github/workflows/release.yml`.

### Build a portable bundle locally

After `mvn clean package`, create a Linux or macOS bundle with the active JDK:

```bash
scripts/package-unix.sh \
  target/pironi-0.1.0-SNAPSHOT.jar build/release dev linux-x64
```

On Windows PowerShell:

```powershell
scripts/package-windows.ps1 `
  -Jar target/pironi-0.1.0-SNAPSHOT.jar `
  -Output build/release `
  -Version dev `
  -Platform windows-x64
```

Both scripts use `jlink`, include `pironi.jar`, the platform launcher and a
trimmed Java runtime, then write an archive and `.sha256` checksum. Pushing a
tag such as `v0.2.0` builds all three bundles and attaches them to the GitHub
release. The release workflow can also be run manually without publishing a
GitHub release.

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

- workspace: the current working directory;
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
available. `/capabilities` shows the authoritative live tool/platform report,
while `/doctor` checks Java, workspace permissions, shell and network access.

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

Pironi requests the same strict JSON Schema from Ollama and schema-capable
OpenAI-compatible providers. If an endpoint explicitly rejects `json_schema`,
Pironi retries once with `json_object` and remembers that decision for the
remaining process lifetime. DeepSeek empty-content responses are retried up to
two additional times; completed tools are not executed again.

The dedicated DeepSeek profile starts directly with `json_object`, because the
current endpoint reports `json_schema` as unavailable. Internal summarization
and context-compression calls use plain text and omit `response_format`
entirely; only agent protocol turns require structured output.

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
- `--approval auto` permits scoped mutating tool calls without prompting. With
  the default workspace shell scope, `run_command` is omitted because a lexical
  shell guard is not an operating-system sandbox.
- `--approval read-only` denies mutating tool calls.
- `--activity auto` is a convenience override for `--approval auto`, including
  when `--approval ask` also appears in the command.

Interactive sessions are persisted under `~/.pironi/sessions`. `/sessions`
lists them, `/resume [ID]` schedules a saved checkpoint for the next request,
and `/compress now` schedules semantic compression for the next request.
If there is not yet any older eligible history, the request remains visibly
pending instead of being discarded.
Model-reported prompt and output token counts drive the compression threshold.
`/new` closes the current session and starts a clean one without restarting
Pironi or changing the selected model.

Skills live under `~/.pironi/skills/NAME/SKILL.md`. Pironi performs a small
lexical metadata scan and automatically loads at most one unambiguous relevant
skill; it does not use embeddings or load every skill body. `/skill NAME`
selects one explicitly, `/skill off` suppresses automatic selection for the
session, and `/skill auto` enables it again.

Learning is reviewable and never writes durable memory immediately.
`propose_skill` can prepare an ephemeral structured draft only after an
explicit first-party correction describes a reusable workflow. `/save-skill NAME`
is the manual equivalent for the last verified turn. Review with
`/pending-skill`, persist with `/accept-skill`, or discard with
`/reject-skill`. `/new`, `/resume`, process exit and rejection clear a
pending draft. Existing skills are never silently overwritten:
`/accept-skill replace` requires the reviewed original hash still to match and
archives the previous version before an atomic replacement.

Skills are procedural guidance below identity, privacy, project rules and
approval policy. They cannot authorize external messages, change `SOUL.md` or
`USER.md`, preserve temporary location as identity, or bypass confirmation.

Portable bundles include a `team-lead` skill for safe Planner/Teams CSV
reconciliation, status reporting, calendar drafts, and Office Open XML
artifacts. Activate it with `/skill team-lead`. Pironi can create `.xlsx`,
`.docx`, and `.pptx` through native Java tools without Microsoft Office,
administrator rights, COM automation, PowerShell generators, or downloads.

`run_command` is considered mutating because arbitrary shell commands can
change files or external state. In auto mode, opt in explicitly with an exact
`--allow-tools` list containing `run_command`, or deliberately broaden
`--shell-scope`; otherwise use `ask` when shell access is needed. Prefer
`read-only` when evaluating a new model.

## Context files and privacy

Pironi can load layered context without requiring one monolithic memory file:

- `SOUL.md` for agent identity and behavior;
- `USER.md` for personal preferences;
- `CLAUDE.md` for project instructions.

For `SOUL.md` and `USER.md`, Pironi starts with
`%USERPROFILE%\.pironi` on Windows or `~/.pironi` on Unix, then loads the
configured `--pironi-home`, followed by `.pironi` directories from the user
home down to the selected workspace. `CLAUDE.md` is loaded along the directory
lineage from the user home down to the workspace. When a workspace is outside
the user home, its directory cascade contains only that workspace; the global
and configured personal homes still participate. Layers are ordered global to
local, and the nearer layer wins when instructions conflict. Duplicate paths
are loaded only once.

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
--task-file PATH
--max-turns N
--context N
--max-output-tokens N
--timeout-seconds N
--trace PATH
--pironi-home PATH
--personal-context auto|allow|deny
--status auto|always|never
--deny-tools NAME,NAME
--allow-tools NAME,NAME
--shell-scope workspace|user|unrestricted
--search-roots PATH,PATH
```

The default trace is `WORKSPACE/.pironi/trace.jsonl`. A trace can contain
prompts, model responses, tool arguments, and tool output. Treat it as
potentially sensitive and do not commit it. The active trace path is hidden
from `list_files`, `find_files`, and `read_file`, even when a custom trace is
placed directly in the workspace.
Unknown CLI options fail startup and close misspellings include a suggestion.
`--task-file` reads the complete task as UTF-8 and cannot be combined with
`--task`; it is the recommended one-shot input on Windows when the prompt
contains Unicode or shell-sensitive characters.
`--allow-tools` enables exactly the named tools and cannot be combined with
`--deny-tools`. `find_files` searches only the roots configured by
`--search-roots`; its default root is the workspace. `list_files` and
`read_file` accept absolute directories/files below these roots. Writing tools
remain restricted to the workspace.

## Live status

In an interactive terminal Pironi reserves the bottom terminal row for a
persistent status line in the interactive terminal:

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
rate from `eval_count / eval_duration`, for example `│ 19.99 tok/s`. This is
the provider's model-generation rate. It updates
when the next response completes and resets after a model change. Providers
without eval timing metadata use `completion_tokens / request duration` and
mark the end-to-end estimate with `~`, for example `│ ~12.40 tok/s`.
The approximate cloud value includes network and provider latency and therefore
must not be compared directly with Ollama's generation-only value. Use the
reproducible wall-time benchmark above when comparing complete harnesses.

Normal output and the ANSI status frame share one `PrintStream`; every frame is
written as one operation to prevent status fragments from being interleaved
inside model output in IDE terminals.

- `--status auto` enables the reserved line only for a supported interactive
  console. It stays off on Windows Terminal because its scroll-region behavior
  can merge the status with wrapped agent output.
- `--status always` forces it on, which is useful in some IDE terminals.
- `--status never` disables it.

Operational activity is printed as a persistent, concise log above the live
status line: the selected skill, tool action and safe target, completion state,
and duration. For example, Pironi may print `• Reading README.md`,
`• Editing src/App.java with apply_patch`, or
`• Running curl https://example.com/data`. This log never displays model
reasoning, file contents, HTTP credentials or query parameters, complete command
arguments, API keys, or passwords.

Status uses `stderr`; the final answer remains clean on `stdout`.
On `/exit`, Pironi clears the reserved row and restores the normal terminal
scroll region.

Ollama responses use NDJSON transport streaming, but Pironi buffers the protocol
envelope until the complete JSON has parsed and automatic verification has
passed. This prevents a plausible answer from being shown before a truncated or
malformed envelope is detected. In an interactive session, the validated final
answer is then rendered progressively in small paced chunks for readability;
this is presentation streaming after validation, not exposure of unvalidated
provider tokens. One-shot mode writes the validated answer directly. `thought`
and raw protocol JSON stay hidden, and the completed answer is retained for
tracing and conversation memory without being printed twice.

Interactive conversation colors distinguish speakers: user input is cyan and
validated agent answers are green. Status, memory and approval messages retain
their neutral UI colors. JLine generates the terminal-specific escape sequences.

Use `/theme` to customize User input, Agent answer, Tool/skill activity, System
messages, and Errors. The keyboard picker uses Up/Down, previews ten colors,
saves with Enter, and cancels with Esc. `Reset defaults` restores the original
palette. The selection is stored in `PIRONI_HOME/theme.properties` and applies
to later sessions without changing prompts, agent identity, or trace content.

## Current tool set

- `list_files`
- `read_file`
- `inspect_file`
- `system_info`
- `write_file`
- `apply_patch`
- `move_file`
- `rollback_checkpoint`
- `find_files`
- `http_get`
- `csv_merge`
- `csv_sanitize`
- `ics_create`
- `xlsx_create`
- `docx_create`
- `pptx_create`
- `run_command`

Writing and moving paths are restricted to the selected workspace. Read-only
tools may also use configured `--search-roots`. `apply_patch` requires
one exact old-text match, shows a diff before approval, creates a checkpoint,
and writes atomically. Before a final answer after a mutation, Pironi
automatically runs the configured verification command or detects Maven/Gradle.
`list_files` accepts workspace-relative directories and absolute directories
below configured search roots. It omits common generated/private directories such as `.git`,
`.pironi`, `.idea`, `target`, `build`, `.gradle`, and `node_modules`.
`--deny-tools` removes exact tool names from this set and rejects unknown names
at startup. It does not restrict filesystem access through `run_command`.
`run_command` requires mutation approval when present, but does not trigger a
second automatic build after a successful command. It is absent from default
auto/workspace sessions. Source changes must use
`apply_patch`, which does trigger automatic verification.
Commands and automatic verification inherit the Java runtime that launched
Pironi: `JAVA_HOME` is set from the active JVM and its `bin` directory is
prepended to `PATH`. Starting Pironi with Java 25 therefore also makes Maven
use Java 25, even when the parent shell still defaults to Java 17.
Shell commands use Bash on Linux/macOS and `cmd.exe` on Windows. Wrapper-based
verification selects `mvnw`/`gradlew` on Unix and their `.cmd`/`.bat`
counterparts on Windows.

`--shell-scope workspace` is the default and rejects explicit absolute paths,
parent traversal, home shortcuts, directory-changing commands and `sudo`.
This is a conservative lexical guardrail, not an operating-system sandbox;
prefer `read_file`, `find_files`, `move_file` and the other scoped tools.
`--shell-scope user` permits paths available to the current OS user but still
blocks `sudo`; `unrestricted` removes the lexical checks and must be used only
in an isolated environment.

`move_file` operates only inside the workspace, refuses overwrite, creates
checkpoints and verifies SHA-256 after the move. `write_file` creates missing
parent directories safely. `find_files` does not follow
results outside an allowed real root and bounds visited files, result count and
content size.

Before a multi-tool batch starts, Pironi validates known file-tool arguments
and approval decisions. A failed preflight prevents the other calls from
running. Runtime mixtures are labelled `partial_success` in the model feedback,
so the agent cannot safely describe the whole batch as successful.
The regression suite covers external-root reads, hidden traces, Unicode output
paths, parent creation, batch preflight, and symlink escape rejection for scoped
file reads.

`http_get` retrieves bounded current information directly through Java and
does not depend on curl or a shell. It accepts HTTPS only, does not follow
redirects, rejects credentials and local/private/link-local destinations, and
caps response bodies at 64 KiB. The generated Runtime capabilities section
tells the model which tools, shell and network path are actually available.

### Microsoft Office documents on Windows

`xlsx_create`, `docx_create`, and `pptx_create` build real Office Open XML ZIP
packages directly in Java. They need neither desktop Office nor COM, scripts,
downloads, administrator rights, or a network connection. Every generated
package is reopened and every XML/relationship part is parsed before success is
reported. These tools create new structured documents; editing arbitrary
existing Office layouts remains outside their scope. `csv_sanitize` protects
formula-like cells before Excel import, `csv_merge` combines compatible exports,
and `ics_create` produces calendar drafts without contacting Outlook or Teams.

Provider finish reasons and the effective `json_schema`/`json_object` response
format are written to the JSONL trace. `length`/`max_tokens`
responses and unexpected end-of-input JSON are classified as truncation and
receive a targeted repair request for a shorter complete JSON object.
The provider response schema requires exactly `thought`, `toolCalls`, and
`finalAnswer`, rejects unknown envelope/tool-call fields, and is shared by the
Ollama and OpenAI-compatible clients.
Successful trace events also include the number of provider request attempts
and any fallback source; failed requests produce a `model_error` event.

After a successful mutating file tool, Pironi owns automatic verification. The
agent prompt tells the model not to repeat the same build through `run_command`
unless automatic verification fails and targeted diagnostics are needed.

For Ollama, Pironi currently sends `think: false`, uses the requested context
window and caps generation with `--max-output-tokens`.
