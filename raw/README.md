# raw: what entered the room

The allow-list of the clean room. Everything under `raw/` was copied in at the
commit named below and is never edited. Anything the harness needs that is not
here is found again from the intent (`README.md`) or from these sources,
never read from an implementation.

## Entered

| Path | From | Pinned at | Why |
|---|---|---|---|
| `loyly/README.md` | `skallinen/loyly` | `17d68d2` | the method, as its author states it |
| `loyly/docs/wow/*.md` | `skallinen/loyly` | `17d68d2` | the way of working, the agent harness, the invariants pattern |
| `loyly/harness/README.md` | `skallinen/loyly` | `17d68d2` | the harness's pieces and the rule each embodies; the failure modes |
| `loyly/harness/spec.feature` | `skallinen/loyly` | `17d68d2` | reference for how a harness states its own contract. Read, never copied |
| `rdm/docs/wow/*.md` | `scope-impact/rdm` | `a4641ae` | our way of working, ported from Löyly and extended through 27 cycles |
| `rdm/decisions/adr-0002..0006` | `scope-impact/rdm` | `a4641ae` | the decisions that shaped rdm-spec; kept as history |
| `rdm/retrospective-01-12.md` | `scope-impact/rdm` | `a4641ae` | where rdm-spec failed against Löyly, by class |
| `rdm/storm/2026-10-24-rdm.md` | `scope-impact/rdm` | `a4641ae` | the one event storm that was held; the seed of the storm record's shape |
| `rdm/spec/events.edn` | `scope-impact/rdm` | `a4641ae` | the event catalogue's shape, as a seed |
| `rdm/spec/needs.edn` | `scope-impact/rdm` | `a4641ae` | the needs, each citing a source; to be re-stated against the intent |
| `allure/` | allurereport.org docs; `allure-framework/allure-java`; `allure-framework/allure-js` | docs fetched 2026-10-29; `allure-java` 3.0.1 (`72fa349`); `allure-js` v3.14.0 (`6b62dd6`) | the result model every judge writes (INV-11): the documentation, the reference model, and the model the browser-driver and Gherkin-runner integrations write through. `raw/allure/README.md` lists each file |
| `judge/` | github.com/stanfordnlp/dspy; arxiv.org/abs/2507.19457; arxiv.org/abs/2301.08243 | `dspy` 3.4.1 (`099982b`); GEPA and I-JEPA abstracts fetched 2026-10-29 | what a learned judge rests on (decision 0002): DSPy's README, signatures, optimizers, metrics and GEPA; the GEPA and I-JEPA abstracts. `raw/judge/README.md` lists each file |

## Never enters

- any implementation of rdm-spec: `src/` (Clojure), `wasm/` (Rust, WIT, WAC),
  `dhf/harness/oracle/oracle.clj`, the oracle's goldens
- `dhf/harness/config.edn` and `stack.md`
- rdm-spec's feature files (`dhf/spec/<slice>/*.feature`), `model.edn`,
  `glossary.md`, `ids.edn`
- the cycle records `dhf/cycles/00` to `27`
- Löyly's tools (`harness/*.sh`, `harness/*.clj`) and goldens
- any Allure integration or reporter (a runner's adapter is a tool's, not the
  model's)
- any judge's weights, prompts or compiled program: a judge is a project's
  plugin, held still with its own goldens, never part of the harness

A rule the old harness had is not evidence that the new one needs it. If it
is needed, it is re-derived from the intent, and the decision says so.

Some of these files name stacks (Löyly's README names its tools; the ADRs
name Clojure and Rust). They are sources, not spec: INV-2 applies to
`docs/` and `harness/spec.feature`, not to `raw/`.
