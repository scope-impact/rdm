"""
Story Audit - Requirements Traceability Analyzer.

Scans codebase for story IDs and generates traceability report.

Usage:
    rdm story audit [repo_path]
"""

from __future__ import annotations

import re
import sys
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

from rdm.story_audit.schema import ID_PATTERN


# =============================================================================
# DATA CLASSES
# =============================================================================


@dataclass
class StoryReference:
    """A reference to a story ID in the codebase."""

    story_id: str
    file_path: str
    line_number: int
    context: str  # "requirement", "test", "source", "doc"
    snippet: str = ""


@dataclass
class AuditResult:
    """Results of the traceability audit."""

    all_ids: set[str] = field(default_factory=set)
    requirements: dict[str, list[StoryReference]] = field(
        default_factory=lambda: defaultdict(list)
    )
    tests: dict[str, list[StoryReference]] = field(
        default_factory=lambda: defaultdict(list)
    )
    sources: dict[str, list[StoryReference]] = field(
        default_factory=lambda: defaultdict(list)
    )
    docs: dict[str, list[StoryReference]] = field(
        default_factory=lambda: defaultdict(list)
    )
    conflicts: list[tuple[str, list[StoryReference]]] = field(default_factory=list)
    orphan_tests: list[str] = field(default_factory=list)
    orphan_sources: list[str] = field(default_factory=list)
    # How many files discovery actually looked at. A ratio whose denominator is
    # zero cannot be scored, and scoring it anyway is what let an empty
    # repository earn full marks, so the counts travel with the result.
    test_files_scanned: int = 0
    source_files_scanned: int = 0
    # Record-first (DHF) design inputs: declared DI ids and the tagged subset.
    design_inputs: dict[str, list[StoryReference]] = field(
        default_factory=lambda: defaultdict(list)
    )


# =============================================================================
# CONSTANTS
# =============================================================================

# Minimum lines for a source file to be flagged as orphan (without traceability)
MIN_SOURCE_FILE_LINES_FOR_ORPHAN_CHECK = 20

# =============================================================================
# PATTERNS (ID_PATTERN imported from schema.py - single source of truth)
# =============================================================================

# Matches @allure.story("US-001") or @allure.feature("FT-001").
# Single definition lives in record.allure; re-exported here for existing callers.
from rdm.record.allure import (  # noqa: E402,F401
    ALLURE_PATTERN,
    claims_a_tag as _claims_a_tag,
    iter_test_files,
)

# Matches @trace("US-001")
TRACE_PATTERN = re.compile(r'@trace\(["\']([^"\']+)["\']')


# =============================================================================
# SCANNING FUNCTIONS
# =============================================================================


def find_ids_in_file(file_path: Path, context: str) -> list[StoryReference]:
    """Find all story IDs in a file."""
    refs = []
    try:
        content = file_path.read_text(encoding="utf-8", errors="ignore")
        for i, line in enumerate(content.splitlines(), 1):
            for match in ID_PATTERN.finditer(line):
                story_id = match.group(0)
                refs.append(
                    StoryReference(
                        story_id=story_id,
                        file_path=str(file_path),
                        line_number=i,
                        context=context,
                        snippet=line.strip()[:80],
                    )
                )
    except Exception as e:
        print(f"Warning: Could not read or parse {file_path}: {e}", file=sys.stderr)
    return refs


def scan_requirements(repo_path: Path) -> dict[str, list[StoryReference]]:
    """Scan requirements directory for story definitions."""
    refs: dict[str, list[StoryReference]] = defaultdict(list)
    req_path = repo_path / "requirements" if repo_path.name != "requirements" else repo_path

    if not req_path.exists():
        return refs

    for yaml_file in req_path.rglob("*.yaml"):
        for ref in find_ids_in_file(yaml_file, "requirement"):
            refs[ref.story_id].append(ref)

    return refs


