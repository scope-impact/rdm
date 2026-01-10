<a href="https://github.com/scope-impact/rdm/actions/workflows/tests.yml/">
  <img src="https://github.com/scope-impact/rdm/actions/workflows/tests.yml/badge.svg?branch=main">
</a>

# Regulatory Documentation Manager

> **Fork Notice:** This is a maintained fork of [innolitics/rdm](https://github.com/innolitics/rdm). All credit for the original work goes to the [Innolitics](https://innolitics.com) team.

RDM is a documentation-as-code tool that provides Markdown templates and Python scripts to manage medical device software documentation. It's especially well-suited for software-only medical devices following IEC 62304.

## Quick Start

### Docker (Recommended)

```sh
# Install rdm CLI
uv tool install git+https://github.com/scope-impact/rdm

# Scaffold project and build documents
rdm init
cd dhf
docker compose run rdm make pdfs
```

### Native Installation

```sh
# Install rdm CLI
uv tool install git+https://github.com/scope-impact/rdm

# Install dependencies (macOS)
brew install pandoc typst

# Scaffold project and build documents
rdm init
cd dhf
make pdfs
```

### Update

```sh
uv tool upgrade rdm
```

## GitHub Action

```yaml
# .github/workflows/pdfs.yml
name: Generate PDFs

on:
  push:
    paths: ['dhf/**']
  workflow_dispatch:

jobs:
  pdfs:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: scope-impact/rdm@v1
```

| Input | Description | Default |
| --- | --- | --- |
| `dhf_path` | Path to the DHF directory | `dhf` |
| `version` | RDM Docker image version | `latest` |
| `artifact_name` | Name for the uploaded artifact | `regulatory-documents` |

## Dependencies

**Docker (recommended):** Just Docker. The image includes Pandoc 3.6, Typst 0.12, and required fonts.

**Native:**
- Python 3.10+
- [uv](https://github.com/astral-sh/uv)
- [Pandoc](https://pandoc.org/) 2.14+
- [Typst](https://typst.app/)
- Make

## Documentation

For detailed documentation on templating, data files, project management backends, and more, see the [original RDM documentation](https://github.com/innolitics/rdm#readme).

## Development

```sh
git clone https://github.com/scope-impact/rdm.git
cd rdm
uv sync --all-extras
uv run pytest tests
```

## Changes from Upstream

### v1.0.0

- Installation via `uv tool install` directly from GitHub
- Migrated from LaTeX to Typst for PDF generation
- New lightweight Docker image (Alpine + Pandoc 3.6 + Typst 0.12)
- Added GitHub Action for CI/CD (`scope-impact/rdm@v1`)
- Fixed broken cross-references in software_plan.md template

## License

[MIT](LICENSE.txt) - Original work by [Innolitics](https://innolitics.com)
