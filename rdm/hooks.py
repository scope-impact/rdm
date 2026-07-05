import os
import shutil
from importlib.resources import files, as_file
from rdm.util import print_info, repo_root

# The design-controls gate: installed by default (DI-26). The remaining hooks
# enforce an issue-reference commit convention some teams use; they are
# installed only on request.
DESIGN_GATE_HOOKS = ('pre-commit',)
ISSUE_HOOKS = ('commit-msg', 'prepare-commit-msg')


def install_hooks(dest=None, with_issue_hooks=False):
    """Install the design-gate pre-commit hook (and, only when asked, the
    issue-reference hooks) into ``dest`` or ``.git/hooks``."""
    hook_files_ref = files(__package__) / 'hook_files'
    names = DESIGN_GATE_HOOKS + (ISSUE_HOOKS if with_issue_hooks else ())

    with as_file(hook_files_ref) as hooks_source:
        if dest is None:
            root = repo_root()
            dest = os.path.join(root, '.git/hooks')

        print_info('Installing hooks into {}'.format(dest))
        os.makedirs(dest, exist_ok=True)
        for name in names:
            source = os.path.join(str(hooks_source), name)
            target = os.path.join(dest, name)
            shutil.copy2(source, target)
            os.chmod(target, os.stat(target).st_mode | 0o755)
        print_info('Successfully installed hooks: {}'.format(', '.join(names)))