def scan_tests(repo_path: Path) -> tuple[dict[str, list[StoryReference]], list[str], int]:
    """Scan test files for story references and find orphans.

    Returns the references, the orphans, and how many test files were seen --
    the last so the caller can tell "no orphans" from "no tests".
    """
    refs: dict[str, list[StoryReference]] = defaultdict(list)
    orphans = []
    scanned = 0
    tests_path = repo_path / "tests"

    if not tests_path.exists():
        # Try apps/*/tests
        for app_tests in repo_path.glob("apps/*/tests"):
            tests_path = app_tests
            break

    if not tests_path.exists():
        return refs, orphans, scanned

    for test_file in iter_test_files(tests_path):
        scanned += 1
        file_refs = find_ids_in_file(test_file, "test")
        if file_refs:
            for ref in file_refs:
                refs[ref.story_id].append(ref)
        else:
            # Check if the file carries a tag syntax at all
            content = test_file.read_text(encoding="utf-8", errors="ignore")
            if not _claims_a_tag(test_file, content):
                orphans.append(str(test_file))

    return refs, orphans, scanned


def scan_sources(repo_path: Path) -> tuple[dict[str, list[StoryReference]], list[str], int]:
    """Scan source files for @trace decorators and find orphans.

    Returns the references, the orphans, and how many source files were seen.
    """
    refs: dict[str, list[StoryReference]] = defaultdict(list)
    orphans = []
    scanned = 0

    # Check both src/ and apps/*/src/
    source_paths = [repo_path / "src"]
    source_paths.extend(repo_path.glob("apps/*/src"))

    for src_path in source_paths:
        if not src_path.exists():
            continue

        for py_file in src_path.rglob("*.py"):
            if py_file.name == "__init__.py":
                continue
            scanned += 1

            file_refs = find_ids_in_file(py_file, "source")
            content = py_file.read_text(encoding="utf-8", errors="ignore")

            # Also check for @trace decorators
            for match in TRACE_PATTERN.finditer(content):
                trace_id = match.group(1)
                if ID_PATTERN.match(trace_id):
                    refs[trace_id].append(
                        StoryReference(
                            story_id=trace_id,
                            file_path=str(py_file),
                            line_number=0,
                            context="source",
                            snippet="@trace decorator",
                        )
                    )

            for ref in file_refs:
                refs[ref.story_id].append(ref)

            # Check if file has any traceability
            if not file_refs and "@trace" not in content:
                # Only flag substantial files
                if len(content.splitlines()) > MIN_SOURCE_FILE_LINES_FOR_ORPHAN_CHECK:
                    orphans.append(str(py_file))

    return refs, orphans, scanned


def scan_design_inputs(
    repo_path: Path,
) -> tuple[dict[str, list[StoryReference]], dict[str, list[StoryReference]]]:
    """Scan a record-first DHF for design inputs and their test-tag coverage.

    Returns ``(declared, tagged)``: every design input declared in the DHF's
    ``kind: design`` documents (as requirement references), and the subset that
    a test references via an ``@allure.story``/``feature`` tag (as test
    references). Both empty when the repository has no ``dhf/`` — legacy
    behavior is unchanged.
    """
    declared: dict[str, list[StoryReference]] = defaultdict(list)
    tagged: dict[str, list[StoryReference]] = defaultdict(list)
    dhf_dir = repo_path / "dhf"
    if not dhf_dir.exists():
        return declared, tagged

    from rdm.record.allure import find_tests_dir, scan_source_tags
    from rdm.record.sdd import context_of, design_inputs, find_design_docs

    doc_by_context = {context_of(doc): doc for doc in find_design_docs(dhf_dir)}
    inputs = design_inputs(dhf_dir)
    for di in inputs:
        doc = doc_by_context.get(di["context"])
        declared[di["id"]].append(
            StoryReference(
                story_id=di["id"],
                file_path=str(doc) if doc else str(dhf_dir),
                line_number=0,
                context="requirement",
                snippet=di["text"][:80],
            )
        )

    tests_dir = find_tests_dir(dhf_dir)
    tag_refs = scan_source_tags(tests_dir) if tests_dir else {}
    for di in inputs:
        for test_file in tag_refs.get(di["id"], []):
            tagged[di["id"]].append(
                StoryReference(
                    story_id=di["id"],
                    file_path=test_file,
                    line_number=0,
                    context="test",
                    snippet="@allure tag",
                )
            )

    return declared, tagged


