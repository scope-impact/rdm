# Ticket 06: The Gate, the Check and delivery, for the later slices' tasks

Slice: `08-plugins` (`plan.md`, Scope In: "subscribers and answerers (Gate,
Check)", ":CycleVerified"; "The oracle task: ... nothing fenced or verified
while it is red"); `plugins.feature`, Rules "An answerer is asked at its
request's place" and "A plugin can follow a verified cycle";
`oracle.feature`, Rule "Nothing is fenced or verified while the oracle is
red". Builds on ticket 05. Every scenario here runs fence or verify, built
by slice 06 in cycle 7: they are pending until then (`pending.md`), and
this ticket is done on its unit tests and a stand-in caller.

## What it delivers

kernel/plugins' `kernel-plugins` interface whole, for the slices that import
it (ED-03):

- `gate`: every plugin answering `:Gate`, in the order declared, given
  `{:request :Gate :task :TASK :cut CUT :cycle CYCLE}` (spec-0358; G-04 for
  fence's) in the project's root; the first that fails stops it (G-03); its
  answer relayed by the task unchanged (spec-0357, spec-0232).
- `ask`: the plugin named answering `:Check`, of the configuration read at
  the root given, run in the checkout with `{:request :Check :cycle :cut
  :checkout :results}` (spec-0362, used by verify's `:plugin` check; the
  results held to the mapping are verify's, spec-0391); a name no plugin
  answering `:Check` bears, an error (spec-0363).
- `deliver`: a task's events, given to their subscribers as the plugins
  stage does (ticket 03); verify gives it CycleVerified once every check
  passed and `ratified.edn` is written (spec-0383, spec-0384); status (slice
  05) what it publishes.

## Scenarios it satisfies

- `@spec-0357` Gate plugins run before fence and verify, in the order declared, and stop them while one fails, `plugins.feature`
- `@spec-0358` A Gate plugin is given the task it gates, `plugins.feature`
- `@spec-0232` Fence and verify refuse while the oracle fails (2 rows), `oracle.feature`
- `@spec-0383` A subscriber to :CycleVerified runs after verify passes, given the cycle, `plugins.feature`
- `@spec-0384` A cycle that is not verified publishes nothing, `plugins.feature`

5 scenarios, pending until cycle 7 (slice 06: fence, verify).

## Components and interfaces it touches

kernel/plugins (`kernel-plugins`: `gate`, `ask`, `deliver`), kernel/bus
(`route`).

## Done when

kernel/plugins' unit tests cover the Gate's input and order and its stop, a
Check asked in a checkout and a name not answering `:Check`, and a delivery
of CycleVerified's EDN; a test component standing in for slice 06, composed
against the slice, calls each through `kernel-plugins`; earlier tickets'
results unchanged. In cycle 7 the scenarios above, and verify's of
`08-plugins/verify.feature`, are verified with slice 06.
