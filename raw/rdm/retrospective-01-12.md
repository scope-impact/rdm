# Retrospective, cycles 01–12: where rdm-spec failed, compared with Löyly

Written 2026-10-26, after cycle 12 was ratified and before it was graded. It
compares each failure the cycles recorded (`dhf/cycles/NN/proposed-spec-changes.md`)
with how Löyly's harness handles the same thing (`loyly:harness/README.md@17d68d2`,
`loyly:harness/spec.feature@17d68d2`).

## 1. The pipeline, with each failure marked where it happened

```
                 LÖYLY                                  rdm-spec (cycles 01–12)
                 ─────                                  ───────────────────────
 ┌──────────────┐                              ┌──────────────┐
 │  SPEC        │ every tool's stdout/stderr   │  SPEC        │ ✗ outputs described as decisions, not shapes
 │              │ pinned "exactly" + shape     │              │     → c08 oracle layout invented
 │              │ contracts per file           │              │     → c11 scorer/scoreboard 25 diffs
 └──────┬───────┘                              │              │ ✗ shared step text owned by nobody
        │                                      │              │     → c01–c08 integration fixes
        ▼                                      └──────┬───────┘
 ┌──────────────┐                              ┌──────▼───────┐
 │  CUT         │ whole app, every cycle       │  CUT+SCOPES  │ ✗ c04 scope picked by hand, one missed
 │              │                              │              │     → `scopes` task built (c05)
 └──────┬───────┘                              │              │ ✗ scoped cycles hid unrebuilt code
        │                                      │              │     for 6 cycles (scorer/scoreboard)
        ▼                                      └──────┬───────┘
 ┌──────────────┐                              ┌──────▼───────┐
 │  FENCE       │ physical clone, proved;      │  FENCE       │ ✗ allow-list let harness/ (goldens)
 │              │ harness/oracle fenced        │              │     into rooms until c05
 └──────┬───────┘                              └──────┬───────┘
        ▼                                      ┌──────▼───────┐
 ┌──────────────┐                              │  BUILD       │ ✗ 5–6 builders in parallel rooms
 │  BUILD       │ one regeneration             │  (parallel)  │     → seams at merge (ctx keys,
 │              │                              │              │       result shapes, helper APIs)
 └──────┬───────┘                              │              │ ✗ fixtures read the room, not a fixture
        │                                      │              │     → c06 0231, c07 0239, c09 :oracle
        │                                      │              │ ✗ builders/graders wrote outside rooms
        │                                      │              │     → /o /e /layout /op at filesystem root
        │                                      │              │ ✗ tests leaked ~12,500 /tmp dirs (to c11)
        ▼                                      └──────┬───────┘
 ┌──────────────┐                              ┌──────▼───────┐
 │  GATE        │ verify.sh; check.sh runs     │  RATIFY      │ ~ old Python hook blocked a commit (c11)
 │  + ORACLE    │ the oracle on every commit:  │  + ORACLE    │ ✗ c08 build: empty oracle = pass w/ warning
 │              │ "silence looks like a pass"  │   (gate)     │ ✗ c05 InstrumentJudged event → replaced
 └──────┬───────┘                              │              │     by a gate (Löyly's check.sh model)
        ▼                                      └──────┬───────┘
 ┌──────────────┐                              ┌──────▼───────┐
 │  GRADE       │ instrument reports to a      │  GRADE       │ ✗ vacuous passes: spec-0100 (no check
 │  + SCORE     │ person, never the loop;      │  + SCORE     │     that the command ran), 0255, 0099
 │              │ unreached item = fail        │              │ ✗ graders' own goldens = consistency,
 └──────┬───────┘                              │              │     not truth (c11 oracle grading)
        ▼                                      └──────┬───────┘
 ┌──────────────┐                              ┌──────▼───────┐
 │  SCOREBOARD  │ both directions, never net   │  SCOREBOARD  │ ✓ held: 0 worse c03–c10, 1 worse c11
 └──────────────┘                              └──────────────┘
                                               ┌──────────────┐
         (no regulated profile in Löyly)       │  AUDIT       │ ✗ 4 rounds, one way to mislead each time:
                                               │  (rdm-only)  │   c08 untracked → c09 uncommitted spec
                                               │              │   → c10 config → c11 ignored files
                                               └──────────────┘
```

