# The clean room

## The fence is physical

A regeneration that can read last week's answer is not regenerating
anything, so the fence builds a clean-room clone and proves it: every prior
implementation and every measurement is deleted from git history, not only
from the working tree.[^fence] A weaker probe once passed while the previous
implementation was still reachable from an older commit; the probe now checks
the object graph, not the working tree.[^failures]

## What the room keeps

In Löyly the room keeps the docs and the harness, less the goldens and the
cycle records, because inside the app directory the agent could write the
very thing that judges it.[^modules] In this harness a room for a scope keeps
the cut and the implementation of every component outside the scope, and
removes the scope's implementation and every measurement. What is kept is
listed in the fence's output, so the builder's writes outside it can be
refused.

## What leaks past a fence

Three leaks rdm-spec met, each now a rule here:

- **A cited source that holds an implementation.** A builder that can reach
  the network can fetch the answer from the citation; the fence removes paths
  from a room's history and cannot remove a public repository. The decision
  was to remove that source and to fence the others' code paths.[^h14] Here
  `raw/` holds no implementation of anything the harness regenerates
  (`raw/README.md`, "Never enters").
- **The old implementation in the tree.** When the harness was both judge and
  product, a room fenced by slice kept every other component's code, and that
  code was the old implementation of the thing being rebuilt. The source had
  to leave the tree.[^adr6] Here the harness is never regenerated, so a room
  never holds an implementation of itself.
- **Goldens reachable from the room.** Until rdm-spec's fifth cycle the
  allow-list let the goldens into rooms.[^retro] Here the goldens live in the
  harness's own oracle, outside any product, and a room is a clone of a
  product's cut.

## Who may write where

Builders and graders stay in their rooms. rdm-spec's history has strays at
the filesystem root written by builders, and tests that leaked thousands of
temporary folders, both found by a person rather than a tool.[^retro] The
clean room is a directory, not a sandbox; the scope fence (`docs/wow/harness.md`)
refuses what it can see, and what it cannot see is still a person's finding.

[^fence]: `raw/loyly/harness/README.md@17d68d2`, "The pieces, in pipeline order", `fence.sh`.
[^failures]: `raw/loyly/harness/README.md@17d68d2`, "The failure modes this shape was learned from".
[^modules]: `raw/loyly/harness/README.md@17d68d2`, "The three modules, and the check that they have not forked", on `probe.sh`.
[^h14]: `raw/rdm/storm/2026-10-24-rdm.md@a4641ae`, "Hotspots", H14.
[^adr6]: `raw/rdm/decisions/adr-0006-wasm-components.md@a4641ae`, "Consequences".
[^retro]: `raw/rdm/retrospective-01-12.md@a4641ae`, section 1 (the pipeline) and section 2, "goldens leakable", "writes outside room", "test fixture leak".