def scan_docs(repo_path: Path) -> dict[str, list[StoryReference]]:
    """Scan documentation for story references."""
    refs: dict[str, list[StoryReference]] = defaultdict(list)
    docs_path = repo_path / "docs"

    if not docs_path.exists():
        return refs

    for md_file in docs_path.rglob("*.md"):
        for ref in find_ids_in_file(md_file, "doc"):
            refs[ref.story_id].append(ref)

    return refs


# =============================================================================
# CONFLICT DETECTION
# =============================================================================


def detect_conflicts(
    requirements: dict[str, list[StoryReference]]
) -> list[tuple[str, list[StoryReference]]]:
    """Detect IDs defined in multiple requirement files.

    Only considers actual definitions (id: XX-XXX) not references.
    """
    conflicts = []

    for story_id, refs in requirements.items():
        # Get unique files where this ID is DEFINED (has "id:" prefix)
        # Skip references like "- FT-001" or "epic_id: EP-001"
        defining_files = set()
        for ref in refs:
            # Use regex to confirm that the id: key is directly followed by this specific story_id
            # This excludes epic_id:, feature_id:, and cases where id: defines a different ID
            if re.match(r"(- )?id:\s*" + re.escape(story_id), ref.snippet, re.IGNORECASE):
                defining_files.add(ref.file_path)

        if len(defining_files) > 1:
            conflicts.append((story_id, refs))

    return conflicts


# =============================================================================
# MAIN AUDIT
# =============================================================================


def run_audit(repo_path: Path) -> AuditResult:
    """Run full traceability audit."""
    result = AuditResult()

    # Scan all locations
    result.requirements = scan_requirements(repo_path)
    result.tests, result.orphan_tests, result.test_files_scanned = scan_tests(repo_path)
    result.sources, result.orphan_sources, result.source_files_scanned = scan_sources(repo_path)
    result.docs = scan_docs(repo_path)

    # Record-first DHF: design inputs join the requirements universe, and their
    # @allure-tagged tests count as coverage — so an untagged design input shows
    # up as a story without coverage and degrades the score, instead of being
    # invisible to a legacy-only scan.
    result.design_inputs, di_tagged = scan_design_inputs(repo_path)
    for di_id, refs in result.design_inputs.items():
        result.requirements[di_id].extend(refs)
    for di_id, refs in di_tagged.items():
        result.tests[di_id].extend(refs)

    # Collect all IDs
    for refs in [result.requirements, result.tests, result.sources, result.docs]:
        result.all_ids.update(refs.keys())

    # Detect conflicts
    result.conflicts = detect_conflicts(result.requirements)

    return result


# =============================================================================
# REPORT PRINTING
# =============================================================================


