import shutil
from importlib.resources import files, as_file
from pathlib import Path

from rdm.kernel.version import release_version

NEXT_STEPS = """\
Next steps (see {out}/AGENT_WORKFLOW.md for the design-controls loop):
  1. Fill in {out}/data/device.yml: your device's name, manufacturer, version and safety class
  2. Replace the placeholders in {out}/documents/ (lines starting TODO, and TODO ... ENDTODO blocks)
  3. Render: cd {out} && make            (PDFs: make pdfs, needs Pandoc and Typst; Word: make docs)
  4. Check coverage: rdm gap 62304_2015_class_b {out}/documents/*.md {out}/documents/**/*.md
     (the templates already reference every clause, so a pass means referenced, not written)\
"""


def init(output_directory):
    init_files_ref = files(__package__) / 'init_files'

    # Use context manager to get actual file system path
    with as_file(init_files_ref) as init_directory:
        shutil.copytree(init_directory, output_directory)

    # The agent runbook is shared with the adopt scaffold (one source of
    # truth): an init project gets the same traceable-loop procedure the
    # brownfield path lays down, at the project root the templates reference.
    runbook_ref = files(__package__) / 'adopt_files' / 'dhf' / 'AGENT_WORKFLOW.md'
    with as_file(runbook_ref) as runbook:
        shutil.copy(runbook, Path(output_directory) / 'AGENT_WORKFLOW.md')

    # The container installs the RDM release that laid the project down, as
    # adopt pins the CI workflow to it.
    dockerfile = Path(output_directory) / 'Dockerfile'
    dockerfile.write_text(dockerfile.read_text().replace('{rdm_version}', release_version()))


def init_command(output_directory) -> int:
    """Run `rdm init`: refuse a directory that exists, else lay the project down
    and say what was laid down and what to do next."""
    out = Path(output_directory)
    if out.exists() or out.is_symlink():
        print(f"Error: {out} already exists: rdm init lays a new project into a new directory "
              "(to bring an existing repository under design controls, use rdm adopt)")
        return 2
    init(str(out))
    laid = sorted(p.relative_to(out).as_posix() for p in out.iterdir())
    print(f"Laid down a new project in {out}: {', '.join(laid)}\n")
    print(NEXT_STEPS.format(out=out))
    return 0
