"""
Scaffold a new design input (DI-22, `rdm story new-input`).

Guides a contributor (human or agent) through authoring a *traced* design input:
allocate the next unused DI id across the whole DHF, insert the
``{id, text, traces_to}`` entry into the chosen context's ``design_inputs``
frontmatter, emit a stub ``@allure.story("DI-n")`` acceptance test that fails
until implemented, and print the remaining traceability checklist. An unknown
context or user need is rejected — a design input cannot be scaffolded outside
the record.

The frontmatter entry is inserted by targeted line edit (never a YAML re-dump)
so hand-authored formatting and comments in the design document survive.
"""

from __future__ import annotations

import re
from pathlib import Path

from rdm.record.allure import find_tests_dir
from rdm.record.sdd import (
    context_of,
    design_input_ids,
    find_design_docs,
    registry_user_needs,
)

_DI_NUMBER = re.compile(r"^DI-(\d+)$")

CHECKLIST = """\
Remaining traceability checklist (see {workflow}):
  1. Describe {di_id} in the '## Design Inputs' / '## Design Outputs' prose of {doc}
  2. Commit the design docs FIRST -- that commit is the approval (design gate)
  3. Implement the design output
  4. Replace the stub body in {test_file} with real assertions
     (one per requirement clause; keep the @allure.story tag)
  5. Independent faithfulness verdict (test-faithfulness skill / second agent):
       rdm story verdict {di_id} --dhf {dhf} --verdict faithful --reviewer ... --rationale ...
  6. Run the gates as CI does (design-gate, acceptance suite, verify,
     faithfulness, release-gate) and regenerate the traceability matrix\
"""

STUB_HEADER = '''"""Acceptance tests for the {context} context's design inputs (see dhf/).

Each test is the acceptance criterion ("live BDD") for a design input, tagged
`@allure.story("DI-...")`. Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import pytest

allure = pytest.importorskip("allure")
'''

STUB_TEST = '''

@allure.story("{di_id}")
@allure.label("output", "TODO")
def test_{fn_suffix}_not_implemented() -> None:
    """{di_id}: {text}"""
    pytest.fail("{di_id} acceptance test not implemented -- replace this stub with real assertions")
'''


def _workflow_pointer(dhf_dir: Path) -> str:
    """Where this DHF's runbook actually lives: both scaffolds (`rdm init`,
    `rdm adopt`) lay `AGENT_WORKFLOW.md` down at the DHF root — point at that,
    not at a hardcoded `dhf/` the project may not have."""
    return str(Path(dhf_dir.name) / "AGENT_WORKFLOW.md")


def next_design_input_id(dhf_dir: Path) -> str:
    """Allocate the next unused DI-n across every design document in the DHF."""
    highest = 0
    for di_id in design_input_ids(dhf_dir):
        match = _DI_NUMBER.match(di_id)
        if match:
            highest = max(highest, int(match.group(1)))
    return f"DI-{highest + 1}"


def docs_by_context(dhf_dir: Path) -> dict[str, Path]:
    """Map each bounded-context name to its `kind: design` document."""
    return {context_of(doc): doc for doc in find_design_docs(dhf_dir)}


def _yaml_quote(text: str) -> str:
    """Double-quote a string for inline YAML."""
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _docstring_escape(text: str) -> str:
    """Escape text for safe embedding in a double-quoted docstring: a quote,
    backslash, or triple-quote in the requirement text must not corrupt the
    generated test module."""
    return text.replace("\\", "\\\\").replace('"', '\\"')


def _frontmatter_close(lines: list[str]) -> int | None:
    """Index of the closing frontmatter fence, or None without a leading block."""
    fences = [i for i, line in enumerate(lines) if line.rstrip("\n") == "---"]
    if len(fences) < 2 or fences[0] != 0:
        return None
    return fences[1]


def insert_design_input(doc_path: Path, di_id: str, text: str, traces_to: list[str]) -> None:
    """Insert a design-input entry into a design doc's frontmatter by line edit.

    Appends to the end of an existing ``design_inputs`` list, or creates the key
    just before the closing frontmatter fence. Raises ``ValueError`` when the
    document has no frontmatter block.
    """
    lines = doc_path.read_text(encoding="utf-8").splitlines(keepends=True)
    close = _frontmatter_close(lines)
    if close is None:
        raise ValueError(f"{doc_path} has no frontmatter block to declare design inputs in")

    entry = (
        f"  - id: {di_id}\n"
        f"    text: {_yaml_quote(text)}\n"
        f"    traces_to: [{', '.join(traces_to)}]\n"
    )

    key_index = None
    for i in range(1, close):
        if re.match(r"^design_inputs:\s*(#.*)?$", lines[i]):
            key_index = i
            break

    if key_index is None:
        lines.insert(close, "design_inputs:\n" + entry)
    else:
        # The list ends at the next non-indented, non-blank line (a sibling
        # top-level key) or at the closing fence.
        end = close
        for i in range(key_index + 1, close):
            stripped = lines[i].rstrip("\n")
            if stripped and not stripped.startswith((" ", "\t")):
                end = i
                break
        lines.insert(end, entry)

    doc_path.write_text("".join(lines), encoding="utf-8")


