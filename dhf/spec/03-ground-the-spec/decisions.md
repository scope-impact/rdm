# Decisions: slice 03-ground-the-spec

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `sources.feature`, Rule: A source is indexed with its repository, its pins and its record

The chain of citations has to bottom out in something no generation step
can quietly rewrite (loyly:docs/wow/invariants.md@17d68d2, INV-1).

### `sources.feature`, Scenario: A source that drops a pin it had fails the check (spec-0121)

A pin is dropped when the index as it stands lacks a pin some earlier
commit of it had. Cycle 21 read the rule as "no commit may ever have
lacked one", and so failed this repository, whose index once deleted rdm
(967c1de) and then restored it as retired, its pin kept (spec owner,
spec-26).

### `sources.feature`, Scenario: A source is retired, never deleted, and nothing may cite it (spec-0236)

A source removed on purpose once read as dropped pins (cycle 04).

### `sources.feature`, Rule: A citation resolves to a source, a pin and a path

Checkouts are machine-local, so they are never committed: they are given on
the command line, a directory holding one checkout per source, named by
the source's id.

### `sources.feature`, Scenario: A citation's finding names the line the citation is on (spec-0402)

spec-32, from cycle 24's grader: with several needs citing one string,
every finding named the line where the string first appeared.

### `sources.feature`, Rule: A source's implementation is fenced

Decided by the event storm's hotspot H14 (dhf/storm/2026-10-24-rdm.md): a
source holding an implementation of what is regenerated hands the builder
the answer. What a source says may be cited; how it is built may not.

### `sources.feature`, Rule: A need's citations resolve

A need's citations are resolved here, with the wiki's, so one component
reports them (cycle 01, C20). The spec check keeps only "a need cites no
source".

### `wiki.feature`, Feature: Wiki

