Feature: Compile the DHF
  A regulatory author can compile a design history file from the system of record: the spec, the executed tests and git.

  @item-07-001
  Scenario: RDM shall render a Markdown template file with Jinja2 against the data files supplied, each available under its file name, and refuse, naming it, a value the template uses that the data does not supply.

  @item-07-002
  Scenario: RDM shall provide the invert_dependencies, join_to, and md_indent template filters used to build traceability tables.

  @item-07-003
  Scenario: RDM shall post-process rendered Markdown: auto-number sections, give a template the words its first render produced by rendering it a second time, so a glossary lists only the terms the document uses, and exclude auditor-only notes from released output.

  @item-07-004
  Scenario: RDM shall extract delimited code snippets from source files (RDOC/ENDRDOC), keyed by name, for inclusion in documents.
