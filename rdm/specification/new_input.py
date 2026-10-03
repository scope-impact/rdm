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
import textwrap
from pathlib import Path

from rdm.kernel.frontmatter import parse_frontmatter
from rdm.kernel.ids import sort_key
from rdm.specification.tags import find_tests_dir, iter_test_files, scan_source_tags
from rdm.specification.sdd import (
    context_of,
    design_input_ids,
    find_design_docs,
    registry_user_needs,
)

_DI_NUMBER = re.compile(r"^DI-(\d+)$")

CHECKLIST = """\
Remaining traceability checklist (see {workflow}):
  1. Describe how {di_id} is met in the '## Design Outputs' of {doc}, by id (never restate it)
  2. Commit the design docs FIRST -- that commit is the approval (design gate)
  3. Implement the design output
  4. Replace the stub body in {test_file} with real assertions
     (one verification step for each thing it requires; keep the @allure.story tag)
  5. Run the gates as CI does (design-gate, acceptance suite, verify,
     release-gate) and regenerate the traceability matrix
  6. Open a pull request: its independent review is the verification review\
"""

STUB_HEADER = '''"""Acceptance tests for the {context} context's design inputs (see dhf/).

Each test verifies a design input (an acceptance criterion) as a whole ("live
BDD"), tagged `@allure.story("DI-...")`; its verification steps are its own
checks, never criteria. Skips cleanly
if allure-pytest is not installed.
"""

from __future__ import annotations

import pytest

allure = pytest.importorskip("allure")
'''

STUB_TEST = '''

@allure.story("{di_id}")
@allure.label("component", "TODO")
def test_{fn_suffix}_not_implemented() -> None:
    """{doc}"""
    pytest.fail("{di_id} acceptance test not implemented -- replace this stub with real assertions")
'''


def _workflow_pointer(dhf_dir: Path) -> str:
    """Where this DHF's runbook actually lives: both scaffolds (`rdm init`,
    `rdm adopt`) lay `AGENT_WORKFLOW.md` down at the DHF root — point at that,
    not at a hardcoded `dhf/` the project may not have."""
    return str(Path(dhf_dir.name) / "AGENT_WORKFLOW.md")


def next_design_input_id(dhf_dir: Path) -> str:
    """Allocate the next unused DI-n: one no design document declares and no
    test is tagged with -- a retired test still tagged DI-n would otherwise
    verify the new input."""
    tests_dir = find_tests_dir(dhf_dir)
    tagged = set(scan_source_tags(tests_dir)) if tests_dir is not None else set()
    numbers = (_DI_NUMBER.match(di_id) for di_id in design_input_ids(dhf_dir) | tagged)
    return f"DI-{max((int(m.group(1)) for m in numbers if m), default=0) + 1}"


def stub_test_file(tests_dir: Path, context: str) -> Path:
    """Where a context's stub tests go: ``acceptance/test_<context>.py``, or
    ``test_<context>_acceptance.py`` when a test module of that name exists
    elsewhere in the suite (pytest refuses two modules of one name)."""
    usual = tests_dir / "acceptance" / f"test_{context}.py"
    if usual.exists() or not any(path.name == usual.name for path in iter_test_files(tests_dir)):
        return usual
    return usual.with_name(f"test_{context}_acceptance.py")


def docs_by_context(dhf_dir: Path) -> dict[str, Path]:
    """Map each bounded-context name to its `kind: design` document."""
    return {context_of(doc): doc for doc in find_design_docs(dhf_dir)}


def _yaml_quote(text: str) -> str:
    """Double-quote a string for inline YAML."""
    return f'"{_docstring_escape(text)}"'


def _docstring_escape(text: str) -> str:
    """Escape text for safe embedding in a double-quoted docstring: a quote,
    backslash, or triple-quote in the requirement text must not corrupt the
    generated test module."""
    return text.replace("\\", "\\\\").replace('"', '\\"')


def _frontmatter_close(lines: list[str]) -> int | None:
    """Index of the closing frontmatter fence, or None without a leading block."""
    fences = [i for i, line in enumerate(lines) if line.rstrip("\r\n") == "---"]
    if len(fences) < 2 or fences[0] != 0:
        return None
    return fences[1]


