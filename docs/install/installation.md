# Installation

## System requirements

| Need | For | Version |
|---|---|---|
| Python | everything | 3.10 or later |
| git | the record, the gates, the hooks | any current release |
| `uv` (or `pip`) | installing RDM | current |
| `pytest`, `allure-pytest` | running tagged acceptance tests | current |
| Pandoc, Typst, Make | rendering documents to PDF | as in the RDM image |
| bash | the pre-commit hook and the agent bootstrap | Git Bash or WSL on Windows |

The RDM Docker image carries Pandoc, Typst, the fonts and RDM itself; use it
when you render PDFs and do not want to install them.

## Install

RDM is a Python CLI (Python 3.10+). Install it from its repository, pinned to
a release; PyPI's `rdm` is another project.

```bash
uv tool install "rdm @ git+https://github.com/scope-impact/rdm@v2.0.0-alpha"
uv tool install "rdm[graph,report] @ git+https://github.com/scope-impact/rdm@v2.0.0-alpha"   # with the extras
rdm --version
```

| Extra | Adds |
|---|---|
| (none) | the record, the gates (`rdm story …`), `gap`, `render`, `init`, `adopt` |
| `graph` | the record as an RDF graph and the agent server (`rdm graph …`) |
| `report` | the verification report as a PDF (`rdm story evidence-report`) |

The tests need `pytest` and `allure-pytest`. Rendering PDFs needs Pandoc,
Typst and Make, or the RDM Docker image, which has them all. In CI, call
RDM's reusable workflow instead of installing it:
[the gates in your CI](../use/ci.md). The CLI is cross-platform; the
pre-commit hook and the bootstrap script are bash, so on Windows run them
under Git Bash or WSL.

## Check the installation

```bash
rdm --version              # the release you pinned
rdm story design-gate --help
```

If `rdm` is not found, the tool directory of `uv` is not on `PATH`: run
`uv tool update-shell` and open a new shell.
