# Ticket 03: Checkouts: each citation's path checked at its pin

Slice: `03-ground-the-spec` (`plan.md`, Scope In: "citations resolved to a
pin and a path"); `sources.feature`, Rule "A citation resolves to a source, a
pin and a path", and `decisions.md`: checkouts are machine-local, given on
the command line, one directory, one checkout per source named by its id.
Builds on ticket 02.

## What it delivers

knowledge/sources, with `--sources DIR` (ED-06):

- DIR resolved against the project's root, or absolute; a directory that does
  not exist: cannot-run, exit 2, saying so, nothing on standard output.
- Per source, its checkout `DIR/<id>`: none there, its citations unchecked,
  the warning naming the missing checkout.
- Per checkout, one git process run through `rdm:host/process` in
  `DIR/<id>`, asking every `commit:path` of its citations: a path there at
  its pin resolves; one not there is `citation-unresolved`, naming the path
  and the pin and where it is cited (the need, spec-0135; the page and the
  footnote once ticket 04 reads them, spec-0128).
- Every fenced path of a source with a checkout, at each pin, asked the same
  way: one that matches nothing is `source-fenced-missing`, naming the
  source, the pin and the path; `.` and a retired source are not asked
  (ED-11).
- A fixture's checkout stands for the commit its scenario names (ED-14).

## Scenarios it satisfies

- `@spec-0171` Source checkouts are given as one directory, one checkout per source, `sources.feature`
- `@spec-0124` A citation resolves when its path exists at its pin, `sources.feature`
- `@spec-0125` A path that does not exist at its pin fails, `sources.feature`
- `@spec-0135` A user need citing a source that does not resolve fails the check, `sources.feature`
- `@spec-0138` A fenced path that matches nothing at a pin fails the check, `sources.feature`

5 scenarios, verified in cycle 4.

## Components and interfaces it touches

knowledge/sources (`knowledge-sources`, `sources-stage`), the host
(`rdm:host/process`, `run`).

## Done when

knowledge/sources' unit tests cover the questions asked of a checkout and
the reading of git's answers with the host stood in for (present, missing,
a missing checkout, git that cannot start); `sh wasm/rdm-spec check
--sources DIR` with checkouts of this repository's sources at their pins
names no finding; `wasm/test.sh` writes a passing result for each scenario
above; earlier tickets' results unchanged.
