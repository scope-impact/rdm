"""
Bring an existing repository under record-first design controls (DI-24).

`rdm adopt [target]` lays down the control surface into a repo that already
has code: the DHF skeleton (V&V plan, per-context design template, design
review, traceability-matrix template), the agent workflow runbook, the
design-gate pre-commit hook, a session bootstrap, and a CI gate workflow.

Existing files are skipped, never overwritten — adoption must not disturb the
repository it is protecting, and re-running is safe (idempotent). The templates
deliberately contain unresolved placeholder markers so the design gate stays
red until the adopting team writes and commits its actual record.
"""

from __future__ import annotations

import shutil
from importlib.resources import as_file, files
from pathlib import Path

# Paths that must be executable at the destination.
_EXECUTABLE = {"scripts/agent-bootstrap.sh", ".githooks/pre-commit"}

NEXT_STEPS = """\
Next steps (see dhf/AGENT_WORKFLOW.md for the full loop):
  1. git config core.hooksPath .githooks   (agent sessions do this automatically)
  2. Register your first user need in dhf/documents/verification_and_validation_plan.md
  3. Rename dhf/documents/design/example_context.md for your first bounded
     context and fill it in -- the design gate stays red until the record is
     written and committed (that commit is the approval)
  4. Declare your first design input: rdm story new-input --dhf dhf --list
  5. Uncomment the remaining CI steps in .github/workflows/design-controls.yml
     once that first design input is verified\
"""


def _copy_if_absent(src: Path, dest: Path, rel: str,
                    copied: list[str], skipped: list[str]) -> None:
    """Copy ``src`` to ``dest`` unless the destination already exists."""
    if dest.exists():
        skipped.append(rel)
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)
    if rel in _EXECUTABLE:
        dest.chmod(dest.stat().st_mode | 0o755)
    copied.append(rel)


def adopt(target: Path) -> tuple[list[str], list[str]]:
    """Lay the control surface into ``target``; return (copied, skipped)."""
    copied: list[str] = []
    skipped: list[str] = []

    adopt_ref = files("rdm") / "adopt_files"
    with as_file(adopt_ref) as adopt_root:
        root = Path(adopt_root)
        for src in sorted(root.rglob("*")):
            if src.is_dir():
                continue
            rel = src.relative_to(root).as_posix()
            _copy_if_absent(src, target / rel, rel, copied, skipped)

    # The pre-commit design gate is copied from hook_files at adopt time so the
    # gate has one source of truth. Only the design gate is installed -- the
    # issue-reference hooks that `rdm hooks` also ships stay opt-in.
    hook_ref = files("rdm") / "hook_files" / "pre-commit"
    with as_file(hook_ref) as hook_src:
        _copy_if_absent(Path(hook_src), target / ".githooks" / "pre-commit",
                        ".githooks/pre-commit", copied, skipped)

    return copied, skipped


def adopt_command(target: str | None = None) -> int:
    """Run the `rdm adopt` command."""
    dest = Path(target or ".").resolve()
    if not dest.is_dir():
        print(f"Error: target is not a directory: {dest}")
        return 2

    copied, skipped = adopt(dest)

    print(f"Adopting record-first design controls into {dest}\n")
    if copied:
        print("Laid down:")
        for rel in copied:
            print(f"  + {rel}")
    if skipped:
        print("Skipped (already exist -- left untouched):")
        for rel in skipped:
            print(f"  = {rel}")
    print()
    print(NEXT_STEPS)
    return 0
