# Cycle 01: proposed spec changes

Ten builders rebuilt the scopes the spec change at 30fbad6 touched: the kernel
first, then nine scopes in parallel, each in its own copy of the clean room.
Merged, the suite passes twice in a row: 208 of 208 scenario results and 129
unit tests.

Integrating the scopes took six fixes by hand. Each one is a contract the spec
left open, so each builder guessed differently:

- SpecParsed's `:needs` is a vector by contract; Status and Site read it as a map.
- SpecParsed's `:component` was the model's id; Trace and Site show the
  model's name. The parser now also carries `:context-name` and `:component-name`.
- Views writes `model-sha256:` on a view's first line; Site read `model-hash:`.
- Site printed the level keyword (`:e2e`) where the spec shows `e2e`.
- The shared Given "the configured results directory does not exist" built a
  project with no spec, so publish and trace could not run.
- The shared cycle Givens set no ROOM, so ratify fell back to a fixed path.

None of this is in the spec yet.

## A. Wrong or contradictory: fix first

1. **spec-0017 vs spec-0077.** A view records the hash of model.edn's bytes,
   but reformatting the model must leave views fresh. Views now records both a
   bytes hash and a value hash. Pick one: the value hash fits spec-0077.
2. **spec-0221 vs spec-0090.** An undeclared event is refused, but a fresh
   init has an empty catalogue and its check must pass. Say the bus enforces
   only a non-empty catalogue, or have init write rdm-spec's catalogue.
3. **The first-line format of a view is unstated** (see the integration list).
   Write it out: `%% model-sha256: <hex>`, or the equivalent in each format.
4. **spec-0138 and spec-0128** still run "rdm-spec check" with no `--sources DIR`
   while their Givens supply a checkout.
5. **spec-0182 and spec-0185 have no Given.** Add "a project whose commit
   tagged spec-3 holds an implementation".
6. **spec-0219 leaks RESULTS_DIR** into commands that scenarios start. Say
   that a scenario's commands see only the environment its steps set.
7. **The README says @wip goes on a scenario only,** but the parser lets it
   reach scenarios from a rule or feature.

## B. Contracts to state

8. **SpecParsed:** say `:component`/`:context` are model ids, and add their
   display names.
9. **KnowledgeIndexed:** the event carries extra `:status`, `:need` and
   `:reasons` that check and site rely on. `:shape` says `:pin`, the index has `:pins`.
10. **Status report vs VerificationDerived:** two shapes for unknown and
    unreadable results. Pick one. Also: does status exit 0 when scenarios fail?
11. **Any task that derives status must subscribe to SpecParsed.** That is an
    ordering rule the model doesn't show.
12. **Output formats builders invented:**
    - `--events` lines
    - warning lines
    - cannot-run lines
    - `ratified.edn`'s `:reason`
    - `score.edn`'s layout
    - the scoreboard
    - release refusal lines
    - the site's `:files` digests
13. **Rule names:** every builder invented its own (check-*, views-*,
    verdicts-*, ...). Either list them in the spec or leave them free.
14. **Init's configuration:** "every directory the tasks read" is unlisted,
    there is no default id prefix, and no rule for a valid prefix.

## C. Scenarios hard to observe

15. **spec-0173:** "for that scope" is unobservable for ratify, which ignores
    `:scope`. spec-0200 sits in scorer.feature but tests checklist.
16. **spec-0214:** its Given covers two cases, but there is one When.
17. **spec-0220:** observable only through checklist.
18. **spec-0215:** `:room-tree` is 7 characters, so the full hash is lost.
    Say whether the short form is intended.
19. **spec-0092:** the "five component pages" leave out the kernel.
    spec-0050: "one stamped scenario" can't pass the model lint.
20. **Citation resolution sits in two components.** spec-0135 and spec-0187
    are in the spec check, but knowledge resolves citations.

## D. Process

- **A cycle has one scope; this cycle had eleven.** Allow a list of scopes, or
  run one cycle per scope.
- **The kernel isn't a bounded context but needs regenerating.** Fence
  accepted `--context kernel`; the spec says nothing about it.
- **One builder wrote scratch files outside its room** (in the integrator's
  scratchpad). Nothing was lost, but the fence relies on the builder obeying
  "no reads or writes outside the room". Löyly fences by allow-list: a builder
  should run where nothing else is reachable.
- **Two builders (fence, init) wrote code before step definitions,** so not
  test-first.
- **spec-1 is a local-only cut.** The remote refused the tag push (HTTP 403),
  and cut.feature says a cut pushes its tag. On the spec owner's decision,
  spec-1 was tagged by hand on d89f6c3 in the working clone only, with
  origin/HEAD set locally to the branch. Say whether a cut may stay local
  when the remote refuses the tag.
