# Raw sources

What the spec is distilled from. A raw source is never edited and never
copied in: it is recorded here and cited.

- **Each source has a record**, a Markdown file beside this one, saying in
  prose what it is and what it is evidence for.
- **Each source is indexed in `sources.edn`**: its repository, every commit it
  was ever cited at (its pins; a pin is never dropped) and its fenced paths.
- **A citation is `<source>:<path>@<commit>`**, the commit one of the
  source's pins.
- **A source's implementation is fenced**: a path that holds an
  implementation of what is regenerated is listed under `:fenced` and never
  cited.
- **A source is retired, never deleted**: it keeps its pins, and nothing may
  cite it.
- **Where a source and the spec disagree, the source wins** and the spec is
  the bug.
