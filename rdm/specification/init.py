import shutil
from importlib.resources import files, as_file
from pathlib import Path

from rdm.kernel.version import release_version
from rdm.specification.spec_scaffold import lay_spec

NEXT_STEPS = """\
Next steps:
  1. Fill in {out}/data/device.yml: your device's name, manufacturer, version and safety class
  2. Replace the placeholders in {out}/documents/ (lines starting TODO, and TODO ... ENDTODO blocks)
  3. Render: cd {out} && make            (PDFs: make pdfs, needs Pandoc and Typst; Word: make docs)
  4. Check coverage: rdm gap 62304_2015_class_b {out}/documents/*.md {out}/documents/**/*.md
     (the templates already reference every clause, so a pass means referenced, not written)
  5. Write the spec in {out}/.pi/spec/core (accepted scenarios in spec.feature, each stamped
     @item-01-SSS; user needs as Allium surfaces and event-model screens), claim each requirement
     in its test with an Allure tag label of its id, and run
     rdm story release-gate --dhf {out} --allure-results <results>\
"""


def init(output_directory):
    init_files_ref = files(__package__) / 'init_files'

    # Use context manager to get actual file system path
    with as_file(init_files_ref) as init_directory:
        shutil.copytree(init_directory, output_directory)

    # The container installs the RDM release that laid the project down, as
    # adopt pins the CI workflow to it.
    dockerfile = Path(output_directory) / 'Dockerfile'
    dockerfile.write_text(dockerfile.read_text().replace('{rdm_version}', release_version()))
    # The spec, the Löyly way: config.yml names its plan (spec: .pi/plan).
    lay_spec(Path(output_directory))


def init_command(output_directory) -> int:
    """Run `rdm init`: refuse a directory that exists, else lay the project down
    and say what was laid down and what to do next."""
    out = Path(output_directory)
    if out.exists() or out.is_symlink():
        print(f"Error: {out} already exists: rdm init lays a new project into a new directory "
              "(to bring an existing repository under RDM, use rdm adopt)")
        return 2
    init(str(out))
    laid = sorted(p.relative_to(out).as_posix() for p in out.iterdir())
    print(f"Laid down a new project in {out}: {', '.join(laid)}\n")
    print(NEXT_STEPS.format(out=out))
    return 0
