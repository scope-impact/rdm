"""
Bring an existing repository under RDM's audit (item-01-003).

`rdm adopt [target]` lays down what RDM reads and runs into a repo that
already has code: the DHF skeleton (V&V plan, traceability-matrix template,
render config, README) and a CI workflow that runs RDM's gates. The
requirements and user needs are the product's spec, which RDM reads.

Existing files are skipped, never overwritten — adoption must not disturb the
repository it is protecting, and re-running is safe (idempotent).
"""

from __future__ import annotations

import shutil
from importlib.resources import as_file, files
from pathlib import Path

from rdm.kernel.version import release_version

# A template that names RDM's own version is pinned to the RDM that adopts:
# the CI workflow calls RDM's reusable gates at this release (item-04-004).
_VERSION_TOKEN = "{rdm_version}"

NEXT_STEPS = """\
Next steps:
  1. Point RDM at your spec: spec/ or features/ (Gherkin), a Löyly-shaped spec at .pi/plan,
     or `spec:` in dhf/config.yml
  2. Have each acceptance test claim its requirement with an Allure `tag` label of its id
     (a Gherkin runner writes a scenario's tags as such labels; in pytest, @allure.tag("<id>"))
  3. Set test-command, acceptance-tests and release-gate in
     .github/workflows/design-controls.yml once the first claimed test passes\
"""


# A template whose name cannot ship as itself: a .gitignore inside the
# package would be read as one by git and the build tools.
_RENAMED = {"gitignore": ".gitignore"}


def _copy_if_absent(src: Path, dest: Path, rel: str,
                    copied: list[str], skipped: list[str]) -> None:
    """Copy ``src`` to ``dest`` unless the destination already exists. A
    symbolic link exists, dangling or not: it is never written through."""
    if dest.exists() or dest.is_symlink():
        skipped.append(rel)
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)
    text = src.read_bytes()
    if _VERSION_TOKEN.encode() in text:
        dest.write_bytes(text.replace(_VERSION_TOKEN.encode(), release_version().encode()))
    copied.append(rel)


def adopt(target: Path) -> tuple[list[str], list[str]]:
    """Lay the control surface into ``target``; return (copied, skipped)."""
    copied: list[str] = []
    skipped: list[str] = []

    adopt_ref = files("rdm.specification") / "adopt_files"
    with as_file(adopt_ref) as adopt_root:
        root = Path(adopt_root)
        for src in sorted(root.rglob("*")):
            if src.is_dir():
                continue
            rel = src.relative_to(root).as_posix()
            rel = _RENAMED.get(rel, rel)
            _copy_if_absent(src, target / rel, rel, copied, skipped)

    return copied, skipped


def adopt_command(target: str | None = None) -> int:
    """Run the `rdm adopt` command."""
    dest = Path(target or ".").resolve()
    if not dest.is_dir():
        print(f"Error: target is not a directory: {dest}")
        return 2

    copied, skipped = adopt(dest)

    print(f"Adopting RDM into {dest}\n")
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
    if ".gitignore" in skipped:
        with as_file(files("rdm.specification") / "adopt_files" / "gitignore") as template:
            wanted = [line for line in Path(template).read_text().splitlines() if line and not line.startswith("#")]
        present = set((dest / ".gitignore").read_text(errors="replace").split())
        missing = [line for line in wanted if line not in present]
        if missing:
            print("\nYour .gitignore was kept. Add these lines, so a run of the acceptance suite is not "
                  "marked dirty by what RDM generates:")
            for line in missing:
                print(f"  {line}")
    return 0
