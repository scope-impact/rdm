# Source: skallinen/loyly

- **Repository:** https://github.com/skallinen/loyly
- **Pinned at:** `17d68d2`, 2026-09-08, "A fourth repository joined the ring,
  so this one has to know about it"
- **What it is:** a sauna-slot booking system that exists to demonstrate a
  method: documentation is the source of truth, code is a build product, and
  deleting and regenerating the code is a measurement of the spec.

## Evidence for

| Topic | Where | Used by |
|---|---|---|
| Docs as the single source of truth; code as a reading of them | `README.md`, "The story this repo is built around" | UN-001 |
| The checklist derived from the spec, ids stamped once and never computed from wording | `README.md`, "What to steal" 1; `harness/README.md`, the pieces table | UN-001, UN-008 |
| The fence: a clean room with every implementation and measurement removed from history, proved | `README.md`, "4. Regenerate"; `harness/README.md` | UN-006 |
| "Done" ratified by a script the claimant does not control | `README.md`, "What to steal" 2; `harness/README.md`, "The gate's boot budget" | UN-007 |
| The instrument refuses to record what it did not measure; unreached items fail; both directions, never the net | `README.md`, "What to steal" 4; `harness/README.md`; `harness/spec.feature` | UN-008 |
| The incidents each guard was learned from | `harness/README.md`, "The failure modes this shape was learned from" | the guards cited in the spec |
| A slice is done only when every scenario in its spec passes against the real interface | `docs/plan/01-booking-core/plan.md`, "Acceptance" | UN-004 |
| Learning not written into the spec dies with the implementation | `README.md`, "What to steal" 5 | the cut and the fence |
| Raw sources, immutable and cited; a domain wiki whose every claim footnotes a source | `README.md`, "How to read the repo"; `docs/wow/invariants.md`, INV-1 | this directory |
| The builder may be an agent or a person, and the machinery treats both the same | `README.md`, "The tutorial: one full loop"; "4. Regenerate" | UN-012 |
| A commit is refused when a check the commit stages fails, through the pre-commit hook | `harness/README.md`, "What makes the checks run" | UN-002 |
| The slice is built in any stack; only the gate's contract is fixed | `README.md`, "1. Build from the docs" | UN-003 |
| Scoring by grading each checklist item against the running build, however you grade, independent of the builder's suite | `README.md`, "3. Measure"; `harness/README.md`, "Walkthrough" | UN-008 |
| Every scenario has a test, written before the code | `docs/wow/invariants.md`, INV-5 | ratification |
| Harness against instrument: what feeds the agent and what reports to a person | `harness/README.md`, "The idea in five lines" | ratification and measurement as separate bounded contexts |

## Fenced

This source's implementation is fenced: never cited. No source checkout ever
reaches a clean room. The paths are in `sources.edn`, `:loyly`.
