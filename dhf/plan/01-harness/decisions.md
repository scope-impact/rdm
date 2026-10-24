# Decisions: slice 01-harness

A decision selects one branch and prunes the rest (Löyly's
`docs/wow/way-of-working.md` §6). Cites: `plan.md` in this directory.

## D1: RDM builds its own harness from a spec, rather than carrying Löyly's

The maintainer asked to build a harness for RDM, via spec
(`dhf/raw/2026-10-maintainer-requests.md`, 2026-10-24). Löyly's harness is built
to be carried byte for byte by every repository in its ring, with differences
only in a configuration file. RDM's harness differs on purpose: it reads the
record's design inputs, grades from acceptance runs, and proves identity with the
record's own text, which a stack-free shared harness cannot. Its rules are
Löyly's, and each guard cites the Löyly step it came from. *Pruned:* joining the
ring now. It stays open: a later slice can make RDM's harness pass Löyly's own
oracle.

## D2: A Rule is a design input; a scenario is an example that accepts it

RDM's glossary makes a design input one acceptance criterion, accepted as a
whole; Löyly makes each scenario a checklist item. Both hold if the Rule is the
design input (tagged with its id, its need and its context) and its scenarios are
the examples that accept it. A design input is verified when all its scenarios
pass; the checklist still scores per scenario. *Pruned:* one scenario per design
input (too coarse for Löyly's scoring), and an id per scenario as the design
input (too fine for RDM's matrix and risk controls).

## D3: Two ids, each with one job

`@DI-n` on the Rule is the regulatory trace: the matrix, risk controls and the
graph join on it, and it continues RDM's numbering (DI-90 onward). `@item-NN-SSS`
on the scenario is the instrument's join, cycle to cycle. Neither is computed
from wording. *Pruned:* Löyly's item ids alone (would cut the trace to 74 design
reviews and the risk register), and DI ids alone (no stable per-scenario join).

## D4: The fence is out of scope

Deleting the code and rebuilding it from the spec is Löyly's heart, and it needs
the whole of RDM specified as slices first. *Pruned for this slice:* the fence
and any regeneration cycle.

## D5: The existing contexts move into slices later

The eight contexts' 76 design inputs stay in their design documents until a
later slice moves them, so this slice's reader must hold both (and refuse a
design input declared in both, DI-90's second scenario). *Pruned:* moving them
first, which would delay the harness behind a rewrite of 78 tests.

## D6: No perception step

Löyly records its harness as strong at telling an agent whether it is right and
weak at handing it an unjudged look at the product; its fuller pipeline ran such
a step and retired it on cost. *Pruned for this slice:* screenshots or journeys
handed back without a verdict.

## D7: The identity probe asks the record's own question

Löyly's probe in that repository can only check a name, and says so. RDM has a
domain question only the right record answers: a design input's text. The gate
asks the served graph for one and compares it with the record read from its
files. *Pruned:* a name or marker check.
