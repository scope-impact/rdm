# rdm-spec domain wiki

The domain of rdm-spec, distilled from its raw sources (`dhf/raw/`). The wiki
sits between the sources and the spec: a user need or a scenario restates what
a wiki page says, and a wiki page restates what a source says. Nothing in the
spec should rest on nothing.

## Rules

- **Every claim footnotes the source it came from**, as
  `<source>:<path>@<commit>` with the section, for example:

  > The checklist is derived from the spec, never written by hand.[^ex]

  [^ex]: `loyly:README.md@17d68d2`, "What to steal" 1.

- **Where a page and a source disagree, the source wins** and the page is the
  bug. Where two sources disagree, the page says so and says which one rdm-spec
  follows and why.
- **No page names an implementation**: no language, framework, tool or
  storage. The configured stack words are refused
  (`dhf/spec/knowledge/wiki.feature`).
- **Every page is listed below**, and nothing is listed that is not a page.

## Pages

- [The Löyly method, where the spec is silent](pages/loyly-method.md): the
  rules rdm-spec follows wherever its own spec leaves a choice open.
- [The regulated profile](pages/regulated-profile.md): clause lists, audit
  tags, the auditor's copy, and why a referenced clause is still graded.
