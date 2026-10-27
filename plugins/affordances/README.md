# affordances

An affordance contract (what can be done, by whom, where, and what it must
say) and a conformance check that holds a served design to it, black box, from
the front page. The research: `dhf/raw/affordance-contracts.md`. Version 1 is
Löyly's, as cycle 04 used it.

- `affordances.example.yaml`, `tokens.example.json`: Löyly's contract and theme,
  as a starting point for a project's own `dhf/spec/`.
- `binding.yaml`: how the design system's components are recognised (Basecoat
  here; a shadcn/ui binding recognises `data-slot`).
- `conformance.cjs`: the judge (Playwright). Its contract rules are general;
  its behaviour rules (configuring, requesting, a refusal that offers a slot)
  name Löyly's actions and must be rewritten for a project's own.
- `probe.sh`: runs the judge; Löyly ran it as its `:serves` probe. As a plugin
  answering `:Check`, it needs the app's address, which a project passes in its
  command or starts the app itself.
