---
hide:
  - navigation
  - toc
---

# Worked example: Part 11 document control

[`examples/github-document-control`](https://github.com/scope-impact/rdm/tree/main/examples/github-document-control)
is a complete record-first project laid out as `rdm init` and `rdm adopt` lay
one out: git and GitHub as a 21 CFR Part 11 document control system, with
GitHub rulesets as design outputs, the pull-request approval as the
electronic signature, a risk register whose controls are its design inputs,
and RDM's reusable gates on every pull request. Its README lists where it uses
each part of RDM.

Its traceability, from its knowledge graph, rebuilt on every docs build from a
live run of its acceptance tests, the same way as [RDM's own](../traceability-map.md):

<div class="rdm-map" data-src="../../assets/example-traceability-map.json">
--8<-- "docs/_hooks/traceability_map.html"
</div>
