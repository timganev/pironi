# Design notes

Why the tools behave the way they do. Moved out of README, which is a manual:
each of these paragraphs records a failure that shaped a decision, and that is
worth keeping and not worth reading before a first run.

## Tools, shell and scopes

Reading and writing both follow the workspace, which `/workspace PATH` moves;
directories taken earlier in the session stay readable, and `--search-roots`
adds read-only roots at startup. `apply_patch` requires
one exact old-text match, shows a diff before approval, creates a checkpoint,
and writes atomically. When the text is not found it names the closest line and
the first character that differs, with both codepoints: a Latin `d` typed for a
Cyrillic `д`, or a non-breaking space read as an ordinary one, is invisible on
screen, and "oldText was not found" sends the model to rewrite the whole file
instead of retrying the line. It matches the file's own line endings: a Git for Windows
checkout is CRLF while a model writes LF, and an exact match then failed on text
that was plainly there and reported it as missing. Before a final answer after a
mutation, Pironi runs the verification command given by `--verify-command`, and
nothing at all when none was given. Detecting a build from a `pom.xml` or a
`gradlew` was worse than it sounds: every mutation paid for the project's whole
test suite, including ones no test can judge.
`list_files` accepts workspace-relative directories and absolute directories
below configured search roots. It omits common generated/private directories such as `.git`,
`.pironi`, `.idea`, `target`, `build`, `.gradle`, and `node_modules`. When a
listing is too large to send, it returns a profile of the tree instead of the
first N paths: file count and total size, counts by extension, the largest
directories, and the newest and largest files. An alphabetical prefix answers
none of the questions that matter in an unfamiliar tree.
`--deny-tools` removes exact tool names from this set and rejects unknown names
at startup. It does not restrict filesystem access through `run_command`.
`run_command` requires mutation approval when present, but does not trigger a
second automatic build after a successful command. It is absent from default
auto/workspace sessions. Source changes must use
`apply_patch`, which does trigger automatic verification.

`app_control` provides allowlisted desktop actions without exposing arbitrary
shell input. Supported applications are Firefox, Chrome, Edge, Obsidian,
VS Code, Notepad, Slack, the system image viewer, and system Settings;
supported actions are `status`, `launch`, `new-window`,
and graceful `close`. It never force-terminates a process. If graceful close
does not complete within five seconds, Pironi reports the remaining processes
instead of escalating. Availability still depends on an active desktop session
and an executable in a known platform location.

`process_inspect` provides a bounded process inventory sorted by resident memory,
accumulated CPU, uptime, or PID. It deliberately excludes command-line arguments
and environment data because those often contain secrets. `process_control`
targets one PID plus the exact executable name observed by `process_inspect`,
guards against PID reuse, refuses critical/system/Pironi processes, and always
requires explicit interactive approval—even with `--approval auto`. Normal GUI
application closure should use `app_control`; `force-kill` is only for a confirmed
disposable or unresponsive process after the impact is understood.

Commands and automatic verification inherit the Java runtime that launched
Pironi: `JAVA_HOME` is set from the active JVM and its `bin` directory is
prepended to `PATH`. Starting Pironi with Java 25 therefore also makes Maven
use Java 25, even when the parent shell still defaults to Java 17.
Shell commands use Bash on Linux/macOS and `cmd.exe` on Windows. Wrapper-based
verification selects `mvnw`/`gradlew` on Unix and their `.cmd`/`.bat`
counterparts on Windows.

Bash runs without `pipefail`. It reported the honest failure of `false | tail -1`,
but `producer | head` is the ordinary way to sample a large output and exits 141
(SIGPIPE) under `pipefail`; inside a substitution such as
`f=$(find . | head -1) && ...` that status short-circuits the whole command line,
so nothing after it runs. The cost of that outweighed the benefit.

A command that runs out of time is stopped, and what it printed up to that point
comes back with the timeout notice. Killing the process closes its output stream,
so the bytes already read are still good - a command stopped halfway through
18,000 files is worth far more as the half it finished than as the bare fact that
it timed out.

A non-zero exit is reported as `exitCode=N` with the cause named when the shell
is reporting a signal: 137 (SIGKILL, usually out of memory), 139 (SIGSEGV), 143
(SIGTERM), 130 (SIGINT), 141 (SIGPIPE), plus 126 and 127. Codes 1-125 belong to
the program that produced them and are passed through, with one note added when
such a code comes with output: some programs answer through the exit status.
`grep` exits 1 when it matches nothing and `diff` exits 1 when files differ,
having printed exactly what was asked for; read as failures, two of three
"failed" calls in one run were commands that had worked. The status itself is
left alone, because sometimes it really is a failure. On cmd.exe
none of that applies - an exit code there is whatever the program chose, so
naming a cause would invent one; only 9009 is named, as cmd's own "command not
found".

`--shell-scope workspace` is the default and rejects explicit absolute paths,
parent traversal, home shortcuts, directory-changing commands and `sudo`.
A slash counts as a path only when a path character follows it, so `sed -n
'/^## x/p'` and `awk '/^## /{print}'` are patterns rather than references to the
root directory - refusing those sent one run to read a 143 KB file whole instead
of cutting out the section it wanted, at ten times the tokens.
This is a conservative lexical guardrail, not an operating-system sandbox;
prefer `read_file`, `find_files`, `move_file` and the other scoped tools.
`--shell-scope user` permits paths available to the current OS user but still
blocks elevation; `unrestricted` removes the lexical checks and must be used only
in an isolated environment.

The guard reads the same escapes in either shell's spelling: UNC paths
(`\\server\share`), the Windows environment expansions (`%USERPROFILE%`,
`%APPDATA%`, `%TEMP%` and the rest) and `runas`/`gsudo` alongside `sudo`. The
Unix absolute-path rule applies only where "/" starts a path - on Windows it read
every cmd switch as one, so `dir /b`, `findstr /s` and `tasklist /FO CSV` were
all rejected under the workspace scope.

`move_file` operates only inside the workspace, refuses overwrite, creates
checkpoints and verifies SHA-256 after the move. There is no delete tool:
deleting goes through `run_command` (`rm`, `del`), which asks before it runs.
`write_file` creates missing parent directories safely, and checkpoints a file
before replacing it - `apply_patch` and `move_file` always did, so the safe
tools could be undone and the destructive one could not. It also says when it
replaced a file that already existed: rewriting a whole file to change a few
lines is the expensive way to edit, and one run spent 4,001 of its 9,952 output
tokens retyping the same script three times. The result points at `apply_patch`
instead. `find_files` does not follow
results outside an allowed real root and bounds visited files, result count and
content size. When it stops at either bound it says so, instead of reporting no
matches: "did not find it" and "stopped looking" are different answers, and a
search that gives up quietly invites the wrong conclusion. Results are relative
to the search root, which is named once at the top: paths under one root share a
long prefix, and repeating it cost 23,000 characters for a hundred results in a
real tree.

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