def update_satisfies(doc_path: Path, refs: list[str]) -> list[str]:
    """Add any user need in ``refs`` missing from the doc's ``satisfies`` list
    (declare-once stays consistent without a hand edit). Returns the needs
    added. Both YAML forms are edited in place — inline ``satisfies: [ … ]``
    and a block list — never by adding a second ``satisfies`` key; a missing
    key is created after ``context:``.
    """
    lines = doc_path.read_text(encoding="utf-8").splitlines(keepends=True)
    close = _frontmatter_close(lines)
    if close is None:
        return []

    for i in range(1, close):
        inline = re.match(r"^satisfies:\s*\[(.*)\]\s*$", lines[i])
        if inline:
            current = [ref.strip() for ref in inline.group(1).split(",") if ref.strip()]
            added = [ref for ref in refs if ref not in current]
            if added:
                lines[i] = f"satisfies: [{', '.join(current + added)}]\n"
                doc_path.write_text("".join(lines), encoding="utf-8")
            return added
        if re.match(r"^satisfies:\s*(#.*)?$", lines[i]):
            # Block-style list: append the missing items to it, keeping the
            # existing indentation. Never emit a duplicate `satisfies:` key.
            current: list[str] = []
            indent, end = "  ", i + 1
            for j in range(i + 1, close):
                item = re.match(r"^(\s+)-\s*(\S+)\s*(#.*)?$", lines[j])
                if not item:
                    break
                indent = item.group(1)
                current.append(item.group(2))
                end = j + 1
            added = [ref for ref in refs if ref not in current]
            for offset, ref in enumerate(added):
                lines.insert(end + offset, f"{indent}- {ref}\n")
            if added:
                doc_path.write_text("".join(lines), encoding="utf-8")
            return added

    # No satisfies key: create it right after `context:` (or before the fence).
    insert_at = next(
        (i + 1 for i in range(1, close) if re.match(r"^context:", lines[i])), close
    )
    lines.insert(insert_at, f"satisfies: [{', '.join(refs)}]\n")
    doc_path.write_text("".join(lines), encoding="utf-8")
    return list(refs)


def write_stub_test(test_file: Path, di_id: str, text: str, context: str) -> None:
    """Append a failing stub test tagged with the new design-input id."""
    fn_suffix = di_id.lower().replace("-", "_")
    stub = STUB_TEST.format(di_id=di_id, fn_suffix=fn_suffix, text=_docstring_escape(text))
    if test_file.exists():
        with test_file.open("a", encoding="utf-8") as handle:
            handle.write(stub)
    else:
        test_file.parent.mkdir(parents=True, exist_ok=True)
        test_file.write_text(STUB_HEADER.format(context=context) + stub, encoding="utf-8")


def _print_inventory(dhf_dir: Path) -> None:
    """Read-only discovery: contexts, taken DI ids, next free id, user needs."""
    contexts = docs_by_context(dhf_dir)
    def _number(di_id: str) -> int:
        match = _DI_NUMBER.match(di_id)
        return int(match.group(1)) if match else 0

    taken = sorted(design_input_ids(dhf_dir), key=_number)
    print(f"DHF: {dhf_dir}\n")
    print("Contexts (kind: design):")
    for name, doc in sorted(contexts.items()):
        print(f"  {name:<14} {doc}")
    print(f"\nDeclared design inputs: {', '.join(taken) if taken else '(none)'}")
    print(f"Next free id: {next_design_input_id(dhf_dir)}")
    print(f"User needs: {', '.join(sorted(registry_user_needs(dhf_dir)))}")


def story_new_input_command(
    dhf_dir: Path | None = None,
    context: str | None = None,
    text: str | None = None,
    traces_to: str | None = None,
    test_file: Path | None = None,
    list_only: bool = False,
) -> int:
    """Run the `rdm story new-input` command."""
    dhf = (dhf_dir or Path("dhf")).resolve()
    if not dhf.exists():
        print(f"Error: DHF directory not found: {dhf}")
        return 2

    if list_only:
        _print_inventory(dhf)
        return 0

    if not (context and text and traces_to):
        print("Error: --context, --text, and --traces-to are required (or use --list)")
        return 2

    contexts = docs_by_context(dhf)
    if context not in contexts:
        print(f"Error: unknown context '{context}'. Known contexts: {', '.join(sorted(contexts))}")
        return 2

    needs = registry_user_needs(dhf)
    refs = [ref.strip() for ref in traces_to.split(",") if ref.strip()]
    unknown = [ref for ref in refs if ref not in needs]
    if unknown:
        print(f"Error: unknown user need(s): {', '.join(unknown)}. "
              f"Registered: {', '.join(sorted(needs))}")
        print("Register a new user need in the V&V plan frontmatter first "
              f"(see {_workflow_pointer(dhf)}).")
        return 2

    di_id = next_design_input_id(dhf)
    doc = contexts[context]
    insert_design_input(doc, di_id, text, refs)
    added_needs = update_satisfies(doc, refs)

    if test_file is None:
        tests_dir = find_tests_dir(dhf) or (dhf.parent / "tests")
        test_file = tests_dir / "acceptance" / f"test_{context}.py"
    write_stub_test(test_file, di_id, text, context)

    print(f"Scaffolded {di_id} ({context}):")
    print(f"  design input -> {doc}")
    if added_needs:
        print(f"  satisfies    -> added {', '.join(added_needs)} to {doc.name}")
    print(f"  stub test    -> {test_file}  (fails until implemented, by design)")
    print()
    print(CHECKLIST.format(di_id=di_id, doc=doc.name, test_file=test_file, dhf=dhf.name,
                           workflow=_workflow_pointer(dhf)))
    return 0
