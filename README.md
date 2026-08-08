# 🔩 Pironi

Pironi is a small Java 25 coding-agent harness for Ollama and
OpenAI-compatible chat-completions APIs. It provides a bounded agent loop,
workspace-scoped tools, explicit mutation approvals, and JSONL traces without
requiring Python or a separate runtime.

This is an early implementation. Use it in a disposable workspace until the
tool and approval behavior has been tested for your use case.

## Download and run

The current binaries are published on the
[Pironi v0.1.1 release page](https://github.com/timganev/pironi/releases/tag/v0.1.1).
Choose the setup that matches the machine:

| Platform                       | Recommended package                          |              Admin rights | Java/Maven required         | Start command              |
| ------------------------------ | -------------------------------------------- | ------------------------: | --------------------------- | -------------------------- |
| Windows 11, locked-down laptop | Windows x64 portable ZIP                     |                        No | Neither; Java 25 is bundled | `pironi.bat ...`           |
| macOS                          | macOS portable archive or standalone JAR     |                        No | None for portable archive   | `./pironi ...`             |
| Linux, full access             | Linux portable archive or standalone JAR     |                        No | None for portable archive   | `./pironi ...`             |
| Development machine            | Source checkout                              |          Depends on setup | JDK 25 and Maven 3.9+       | `mvn clean verify`         |

The standalone JAR needs Java 25. Portable archives are larger because they
contain a trimmed Java 25 runtime. Maven is needed only when building or
testing Pironi from source. Starting with the next tagged release, CI produces
`windows-x64.zip`, `linux-x64.tar.gz`, and `macos-arm64.tar.gz` bundles with
SHA-256 checksum files.

### Windows 11 without admin rights

This is the recommended setup for a managed Windows x64 laptop where software
cannot be installed:

1. Download the latest Windows x64 ZIP from the
   [Pironi releases page](https://github.com/timganev/pironi/releases/latest).
2. Extract the complete ZIP into a writable location such as
   `Documents\Pironi`. Keep `pironi.bat`, `pironi.jar`, and `runtime` together.
3. Open the extracted folder, click the address bar, enter `cmd`, and press
   Enter to open Command Prompt in the correct directory.
4. Set the API key for that window and start Pironi:

```bat
set DEEPSEEK_API_KEY=your-key

pironi.bat ^
  --provider deepseek ^
  --model deepseek-v4-flash ^
  --workspace "%USERPROFILE%\Documents\project" ^
  --context 131072 ^
  --max-output-tokens 16384 ^
  --max-turns 30 ^
  --activity auto
```

No installer, elevation, Maven, `JAVA_HOME`, or system `PATH` change is used.
The key set with `set` exists only in the current Command Prompt process; set
it again in each new window. `--activity auto` permits scoped workspace-changing
tool calls without confirmation, so use it only in a project that may be
modified. For safety, it does not expose `run_command` with the default
`--shell-scope workspace`.

When no override is supplied, `pironi.bat` writes only below
`%USERPROFILE%\Documents\PironiWorkspace`, permits read-only file searches below
`%USERPROFILE%` (including Downloads, Desktop, and Documents), and uses the
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

Then set the API key and launch the downloaded JAR:

```bat
set "DEEPSEEK_API_KEY=your-key"

java -jar "%USERPROFILE%\Downloads\pironi-0.1.0-SNAPSHOT.jar" ^
  --provider deepseek ^
  --model deepseek-v4-flash ^
  --workspace "%USERPROFILE%\Documents\project" ^
  --context 131072 ^
  --max-output-tokens 16384 ^
  --max-turns 30 ^
  --activity auto
```

These `set` commands affect only the current Command Prompt process and
disappear when the window is closed. They are unnecessary when using the
Windows portable ZIP, because `pironi.bat` starts the bundled runtime directly.

### macOS

Install a Java 25 JDK, for example Eclipse Temurin through Homebrew, and verify
that the active runtime is version 25:

```bash
brew install --cask temurin@25
java -version
```

Download the standalone JAR and run it. Maven is not required:

```bash
mkdir -p "$HOME/Applications/Pironi"
curl -fL \
  https://github.com/timganev/pironi/releases/download/v0.1.1/pironi-0.1.0-SNAPSHOT.jar \
  -o "$HOME/Applications/Pironi/pironi.jar"

export DEEPSEEK_API_KEY='your-key'
java -jar "$HOME/Applications/Pironi/pironi.jar" \
  --provider deepseek \
  --model deepseek-v4-flash \
  --workspace "$HOME/Projects/project" \
  --context 131072 \
  --max-output-tokens 16384 \
  --max-turns 30 \
  --activity auto
```

Apple Silicon and Intel Macs use the same JAR; the installed Java runtime must
match the Mac architecture. Without Homebrew, install Java 25 from
[Eclipse Temurin](https://adoptium.net/temurin/releases/?version=25).

### Linux with full administrative access

This distribution-independent example installs the latest Temurin 25 x64 JRE
under `/opt`, Pironi under `/opt/pironi`, and a launcher under
`/usr/local/bin`. Maven is not installed because the release JAR does not need
it:

```bash
curl -fL \
  'https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jre/hotspot/normal/eclipse' \
  -o /tmp/temurin-25-jre.tar.gz

sudo install -d /opt/pironi-java-25 /opt/pironi
sudo tar -xzf /tmp/temurin-25-jre.tar.gz \
  -C /opt/pironi-java-25 --strip-components=1
sudo curl -fL \
  https://github.com/timganev/pironi/releases/download/v0.1.1/pironi-0.1.0-SNAPSHOT.jar \
  -o /opt/pironi/pironi.jar

sudo ln -sf /opt/pironi-java-25/bin/java /usr/local/bin/pironi-java
printf '%s\n' '#!/bin/sh' \
  'exec /usr/local/bin/pironi-java -jar /opt/pironi/pironi.jar "$@"' \
  | sudo tee /usr/local/bin/pironi >/dev/null
sudo chmod 755 /usr/local/bin/pironi
```

Start Pironi with local Ollama:

```bash
pironi \
  --provider ollama \
  --model MODEL \
  --workspace /path/to/project \
  --approval ask
```

Or use DeepSeek:

```bash
export DEEPSEEK_API_KEY='your-key'
pironi \
  --provider deepseek \
  --model deepseek-v4-flash \
  --workspace /path/to/project \
  --activity auto
```

For Linux ARM64, replace `x64` with `aarch64` in the Adoptium API URL.

## Harness baseline: Pironi vs Hermes

This baseline was repeated on 2026-08-07 on the same machine, against the same
direct DeepSeek endpoint, with `deepseek-v4-flash`. Each harness was started
three times in a new one-shot session with the exact prompt `хей`, an empty
workspace, and no requested project or personal context. The table reports the
median, followed by the observed range where useful. Wall time includes process
startup, request latency, generation, parsing, and shutdown.

| Metric | Pironi | Hermes |
| --- | ---: | ---: |
| Harness | Pironi | Hermes |
| Representative result | `Привет! Чем могу помочь?` | `Хей, Тим. На линия съм. С какво да помогна?` |
| Wall time | 3.57 s (3.57–3.67) | 6.22 s (5.84–6.42) |
| Peak RSS | 132 MiB (129–132) | 127 MiB (126–127) |
| Uncached input | 1,134 | 16,335 (15,567–21,967) |
| Cache read | not reported | 5,632 (0–6,400) |
| Effective prompt | 1,134 | 21,967 |
| Output | 125 (116–141) | 94 (78–98) |
| Total tokens | 1,259 | 22,061 |
| API calls | 1 | 1 |

For this deliberately tiny request, Pironi's median was 2.65 seconds (about
43%) faster and its effective prompt was about 19.4 times smaller. Its total
token count was about 94% lower. The result measures harness overhead, not
general model quality: Hermes supplied a much larger built-in prompt/tool
environment and consistently produced the more appropriate Bulgarian response,
while all three Pironi runs interpreted `хей` as Russian. Output-token
accounting may include provider-side reasoning differently, so effective input
is the more useful token comparison.

Pironi has become heavier at the prompt level since the 2026-07-24 baseline:
its input grew from 472 to 1,134 tokens, or about 2.4 times, as capabilities and
protocol guidance were added. That increase is real, but it did not make Pironi
heavier than Hermes in this test. Pironi still sent about 95% fewer effective
prompt tokens and finished sooner. Process memory tells a different story: the
Java process used about 4% more peak RSS than Hermes at the median. Latency is
provider-sensitive, so the small three-run sample should be treated as a local
startup-and-request baseline, not a general performance ranking.

The measured software footprints differ substantially:

| Metric                   |                                                 Pironi |                                                                              Hermes |
| ------------------------ | -----------------------------------------------------: | ----------------------------------------------------------------------------------: |
| Harness                  |                                Pironi `0.1.0-SNAPSHOT` |                                                                     Hermes `0.17.0` |
| Implementation           |                                                Java 25 |                                      Python 3.11 core with TypeScript UI components |
| Measured local footprint |                         3.8 MiB shaded JAR (4.0 MB) |                                                    7.1 GiB installation (7.6 GB) |
| Source-only footprint    | about 471 KiB, excluding `.git`, `target`, `build`, IDE data, and traces | about 122 MiB, excluding `.git`, `venv`, `node_modules`, build, and generated output |

The footprint figures are not perfectly symmetrical. Pironi's JAR does not
bundle the Java runtime, while the measured Hermes checkout contains a 5.4 GB
Python virtual environment and 1.2 GB `node_modules` directory. Hermes also
implements gateways, messaging integrations, plugins, skills, browser and
desktop UI features that Pironi intentionally does not provide.

Commands used for each baseline run (with a fresh directory per repetition):

```bash
# Hermes: safe mode disables custom rules, memory, plugins, and MCP servers.
hermes --safe-mode \
  --provider deepseek \
  --model deepseek-v4-flash \
  -z 'хей'

# Pironi: empty workspace, no personal context, no TUI/status output.
java -jar target/pironi-0.1.0-SNAPSHOT.jar \
  --provider deepseek \
  --model deepseek-v4-flash \
  --no-interactive \
  --workspace /tmp/empty-pironi-workspace \
  --pironi-home /tmp/empty-pironi-home \
  --trace /tmp/empty-pironi-workspace/trace.jsonl \
  --approval read-only \
  --status never \
  --personal-context deny \
  --max-turns 2 \
  --task 'хей'
```

Hermes usage came from sessions `20260807_140129_bf87c4`,
`20260807_140135_9eccc7`, and `20260807_140141_10f715` in its local session
database. Pironi usage came from the corresponding `model_response` trace
events. `/usr/bin/time` supplied wall time and peak RSS. Minion remains excluded
from the comparison at the user's request.

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
Model-reported prompt and output token counts drive the compression threshold.
`/new` closes the current session and starts a clean one without restarting
Pironi or changing the selected model.

Skills live under `~/.pironi/skills/NAME/SKILL.md`. `/skill NAME` activates a
skill for subsequent agent prompts, `/skill off` clears it, and
`/save-skill NAME` saves the last successfully completed turn as a reusable
skill.

`run_command` is considered mutating because arbitrary shell commands can
change files or external state. In auto mode, opt in explicitly with an exact
`--allow-tools` list containing `run_command`, or deliberately broaden
`--shell-scope`; otherwise use `ask` when shell access is needed. Prefer
`read-only` when evaluating a new model.

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
`--allow-tools` enables exactly the named tools and cannot be combined with
`--deny-tools`. `find_files` searches only the roots configured by
`--search-roots`; its default root is the workspace. `read_file` accepts the
absolute paths returned by `find_files` as read-only inputs, while all writing
tools remain restricted to the workspace.

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

Ollama responses use NDJSON transport streaming, but Pironi buffers the protocol
envelope and prints `finalAnswer` only after the complete JSON has parsed and
automatic verification has passed. This prevents a plausible answer from being
shown before a truncated or malformed envelope is detected. `thought` and raw
protocol JSON stay hidden, and the completed answer is retained for tracing and
conversation memory without being printed twice.

Interactive conversation colors distinguish speakers: user input is cyan and
streamed agent answers are green. Status, memory and approval messages retain
their neutral UI colors. JLine generates the terminal-specific escape sequences.

## Current tool set

- `list_files`
- `read_file`
- `write_file`
- `apply_patch`
- `move_file`
- `rollback_checkpoint`
- `find_files`
- `http_get`
- `run_command`

All file paths are restricted to the selected workspace. `apply_patch` requires
one exact old-text match, shows a diff before approval, creates a checkpoint,
and writes atomically. Before a final answer after a mutation, Pironi
automatically runs the configured verification command or detects Maven/Gradle.
`list_files` omits common generated/private directories such as `.git`,
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
