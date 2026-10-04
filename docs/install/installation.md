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

The `v2.0.0-alpha` tag predates this revision of the manual. Until the next
release is tagged, install from `@main` to get everything described here.

| Extra | Adds |
|---|---|
| (none) | the record, the gates (`rdm story …`), `gap`, `render`, `init`, `adopt` |
| `graph` | the record as an RDF graph and the agent server (`rdm graph …`) |
| `report` | the verification report as a PDF (`rdm story evidence-report`); without it the evidence bundle records the report as not rendered |
| `validation` | Playwright, for formative usability-persona runs ([validation evidence](../use/validation-evidence.md)) |

The tests need `pytest` and `allure-pytest`. Rendering PDFs needs Pandoc,
Typst and Make, or the RDM Docker image, which has them all. In CI, call
RDM's reusable workflow instead of installing it:
[the gates in your CI](../use/ci.md). The CLI is cross-platform; the
pre-commit hook and the bootstrap script are bash, so on Windows run them
under Git Bash or WSL.

## Check the installation

```bash
rdm --version              # the release you pinned, in Python's form: v2.0.0-alpha prints 2.0.0a0
rdm story design-gate --help
```

If `rdm` is not found, the tool directory of `uv` is not on `PATH`: run
`uv tool update-shell` and open a new shell.

## Upgrade

1. Read the [revision history](../revision-history.md) for the releases
   between yours and the new one.
2. Reinstall at the new tag: `uv tool install --force "rdm @ git+https://github.com/scope-impact/rdm@<tag>"`.
3. In CI, change both references to the old tag in
   `.github/workflows/design-controls.yml` (the `uses:` line and `rdm-ref`).
4. Run the gates on your record, restart any running agent server, and
   repeat your validation of RDM for the new release
   ([Validating RDM for your use](../about/intended-use.md#validating-rdm-for-your-use)).

## Uninstall

```bash
uv tool uninstall rdm
git config --unset core.hooksPath     # in each clone, to turn the hooks off
```

Uninstalling loses no record: it is Markdown, tests and git history, readable
without RDM. Remove `.githooks/` and the CI workflow from the repository only
if you stop keeping the record under design controls.
