# Plan vs. record

Two kinds of data live near a project, and only one of them is evidence.
Mixing them up is the easiest way to get design controls wrong.

## The record: evidence

| Where | What it is | It is the truth for |
|-------|------------|---------------------|
| design documents and the V&V plan (`dhf/documents/…`) | design inputs and user needs | what the product must do |
| Allure results | test runs | whether each design input passes |
| git history | reviewed, merged pull requests | who approved what, and when |

Everything RDM renders — the documents, the traceability matrix, the graph —
is built from these and nothing else.

## The plan: not evidence

| Where | What it is | It is not |
|-------|------------|-----------|
| task trackers (Backlog.md, issues, project boards) | who does what, and when | a design input, or an approval |

- Work reaches the record only through git: a commit in a pull request. RDM
  never reads a planning tool.
- Never quote, link or screenshot a task or issue as evidence. Evidence
  comes from the design documents, the test results or git.

Keeping them apart also keeps your planning tools simple: since nothing
relies on them as evidence, you never have to validate or retain them like
a record.

## The test

> If you deleted every task and issue, would your evidence change? If not,
> it is plan. If it would, it is record, and belongs in the repository.
