import os
from importlib.resources import files, as_file
from rdm.util import print_info, copy_directory, repo_root


def install_hooks(dest=None):
    hook_files_ref = files(__package__) / 'hook_files'

    with as_file(hook_files_ref) as hooks_source:
        if dest is None:
            root = repo_root()
            dest = os.path.join(root, '.git/hooks')

        print_info('Installing hooks into {}'.format(dest))
        copy_directory(str(hooks_source), dest)
        print_info('Successfully installed hooks')