def insert_design_input(doc_path: Path, di_id: str, text: str, traces_to: list[str]) -> None:
    """Insert a design-input entry into a design doc's frontmatter by line edit.

    Appends to the end of an existing ``design_inputs`` list (an empty ``[]``
    becomes that list), or creates the key
    just before the closing frontmatter fence. Raises ``ValueError`` when the
    document has no frontmatter block.
    """
    original = doc_path.read_bytes().decode("utf-8")  # as written: CRLF stays CRLF
    nl = "\r\n" if "\r\n" in original else "\n"
    lines = original.splitlines(keepends=True)
    close = _frontmatter_close(lines)
    if close is None:
        raise ValueError(f"{doc_path} has no frontmatter block to declare design inputs in")

    def entry_at(indent: str) -> str:
        return (f"{indent}- id: {di_id}{nl}"
                f"{indent}  text: {_yaml_quote(text)}{nl}"
                f"{indent}  traces_to: [{', '.join(traces_to)}]{nl}")

    entry = entry_at("  ")

    key_index = None
    for i in range(1, close):
        if re.match(r"^design_inputs:\s*(#.*)?$", lines[i]):
            key_index = i
            break
        if empty := re.match(r"^design_inputs:\s*\[\s*\]\s*(#[^\r\n]*)?", lines[i]):
            # An empty flow list becomes the block list, never a second key; its comment stays.
            lines[i] = "design_inputs:" + (f"  {empty.group(1)}" if empty.group(1) else "") + nl
            key_index = i
            break

    if key_index is None:
        lines.insert(close, "design_inputs:" + nl + entry)
    else:
        # The list ends at the next non-indented, non-blank line (a sibling
        # top-level key) or at the closing fence.
        end, indent = close, "  "
        for i in range(key_index + 1, close):
            stripped = lines[i].rstrip("\r\n")
            if stripped.lstrip().startswith("- ") and i == key_index + 1:
                indent = stripped[:len(stripped) - len(stripped.lstrip())]  # the list's own indentation
            if stripped and not stripped.startswith((" ", "\t")) and not (indent == "" and stripped.startswith("-")):
                end = i
                break
        lines.insert(end, entry_at(indent))

    edited = "".join(lines)
    # Read the edit back: the document must declare what it did, plus the new
    # input; otherwise refuse rather than write a document the gates misread.
    before, after = parse_frontmatter(original), parse_frontmatter(edited)
    was = before.get("design_inputs") or []
    if (not isinstance(was, list) or after.get("design_inputs") != [*was, {
            "id": di_id, "text": text, "traces_to": traces_to}]
            or {k: v for k, v in after.items() if k != "design_inputs"}
            != {k: v for k, v in before.items() if k != "design_inputs"}):
        raise ValueError(f"cannot add {di_id} to {doc_path} without changing what it declares "
                         "(its design_inputs list is in a form this edit does not handle): add it by hand")
    doc_path.write_bytes(edited.encode("utf-8"))


def write_stub_test(test_file: Path, di_id: str, text: str, context: str) -> None:
    """Append a failing stub test tagged with the new design-input id."""
    fn_suffix = di_id.lower().replace("-", "_")
    # Wrapped, so a long requirement does not fail the project's line-length lint.
    doc = "\n    ".join(textwrap.wrap(f"{di_id}: {_docstring_escape(text)}", 88))
    stub = STUB_TEST.format(di_id=di_id, fn_suffix=fn_suffix, doc=doc)
    if test_file.exists():
        with test_file.open("a", encoding="utf-8") as handle:
            handle.write(stub)
    else:
        test_file.parent.mkdir(parents=True, exist_ok=True)
        test_file.write_text(STUB_HEADER.format(context=context) + stub, encoding="utf-8")


def _print_inventory(dhf_dir: Path) -> None:
    """Read-only discovery: contexts, taken DI ids, next free id, user needs."""
    contexts = docs_by_context(dhf_dir)
    taken = sorted(design_input_ids(dhf_dir), key=sort_key)
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
    owners = [doc for doc in find_design_docs(dhf) if context_of(doc) == context]
    if len(owners) > 1:
        print(f"Error: context '{context}' has {len(owners)} design documents "
              f"({', '.join(str(doc.relative_to(dhf)) for doc in owners)}); keep one, then add the input")
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
    try:
        insert_design_input(doc, di_id, text, refs)
    except ValueError as error:
        print(f"Error: {error}")
        return 2

    if test_file is None:
        test_file = stub_test_file(find_tests_dir(dhf) or (dhf.parent / "tests"), context)
    write_stub_test(test_file, di_id, text, context)

    print(f"Scaffolded {di_id} ({context}):")
    print(f"  design input -> {doc}")
    print(f"  stub test    -> {test_file}  (fails until implemented, by design)")
    print()
    print(CHECKLIST.format(di_id=di_id, doc=doc.name, test_file=test_file,
                           workflow=_workflow_pointer(dhf)))
    return 0
