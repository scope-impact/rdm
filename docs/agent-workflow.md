# Changing the record

RDM is a design-controls tool that governs its own development with the same
controls it provides. The consequence: a change to RDM is **not just working
code** — it is working code plus an unbroken, machine-checkable chain of
evidence:

> **user need** (why) → **design input** (what) → **implementation** (how) →
> **tagged test** (proof) → **generated traceability matrix**, with approval
> recorded as the git commit of the design docs and independent verification
> as the human-reviewed pull request.

Every link is enforced: a pre-commit hook blocks implementation commits until
the design record is approved, and CI runs four gates (design-gate →
acceptance tests → verify → release-gate) on every push. Because an agent may
have written the requirement, the code, *and* the test, the pull request is
reviewed by someone other than the author, who judges whether each tagged test
would actually fail if the behavior broke.

The canonical, step-by-step procedure lives **inside the DHF it governs**, so
agents and humans working in the repository find one authoritative copy:

> [`dhf/AGENT_WORKFLOW.md`](https://github.com/scope-impact/rdm/blob/main/dhf/AGENT_WORKFLOW.md)

It contains a decision tree (does your change need a design input?), the loop
above with **why / do / done-when** for each step, a worked example drawn from
the repository's own history, and tables of hard rules and gate-failure
fixes.

The model behind the procedure is [How RDM works](record-first-architecture.md)
and [the data model](data-model.md); what to write is
[Design inputs and tests](design-controls.md), and what the gates then check is
[The gates](gates.md).
