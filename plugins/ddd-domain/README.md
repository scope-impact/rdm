# ddd-domain

A project's domain model, `dhf/spec/domain.edn`, held to the rules of
domain-driven design: what an event storm decides, kept as data. Until spec-25
this was rdm-spec's core component `architecture/domain`; no project used it,
and its shape is one school of DDD, so it became a template (ADR 0003).

- `domain.feature`: the rules, as the core held them (the file's shape, every
  element sourced, and eight DDD rules: one handler per command, no silent
  command, no orphan event, read models fed by events, invariants verified by a
  stamped scenario, no aggregate reading another context's, every emitted event
  catalogued). A project that adds this plugin copies these scenarios into its
  own spec, under a component of its own, and changes them as its model grows.
- `lint.clj`: the lint, a subscriber to `SpecParsed` (every stamped scenario's
  id, for "verified by a stamped scenario"), reading the configuration's
  `:model`, `:events` and `:raw-dir`. Written by halla-health's wallet, the
  first project that added this plugin, and brought back as version 2
  (spec-32). `bb lint.clj [DOMAIN]`, DOMAIN defaulting to
  `dhf/spec/domain.edn`; a project with no domain model passes.
