# Research: affordance contracts for agent-designed UI

- **Written:** 2026-10-27, by the spec owner's session (Sathish Narayanan, with Claude).
- **What it is:** research and an experiment, not an external source. It is the
  ground for a coming spec change (Löyly's spec-4; possibly rdm-spec's spec-25)
  and is cited from there.
- **The experiment's code:** a local git repository,
  `/home/user/loyly-compare/webmcp-example` at `e3b7500` (not pushed).
- **The shared document:** https://claude.ai/artifact/Dwxh3RhYtBu55rw67chLCQ
  (the same text, with a drawn diagram).

A UX contract held as data (the affordances: what can be done, by whom, where,
and what it must say) lets an agent redesign a UI freely while every design is
judged the same way, mechanically. On the Löyly sauna app, three designs in two
design systems (shadcn/ui on React, Basecoat on plain HTML) each passed all 29
conformance rules from one framework-free contract, and designs that broke it
failed exactly where they broke it.

## The problem

When an agent designs the UI, the design differs on every regeneration, and
every usual way of judging it breaks.

| Way of judging | Why it fails on a regenerated UI | Seen in |
|---|---|---|
| Screenshot or snapshot tests | they hold the layout and wording, so a correct redesign turns them red | rdm-spec's first oracle held wording no spec stated and went red on correct builds (cycles 17 to 20) |
| A person judging every build | too slow and too costly, and two people judge differently | |
| The builder's own tests | they can reach the app through a back door the UI never offers | Löyly cycle 03: the builder's tests configured sauna days through `PUT /api/rooms/{room}/days`, which no page offered |
| A black-box agent | it can only judge what it can find | Löyly cycle 03: 11 of 11 scenarios passed the builder's tests and verify, then 0 of 11 for a black-box grader that found no way to set up a sauna day |

The cycle 03 result is the whole argument in one line: the app was correct and
unusable, and nothing but a black-box grader noticed. The fix is the rule that
already fixed rdm-spec's oracle: **judge only what the contract states, and
leave everything else to a person, on purpose.**

## The model

```
                 affordances.yaml   (actions, screens, must and seen, tokens, bindings)
          ┌─────────────┼──────────────────────────┐
          ▼             ▼                          ▼
   UI design,       WebMCP tools               step vocabulary
   any binding      (agents: the same forms)   (tests: forms or tools)
   (people)
          └─────────────┼──────────────────────────┘
                        ▼
                  one execute: the action's call  ──▶  the app's API (unchanged)

 What judges each design, cheapest first:
   1 Conformance   every build, a gate; the rules come from the yaml
   2 Behaviour     the builder's tests, with evidence attached
   3 Black box     a grader drives only the forms or the tools
   4 Posterior     labels across cycles: how far to trust 1 to 3
   5 A person      only what the contract leaves as "seen"
```

The contract is the only thing held fixed. A UI design, the WebMCP tools and
the scenarios' steps are three renderings of it, and all three reach the app
through the one call each action declares.

## The contract

`affordances.yaml` is the UI's spec: an agent may build any UI that realises it.
It follows the shape of Halla Health's Translation `affordances.json` (actions,
audience, screens with `must` and `seen`, a step vocabulary;
`scope-impact/halla-health-content`, `dhf/sdd/SDD-Translation/affordances.json`,
version 2) and adds a tool binding per action, abstract components and a
conformance list.

| Section | Holds | Read by |
|---|---|---|
| `actions` | per action: `intent`, `audience`, `input` (each with an abstract `component`, `type`, `label`), `outcomes`, `rejections`, `submit`, the API `call`, and the `tool` name | WebMCP (tool name, description, schema), every renderer (forms, labels), tests |
| `rejections.*.offers` / `links` | what a refusal offers instead: the action filled in, or a link | renderers; the check that a refusal is never a dead end |
| `screens` | per screen: `path`, `offers`, `links`, `reachedFrom`, `must` (heading, buttons), `seen` | renderers; the reachability and `must` checks |
| `ui` | which abstract component realises an action, a submit, an outcome, a refusal; the tokens file | bindings |
| `conformance` | the rules every design is held to | `conformance.cjs` |
| `steps` | the scenarios' vocabulary, each phrase mapped to an action | a harness that drives either the forms or the tools |

