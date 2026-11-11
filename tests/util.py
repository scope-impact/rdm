import json
import subprocess
from pathlib import Path

import jinja2
from jinja2 import FunctionLoader

from rdm.publishing.render import render_template_to_string

# A complete (placeholder-free) design document for gate fixtures.
COMPLETE_DOC = "# Doc\n\nApproved and complete.\n"


def git_run(repo: Path, *args: str) -> str:
    """Run a git command in `repo` with a fixed test identity; return its output."""
    return subprocess.run(
        ["git", "-c", "user.email=t@t", "-c", "user.name=t", *args],
        cwd=repo,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def write_allure_result(results_dir: Path, name: str, status: str, *item_ids: str) -> None:
    """Write one Allure ``*-result.json`` claiming the given requirements
    (``item-NN-SSS``) with ``tag`` labels, as a Gherkin runner or
    ``@allure.tag`` writes them."""
    results_dir.mkdir(parents=True, exist_ok=True)
    labels = [{"name": "tag", "value": ident} for ident in item_ids]
    (results_dir / f"{name}-result.json").write_text(
        json.dumps({"name": name, "status": status, "labels": labels})
    )


def write_spec(
    repo: Path,
    items: tuple[tuple[str, str, list[str]], ...] = (),
    needs: dict[str, tuple[str, str, str]] | None = None,
    contexts: dict[str, str] | None = None,
    unstamped: tuple[tuple[str, str], ...] = (),
    layout: str = "gherkin",
) -> Path:
    """Write a spec under ``repo`` and return its root.

    ``items`` is ``(item-NN-SSS, scenario title, [user needs it addresses])``;
    ``needs`` is ``{name: (context, actor, text)}``; ``contexts`` names each
    slice's context (``{"01": "alarms"}``; default ``context01``);
    ``unstamped`` adds ``(slice, title)`` scenarios without an id.

    ``layout="gherkin"`` (the default): plain feature files,
    ``repo/spec/<context>/<need>.feature``, each need a Feature (its text the
    description) holding the scenarios whose first need it is; scenarios with
    no need sit under a Feature named for the context. ``layout="loyly"``:
    Löyly's layout under ``repo/.pi``, each need an Allium surface whose
    guarantee, of the same name, the scenario is tagged with; returns the plan
    directory.
    """
    needs = needs or {}
    contexts = dict(contexts or {})
    slices = sorted({ident.split("-")[1] for ident, _, _ in items} | {s for s, _ in unstamped}
                    | {s for s, c in contexts.items()})
    if layout == "gherkin":
        root = repo / "spec"
        for slice_no in slices:
            context = contexts.setdefault(slice_no, f"context{slice_no}")
            folder = root / context
            folder.mkdir(parents=True, exist_ok=True)
            groups: dict[str | None, list[str]] = {}
            for ident, title, covers in items:
                if ident.split("-")[1] == slice_no:
                    groups.setdefault(covers[0] if covers else None, []).extend(
                        [f"  @{ident}", f"  Scenario: {title}", ""])
            for slice_of, title in unstamped:
                if slice_of == slice_no:
                    groups.setdefault(None, []).extend([f"  Scenario: {title}", ""])
            for need, scenarios in groups.items():
                head = [f"Feature: {need}", f"  {needs[need][2]}", ""] if need in needs else \
                    [f"Feature: {need or context}", ""]
                (folder / f"{need or context}.feature").write_text("\n".join(head + scenarios))
        return root
    plan = repo / ".pi" / "plan"
    plan.mkdir(parents=True, exist_ok=True)
    for slice_no in slices:
        context = contexts.setdefault(slice_no, f"context{slice_no}")
        folder = repo / ".pi" / "spec" / context
        (folder / "allium").mkdir(parents=True, exist_ok=True)
        lines = [f"Feature: {context}", ""]
        for ident, title, covers in items:
            if ident.split("-")[1] != slice_no:
                continue
            tags = " ".join(f"@allium-guarantee.{n}" for n in covers)
            lines += [f"  {tags}" if tags else "", f"  @{ident}", f"  Scenario: {title}", ""]
        for slice_of, title in unstamped:
            if slice_of == slice_no:
                lines += [f"  Scenario: {title}", ""]
        (folder / "spec.feature").write_text("\n".join(lines))
        surfaces = []
        for name, (ctx, actor, text) in needs.items():
            if ctx == context:
                surfaces += [f"-- {text}", f"surface {name} {{", f"    facing user: {actor}", "",
                             f"    @guarantee {name}", "}", ""]
        (folder / "allium" / "needs.allium").write_text("\n".join(surfaces))
        link = plan / f"{slice_no}-{context}"
        if not link.exists():
            link.symlink_to(Path("..") / "spec" / context)
    return plan


def render_from_string(
    input_string=None,
    context=None,
    config=None,
    template_name=None,
    input_dictionary=None
):
    jinja2.clear_caches()
    if config is None:
        config = {}
    if template_name is None:
        template_name = 'input.md'
    if input_dictionary is None:
        input_dictionary = {}
    if input_string is not None:
        input_dictionary[template_name] = input_string
    if context is None:
        context = {}

    def load_string(template_name):
        return input_dictionary[template_name]

    loaders = [FunctionLoader(load_string)]

    return render_template_to_string(config, template_name, context, loaders=loaders)


def spec_project(
    root: Path,
    items: tuple[tuple[str, str, list[str]], ...] = (),
    needs: dict[str, tuple[str, str, str]] | None = None,
    contexts: dict[str, str] | None = None,
    *,
    commit: bool = True,
    **spec_options,
) -> Path:
    """A git repository at ``root`` with a DHF (``dhf/documents``) and a
    spec (:func:`write_spec`); returns the DHF."""
    docs = root / "dhf" / "documents"
    docs.mkdir(parents=True, exist_ok=True)
    git_run(root, "init", "-q")
    write_spec(root, items, needs, contexts, **spec_options)
    if commit:
        git_run(root, "add", "-A")
        git_run(root, "commit", "-qm", "record")
    return root / "dhf"
