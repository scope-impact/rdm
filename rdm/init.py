import shutil
from importlib.resources import files, as_file


def init(output_directory):
    # Get reference to init_files directory
    init_files_ref = files(__package__) / 'init_files'

    # Use context manager to get actual file system path
    with as_file(init_files_ref) as init_directory:
        shutil.copytree(init_directory, output_directory)