## 2. Failures by class: what Löyly already had a rule for

```
 class of failure                    cycles hit         Löyly had the rule?   fixed in
 ─────────────────────────────────── ────────────────── ───────────────────── ─────────
 unpinned output shapes              c08, c11, c12      YES  "stdout is exactly"  spec-13,16
 shared steps / merge seams          c01–c09, c12       n/a  (one builder)        README c08–c10
 fixture reads the room              c06, c07, c09      n/a                       README c08
 scope picking hides code            c04, c05–c10       YES  whole app each cycle  scopes + c11
 goldens reachable / leakable        ≤c05               YES  harness/oracle fenced  c05, c09 OCI
 silent pass (empty/no-run judge)    c08, c11 (0100)    YES  "silence = pass" risk  spec-13,16
 audit not one commit                c08–c11            n/a  (rdm-only)            spec-12..16
 writes outside rooms                c06, c11, others   n/a                       open (person)
 test fixture leak                   c01–c10            n/a                       c11 kernel
```

```
 how often each class bit (✗ = one cycle it surfaced)

 merge seams          ✗✗✗✗✗✗✗✗  (8)   ███████████████████████████
 audit loopholes      ✗✗✗✗      (4)   █████████████
 unpinned outputs     ✗✗✗       (3)   ██████████
 fixture reads room   ✗✗✗       (3)   ██████████
 writes outside room  ✗✗✗       (3)   ██████████
 silent passes        ✗✗        (2)   ██████
 scope hides code     ✗✗        (2)   ██████
 goldens leakable     ✗         (1)   ███
```

## 3. The root cause, in one picture

```
              LÖYLY                                rdm-spec
   ┌───────────────────────────┐        ┌───────────────────────────┐
   │ regenerate:   the APP     │        │ regenerate:   app AND     │
   │ keep stable:  INSTRUMENT  │        │               INSTRUMENT  │ ← harder setting
   │ spec pins:    BYTES       │        │ spec pinned:  DECISIONS   │ ← gap
   │ builders:     ONE         │        │ builders:     MANY,       │ ← seams
   │                           │        │               PARALLEL    │
   └───────────────────────────┘        └───────────────────────────┘
          little to guess                   much to guess, nothing
                                            in the room to check the guess
```

Most of rdm-spec's failures came from three deliberate departures from Löyly:

- rebuilding the instrument as well as the app
- rebuilding in scoped cycles
- running many builders in parallel

Each one let builders guess something Löyly's spec states exactly. Where we did
follow Löyly, it held: the gate, re-recording goldens only with a stated reason,
and counting both directions with a moved denominator said aloud. That is how
the cycle 11 drift was caught, not shipped.

The audit loopholes are rdm-spec's own: Löyly has no regulated profile. They
took four rounds because each fix closed one way in (untracked, then
uncommitted spec, then config, then ignored files) rather than the principle.
spec-16's rule, "every file it reads is HEAD's tree", is that principle.

## What follows for the next spec change

1. **Any output something else reads is stated exactly, as EDN,** in a contract
   scenario: Löyly's "stdout is exactly", adapted to a command that prints on
   one line. Still loose:
   - status's result
   - scopes' result
   - `ratified.edn` and its tree hash
   - the oracle's result
   - the scoreboard's moved-denominator and bootstrap forms
   - the kernel's exported step helpers
2. **A golden holds nothing the spec does not state.**
3. **A whole-project cycle every few scoped cycles,** since a scoped cycle hides
   whatever it does not rebuild.
4. **Writes outside a room** are still open: the clean room is a directory, not
   a sandbox, and the strays at the filesystem root need a person to remove.
