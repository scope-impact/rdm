# Ticket 02: Citations: read from the needs, resolved to a source, a pin and a fence

Slice: `03-ground-the-spec` (`plan.md`, Scope In: "citations resolved to a
pin and a path; a source's implementation fenced"); `sources.feature`, Rules
"A citation resolves to a source, a pin and a path", "A source's
implementation is fenced", "Citations are read from the needs and the wiki,
nothing else", "A need's citations resolve", and `decisions.md`, why each
exists. Builds on ticket 01. No checkout yet: every citation that names a
recorded, pinned, unfenced path is unchecked here; ticket 03 checks it.

## What it delivers

knowledge/sources, `knowledge-sources.index`, over every spec's needs
(ED-10), each citation on the line it is written (spec-0402), and nothing
else of the spec (spec-0199):

- A need's source that is not a citation: `citation-malformed`, naming the
  need and the source (ED-11).
- A citation, in order (ED-11): a source not recorded,
  `citation-unknown-source`; a retired source, `citation-retired`, saying its
  source is retired; a commit that is none of the source's pins,
  `citation-unpinned`, naming the commit and the pins; a path a fenced path
  covers, `citation-fenced`, naming the fenced path or directory; each naming
  the citation and where it is cited.
- No `--sources`: every other citation unchecked, the warning
  `citation-unchecked` naming the citation, where it is cited and that no
  `--sources` was given; a warning, so check still passes (spec-0187).
- KnowledgeIndexed's `:citations`, each with its status and reasons; the
  findings in ED-09's order.

## Scenarios it satisfies

- `@spec-0240` A need's source that is not a citation fails the check, `sources.feature`
- `@spec-0402` A citation's finding names the line the citation is on, `sources.feature`
- `@spec-0122` A citation naming no recorded source fails, `sources.feature`
- `@spec-0123` A citation at a commit that is not one of its source's pins fails, `sources.feature`
- `@spec-0126` Without a checkout, a citation's path is reported unchecked, never passed, `sources.feature`
- `@spec-0187` A citation that could not be checked passes the check with a warning, `sources.feature`
- `@spec-0136` A citation of a fenced path fails, `sources.feature`
- `@spec-0137` A citation inside a fenced directory fails, `sources.feature`
- `@spec-0199` Only needs.edn and the wiki's pages are scanned for citations, `sources.feature`

9 scenarios, verified in cycle 4.

## Components and interfaces it touches

knowledge/sources (`knowledge-sources`, `sources-stage`), the event record
`knowledge-indexed`.

## Done when

knowledge/sources' unit tests cover the citation's grammar, a pin matched
short or long, a fenced path and directory and `.`, each status and each
rule above, and two needs citing one string on two lines;
`sh wasm/rdm-spec check` in this repository names no citation finding but
`citation-unchecked` warnings; `wasm/test.sh` writes a passing result for
each scenario above; ticket 01's results unchanged.
