# Troubleshooting

## A gate fails

| Message (abridged) | Cause | Fix |
|---|---|---|
| design-gate: *contains unresolved placeholders* | a document still holds `TODO` markers | finish the document |
| design-gate: *has uncommitted changes; the current revision is not approved* | a design document or the design review is edited but not committed | commit it; the reviewed pull request that merges it is the approval |
| design-gate: *view … 's image was not drawn from the current workspace.dsl* | `dhf/c4/workspace.dsl` changed after its views were drawn | `rdm c4 draw --dhf dhf`, then commit the views with the workspace |
| pre-commit: *commit blocked* | implementation is staged while the design gate fails | commit the record first, then the implementation |
| release-gate: *design input DI-n not verified by any passing Allure test* | no passing run carries the tag | write or tag the test, and rerun the acceptance suite |
| release-gate: *design input DI-n FAILED verification* | a run tagged with it failed | fix the implementation, or the test |
| release-gate: *user need addressed by no design input* | a need has no input tracing to it | add an input with `traces_to`, or remove the need |
| release-gate: *no risk_policy is declared* | the register has no acceptability criteria | declare a `risk_policy` |
| release-gate: *risk … residual not evaluated* | a control's tagged test has no passing run | make that test pass |
| release-gate: *needs an acceptance* or *unacceptable residual* | the residual risk is above what the policy accepts | strengthen the control, or record who accepted the residual and why |
| release-gate or verify: *result file … cannot be read: it could hold a failed run* | an Allure result is not JSON, not UTF-8, a symbolic link, or has a status Allure does not write | rerun the acceptance suite with `--clean-alluredir`; never edit result files |
| design-gate or release-gate: *Allure tag … matches no design input* (the graph says *test tag … names no declared design input*) | a test is tagged with an id the record does not declare | correct the tag, or declare the input |
| design-gate passes, but says approval *could not be checked (not a git work tree)* | the record is not in a git repository, so commits cannot be read | run the gates inside the repository; outside git the approval check is skipped, not passed |
| `new-input` refuses and changes nothing | the context has two design documents, the user need is unknown, or its `design_inputs` list cannot be extended in place | fix the record by hand, then rerun |

## The graph

| Symptom | Cause | Fix |
|---|---|---|
| *the graph commands need the optional extra* | RDM was installed without `graph` | install `rdm[graph]` |
| *store not found* | `query --store` or `serve` names a store never built | `rdm graph build --store <dir>` first |
| *--infer and --unit-coverage apply to the in-memory projection* | those options were given with `--store` | leave out `--store`, or build the store with them |
| *test run tested another commit than the record's* | the results come from an earlier commit | rerun the acceptance suite on this commit |
| *document's latest change has not landed on the default branch* | the change is on a branch not merged yet | expected until the pull request merges |
| the agent server misses something new in the record's vocabulary | it still runs the RDM it started with | restart it after upgrading RDM |

## Installation and rendering

| Symptom | Cause | Fix |
|---|---|---|
| `rdm: command not found` | the tool directory is not on `PATH` | `uv tool update-shell`, then open a new shell |
| the hook fails with *rdm not on PATH* | the hook runs outside the environment RDM is installed in | install RDM as a tool, or run git from a shell where `uv run rdm` works |
| *the verification report needs rdm-typst* | the typeset provider is missing | build it: `cargo build --release --manifest-path providers/typst/Cargo.toml`, and put it on `PATH` or in `RDM_TYPST` |
| no document PDFs | Pandoc or Typst is missing | install them, or render in the RDM Docker image |

Still stuck: open an issue on
[scope-impact/rdm](https://github.com/scope-impact/rdm/issues) with the
command and its full output.