**Fixed against free.** The split is Halla's `must`/`seen`:

- **Fixed (contract):** which actions exist and who may use them; their
  inputs, outcomes and refusals; where each action is offered and how a screen
  is reached; the `must` texts; the WebMCP tools.
- **Free (the designer's):** layout, flow, navigation style, which widget
  within the abstract component, wording outside `must`, visual design within
  the tokens.

**One execute.** A person's form submit and an agent's tool call both make the
action's `call`, so the tool cannot keep working while the form is broken. With
declarative WebMCP the tool *is* the form (`toolname` on the `<form>`).

## Framework-agnostic by three layers

The contract names no framework. It names abstract components (Open UI and ARIA
names: `textbox`, `select`, `checkbox-group`, `button`, `card`, `alert`); a
design system joins through a binding that says which of its components
realises each one and how to recognise it from outside; the theme is design
tokens every binding reads.

| Layer | File | Follows |
|---|---|---|
| Contract | `affordances.yaml` | Open UI component names, ARIA roles |
| Theme | `tokens.json`, written to `tokens.css` | Design Tokens Format Module 2025.10 (DTCG; a Community Group report, stable, not a W3C Recommendation) |
| Binding | `bindings/<system>.yaml`: abstract component to a CSS selector that matches only that system's component | one file per design system |

What makes a design system bindable is a stable mark the check can read from
outside:

| Design system | Runs on | Recognised by | Status here |
|---|---|---|---|
| shadcn/ui | React | `data-slot="button"`, `"input"`, `"card"` | built and measured |
| Basecoat | plain HTML and CSS, no framework | classes `btn`, `input`, `select`, `card`, `alert`, `fieldset` | built and measured |
| Ark UI / Zag.js (Chakra v3, Park UI) | React, Vue, Solid, Svelte | `data-scope` + `data-part`, plus `data-state` | researched |
| Web Awesome (Shoelace's successor) | native web components | element names, `<wa-button>` | researched |
| Material Web, Adobe Spectrum Web Components | web components | `<md-filled-button>`, `<sp-button>` | researched |
| GOV.UK Design System, USWDS | server-rendered HTML | BEM classes `govuk-button`, `usa-button` | known; not covered by the search |

Every rule but one is the same for any system: forms and `toolname`s, WebMCP
registration, accessible labels, `must` texts, reachability, the one execute,
refusals, the theme. Only "each control is the design system's" reads the
binding.

## What we built and measured

Every correct design passed all 29 rules, twice, and every rule-breaking
design failed exactly the rules it broke. All designs sit in front of the
unchanged Löyly app (rdm-spec cycle 03's build) and use its API; the check runs
black box with Playwright from `/`.

| Design | Rendered by | Judged by binding | Rules held |
|---|---|---|---|
| `stacked`: one column of cards | shadcn/ui, React 19, prerendered | shadcn | 29 of 29 |
| `grid`: two columns, fields side by side | shadcn/ui, React 19, prerendered | shadcn | 29 of 29 |
| `plain` | Basecoat, HTML from the Babashka server, no framework | basecoat | 29 of 29 |
| `rogue`: a raw red-bordered input, no link to Manage | shadcn/ui | shadcn | 16 of 21 |
| `rogue`: the same two faults | Basecoat | basecoat | 16 of 21 |
| `stacked`, judged by the wrong system | shadcn/ui | basecoat | 20 of 29 |
| `plain`, judged by the wrong system | Basecoat | shadcn | 20 of 29 |

Both rogue designs fail the same five rules: the Apartment input is not its
declared `textbox` and not the design system's (on two forms each), and Manage
is not reachable from `/`. They are judged on 21 rules because the check never
reaches the Manage screen. The wrong-binding rows show the check tells the
systems apart.

**The 29 rules**, per screen and per action:

- **Contract:** forms match the screen's `offers`; no undeclared `toolname`;
  WebMCP registers exactly those actions; the `must` heading and buttons are
  present; every screen is reachable by links from `/`.
- **Components and accessibility:** every input is found by its contract label
  (`getByLabel`, or a `group` named by the label); each is its declared
  abstract component; every control is the design system's, inside a card; no
  inline style or arbitrary colour.
- **Behaviour:** a person's Save makes `PUT /api/rooms/<room>/days`; an agent's
  `requestStandingSlot` tool gets a 201 through the same execute; a refusal
  offers the free slot as a filled-in button; `--primary` equals
  `tokens.json`'s colour in both systems.

Three faults found on the way were in the harness, not the designs: Radix's
hidden checkbox inputs carry inline styles (now skipped); the first tool result
let the app's `status: "confirmed"` overwrite the HTTP status (now
`{status, answer}`); and the check had to set up its own state (it now uses a
fresh dressing room each run).

## Research lineage

Every piece has prior art; what we did not find anywhere is the combination: a
contract held as data, a UI an agent generates freely, and a gate that judges
the UI against the contract, with discoverability as a gate and human judgement
kept for what the contract leaves open.

| Work | Kind | The idea | What we take |
|---|---|---|---|
| [Don Norman, "Signifiers, not affordances"](https://jnd.org/signifiers-not-affordances/) (ACM Interactions, 2008) | essay | an affordance must be signalled to be used | cycle 03's app had the action and no signifier |
| [Cameleon Reference Framework](https://www.w3.org/community/uad/wiki/Cameleon_Reference_Framework) (EU CAMELEON; W3C MBUI) | research framework | task model, abstract UI, concrete UI, final UI | `affordances.yaml` is the abstract UI; a design is a concrete UI; its open problem (edits flowing back up) is why we gate instead of derive |
| [Naked Objects](https://causeway.apache.org/docs/2.0.0/_attachments/Pawson-Naked-Objects-thesis.pdf) (Pawson, PhD thesis 2004; Apache Causeway) | pattern | the UI generated entirely from domain actions | the guarantee that no action is hidden, without its generic UI |
| [Daniel Jackson, *The Essence of Software*](https://press.princeton.edu/ideas/daniel-jackson-on-the-essence-of-software) | book | concepts with purpose, actions and an operational principle | `intent`, `actions`, scenarios; effects arrive later, hence `outcomes` and events |
| JavaServer Faces | framework | managed-bean bindings; render kits draw one component tree many ways | WebMCP as a second render kit; unlike JSF, the binding lives outside the view |
| [HATEOAS](https://four.htmx.org/essays/hateoas), [Mike Amundsen](https://www.infoq.com/presentations/Building-Hypermedia-API), [ALPS](https://datatracker.ietf.org/doc/draft-amundsen-richardson-foster-alps/), HAL-FORMS, Siren | hypermedia | the server sends the next actions with each response | forms and links from `/` as the agent's map |
| [Apple App Intents](https://developer.apple.com/shortcuts/) (WWDC22) | platform | an app declares its actions once; Siri, Shortcuts, Spotlight use them | one declaration, many renderers, at OS scale |
| [WebMCP](https://developer.chrome.com/docs/ai/webmcp/declarative-api) (W3C WebML CG, Chrome trial) | proposed standard | forms annotated `toolname`, or tools registered from script | the agent renderer, from the same contract |
| [VOIX](https://arxiv.org/abs/2511.11287) (arXiv 2511.11287), [signifiers for agents](https://arxiv.org/pdf/2302.06970) (arXiv 2302.06970) | papers | sites declare actions and state to agents; Norman's signifiers for agents | agents as users need declared affordances |
| [A2UI](https://developers.googleblog.com/en/a2ui-and-mcp-apps/), MCP Apps, AG-UI | generative UI | constrain how an agent describes a UI, or let it return HTML | the reverse of ours: they constrain the generator, we judge the result |
| [GUISpector](https://arxiv.org/pdf/2510.04791) (arXiv 2510.04791) | paper | an LLM agent checks requirements against GUI prototypes | the black-box grader, judged against prose; ours judges against data |
| [LLM-generated UIs and accessibility](https://dl.acm.org/doi/10.1145/3744257.3744266) (ACM 2025) | study | generated UI code has critical accessibility violations | automated scan plus expert review: our gate plus a person |
| [The accessibility tree as the agent's interface](https://www.infoworld.com/article/4193332/accessibility-is-the-first-class-interface-for-ai-agents.html); [Testing Library](https://testing-library.com/docs/api-helpers) | practice | Playwright MCP and browser agents read ARIA roles; tests query by role | one check serves screen readers, agents and tests |
| [Ink & Switch, End-user Programming](https://www.inkandswitch.com/end-user-programming/) (2019); [Kent C. Dodds](https://blog.kentcdodds.com/write-tests-not-too-many-mostly-integration) | essays | internal and external APIs mostly the same; tests that resemble use | the builder's tests go through the same door as people |
| [Open UI](https://open-ui.org/design-system-analysis-guide) (W3C WICG), [DTCG 2025.10](https://www.w3.org/community/reports/design-tokens/CG-FINAL-format-20251028/) | standards work | shared component names and anatomy; a tokens format | the abstract components and the theme |

## Limits and open questions

- **No agent has designed a UI yet.** The five designs were written by hand as
  variants; whether agent designs converge on the contract is the next
  experiment.
- **WebMCP was stubbed.** The checks record what `navigator.modelContext` is
  given; no WebMCP browser was used, and the API is still a proposal in
  Chrome's trial.
- **Refusal texts are not held.** The contract's `must` rejection texts are
  new; the app still says its own words, so no rule checks them yet.
- **`audience` is declared, not enforced.** The app has no sign-in, so anyone
  can configure days. In Halla, tools would be filtered by the user's role.
- **Usability stays with a person.** The rules prove every affordance exists,
  is findable and works; whether a resident finds it clear is a sampled human
  judgement, recorded as a label for the posterior.
- **Open:** should rdm-spec lint `affordances.yaml` the way it lints
  `domain.edn`? Should a binding be an rdm-spec concept or stay the project's
  own?

## Next steps

1. Put `affordances.yaml` into Löyly's spec (spec-4) with the manager scenario,
   and make the conformance check Löyly's verify probe.
2. Let three builder agents each design the UI from the same contract, free on
   layout, and gate each with the conformance check and a black-box grader.
3. Add a third binding (Ark UI or Web Awesome) to test the agnostic claim
   beyond shadcn's look.
4. Add `tool` bindings to Halla's Translation `affordances.json` and a WebMCP
   provider in the Payload admin.

## Sources

The lineage table links each work. The figures here come from our own runs.
Most web sources were read through search summaries, not opened in full.

- [shadcn-ui/ui](https://github.com/shadcn-ui/ui) at `8f04138`: the shadcn/ui
  components and theme used, copied unmodified
- [Basecoat](https://basecoatui.com/introduction/)
  ([GitHub](https://github.com/hunvreus/basecoat)), npm `basecoat-css` 1.1.0
- [Ark UI styling guide](https://ark-ui.com/docs/guides/styling) and
  [Zag.js](https://zagjs.com/overview/introduction)
- [Web Awesome](https://blog.openreplay.com/framework-agnostic-ui-web-awesome/)
  and [Shoelace](https://shoelace.style/)
- [Chrome: WebMCP declarative API](https://developer.chrome.com/docs/ai/webmcp/declarative-api)
- [Design Tokens Format Module 2025.10](https://www.w3.org/community/reports/design-tokens/CG-FINAL-format-20251028/)
- [Open UI design system analysis guide](https://open-ui.org/design-system-analysis-guide)
- [W3C MBUI introduction](https://www.w3.org/2011/mbui/drafts/mbui-intro)
- Halla Health's Translation contract: `scope-impact/halla-health-content`,
  `dhf/sdd/SDD-Translation/affordances.json` (version 2)
- Löyly with rdm-spec: cycle 03's record, `loyly-compare/loyly-rdm/dhf/cycles/03`
  (local)
