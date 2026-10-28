# Ticket 04: The wiki: every page indexed and cited, its footnotes resolved, no implementation named

Slice: `03-ground-the-spec` (`plan.md`, Scope In: "The wiki: every claim
cited, one index holding every page, no implementation named");
`wiki.feature` and `decisions.md`, why its rules are as they are; the
glossary's "Domain wiki". Builds on ticket 03.

## What it delivers

- knowledge/sources reads the wiki's footnotes (ED-05, ED-11): every
  citation on a footnote definition of every `.md` file of the wiki's
  folder, resolved as the needs' are (tickets 02 and 03), each finding naming
  the page, the footnote and why it did not resolve; KnowledgeIndexed's
  `:pages` and its footnote citations (`:need` none).
- knowledge/wiki, `knowledge-wiki.check` and `wiki-stage.run` (ED-13): a
  page with no footnote, `wiki-uncited`, naming the page; a page the index
  does not link, `wiki-unindexed`, naming the page; an index link with no
  page, `wiki-page-missing`, naming the entry; pages and no index,
  `wiki-no-index`; each line of a page or the index naming a configured
  stack word, `stack-word`, naming the page, the line and the word. Rule
  names as ED-07 settles.

## Scenarios it satisfies

- `@spec-0127` A page with no footnote fails the check, `wiki.feature`
- `@spec-0128` A footnote whose citation does not resolve fails the check, `wiki.feature`
- `@spec-0129` A page whose footnotes all resolve passes, `wiki.feature`
- `@spec-0130` A page the index does not list fails the check, `wiki.feature`
- `@spec-0131` An index entry with no page fails the check, `wiki.feature`
- `@spec-0132` A page naming a configured stack word fails the check, `wiki.feature`

6 scenarios, verified in cycle 4.

## Components and interfaces it touches

knowledge/wiki (`knowledge-wiki`, `wiki-stage`), knowledge/sources
(`knowledge-sources`, its footnote reading).

## Done when

knowledge/wiki's unit tests cover each rule above, a link with an anchor, a
URL, a stack word inside a longer word (not one), and a project with no wiki
folder; knowledge/sources' unit tests cover footnote definitions with one
and several citations; `sh wasm/rdm-spec check` in this repository names no
wiki finding, and on a skeleton made by `rdm-spec init` none either
(spec-0090); `wasm/test.sh` writes a passing result for each scenario above;
earlier tickets' results unchanged.
