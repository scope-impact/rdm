# Guides

Every change goes round the same loop. Start with *Changing the record*;
the other guides go deeper on one part of it.

```
 the Gherkin spec ───► tag the tests ──► run the gates ──► review and merge
   (tagged scenarios)     (record)          (gates)            (your forge)
        ▲                                                       │
        └──────── query the graph, render the documents ◄───────┘
```

| Task | Page |
|---|---|
| Make a change, step by step | [Changing the record](changing-the-record.md) |
| Read the spec, write the tests that claim its requirements | [The spec and its tests](spec-and-tests.md) |
| Keep the risk register and its controls | [Risk register](risk-register.md) |
| Record validation evidence | [Validation evidence](validation-evidence.md) |
| Run the release gate | [The gates](gates.md) |
| Check documents against a standard's checklist | [Gap analysis](gap-analysis.md) |
| Run the gates in CI | [The gates in your CI](ci.md) |
| Query the record as a graph | [The record as a graph](graph.md) |
| Browse the graph | [Browsing in Graph Explorer](graph-explorer.md) |
| Let agents read the record | [For agents](agents.md) |
| Render documents | [Authoring and rendering](documents.md) |

When a step fails, see [Troubleshooting](../troubleshooting.md).
