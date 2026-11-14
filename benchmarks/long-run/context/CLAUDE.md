# Workspace instructions (benchmark fixture)

This file is fixed on purpose. The long-run benchmark loads it instead of the user's own
`~/CLAUDE.md`, so a change to that file never moves the baseline. It holds no personal data; it
stands in for a typical set of working rules of similar size and shape.

## Language

- Answer in Bulgarian unless the user writes in another language.
- Code, identifiers, comments and commit messages are in English.
- Keep technical terms (API, JSON, commit, unittest) as they are; do not translate them.

## How to work

- Read before you change: open the file, look at its tests, then edit.
- Prefer the smallest change that solves the task. No drive-by refactors, renames or reformatting.
- One task at a time. When a step is done, say what changed and how it was checked.
- When the task is ambiguous in a way that would change the result, state the readings and pick
  the most literal one; say which one you picked.
- Do not invent file contents, numbers or command output. If you did not read or run it, say so.

## Files

- Stay inside the workspace. Never write outside it.
- Do not delete or overwrite files the task did not mention.
- Keep file names exactly as the task gives them, including case and extension.
- Write text files as UTF-8 with a trailing newline.
- When moving files, check afterwards that the source is gone and the target exists.

## Python projects

- Run tests from the project root: `python3 -m unittest discover -t . -s <package>`.
- A failing test is information. Fix the code, not the test, unless the task says otherwise.
- New functions get tests next to the existing ones, in the same style.
- Use only the standard library unless the project already depends on something else.

## Data and logs

- When counting, count from the data, not from a sample of it. Prefer a script over reading by eye
  when there are more than a few dozen lines.
- When a result goes into a JSON file, keep the keys that are already there.
- Report numbers exactly as computed; do not round unless asked.

## Shell

- Prefer read-only commands for inspection (`ls`, `grep`, `wc`, `head`).
- Quote paths that may contain spaces.
- Do not install packages, start servers or change system settings.

## Git

- Initialise a repository only when the task asks for it.
- Commit with the exact message the task gives.
- Never rewrite history, never push.

## Answers

- Lead with the result, then the reasoning, then the risks.
- Short paragraphs or bullet points; no filler, no praise, no motivational phrases.
- When asked to answer with a single value, answer with that value only.
- When asked to remember something for later, keep it for the rest of the session.
