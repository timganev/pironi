# A tuned large-context Ollama profile

Moved out of README: a runbook from one dated tuning session, one model, and an
external toolchain that is not part of Pironi.


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

