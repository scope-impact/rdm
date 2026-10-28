# Decisions: slice 09-publish

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

Before slice development (spec-33), decisions were kept per cycle; this
slice's are in `dhf/cycles/NN/decisions.yaml` of cycles 01 to 24.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `site.feature`, Feature: Site

The site is Markdown, built to HTML by the configured site builder, an
external tool (dhf/harness/config.edn, :site): decided by the event storm's
hotspot H6. rdm-spec writes the builder's configuration, never its code.

### `site.feature`, Scenario: Publishing refuses views older than the model (spec-0099)

One copy of the model hash: publish runs the views' freshness check as a
gate before the site, so the site never computes the hash itself
(loyly:harness/README.md@17d68d2, "The pieces, in pipeline order":
"four copies drift into four instruments").

### `site.feature`, Scenario: The wiki's index is published with its footnotes linked, as a page is (spec-0273)

The index's own footnote was published unlinked while the pages' were
linked (cycle 06, grading).

### `site.feature`, Scenario: The wiki is reachable from the home page and the navigation (spec-0239)

Wiki pages were published but reachable by no link (cycle 05, 1); three
builders read spec-0146 and spec-0133 three ways.

### `site.feature`, Scenario: Without the site builder, building fails and names the missing tool (spec-0149)

Incident: a gate once reported success because a missing tool turned its
command into a no-op that exited 0 (loyly:harness/README.md@17d68d2, "The failure modes this shape was learned from").

