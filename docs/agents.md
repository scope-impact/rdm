# For agents

An agent working on a regulated product needs the same answers a reviewer
does — what does this design input require, which tests verify it, did they
pass at this commit, which risks does it control — and it must not be able to
change the answers. `rdm graph mcp` gives it the record as a read-only
knowledge source: it reads the current Markdown and test results on every
call, and it has no way to write.

## Register the server

`rdm graph mcp` serves the record to an agent as a
[Model Context Protocol](https://modelcontextprotocol.io) server over stdio,
which most agent harnesses speak. Register it once; for Claude Code, in the
project's `.mcp.json`:

```json
{
  "mcpServers": {
    "rdm": {
      "command": "rdm",
      "args": ["graph", "mcp", "--dhf", "dhf", "--allure-results", "dhf/allure-results"]
    }
  }
}
```

Other harnesses take the same command and arguments in their own MCP
settings.

## The tools

| Tool | Answers |
| --- | --- |
| `trace` | a `UN-n`, `DI-n` or risk id: text, contexts, owning document and last commit, needs refined, tagged tests and runs, the components its runs name and, apart, reach, risks controlled; for a risk, its chain, scores and controlling inputs ([risk register](risk.md)) |
| `query` | any read-only SPARQL (SELECT, ASK, CONSTRUCT, DESCRIBE), prefixes predeclared, capped at 200 rows by default with `truncated` saying when |
| `schema` | the vocabulary, prefixes and the rules for derived relations, so the agent can write its own queries |
| `validate` | the gate shapes' results: violations and warnings |

Queries see the derived relations too (for example `rdm:serves`), in the
separate inferred graph ([derived relations](graph.md#derived-relations-rules-not-facts)).

Every call projects the record afresh (a fraction of a second), so an agent
editing a branch sees its own edits on the next call — there is no store to
rebuild.

## Read-only by construction

It cannot change anything or reach the network. There is no write tool,
`query` refuses `SERVICE` (a federated query would make the server send HTTP
requests), `trace` takes only an id, and `query` refuses SPARQL
Update. An agent that wants to change the record does what a person does:
edits the Markdown and tests, and opens a pull request for review.
