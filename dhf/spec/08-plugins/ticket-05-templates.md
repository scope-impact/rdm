# Ticket 05: Templates: plugin add, carried by every build

Slice: `08-plugins` (`plan.md`, Scope In: "templates added and then owned");
`plugins.feature`, Rule "A plugin is added from the templates rdm-spec
carries, and then owned", and `decisions.md`, why each exists. Builds on
ticket 04.

## What it delivers

- The templates of the repository's `plugins/` carried inside
  kernel/plugins' component, every file of each (ED-05), so any copy of the
  build carries the same ones (spec-0364; G-10 for its "package" row).
- kernel/plugins, `templates` and `add` ("rdm-spec plugin add NAME"): a
  template rdm-spec carries copied into `dhf/harness/plugins/NAME/`,
  unchanged (G-12), with `origin.edn` holding exactly `{:template :version
  :copied}` (G-09), the configuration not changed, and the `:plugins` entry
  to add printed (G-08) (spec-0359); a directory already there: exit 1, naming
  it, copying nothing; a name no template bears: exit 2, naming it and
  listing every template with its version (spec-0360, spec-0364); any other
  argument than `add NAME`: exit 2, an unknown argument (`dhf/spec/README.md`).
- The tasks component's `plugin` entry, as spec-0176 writes it.
- An added template, declared as it says, runs under check and does not
  answer cannot run (spec-0405; G-11 for the row `affordances`, a plugin
  answering `:Check`, which check does not ask).

## Scenarios it satisfies

- `@spec-0359` plugin add copies a template into the project and says how to declare it, `plugins.feature`
- `@spec-0360` plugin add never overwrites and never guesses (2 rows), `plugins.feature`
- `@spec-0364` rdm-spec carries the same templates however it was installed (2 rows), `plugins.feature`
- `@spec-0405` An added template's plugin runs as it is declared (2 rows), `plugins.feature`

4 scenarios, verified in cycle 5 but for spec-0364's row "as a package (a
jar)", pending on G-10 (`pending.md`).

## Components and interfaces it touches

kernel/plugins (`templates`, `add`), the tasks component, the build of
kernel/plugins' crate (reading `plugins/`, a kept path).

## Done when

kernel/plugins' unit tests cover the templates carried, a copy and its
`origin.edn`, an existing directory and an unknown name; the built command,
copied with its host outside the repository, lists the same templates;
`wasm/test.sh` writes a passing result for each scenario above (its
`ddd-domain` row needs the template's interpreter on the tests' PATH);
earlier tickets' results unchanged.