def print_report(result: AuditResult, repo_path: Path) -> None:
    """Print audit report."""
    print("=" * 60)
    print("         STORY AUDIT: TRACEABILITY REPORT")
    print("=" * 60)
    print(f"\nRepository: {repo_path.absolute()}")
    print()

    # Summary
    print("## Summary\n")
    total_stories = len(result.all_ids)
    stories_with_tests = len(set(result.tests.keys()))
    stories_with_source = len(set(result.sources.keys()))
    stories_in_reqs = len(set(result.requirements.keys()))

    print("| Metric | Count |")
    print("|--------|-------|")
    print(f"| Total unique IDs | {total_stories} |")
    print(f"| In requirements | {stories_in_reqs} |")
    print(f"| In tests | {stories_with_tests} |")
    print(f"| In source (@trace) | {stories_with_source} |")
    print(f"| ID conflicts | {len(result.conflicts)} |")
    print(f"| Orphan test files | {len(result.orphan_tests)} |")
    print(f"| Orphan source files | {len(result.orphan_sources)} |")
    if result.design_inputs:
        print(f"| Design inputs (DHF) | {len(result.design_inputs)} |")
    print()

    # Record-first design inputs: per-input test-tag coverage
    if result.design_inputs:
        print(f"## Design Inputs (DHF) ({len(result.design_inputs)})\n")
        print("| Design input | Test tag |")
        print("|--------------|----------|")
        for di_id in sorted(result.design_inputs, key=lambda d: (len(d), d)):
            di_tests = [r for r in result.tests.get(di_id, []) if r.context == "test"]
            status = f"tagged ({len(di_tests)} file(s))" if di_tests else "UNTAGGED"
            print(f"| {di_id} | {status} |")
        print()

    # ID Conflicts
    if result.conflicts:
        print("## ID Conflicts Found\n")
        print("| ID | Files |")
        print("|----|-------|")
        for story_id, refs in result.conflicts:
            files = set(r.file_path for r in refs)
            print(f"| {story_id} | {', '.join(f.split('/')[-1] for f in files)} |")
        print()

    # Coverage gaps
    req_ids = set(result.requirements.keys())
    tested_ids = set(result.tests.keys())
    traced_ids = set(result.sources.keys())
    covered_ids = tested_ids | traced_ids

    untested = req_ids - covered_ids
    if untested:
        print(f"## Stories Without Coverage ({len(untested)})\n")
        for story_id in sorted(untested)[:20]:
            print(f"- {story_id}")
        if len(untested) > 20:
            print(f"- ... and {len(untested) - 20} more")
        print()

    # Orphan files
    if result.orphan_tests:
        print(f"## Orphan Test Files ({len(result.orphan_tests)})\n")
        print("Tests without @allure story reference:")
        for f in result.orphan_tests[:10]:
            print(f"- {f}")
        if len(result.orphan_tests) > 10:
            print(f"- ... and {len(result.orphan_tests) - 10} more")
        print()

    if result.orphan_sources:
        print(f"## Orphan Source Files ({len(result.orphan_sources)})\n")
        print("Source files without traceability:")
        for f in result.orphan_sources[:10]:
            print(f"- {f}")
        if len(result.orphan_sources) > 10:
            print(f"- ... and {len(result.orphan_sources) - 10} more")
        print()

    # Feature breakdown
    print("## Coverage by Prefix\n")
    prefix_stats: dict[str, dict[str, int]] = defaultdict(
        lambda: {"total": 0, "tested": 0, "traced": 0}
    )

    for story_id in result.all_ids:
        prefix = story_id.split("-")[0]
        prefix_stats[prefix]["total"] += 1
        if story_id in tested_ids:
            prefix_stats[prefix]["tested"] += 1
        if story_id in traced_ids:
            prefix_stats[prefix]["traced"] += 1

    print("| Prefix | Total | Tested | Traced | Coverage |")
    print("|--------|-------|--------|--------|----------|")
    for prefix in sorted(prefix_stats.keys()):
        stats = prefix_stats[prefix]
        covered = stats["tested"] + stats["traced"]
        pct = (covered / stats["total"] * 100) if stats["total"] > 0 else 0
        status = "[OK]" if pct >= 80 else "[WARN]" if pct >= 50 else "[FAIL]"
        print(
            f"| {prefix} | {stats['total']} | {stats['tested']} | {stats['traced']} | {pct:.0f}% {status} |"
        )
    print()

    # Health score.
    #
    # Every criterion below is a ratio, and a ratio over an empty set says
    # nothing about the repository. Each one used to resolve that case in the
    # repository's favour -- coverage fell back to 100%, and zero orphans out of
    # zero files read as "few orphans" -- so an empty directory scored 100/100,
    # grade A. A repo with no requirements is unaudited, not perfectly
    # traceable, so an unmeasurable criterion now awards nothing and says which
    # evidence was missing.
    print("## Traceability Score\n")
    score = 0
    attainable = 0
    unmeasured: list[str] = []

    def award(points: int, earned: int, detail: str) -> None:
        """Score one criterion that could be measured."""
        nonlocal score, attainable
        attainable += points
        score += earned
        mark = "x" if earned == points else " "
        print(f"- [{mark}] {detail} (+{earned})")

    def skip(label: str, missing: str) -> None:
        """Decline to score a criterion whose evidence set is empty."""
        unmeasured.append(missing)
        print(f"- [!] {missing}: {label} not scored (+0)")

    # No conflicts: +30. Vacuous with no IDs at all.
    if not result.all_ids:
        skip("conflicts", "No IDs found")
    elif not result.conflicts:
        award(30, 30, "No ID conflicts")
    else:
        award(30, 0, f"ID conflicts found: {len(result.conflicts)}")

    # Coverage >= 70%: +30. Undefined with no requirements.
    if not req_ids:
        skip("coverage", "No requirements found")
    else:
        # Intersect: covered_ids holds every tested or traced id, including
        # ones that are not requirements at all, so dividing the raw count by
        # the requirement count reported coverage above 100% (rdm's own repo
        # read 194%) and could clear the 70% bar on ids no requirement asked
        # for. What is being measured is the covered share OF the requirements.
        coverage = len(req_ids & covered_ids) / len(req_ids) * 100
        if coverage >= 70:
            award(30, 30, f"Coverage >= 70% ({coverage:.0f}%)")
        else:
            award(30, int(coverage / 70 * 30), f"Coverage {coverage:.0f}%")

    # Few orphan tests: +20. Zero tests is not zero orphans.
    if not result.test_files_scanned:
        skip("orphan tests", "No test files found")
    else:
        orphan_pct = len(result.orphan_tests) / result.test_files_scanned * 100
        if orphan_pct < 20:
            award(20, 20, f"Orphan tests < 20% ({orphan_pct:.0f}%)")
        else:
            award(20, 0, f"Orphan tests {orphan_pct:.0f}%")

    # Few orphan sources: +20. Zero sources is not zero orphans.
    if not result.source_files_scanned:
        skip("orphan sources", "No source files found")
    elif len(result.orphan_sources) < 5:
        award(20, 20, f"Orphan sources < 5 ({len(result.orphan_sources)})")
    else:
        award(20, 0, f"Orphan sources: {len(result.orphan_sources)}")

    print(f"\n**Total Score: {score}/100**")

    if attainable == 0:
        grade = "N/A - No evidence found to audit"
    elif score >= 90:
        grade = "A - Excellent traceability"
    elif score >= 70:
        grade = "B - Good traceability"
    elif score >= 50:
        grade = "C - Needs improvement"
    else:
        grade = "D - Significant gaps"

    print(f"**Grade: {grade}**")
    if unmeasured:
        print()
        print(
            f"> Only {attainable} of 100 points were attainable: "
            + "; ".join(unmeasured)
            + ". A criterion with nothing to measure earns nothing, so this score "
            "is a floor and the grade reflects what could be checked, not what is true."
        )
    print()

    print("=" * 60)


# =============================================================================
# CLI ENTRY POINT
# =============================================================================


def story_audit_command(repo_path: Path | None = None) -> int:
    """Run story audit command."""
    if repo_path is None:
        repo_path = Path(".")

    if not (repo_path / ".git").exists() and not (repo_path / "requirements").exists():
        print(f"Warning: {repo_path} may not be a valid project directory")

    result = run_audit(repo_path)
    print_report(result, repo_path)

    # Exit with error if conflicts found
    if result.conflicts:
        return 1
    return 0


def main() -> None:
    """CLI entry point."""
    repo_path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".")
    sys.exit(story_audit_command(repo_path))


if __name__ == "__main__":
    main()
