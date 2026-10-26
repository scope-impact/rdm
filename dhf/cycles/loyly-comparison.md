# Löyly built twice: Löyly's own harness against rdm-spec

2026-10-26. rdm-spec's first project other than itself. Löyly's booking core
(loyly@17d68d2, `docs/plan/01-booking-core/spec.feature`, 11 scenarios) was
built twice from the same scenarios, word for word:

- **Track A** used Löyly's own harness.
- **Track B** used rdm-spec, with cycle 15's release (514bc1d).

Held the same in both tracks:
- the stack (Babashka and its built-in web server)
- one builder
- the grader's brief, word for word, driving the running app over HTTP

Every run was observed with `rdm-spec observe`. The workspaces are local, in
`/home/user/loyly-compare/`:
- `loyly-harness`, Löyly's cycle 01
- `loyly-rdm`, rdm-spec's cycle 01; Track A is recorded there as cycle 90

## The result

| | A: Löyly harness | B: rdm-spec |
|---|---|---|
| Spec preparation | none: the slice and its tickets exist | translation into needs, model, events and three components; about 20 minutes of the integrator's work |
| Builder | planner 3.6 min + implementer 7.5 min; 278,311 tokens; 56 tool calls | 8.8 min; 129,155 tokens; 19 tool calls |
| Gate | `verify.sh` exit 0: the instrument checked, the suite green, the app booted and probed (poll 2 of 20) | `ratify` passed: the builder's tests wrote 11 passing results in a checkout of the cut |
| Grader | 2.9 min; 81,249 tokens | 2.0 min; 80,868 tokens |
| **Score** | **11 / 11** (`score.clj`) | **11 / 11** (`rdm-spec score`) |
| Cycle total | 14.6 min; 359,560 tokens | 10.8 min; 210,023 tokens |
| Breaches | 0 | 0 |

The same app, scored the same way, came out at 11 of 11 from both. Track B
cost about 40% fewer tokens and a quarter less time, because the spec was
already decomposed into components and needs: no planning phase was needed.
The translation that made that possible was done by hand, beforehand, and
is not in Track B's count.

## What each harness had that the other did not

| | Löyly | rdm-spec |
|---|---|---|
| The app is proved to serve | **yes**: boots it, polls it, probes its identity | **no**: ratify checks results only; serving was checked by hand |
| Results tied to scenario ids | no: the suite's exit code | **yes**: every result names its scenario; status and trace show the chain to needs |
| Needs and sources traced | through the wiki's footnotes | **yes**: needs cite sources, and check resolves them |
| Planning phase | **yes**: plan, then implement, separate runs | no: one builder from the decomposed spec |
| Checklist kept current | derived on demand; the committed one was stale (8 of 11) | derived per cycle into its record |
| Fence proved on the object graph | **yes** | the room is one fresh commit, so no history exists to probe |
| Instrument checked before the gate | `check.sh` (oracle, adoption) | the oracle, when the project names one |
| Runs observed | no | **yes** (`observe`) |

## Findings for rdm-spec as a harness for any project

1. **rdm-spec doesn't check that the app serves** (as Löyly's `verify.sh`
   does). A project that is an app needs ratify to start it and probe it:
   a serve command and a probe in the configuration, beside
   `:test-command`.
2. **The result contract is not in the project.** A builder of a project
   made by `init` cannot learn the Allure result format, or the `spec`
   label ratify depends on, from its own spec. The integrator had to write
   it into the brief. The skeleton should carry it, in its README or
   glossary.
3. **The architecture rules are rdm-spec's, imposed on the project.**
   "Contexts relate only through events" made the translation invent a
   `DaysConfigured` event that Löyly's spec never names. The 45-character
   description limit also bit. A project should be able to relax these
   lint rules.
4. **There is no level for a scenario a person judges.** Löyly's 01-011
   ("judged usable" by a person who has not read the configuration) became
   `@e2e`. Both builders automated a proxy for it, and both graders
   approximated the judge. A `@judged` level would make the gap visible
   rather than hidden.
5. **`observe` knows only builder, grader and integrator.** Löyly's planner
   and implementer had to be recorded as one builder run.
6. **Translation is a step with no tool.** Adopting a project with an
   existing spec (`adopt`) does not map its scenarios, ids or tags. A
   mapping (Löyly item to rdm-spec id) was kept by hand, in a comment above
   each scenario.

## Findings for Löyly (for its owner; not acted on)

- Its fence removes `impl/README.md`, the file that states the build
  contract, so the planner read the contract from `verify.sh`.
- Its committed cycle 01 checklist is stale: 8 items, where the spec
  derives 11.
- Its spec leaves unstated, as both graders and builders found:
  - **01-006:** which slot the second request names
  - **01-008:** whether the invoicing history counts as a view
  - the slot times on days other than Tuesday
  - whether bookings survive a restart

## Caveats

- **The briefs differed where the methods do.** Track A followed Löyly's
  read order and two-phase prompting; Track B followed rdm-spec's one
  builder. Two lapses in my own brief:
  - I asked Track A's planner to write the stack into the plan, against
    Löyly's INV-2.
  - I wrote the Allure format into Track B's brief (finding 2).
- **One person judged nothing.** Both graders approximated 01-011's judge:
  - Track A's grader had already seen the front page, which lists the days.
  - Track B's grader configured the days itself.
- **One build each.** A single cycle per track; the difference in cost may
  not hold over more cycles or a larger slice.

## Cycle 02: rebuilt with rdm-spec's new gate (cycle 16's release)

After spec-20, `verify` replaced ratify, and `loyly-rdm` declared two
checks:
- its tests
- a `:serves` check that starts `bb serve.clj` fresh, polls it within
  10 seconds in 20 polls, and runs `dhf/harness/probe.sh`, which lives in a
  kept path

The project's README now carries the results contract. The builder's brief
no longer describes the Allure format. The app was rebuilt from nothing
(`spec-2`).

| | A: Löyly harness | B: rdm-spec, cycle 01 | B: rdm-spec, cycle 02 |
|---|---|---|---|
| Builder | 11.6 min; 278,311 tokens | 8.8 min; 129,155 tokens | 10.2 min; 149,895 tokens |
| Gate | `verify.sh`: suite, boot, probe | ratify: results only | **verify: tests, then serves (answered on poll 1 of 20, probe passed)** |
| Grader | 2.9 min; 81,249 tokens | 2.0 min; 80,868 tokens | 3.6 min; 100,885 tokens |
| Score | 11 / 11 | 11 / 11 | 11 / 11 |
| Cycle | 14.6 min; 359,560 tokens | 10.8 min; 210,023 tokens | 13.8 min; 250,780 tokens |

**Finding 1 (serving proof) is closed.** The gate was checked to fail:
- A start command that never answers failed the tests first, and `serves`
  was listed as not run.
- With the port already held, verify refused (exit 2) and started nothing.

**Finding 2 (result contract) is closed.** The builder wrote valid results
from the project's README alone. Things it found unsaid, for spec-21:
- the values of `suite` and `subSuite`
- whether a `tag` keeps its `@`
- an issue link's URL
- `failed` against `broken`
- where results go when RESULTS_DIR is unset

**Also found:**
- When the app hung, one of the builder's own tests left the app running
  after it gave up on it. Verify's checks left nothing behind.
- spec-0008's judge is not truly blind: its Given names the days, so
  whoever sets the state up already knows them. That is finding 4 (a judged
  level) again.
