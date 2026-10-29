# The method

## The spec is the asset; code is a build product

The method treats documentation as the source of truth and code as a build
product of it. Because the spec, not any one codebase, is the asset, the
implementation can be deleted and regenerated, and the difference between
two generations says how complete the spec is.[^wow0] Said as custody: the
spec is the artefact looked after over time, and the code deliberately is
not; of the four positions (both, neither, code only, spec only) the method
picks one on purpose.[^wow0]

## The five mechanisms

1. A person sharpens the spec; at a fixed time it is cut (tagged, frozen).
2. An agent regenerates the application from the cut, inside a fence: a
   clean room where every prior implementation and every measurement is
   deleted from git history, so nothing can be copied, only rebuilt.
3. A gate ratifies the result: "done" is a script the builder does not
   control proving the suite green.
4. An instrument scores the result, one item per spec scenario, with honest
   denominators.
5. Anything not written into the spec dies with the implementation. That
   destructive cut is the entire mechanism: it forces learning into the spec,
   because the code cannot carry it.[^five]

Everything else in a fuller pipeline (an unattended multi-round loop with a
deadline, deployment of every cycle, the same spec built in three stacks with
the instrument held still) is scaffolding around these five.[^elides]

## Harness and instrument are kept apart

A harness feeds what it learns back into the generation loop so the agent can
iterate against it; the gate and the fence are harness. An instrument reports
to a person, once a cycle, and never into the loop. The most valuable
measurement is one the agent never sees, which is why the whole thing is not
called a harness.[^five] This repository keeps the distinction in its tasks:
`fence` and `verify` are harness; `checklist`, `score` and `scoreboard` are
instrument.

## The layers, and the anti-drift rule

Knowledge, specification, planning, acceptance, implementation and
verification form a funnel; each layer feeds the next and nothing may drift
from what precedes it. The same domain names appear at every layer, only
re-cased, so a Gherkin line and a code function that disagree about a name
are a bug by construction.[^layers] The funnel has a second reading: the
layers are several descriptions of one rule (prose, Gherkin, a worked
example, a state diagram), each leaving gaps the others cover.[^layers]

## The gate on behaviour: no plan entry, no code

Behaviour changes start in the docs. A slice directory with a plan naming the
goal, the scope and the wiki pages it rests on; a feature file stating the
acceptance criteria; tickets naming the scenarios each satisfies. An agent or
a person arriving with a feature idea and no slice writes the slice first.
This is the single most load-bearing rule: it makes the docs lead instead of
describe.[^gate]

## What the method costs when applied to a harness

rdm-spec applied the method to the harness itself, so the instrument was
regenerated with the product. Most of its failures came from three
departures from Löyly: rebuilding the instrument as well as the app, rebuilding
in scoped cycles, and running many builders in parallel. Each let builders
guess something Löyly's spec states exactly. Where it followed Löyly (the
gate, goldens re-recorded only with a stated reason, both directions counted
with a moved denominator said aloud) it held.[^retro] This harness takes the
first lesson whole (the harness is held still, INV-3) and the third (one
builder), and keeps the second as a deliberate departure with its own
safeguard: see [scope-and-container](scope-and-container.md).

[^wow0]: `raw/loyly/docs/wow/way-of-working.md@17d68d2`, section 0.
[^five]: `raw/loyly/harness/README.md@17d68d2`, "The idea in five lines".
[^elides]: `raw/loyly/harness/README.md@17d68d2`, "What the full pipeline has that this demo elides".
[^layers]: `raw/loyly/docs/wow/way-of-working.md@17d68d2`, section 1.
[^gate]: `raw/loyly/docs/wow/way-of-working.md@17d68d2`, section 2.
[^retro]: `raw/rdm/retrospective-01-12.md@a4641ae`, section 3, "The root cause, in one picture".
