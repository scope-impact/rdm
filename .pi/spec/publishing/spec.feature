Feature: Publishing
  RDM's own requirements in this context: each stamped scenario is one requirement,
  verified by the acceptance tests that claim it with @allure.tag("item-NN-SSS").

  @allium-guarantee.CompileDhf
  @item-07-001
  Scenario: RDM shall render a Markdown template file with Jinja2 against the data files supplied, each available under its file name, and refuse, naming it, a value the template uses that the data does not supply.

  @allium-guarantee.CompileDhf
  @item-07-002
  Scenario: RDM shall provide the invert_dependencies, join_to, and md_indent template filters used to build traceability tables.

  @allium-guarantee.CompileDhf
  @item-07-003
  Scenario: RDM shall post-process rendered Markdown: auto-number sections, give a template the words its first render produced by rendering it a second time, so a glossary lists only the terms the document uses, and exclude auditor-only notes from released output.

  @allium-guarantee.CompileDhf
  @item-07-004
  Scenario: RDM shall extract delimited code snippets from source files (RDOC/ENDRDOC), keyed by name, for inclusion in documents.

  @allium-guarantee.UserManual
  @item-07-005
  Scenario: RDM's instructions for use shall be one manual whose controlled document lists its pages in reading order: a cover naming the product, the release it is for (filled in by the build: the release tag it is built from, or the development version otherwise), and the manual's own id and revision; then what RDM is for (its intended use); how it works; limits and risks; install; quick start; guides (the operating instructions); troubleshooting; reference; and changelog (the revision history).

  @allium-guarantee.UserManual
  @item-07-006
  Scenario: RDM's instructions for use shall be published once per release tag and once for the development version, every published release kept and selectable beside the others, the newest release being the default.

  @allium-guarantee.UserManual @allium-guarantee.RiskRegister
  @item-07-007
  Scenario: RDM's instructions for use shall disclose each risk of RDM's risk register by its id, with what the user must do about its residual risk.

  @allium-guarantee.ReleaseEvidence
  @item-07-008
  Scenario: RDM shall generate device-master-record index data from controlled documents' frontmatter, writing one entry per document (id, title, path, revision) to a data file the DMR index renders from.

  @allium-guarantee.ReleaseEvidence
  @item-07-009
  Scenario: RDM shall render a verification report to PDF from the record and the Allure results it is given, for review by someone who did not run the tests: a header naming the repository, the record's commit, the commits tested, the executor and environment the results record, the RDM version and one SHA-256 over the result files; an evidence status that is release-grade only when every requirement has a passing run, no run failed, and every run tested the record's commit with no uncommitted changes, and that otherwise names each reason; the anomalies (failed, broken or skipped runs, requirements with no run, attachments not found, and tags naming no requirement); a summary of the risk register's state; a traceability table of user need, requirement, the risks it is a control for, its tests and their result; per requirement, its text, context, user needs, outputs, whether it is a baseline or a risk-based acceptance criterion, and each risk it is a control for with that risk's status and residual decision, and for each run of a test tagged with it the test's file and function, its result, date and duration, its commit and worktree state where they differ from the header, any failure message and trace, its labels other than those the report already shows, its links, each step as a verification step with its own result, and the attachments the test made (text inline up to a limit, images embedded, any other file named with its SHA-256), with captured output and the copy of the requirement listed by name and SHA-256 only, and the Allure severity label left out; and an appendix listing every result file with its SHA-256. An unreadable result file or uncommitted changes to the record is a reason the evidence is not release-grade; text from the results is set as text, never markup; a symbolic link in the results is never read; and with no Typst available the report says so.
