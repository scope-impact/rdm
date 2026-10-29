# 0001: The harness is rewritten in a clean room

> Status: Accepted, 2026-10-29, by the spec owner (Sathish Narayanan).

## Context

rdm-spec (`scope-impact/rdm`, branch `claude/elegant-feynman-wx2ayn`) ran 27
regeneration cycles in five days. Its retrospectives found that it had
drifted from its intent: a pluggable, opinionated harness after Löyly that
forces an event storm and regenerates by container and component. By cycle
27 it was 430 scenarios across 32 components, it regenerated itself, its
opinions had become configuration keys, and the storm was "never part of the
spec".

## Decision

- **A clean room.** An orphan branch of `scope-impact/rdm`
  (`clean-room/harness`) that shares no history with the old branch. What
  entered is the allow-list in `raw/README.md`, pinned by commit; no
  implementation of rdm-spec or of Löyly's tools did.
- **One language, held still.** The harness is one Rust binary, written by
  hand, never regenerated (INV-3).
- **The first experiment is Löyly's sauna booking** (`raw/loyly/` at
  `17d68d2`), once the harness runs end to end on a toy spec. rdm-spec built it
  twice (11/11), so the results compare.
- **The intent is the first file** (`README.md`), and every need and rule
  cites it. This is the rule rdm-spec lacked.
- **One format: TOML**, for what a person writes (the configuration, the
  storm, the model, the cycle record) and for what a task prints and writes
  (checklist, verdicts, score, verified). TOML is the language's own
  configuration format, reads and diffs well by hand, and allows comments.
  Not YAML (its parser is unmaintained in this language, and `no`, `03` and
  `1.0` parse as things they are not); not EDN, which was a leftover of
  rdm-spec's stack and has no mature parser here; and not a second format
  for the tools' outputs, because two formats are two byte contracts to
  hold still. TOML has no null, so an absent value is an absent key.

## What was learned, and the rule each lesson became

| Mistake, where recorded | Cost | Rule |
|---|---|---|
| The harness regenerated itself (retrospective 01-12, section 3) | every judge change was a crisis: goldens re-recorded (c19), an install rolled back (c21), a build accepted on evidence (c25), goldens skipped at an install (c23) | INV-3 |
| Scopes picked by hand, then widened by hand (c04, c23) | wrong rebuilds, then a whole slice machinery to compensate | INV-4 |
| Many builders in parallel (c01 to c12) | merge seams in eight cycles | one builder per cycle (way of working) |
| Outputs described, not pinned (c08, c11) | builders invented shapes; graders judged consistency, not truth | every output something reads is pinned in `harness/spec.feature` |
| Opinions became options (spec-19, spec-25, `:contexts-relate-by`, the storm) | the intent eroded with no single wrong step | INV-7 |
| The intent was never written | nothing to hold each loosening to | `README.md` first; needs cite it |
| No gate on the harness repository after the cutover | 234 commits verified by hand or by an agent | INV-9 |
| The integrator accepted plans "as recommended under the goal" (slices 03, 04, 06, 07; cycle 12 never graded) | the owner's decisions made for them | INV-10 |
| Held-still things drifted at transitions (goldens, the oracle, the WIT) | every expensive recovery | INV-8 |

## Consequences

- The harness is small: Löyly's eleven pieces plus `storm`, `model` and
  `scopes`. What rdm-spec also had (bus, sources, wiki, site, regulated
  profile, several specs, project in a folder, trace, release, observe) is
  out of the core; each returns as a plugin when a second project asks.
- The WebAssembly component work from rdm-spec's cycles 26 and 27 is not
  carried. The idea survives as a possible product under test, never as the
  harness.
- The old branch stays as history and is not merged to `main`.

## Addendum, 2026-10-29: the gate reads results in Allure's model

The spec owner: every tool's results are instrumented in the tool-neutral
Allure result model, and the tools are not limited (a Gherkin runner, a
browser driver with its trace, a unit framework, a script). Carried into
INV-11, the way of working ("Results are tool-neutral"), the builder's
brief, the glossary (result, status, label, attachment, tool) and verify's
scenarios in `harness/spec.feature`: the runner's exit code is never read.
