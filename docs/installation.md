# Installation

RDM is a Python CLI (Python 3.10+). Install it from its repository, pinned to
a release; PyPI's `rdm` is another project.

```bash
pip install "rdm @ git+https://github.com/scope-impact/rdm@v2.0.0-alpha"         # core: record, gates (rdm story …), gap, render, init, adopt
pip install "rdm[graph] @ git+https://github.com/scope-impact/rdm@v2.0.0-alpha"  # + the record as an RDF graph and the agent server (rdm graph …)
pip install "rdm[report] @ git+https://github.com/scope-impact/rdm@v2.0.0-alpha" # + the verification report as PDF (rdm story evidence-report)
```

With [uv](https://docs.astral.sh/uv/) inside a project:

```bash
uv add "rdm @ git+https://github.com/scope-impact/rdm@v2.0.0-alpha"
```

In CI, call RDM's reusable workflow instead of installing it:
[the gates in your CI](reusable-ci.md).

Check the install:

```bash
rdm --version
rdm --help
```

## Optional tooling

| Tool | Needed for |
|---|---|
| [Pandoc](https://pandoc.org/) (+ LaTeX) or Typst | converting rendered Markdown to PDF/DOCX |
| `pytest` + `allure-pytest` | producing the executed verification evidence the gates consume |
| `git` | the record itself — approval, history, baselines |

## Platform note

The CLI is cross-platform Python; the *enforcement* pieces — the pre-commit
design gate, `scripts/agent-bootstrap.sh`, and the example's `setup.sh` — are
bash scripts. On Windows, run them under Git Bash or WSL.

## For contributors to RDM

```bash
git clone https://github.com/scope-impact/rdm && cd rdm
uv sync --all-extras
uv run pytest tests
```

Contributions follow RDM's own design controls — read the
[agent workflow](agent-workflow.md) before changing behavior.
