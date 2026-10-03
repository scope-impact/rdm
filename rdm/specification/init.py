import shutil
from importlib.resources import files, as_file
from pathlib import Path


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
