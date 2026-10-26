# CLAUDE.md

Guidance for Claude Code (claude.ai/code) in this repository. `AGENTS.md`
holds the same for other harnesses: edit both.

## What this repository is

rdm-spec: the spec is the product, the code is regenerated from it (the Löyly
method). Since the cutover (2026-10-26) there is no Python implementation;
see `README.md` and `dhf/decisions/adr-0002-spec-first-regeneration.md`.

## Rules

- **Behaviour changes in the spec, never in the code.** Edit `dhf/spec/`
  (feature files, `model.edn`, `events.edn`, `glossary.md`, `README.md`),
  then `rdm-spec stamp`, `rdm-spec views` if the model changed, and
  `rdm-spec check`. `src/` and `test/` are replaced by the next regeneration.
- **The spec's conventions are `dhf/spec/README.md`;** its vocabulary is
  `dhf/spec/glossary.md`. Where the spec is silent, follow
  `dhf/wiki/pages/loyly-method.md`.
- **A cut is tagged `spec-N`** on a merged spec change; a regeneration reads
  that cut and nothing else.
- **The way of working is kept as skills,** not spec: `dhf/harness/skills/`
  (builder, grader, cycle, and the stack's skill), with the rules that hold
  them and what enforces each in `dhf/harness/invariants.md`. One builder per
  cycle; builders and graders stay in their rooms; graders never read
  `test/`.
- **The oracle is held still:** `dhf/harness/oracle/oracle.clj` is written by
  hand and never regenerated; its goldens never enter the repository
  (`oracle.edn` in an artifact pinned by digest in `dhf/harness/oracle/pin.edn`),
  and a golden changes only by `bb dhf/harness/oracle/oracle.clj record --reason R`,
  naming with `--changes` every existing golden it changes.
- **Never re-record a golden to get a green.** A golden holds nothing the spec
  does not state.
- **The regulated profile** is on only where a project's configuration holds
  `:regulated`.

## Build and test

```bash
rdm-spec check                                   # the gate on every spec change
RESULTS_DIR="$(mktemp -d)" bb test.clj           # the implementation's tests (scenarios and unit tests)
bb test.clj --no-unit dhf/spec/<context>         # one context's scenarios
```

Stack: `dhf/harness/stack.md`. The installed `rdm-spec` is the last accepted
build; it reads the oracle from the local artifact layout `ORACLE_LAYOUT`
until the registry is reachable.
