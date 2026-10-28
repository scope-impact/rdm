# Ticket 05: Pins never dropped: the index's history, and retired sources

Slice: `03-ground-the-spec` (`plan.md`, Scope In: "Sources: each indexed
with its repository, pins and record"); `sources.feature`, Rule "A source is
indexed with its repository, its pins and its record", and `decisions.md`,
why spec-0121 and spec-0236 exist; the glossary's "Retired source". Builds on
ticket 04 (spec-0236 cites from a wiki page).

## What it delivers

knowledge/sources, the index's history read through git run by the host in
the project's root (ED-06, ED-12): every commit that changed the index,
oldest first, each version read, the working tree last; for each source of
the index as it stands, each pin an earlier version had that it lacks,
`source-pin-dropped`, naming the source, the pin and the commit that dropped
it. A pin dropped and later restored is not dropped; a retired source keeps
its pins, so retiring it drops none, and every citation of it fails as
`citation-retired` (ticket 02). Outside a git repository, no history and no
finding of it.

## Scenarios it satisfies

- `@spec-0121` A source that drops a pin it had fails the check, `sources.feature`
- `@spec-0365` A pin dropped and later restored is not a dropped pin, `sources.feature`
- `@spec-0236` A source is retired, never deleted, and nothing may cite it, `sources.feature`

3 scenarios, verified in cycle 4.

## Components and interfaces it touches

knowledge/sources (`knowledge-sources`, `sources-stage`), the host
(`rdm:host/process`, `run`).

## Done when

knowledge/sources' unit tests cover dropped pins over histories as values (a
pin replaced, dropped then restored, retired with its pins, a version that is
not EDN), and git's answers read with the host stood in for;
`sh wasm/rdm-spec check` in this repository (whose index once removed rdm
and restored it retired, 967c1de) names no dropped pin; `wasm/test.sh`
writes a passing result for each scenario above; earlier tickets' results
unchanged.
