# Troubleshooting

## A gate fails

| Message (abridged) | Cause | Fix |
|---|---|---|
| release-gate: *no spec found* | no `.pi/plan` up to the repository root, and no `spec:` in `dhf/config.yml` | add the factory's spec, or name its plan directory as `spec:` |
| release-gate: *scenario … carries no @item id* | an accepted scenario was never stamped | stamp it with the factory's stamper; RDM never stamps |
| release-gate: *item-NN-SSS is stamped on more than one scenario* | one id on two scenarios | fix the stamps in the spec |
| release-gate: *requirement item-NN-SSS not verified by any passing Allure test* | no passing run carries the tag | write or tag the test (`@allure.tag("item-NN-SSS")`), and rerun the acceptance suite |
| release-gate: *requirement item-NN-SSS FAILED verification* | a run tagged with it failed | fix the implementation, or the test |
| release-gate: *user need … is addressed by no requirement covering its rules or guarantees* | no scenario is tagged with an `@allium-…` obligation of the need's surfaces | the spec needs such a scenario, or the surface's rules and guarantees need revisiting |
| release-gate: *no risk_policy is declared* | the register has no acceptability criteria | declare a `risk_policy` |
| release-gate: *risk … residual not evaluated* | a control's tagged test has no passing run | make that test pass |
| release-gate: *needs an acceptance* or *unacceptable residual* | the residual risk is above what the policy accepts | strengthen the control, or record who accepted the residual and why |
| release-gate or verify: *result file … cannot be read: it could hold a failed run* | an Allure result is not JSON, not UTF-8, a symbolic link, or has a status Allure does not write | rerun the acceptance suite with `--clean-alluredir`; never edit result files |
| release-gate: *Allure result tag … matches no requirement* (the graph says *test run claims …, which is not a requirement of the spec*) | a test claims an item id the spec does not stamp | correct the tag |

## The graph

| Symptom | Cause | Fix |
|---|---|---|
| *the graph commands need the optional extra* | RDM was installed without `graph` | install `rdm[graph]` |
| *store not found* | `query --store` or `serve` names a store never built | `rdm graph build --store <dir>` first |
| *test run tested another commit than the record's* | the results come from an earlier commit | rerun the acceptance suite on this commit |
| *document's latest change has not landed on the default branch* | the change is on a branch not merged yet | expected until the pull request merges |
| the agent server misses something new in the record's vocabulary | it still runs the RDM it started with | restart it after upgrading RDM |

## Installation and rendering

| Symptom | Cause | Fix |
|---|---|---|
| `rdm: command not found` | the tool directory is not on `PATH` | `uv tool update-shell`, then open a new shell |
| *the verification report needs Typst* | Typst is missing | install the `report` extra (`rdm[report]`) or a `typst` executable, or render in the RDM Docker image |
| no document PDFs | Pandoc or Typst is missing | install them, or render in the RDM Docker image |

Still stuck: open an issue on
[scope-impact/rdm](https://github.com/scope-impact/rdm/issues) with the
command and its full output.
