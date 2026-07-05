# Installation

RDM is a Python CLI (Python 3.9+).

```bash
pip install rdm                    # core: render, init, adopt, gap, collect, translate
pip install "rdm[story-audit]"     # + design controls & traceability (rdm story …)
pip install "rdm[github]"          # + GitHub project-management sync (rdm pm …)
```

With [uv](https://docs.astral.sh/uv/) inside a project:

```bash
uv add "rdm[story-audit]"
```

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
