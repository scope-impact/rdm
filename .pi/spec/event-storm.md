# RDM: Big Picture EventStorming

The human board; `event-storm.edn` is the same board as data, read by RDM's Löyly
adapter (an event an actor causes, produced by a screen's command, shows with that user
need).

| Phase | Event | Caused by |
|---|---|---|
| Specify (upstream) | Scenario stamped | spec author, in the spec's own factory |
| Bring a product under RDM | Project scaffolded · **Repository adopted** | regulatory author |
| Keep the record | Document rendered · Gaps reported · Coverage reported | regulatory author |
| Verify | **Verification recorded** · Graph built · Graph validated · Explorer file written · Record served to an agent | author, CI, agent |
| Validate | User need validated | reviewer |
| Release | **Release gate passed** (unhappy: Release blocked) · Evidence bundle written | release manager, CI |

Pivotal events in bold.
