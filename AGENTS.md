# Design controls come first

RDM is a design-controls tool that governs its own development with the same
controls. **The intent: a change here is not just working code — it is working
code plus an unbroken chain of evidence** (user need → design input → tagged
test → generated traceability matrix), with approval recorded as the git commit
of the design docs and independent verification as the reviewed pull request.
CI and the pre-commit hook check every link; a change that skips one fails the
build.

Before writing any implementation, read **`dhf/AGENT_WORKFLOW.md`** — the
canonical procedure, with a decision tree, why/do/done-when for each step,
and gate-failure fixes. The short version:

- A behavior change is governed by a **design input** (`DI-n`): declare it
  in its owning context's `design_inputs` frontmatter, then commit the design
  docs **before** the implementation — the commit is the approval, and the
  pull-request reviewer reads the order.
- The design input is the acceptance criterion; the tagged test
  (`@allure.story("DI-n")`, `tests/acceptance/`) **verifies** it. Its
  verification steps are the test's own checks, never acceptance criteria
  (vocabulary: `CONTEXT.md`). Whether it actually
  proves the DI is judged by the
  **independent** pull-request reviewer — never the test's author.
- Never hand-edit `dhf/documents/traceability_matrix.md` — it is generated
  from executed results.

Plans, tasks and issues are coordination, never the record: never cite them
as design, verification or approval evidence (`docs/about/plan-vs-record.md`).
