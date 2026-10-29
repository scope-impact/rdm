# Löyly: The Way We Work

> **A demo-sized distillation.** This is a condensed adaptation of a fuller
> method used on production projects, sized so one repo can carry it whole.
> Everything here is the real method; what is missing is only scale
> (more slices, more stacks, longer history), never substance.

Löyly is a sauna-slot booking system for Nordic housing cooperatives,
grounded in real documents: a public corpus from Finnish, Swedish and
Norwegian cooperatives, and As Oy Soukanvuori (Espoo) as the first real
configuration. The domain is real enough to argue about and small enough to
hold in your head, which is exactly what the method needs to be demonstrated.

---

## 0. The one-paragraph version

We treat **documentation as the source of truth** and code as a **build
product** of it. Domain knowledge is captured into a domain wiki
(`docs/wiki/`) that traces back to raw sources (`raw/`). Work is delivered in
vertical **slices**, each specified as **Gherkin** acceptance criteria,
broken into **tickets**, and driven by outer-loop BDD plus inner-loop TDD
(red, green, refactor). Every divergence from a plan is a **decision** that
gets recorded. Because the spec, not any one codebase, is the real asset,
the implementation can be deleted and regenerated, and the difference
between two generations tells you how complete the spec is.

Said as custody: the specification is the artefact we look after over time, and
the code is deliberately not. Both can be looked after, or neither, or only the
code, and this method picks one of those four positions on purpose. Sections 4
and 5 below are what that choice costs and what it buys.

---

## 1. The layer pipeline

A funnel from fuzzy knowledge to verified code. Each layer feeds the next
and nothing is allowed to drift from what precedes it.

```
KNOWLEDGE          raw sources: booking mail, work orders, house rules, interviews
   |               -> raw/  (immutable) distilled into docs/wiki/
   v
SPECIFICATION      the domain model: terms, rules, actors, slot vocabulary
   |               -> docs/wiki/  (start at its index.md)
   v
PLANNING           a slice: one coherent increment of behaviour
   |               -> docs/plan/NN-slug/plan.md
   v
ACCEPTANCE         executable narratives in the domain language
   |               -> docs/plan/NN-slug/spec.feature
   v
IMPLEMENTATION     tickets -> TDD: e2e red -> unit red -> green -> refactor
   |               -> docs/plan/NN-slug/ticket-*.md  +  impl/
   v
VERIFICATION       ratified "done" (real interface, fresh state) + decisions
                   -> docs/plan/NN-slug/decisions.md
```

The anti-drift invariant: **the same domain names appear at every layer**,
only re-cased per layer ("standing slot" in prose, `StandingSlot` in
diagrams, `standing-slot` or `standingSlot` in code). If a Gherkin line and
a code function disagree about a name, that is a bug by construction.

The funnel is one reading of these layers. The other is that they are several
ways of describing the same rule: prose in the wiki, Gherkin in the acceptance
criteria, a hand-worked example with an independent recomputation beside it, and
a state diagram wherever behaviour needs one. Each way suits a different person
saying what they want, and each leaves gaps the others cover, so a rule written
four ways has been described from four directions. The two readings disagree
about one thing. A funnel says a defect caught late means an earlier layer
failed. Layered descriptions say a defect caught late is the layering working.

---

## 2. The gate: no plan entry, no code

Behaviour changes start in `docs/`, not in an editor. Before any
implementation work:

1. A slice directory exists under `docs/plan/` with a `plan.md` naming the
   goal, the scope, and the wiki pages it rests on.
2. `spec.feature` states the acceptance criteria in domain language.
3. Tickets break the slice into coherent units, each naming the scenarios
   it satisfies.

An agent (or a human) arriving with a feature idea and no slice writes the
slice first. This is the single most load-bearing rule in the method: it is
what makes the docs *lead* instead of describe.

---

## 3. The minimum reference rule

**Every internal document cites at least one other document.** A wiki page
cites the raw source it distills; a plan cites the wiki pages it rests on; a
ticket cites the spec scenarios it implements; a decision cites the plan
item it diverged from.

This does double duty: **traceability** (no claim rests on nothing) and
**consumption enforcement** (you cannot write before engaging with what
informs it). A page with no citations is not finished, it is filed.

---

## 4. The spec/implementation firewall

`docs/wiki/` and `docs/plan/` are **implementation-agnostic**: no language,
framework, database, hosting, vendor, or UI-toolkit names appear in them,
ever. A reader must be able to build Löyly as a web app, a mobile app,
or a phone-in voice service from the docs alone.

Anything stack-specific lives in `impl/` (or in per-track notes inside it).
`impl/` may cite the docs; the docs never depend on `impl/`. When a spec
turns out to be insufficient to build from, that is a spec bug: fix the
spec, not the reading.

---

## 5. Regeneration as a validation instrument

Because code is a build product, deleting `impl/` is not a catastrophe, it
is a measurement. The demo is built around this loop:

1. **Vibe run**: build the app from a one-line prompt, no docs. Fast, and
   full of invented rules.
2. **Capture**: extract the real domain knowledge into `raw/` and
   `docs/wiki/`.
3. **Regenerate**: build again, this time from the docs, through the gate,
   inside the fence (`harness/fence.sh`).
4. **Study the delta**: every difference between run 1 and run 3 is either
   a rule the vibe run invented or a rule the docs captured. The delta *is*
   the argument for the method.

Beyond the demo the same instrument applies: if a fresh agent pointed
at the docs cannot reproduce the behaviour, the docs are incomplete, and
that is a finding to fix in the docs.

---

## 6. Decisions

Planning and history are the same structure in opposite directions. A
**decision** selects one branch and prunes the rest, so every divergence
between a plan item and what actually happened is a decision and is
recorded in the slice's `decisions.md`, with the pruned branch named.

## 7. Where the rules are enforced

Rules without enforcement points are wishes. The demo-scale invariants,
each with its named enforcement, live in `docs/wow/invariants.md`. How to
point an agent at this repo, and the guardrails it runs under, live in
`docs/wow/harness.md`. Read both before writing anything.
