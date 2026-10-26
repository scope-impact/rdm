# The oracle, held still

`oracle.clj` judges rdm-spec's instrument as a black box: each case writes its
own fixture, runs `rdm-spec` command lines, and compares the exit, standard
output, error stream and written files with the golden recorded for it. It is
written by hand and changed only by a reviewed commit. It is never
regenerated, because a judge rebuilt from the same spec agrees with whatever
the spec got wrong (cycles 16 to 18; Löyly holds its harness still for the
same reason).

- `oracle.clj`: the tool. Run `bb dhf/harness/oracle/oracle.clj`, or
  `bb dhf/harness/oracle/oracle.clj record --reason R [--changes C:K,...]`.
  `RDM_SPEC` names the rdm-spec judged; `ORACLE_LAYOUT` names a local OCI
  layout.
- `pin.edn`: the artifact holding `oracle.edn`, pinned by digest.

**Status:** approved by the spec owner (2026-10-26). The gate runs it
from releases built from spec-23 on (`:oracle :command` in
`dhf/harness/config.edn`); until one is installed, the installed release still
gates with its own built-in oracle. Run it by hand meanwhile.

**Its first findings:** recorded from cycle 17's release, the accepted
instrument, it gives that release 0 differences, twice. Cycle 18's build
gets 17 differences:
- 2 intended, from spec-22's scopes
- 11 of drifted wording
- 4 a regression: verify demands evidence from a `@wip` scenario

Cycle 18's grader found none of them.

**Cycle 19's install:** the goldens still held cycle 17's wording, a
`:reason` and a count no scenario states, so cycle 19's correct build got 15
differences. The oracle now compares only what the spec states: each error
line's head (FILE:LINE: RULE, and the element for `mapping-differs` and
`evidence-missing`), and nothing on a path in `unstated`, each with its
reason. With that, cycle 17's release gets 0 differences against its own
goldens. The 6 cases spec-22 and spec-23 changed were re-recorded from cycle
19's release, which then gets 0; cycle 17's gets exactly those 10
differences.

## Recording

- **A golden holds nothing the spec does not state.** Before a golden is
  recorded or re-recorded, every key it holds traces to a scenario (cycle 11:
  the scorer's and scoreboard's goldens held shapes no scenario stated).
