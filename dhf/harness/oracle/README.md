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

**Status:** proposed, awaiting the spec owner's review of `oracle.clj` and of
its 20 goldens. Nothing runs it yet: the gate still runs the installed
release's own oracle (`:oracle` in `dhf/harness/config.edn`). spec-23 makes
the gate run this one.

**Its first findings:** recorded from cycle 17's release, the accepted
instrument, it gives that release 0 differences, twice. Cycle 18's build
gets 17 differences:
- 2 intended, from spec-22's scopes
- 11 of drifted wording
- 4 a regression: verify demands evidence from a `@wip` scenario

Cycle 18's grader found none of them.
