# Functional DDD in TypeScript

Guidance for a builder, not spec: the spec says what rdm-spec does, this says
one good shape for the code. Distilled from PensionBee's DDD workshop
(github.com/pensionbee/ddd-workshop, branch `final`), itself after Anthony
Manning-Franklin's "Functional Domain Driven Design: Simplified". Nothing here
is copied from it; where the spec and this skill disagree, the spec wins.

## Where code lives

The configuration's `:code-layout` gives each component one folder, so a cycle
can fence one component away. Inside it, the workshop's shape:

```
src/contexts/<context>/<component>/
  core/                      pure: no file, process, network or clock
    commandHandlers/<verb>.handler.ts
    entities/<thing>.ts      zod schema, its type, a parser
    events/<thing>.events.ts the events the component publishes
    policies.ts              "whenever EVENT, then COMMAND"
  infra/                     the only place with I/O
    repositories/<thing>Repository.ts
  interface/<task>.ts        the rdm-spec task: arguments in, result and findings out
src/contexts/kernel/<component>/   command, bus, runner: shared code no context owns
test/steps/<context>_<component>.ts
```

Unit tests sit beside what they test, as `<file>.spec.ts`.

## How a command handler reads

Every handler is the same five steps, in this order:

1. **Parse** the command's data with its zod schema; bad data stops here.
2. **Fetch** the state it needs, through repositories.
3. **Derive** the event: a pure function `(data, state) => Event`. All the
   rules live here, so they are tested without I/O.
4. **Update** state from the event, through repositories.
5. **Publish** the event on the bus and return it.

A refusal is an event too (`SCOPES_NOT_LISTED/NOT_A_CUT`), not a thrown
error: the deriver returns it, and the interface turns it into a finding or a
`cannot-run`.

## Data

- **Entities and events are zod schemas first,** types inferred from them.
  Anything read from disk (EDN, JSON, YAML) is parsed through a schema before
  the core sees it.
- **Events are `{type, payload}`.** rdm-spec's own events are the ones in
  `dhf/spec/events.edn`; their payloads carry exactly the keys it declares.
- **EDN in, EDN out.** rdm-spec's files and every task's standard output are
  EDN, so read and print them with one EDN library at the edge (`infra/`), and
  keep plain data in the core. Keywords stay keywords on output; check the
  printed shape against the scenario that pins it.

## The bus and policies

- **The kernel's bus publishes and subscribes in process,** within one run of
  the command, synchronously and in subscription order, as
  `dhf/spec/kernel/bus.feature` states. A context reacts to another only
  through it.
- **A policy is "whenever this event, then this command",** declared in its
  component's `core/policies.ts` and wired once at start.

## Testing

- **Derivers are tested as pure functions,** case by case: these are most of
  the unit tests.
- **Repositories are tested against real files** in a temporary directory you
  make and remove.
- **Scenarios run through rdm-spec's own runner,** its step definitions in
  `test/steps/`, writing Allure results as `dhf/spec/README.md`'s results
  contract states.
- **Run `tsc --noEmit` with the tests:** a type error is a failing build.

## Style

- **Functions and plain data:** no classes, no mutation outside
  repositories.
- **One exported function per handler, one schema per entity.** Name files
  for what they hold.
- **Comments say why, not what:** the spec already says what.
