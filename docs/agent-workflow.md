# Agent workflow — changing RDM traceably

RDM develops itself under its own record-first design controls, and the
canonical, end-to-end contributor procedure lives **inside the DHF it governs**:

> [`dhf/AGENT_WORKFLOW.md`](https://github.com/scope-impact/rdm/blob/main/dhf/AGENT_WORKFLOW.md)

It covers, in execution order:

1. a decision tree — does the change need a design input at all;
2. the 8-step traceable loop — user need → design input (scaffolded with
   `rdm story new-input`) → design-doc approval commit → implementation →
   `@allure.story("DI-n")` acceptance test → independent faithfulness verdict →
   the five gate commands CI runs → regenerated traceability matrix;
3. the hard rules (never hand-edit the matrix; an edited test re-opens its
   review; planning artifacts are never evidence);
4. a failure-diagnosis table mapping each gate failure to its fix.

The runbook is kept next to the record rather than in this site so that agents
and humans working in the repository find one authoritative copy; this page
exists so site readers can find it too. The model behind the procedure is
described in [Record-first architecture](record-first-architecture.md),
[Plan vs. record](plan-vs-record.md), and
[ADR 0001](adr-0001-bounded-context-user-needs.md).
