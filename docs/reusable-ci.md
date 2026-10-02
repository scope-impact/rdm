# RDM's gates in your CI

Any repository can run RDM's gates in GitHub Actions without copying them.
RDM publishes three pieces. Each one installs RDM from the revision you pin,
never by name from a package index. (PyPI's `rdm` is another project.)

| Piece | Use it for | Pinned as |
| --- | --- | --- |
| Reusable workflow `.github/workflows/gates.yml` | the whole job: your acceptance tests, then the design gate, verify, the release gate, graph validation and the evidence bundle | `scope-impact/rdm/.github/workflows/gates.yml@<ref>` |
| Composite action `actions/gates` | the gates as steps in a workflow of your own, after your tests | `scope-impact/rdm/actions/gates@<ref>` |
| Composite action at the root | rendering the documents to PDF, in the RDM image of the same release | `scope-impact/rdm@<ref>` |

`rdm adopt` lays down a workflow that calls the reusable workflow, pinned to
the RDM release that ran the adoption. RDM's own CI calls the same workflow,
pinned to the commit under test, so the workflow you call is the one RDM
gates itself with.

## The reusable workflow

```yaml
# .github/workflows/design-controls.yml
name: Design controls
on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

jobs:
  design-controls:
    uses: scope-impact/rdm/.github/workflows/gates.yml@v1.2.0
    with:
      rdm-ref: v1.2.0                     # the same ref as after the @
      install-command: pip install -e .   # your own dependencies, if the tests need them
```

A reusable workflow cannot see the ref it was called at, so you write the
ref twice: after the `@`, and as `rdm-ref`. The workflow checks out RDM at
`rdm-ref` and installs RDM, `pytest` and `allure-pytest` from that checkout.
To upgrade, change both refs together.

A tag can be moved; a commit sha cannot. For the strictest pin, use the
release's commit sha in both places, with the tag as a comment:
`gates.yml@<sha> # v1.2.0`. Dependabot's `github-actions` ecosystem keeps both
the sha and the comment current. RDM pins its own third-party actions that way.
The workflow asks only for `contents: read`. Inputs reach its scripts through
environment variables, never spliced into the script text.

| Input | Default | |
| --- | --- | --- |
| `rdm-ref` | — (required) | the RDM tag or sha the workflow is pinned to |
| `rdm-repository` | `scope-impact/rdm` | a fork, say |
| `python-version` | `3.12` | |
| `dhf` | `dhf` | the design history file |
| `install-command` | none | installs your dependencies before the tests |
| `acceptance-tests` | `true` | `false` until you have a tagged acceptance test |
| `test-command` | `pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results` | |
| `allure-results` | `dhf/allure-results` | where `test-command` writes its results |
| `release-gate` | `true` | `false` until your first design input lands |
| `graph-validate` | `true` | the gate rules as SHACL, plus graph-only warnings |
| `checklists` | none | space-separated checklists for graph validation (`rdm gap --list`) |
| `evidence-bundle` | `true` | writes the release evidence bundle, including the [verification report](gates.md#the-verification-report) PDF, and uploads it as `rdm-evidence` |
| `allure-report` | `true` | renders the Allure HTML report and uploads it as `allure-report` |

The verification record (the Allure results and `verification.yml`) is
uploaded as `verification-record`, even when a gate fails.

A required status check on the caller's side is named after both jobs:
`design-controls / gates`.

### Phased adoption

A freshly adopted repository has no design input and no tests yet. The
workflow `rdm adopt` lays down sets `acceptance-tests: false` and
`release-gate: false`, so only the design gate runs. Set both to `true` when
your first design input and its tagged test land.

## The gates action, in a workflow of your own

When your tests need their own setup (services, a build matrix, another
language), run them your way, then add the gates as a step:

```yaml
    steps:
      - uses: actions/checkout@v7
        with:
          fetch-depth: 0
      # ... your own setup and tests, writing Allure results to dhf/allure-results
      - uses: scope-impact/rdm/actions/gates@v1.2.0
        with:
          checklists: 62304_2015_class_b
```

The action installs RDM with the `graph` extra from its own revision, then
runs the same steps the reusable workflow does. It takes the workflow's gate
inputs (`dhf`, `allure-results`, `release-gate`, `graph-validate`,
`checklists`, `evidence-bundle`), plus `artifact-name`, `python-version`, and
`install-rdm` (`false` when RDM is already installed from the same revision).
Verify and the evidence bundle run only when there are Allure results.

## Rendering the documents

```yaml
      - uses: actions/checkout@v7
      - uses: scope-impact/rdm@v1.2.0
```

The action runs `make pdfs` in `ghcr.io/scope-impact/rdm` and uploads the
PDFs. The image tag follows the action's ref: `@v1.2.0` renders with image
`1.2.0` and `@v1` with image `1`. A branch or a sha falls back to `latest`.
Set `version` to choose the image yourself.