The wiki sits between the raw sources and the spec: the domain, distilled,
every claim footnoted to a source (loyly:README.md@17d68d2, "How to
read the repo"; docs/wiki/index.md@17d68d2).

### `wiki.feature`, Rule: Every claim in the wiki cites a source

The wiki is dhf/wiki/index.md and the pages under dhf/wiki/pages/. The
index carries the rules and the list of pages; the rules below govern pages.

### `wiki.feature`, Rule: The wiki never names an implementation

A wiki that names a stack has become a description of one implementation,
and a regeneration from it is no longer a measurement (loyly:docs/wow/invariants.md@17d68d2, INV-2).



## Expected decisions (planned for the component build, ADR 0006)

Written in the plan phase of the component build (ADR 0006,
`dhf/decisions/adr-0006-wasm-components.md`, cycle 4 of its build order;
`docs/wow/harness.md`, "The planner"): each question a builder of this slice
would otherwise decide alone, with its options and the planner's
recommendation. The spec owner settles each before the build, or turns it
into a scenario. The tickets (`ticket-01` to `ticket-05`) cite them as ED-NN;
slice 01's, 02's and 04's are cited as "slice 01's ED-NN" and so on. The
architecture is in `dhf/wit/` (`knowledge-sources.wit`, `knowledge-wiki.wit`,
`slice-03.wit`, `slice-03.wac`, and the interface `knowledge-events` in
`events.wit`). Slice 02's settlements hold here: a stage not built is a
warning (its ED-02), the events are values handed to each stage as EDN (its
ED-04), the specs' declaration is refused before any stage runs (its ED-03).

### ED-01: Scenarios whose task another slice builds

As slices 01, 02 and 04, settled there: no bridge. This slice's components
carry every scenario they own in any slice, 26 in all: every scenario of
`sources` and `wiki` sits in this slice. Every When runs check, built by
cycle 2, and no Then needs a finding of a stage a later cycle builds (the
paths and plugins stages, not built, are warnings).

- A. None pending (`pending.md`): all 26 verified in cycle 4.

Recommendation: A.

### ED-02: How the slice's stages reach check

Slice 02's check imports one interface per later stage (`check-stages`), the
not-built stand-in answering each until its slice is built. This slice builds
two of them: sources (the first stage of a run) and the wiki.

- A. As slice 04's ED-02: knowledge/sources exports `sources-stage` and
  knowledge/wiki exports `wiki-stage`, each the stage interface written out as
  `check-stages` writes it; the slice exports both, and `rdm-spec.wac` wires
  them by name into slice 02's instance, ahead of the stand-in's spread
  (ticket 01 gives the lines). The slice imports no other slice. Composed by
  the planner against stand-ins: the slice alone, and the command with slices
  02, 03 and 04.
- B. A stages component of its own, not in the model, adapting both.

Recommendation: A.

### ED-03: Who exports the slice's tasks

Every slice exports one `tasks` (ADR 0006, "How the command reaches the
slices"), but this slice runs no task of its own: its rules run within check
(spec-0176's table names no task for sources or the wiki).

- A. knowledge/sources exports `tasks` beside its own interface: no entries,
  and `run` answers none for every task (`tasks.wit`: "none for a slice that
  runs no task of its own"); `rdm-spec.wac` wires it to the command's import
  `ground-the-spec`.
- B. The slice exports no `tasks`, and the not-built stand-in keeps answering
  the command's import: against the ADR's "every slice exports one".

Recommendation: A.

### ED-04: Where KnowledgeIndexed's record lives

`events.wit` holds one record per event. Its interface `events` is exported
by spec/parser (slice 02's ED-07); adding a record to it changes an
interface slice 02 is built to, and a slice-03 interface using its types
imports slice 02, which imports this slice's stages: a cycle no composition
can wire.

- A. An interface of its own in `events.wit`, `knowledge-events`: the record
  `knowledge-indexed` and the types it carries (its own `revision` and
  `finding`, written as `events` writes them), exported by knowledge/sources
  beside its interface, as spec/parser exports `events`. Another slice reads
  the event as EDN.
- B. The record inside `knowledge-sources`: the catalogue then lives in two
  files.

Recommendation: A, and the same for each later slice that publishes an event
(slices 05, 06, 10): one events interface per publishing slice.

### ED-05: Who resolves and reports the wiki's footnotes

This slice's decisions say a need's citations are resolved "with the wiki's,
so one component reports them" (cycle 01, C20); the model has knowledge/wiki
"Resolves footnotes with" knowledge/sources, and KnowledgeIndexed carries
every citation, the wiki's included. spec-0128 is the wiki's scenario.

- A. As the installed release: knowledge/sources reads the wiki's footnotes,
  resolves every citation, the needs' and the wiki's, and reports each that
  does not resolve as its finding (naming the page and the footnote,
  spec-0128); the wiki checks its pages, index and words, and imports nothing
  of sources. The model's relationship then describes no call.
- B. The wiki imports `knowledge-sources` and resolves and reports its own
  footnotes; sources still resolves them for the event: each footnote resolved
  twice, and its finding moves from the sources stage's place to the wiki's.

Recommendation: A. The spec owner keeps the relationship as written, or
rewords it ("Resolves the footnotes of", from sources to the wiki) in the
next spec change.

### ED-06: How sources reaches git and the checkouts

The component build cannot start a process, and the checkouts given by
`--sources DIR` lie outside the project's folder, the only folder the host
gives a component; the index's history is in the project's repository. Both
go through the host's `rdm:host/process` (ADR 0006, "A small native host").

- A. Through the host, git and nothing else but `test -d`: `test -d DIR` (DIR
  relative to the project's root, or absolute) before anything is read, a
  missing directory cannot-run, exit 2, saying so; per source, `test -d
  DIR/<id>`, a missing checkout leaving its citations unchecked with the
  reason; per checkout, one `git -C DIR/<id> cat-file --batch-check`, every
  `commit:path` question of its citations and fenced paths on its standard
  input, a line ending `missing` an absent path; the history as ED-12, in the
  project's root. git that cannot be started is cannot-run, exit 2. A time
  limit of 120 seconds a process.
- B. One `git cat-file -e` per citation, as the installed release: a process
  per citation.
- C. The host preopens the `--sources` directory: the host reads check's
  arguments, logic in the host.

Recommendation: A.

### ED-07: What the stages print, and their rule names

No contract scenario states a result of the sources or wiki stages; three
rule names and one warning are stated (`source-no-repository`,
`source-no-record`, `citation-malformed`; `citation-unchecked`). `dhf/spec/README.md`:
a RULE is the owning component's choice unless a scenario names it.

- A. The installed release's: neither stage adds to check's result (reported
  none); the sources stage publishes KnowledgeIndexed and reports nothing
  else; the rules `sources-unreadable`, `source-no-pin`,
  `source-no-repository`, `source-no-record`, `source-pin-dropped`,
  `source-fenced-missing`, `citation-malformed`, `citation-unknown-source`,
  `citation-retired`, `citation-unpinned`, `citation-fenced`,
  `citation-unresolved`, the warning `citation-unchecked`; the wiki's
  `wiki-uncited`, `wiki-unindexed`, `wiki-page-missing`, `wiki-no-index`,
  `stack-word`. Each message names what its scenario says it names (the
  source, the pin and the commit that dropped it, the citation and where it
  is cited: the need's id, or the page and `[^label]`).
- B. Only the rules the scenarios name, the others chosen anew.

Recommendation: A, so the oracle's goldens of the installed release hold.

### ED-08: The host's standard interfaces in this slice's worlds

As slice 02's ED-08, settled there: the cycle that builds the slice adds to
`ground-the-spec` and each component world the one line slice 01's worlds
carry, in the version they carry then; nothing else in this slice's
architecture changes.

Recommendation: A, as slices 02 and 04.

### ED-09: The order of the sources stage's findings, and the warnings in the event

The installed release reports the citations' findings in an order no rule
fixes (a map's), and publishes its warnings among KnowledgeIndexed's
`:findings`, whose shape (`{:file :line :rule :message}`) has no warning flag.

- A. The index's findings (unreadable, then each source in the index's
  order: no pin, no repository, no record), dropped pins, fenced paths
  matching nothing, then the citations: every spec's needs file in line
  order, then the wiki's pages in path order, each in line order. The event's
  `:findings` hold the same lines, warnings included, without a flag (as
  ClausesIndexed says of its own); the stage's answer flags the warnings.
- B. The release's order, whatever it is: not reproducible.
- C. The event's `:findings` without the warnings.

Recommendation: A. The oracle compares a finding's head only, the heads
sorted ("the order of findings is stated nowhere",
`dhf/harness/oracle/oracle.clj`), so A moves no golden.

### ED-10: Where the stages find the raw sources, the needs and the wiki

A stage is given check's arguments, the folder and the run's events; the
specs' declaration is spec/parser's, which this slice cannot import (ED-02).
The sources stage runs first, before SpecParsed is published.

- A. As slice 04's ED-04: the configuration read with the kernel's shared
  code (slice 01's ED-13): `:raw-dir` (its `sources.edn`, and the records
  beside it), every spec's needs (each entry of `:specs`, its folder's
  `needs.edn`, else `:needs`), read as text so each citation keeps its line
  (spec-0402); `:wiki-dir`; `:stack-words`. A project with no sources index
  has no source: every citation names an unknown source.
- B. The needs from SpecParsed: the sources stage would have to run after the
  parser, against spec-0166's order (KnowledgeIndexed first).

Recommendation: A.

### ED-11: A citation, a pin and a fenced path, exactly

The glossary writes a citation `<source>:<path>@<commit>`; no scenario says
how a citation is found in a footnote, how short a commit may be, or how a
fenced path covers another.

- A. The installed release's: a citation is a source id (a letter or digit,
  then letters, digits, `.`, `_`, `-`), `:`, a path without spaces, quotes or
  `@`, `@` and 7 to 40 hex digits; a need's source that is not one, whole, is
  `citation-malformed`; a footnote is a line starting `[^label]:`, every
  citation on it read (the index's footnotes too, and `:pages` lists every
  `.md` file of the wiki, the index included). A commit matches a pin when
  either starts with the other, case ignored. A fenced path covers itself
  and everything under it, `.` the whole source. A retired source's
  citations fail before its pins are looked at; its fenced paths are not
  checked against a checkout.
- B. Full 40-digit commits only: every citation in this repository fails.

Recommendation: A.

### ED-12: A dropped pin, read from the index's history

spec-0121, spec-0365 and spec-0236, and this slice's decisions: a pin is
dropped when the index as it stands lacks a pin some earlier commit of it
had.

- A. The installed release's: every commit that changed the index, oldest
  first (`git log --format=%H --reverse -- <index>`), each version read
  (`git cat-file --batch`, the index's path from the repository's root, the
  folder joined to it), a version that cannot be read as EDN taken as empty;
  the working tree last. For each source of the index as it stands, each pin
  an earlier version had that it lacks is one finding, naming the source,
  the pin, and the first commit after the last version that had it (its
  short id, or "the working tree"). A source removed and restored with its
  pins drops nothing (spec-0365); a source removed and not restored is not
  in the index as it stands, so it is reported by nothing (a question for
  the spec owner). Outside a git repository, no history.
- B. Any commit lacking a pin a former one had: cycle 21's misreading,
  refused by this slice's decisions.

Recommendation: A.

### ED-13: What the wiki reads

The decisions say the wiki is `index.md` and the pages under `pages/`; the
scenarios do not say what a footnote, a link of the index, or a word is.

- A. The installed release's: a page is every `.md` file of the wiki's
  folder but `index.md`; a page with no footnote definition (`[^label]:` at
  a line's start) is `wiki-uncited`; the index's links are Markdown links to
  a relative `.md` target (an anchor ignored, a URL not a link), each
  resolved against the wiki's folder; a page none links is `wiki-unindexed`
  (its file, no line); a link with no page is `wiki-page-missing` (the
  index's line); pages and no index is `wiki-no-index`; a stack word is a
  configured word as a whole word, case kept, one finding per word and line,
  on the pages and the index. No wiki folder: no finding.
- B. Pages only under `pages/`, a stricter index.

Recommendation: A. The skeleton's wiki (an index with an empty list of
pages) passes, so spec-0090 holds.

### ED-14: A commit a scenario names, in a fixture

Scenarios name real commits (`loyly` at `17d68d2`, `innolitics-rdm` at
`f21b508`); a fixture's checkout cannot make a commit with a given id, and
the harness says a scenario's commits are its fixture's.

- A. As the installed release's tests: a checkout the fixture makes is a
  repository holding the scenario's files in one commit, and that commit
  stands for the one the scenario names: the fixture's index pins it and its
  citations cite it. A grader does the same (`docs/wow/harness.md`, "The
  grader": set up what the Given says).
- B. Real clones of the sources, fetched by the tests: the network in every
  run.

Recommendation: A, and the spec owner may reword the Givens ("pinned at the
checkout's commit") in the next spec change.
