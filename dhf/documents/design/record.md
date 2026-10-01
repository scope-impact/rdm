---
id: SDS-REC-001
kind: design
context: record
satisfies: [UN-001, UN-004, UN-012]
design_inputs:
  - id: DI-1
    text: "RDM shall read the user-need registry + satisfies refs from frontmatter and ingest Allure results, with no project-management dependency."
    traces_to: [UN-001, UN-004]
  - id: DI-6
    text: "RDM shall mark planning-tool outputs as non-record, keeping planning artifacts out of the controlled record."
    traces_to: []
  - id: DI-29
    text: "RDM shall generate device-master-record index data from controlled documents' frontmatter, writing one entry per document (id, title, path, revision) to a data file the DMR index renders from."
    traces_to: [UN-012]
  - id: DI-31
    text: "RDM shall discover verification tags in non-Python test sources — JavaScript/TypeScript allure calls and Java Story/Feature annotations across conventional test-file names — so tag-linkage warnings, audit coverage, and faithfulness hashing work in polyglot repositories, with function-scope hashing for Python and whole-file scope for other languages."
    traces_to: [UN-004]
  - id: DI-34
    text: "RDM shall keep an append-only, hash-chained event journal in the DHF (journal.jsonl), appending one event each time a faithfulness verdict is recorded, each event carrying a sequence number, a timestamp, a type, a payload, the previous event's hash, and its own SHA-256 hash over its body and the previous hash; rdm story journal --verify shall detect any edited, deleted, inserted, or reordered event and report the first broken sequence number; and the release gate shall block on a journal that fails verification."
    traces_to: [UN-012]
---

# Record — Software Design

## Design Inputs

This context owns the design inputs declared in the frontmatter:

- **DI-1 (record ingest)** — read the user-need registry and per-context
  `satisfies` references from frontmatter, and ingest executed Allure results,
  without depending on any project-management tool. Refines UN-001 and UN-004.
- **DI-6 (plan/record separation)** — mark planning-tool outputs as non-record,
  keeping planning artifacts out of the controlled record. A cross-cutting
  constraint (`traces_to: []`). (That planning tooling is *optional* — the record
  core needs no planning extra — is the structural property DI-1's "no
  project-management dependency" test already pins.)
- **DI-29 (DMR index data)** — `rdm story dmr` generates device-master-record
  index data from the controlled documents' own frontmatter (one entry per
  document: id, title, path, revision), so the DMR index is derived from the
  record rather than hand-maintained — the same generated-not-transcribed rule
  the traceability matrix follows. Refines UN-012.
- **DI-31 (polyglot tag discovery)** — executed verification (Allure results)
  was always language-agnostic; source-tag scanning was Python-only, so a
  TypeScript or Java product got no authoring-time linkage warnings, no audit
  coverage, and no faithfulness hashing. Tag discovery now also reads
  JavaScript/TypeScript `allure.story(...)`/`allure.feature(...)` calls and
  Java `@Story(...)`/`@Feature(...)` annotations across conventional test-file
  names (`*.test.*` / `*.spec.*` / `*Test.java` / `*_test.go` …).
  Function-scope verdict hashing remains Python (AST); other languages pin at
  whole-file scope — stated, not silent. Refines UN-004.
- **DI-34 (hash-chained journal)** — `dhf/journal.jsonl` is an append-only,
  tamper-evident log of record events, borrowed from the Phoenix architecture's
  provenance journal. Each line is `{seq, timestamp, type, payload, prev_hash,
  event_hash}`, where `event_hash` is the SHA-256 of the canonical JSON body
  (every field but `event_hash`) — which includes `prev_hash` — so each event
  commits to the whole history before it. Recording a faithfulness verdict
  appends a `verdict` event (input, verdict, reviewer, pin, and the SHA-256 of
  the verdict file written). `rdm story journal --verify` recomputes the chain
  and reports the first broken `seq`: an edited event (its hash no longer
  matches its body), a deleted, inserted, or reordered event (its `seq` or
  `prev_hash` no longer follows its predecessor). The release gate blocks on a
  journal that fails verification; an absent journal is not a failure (the
  chain starts at the first recorded event). Git history remains the primary
  audit trail; the journal makes the record's own event sequence verifiable
  without trusting that history was never rewritten. Refines UN-012.

## Design Outputs

Ingests the system of record so the rest of RDM can compile and gate the DHF.

- `rdm/record/sdd.py` — discover per-context design documents (`kind: design`);
  read the user-need registry (`user_needs`), the design inputs (`design_inputs`),
  and `satisfies` references from frontmatter.
- `rdm/record/allure.py` — parse an Allure results directory into per-design-input
  executed status.
- `rdm/record/verify.py` — build the verification data the DHF renders from.
- `rdm/record/journal.py` — `append_event`, `verify_journal`; appended to by
  `record_verdict`, checked by `run_release_gate` and `rdm story journal
  --verify` (DI-34).

The layer is dependency-light (no pydantic / no planning extra), which is itself
how DI-6 is met. Acceptance criteria are verified by `@allure.story("DI-1")` and
`@allure.story("DI-6")` tests.
