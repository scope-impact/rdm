"""
Validate Backlog.md markdown files for consistency and DuckDB schema compatibility.

Usage:
    rdm story backlog-validate [backlog_dir]
    rdm story backlog-validate --file path/to/task.md

Checks performed:
- Schema validation: Pydantic models match DuckDB sync expectations
- Frontmatter: required fields present, valid YAML
- IDs: valid format, no duplicates
- References: milestones exist, parent tasks exist
- Status: valid enum values
- Acceptance criteria: proper format

Exit codes:
- 0: All validations passed
- 1: Validation errors found
- 2: File/directory not found
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

try:
    import yaml
    from pydantic import ValidationError as PydanticValidationError
except ImportError:
    raise ImportError("pyyaml and pydantic are required. Install with: pip install rdm[story-audit]")

from rdm.story_audit.backlog_schema import (
    SCHEMA_VERSION,
    BacklogConfig,
    Task,
    Milestone,
    RiskDoc,
    Decision,
)
from rdm.story_audit.backlog_parser import (
    parse_task,
    parse_milestone,
    parse_decision,
    parse_risk_cluster,
    parse_config,
)


# =============================================================================
# VALIDATION RESULT TYPES
# =============================================================================


@dataclass
class ValidationError:
    """A single validation error."""

    file: str
    line: int | None
    code: str
    message: str

    def __str__(self) -> str:
        loc = f"{self.file}:{self.line}" if self.line else self.file
        return f"[{self.code}] {loc}: {self.message}"


@dataclass
class ValidationResult:
    """Result of validating a backlog directory."""

    errors: list[ValidationError] = field(default_factory=list)
    warnings: list[ValidationError] = field(default_factory=list)
    files_checked: int = 0
    tasks_count: int = 0
    milestones_count: int = 0
    risks_count: int = 0
    decisions_count: int = 0

    @property
    def is_valid(self) -> bool:
        return len(self.errors) == 0

    def add_error(
        self, file: str, code: str, message: str, line: int | None = None
    ) -> None:
        self.errors.append(ValidationError(file, line, code, message))

    def add_warning(
        self, file: str, code: str, message: str, line: int | None = None
    ) -> None:
        self.warnings.append(ValidationError(file, line, code, message))


# =============================================================================
# VALIDATION RULES
# =============================================================================

# Valid status values for tasks
VALID_TASK_STATUSES = {"To Do", "In Progress", "Done", "Blocked", "Cancelled"}

# Valid status values for milestones
VALID_MILESTONE_STATUSES = {"active", "completed", "planned"}

# Valid decision statuses
VALID_DECISION_STATUSES = {"proposed", "accepted", "deprecated", "superseded"}

# Valid priority values
VALID_PRIORITIES = {"low", "medium", "high", "critical"}

# ID patterns
MILESTONE_ID_PATTERN = re.compile(r"^m-\d+$")  # e.g., m-1, m-2
DECISION_ID_PATTERN = re.compile(r"^decision-\d+$")


# =============================================================================
# FRONTMATTER PARSING
# =============================================================================


def parse_frontmatter(content: str) -> tuple[dict | None, str, int]:
    """Extract YAML frontmatter from markdown.

    Returns:
        Tuple of (frontmatter dict or None if invalid, body, end_line)
    """
    if not content.startswith("---"):
        return None, content, 0

    end_match = re.search(r"\n---\s*\n", content[3:])
    if not end_match:
        return None, content, 0

    yaml_str = content[3 : end_match.start() + 3]
    body = content[end_match.end() + 3 :]
    end_line = yaml_str.count("\n") + 2  # +2 for opening and closing ---

    try:
        frontmatter = yaml.safe_load(yaml_str) or {}
        return frontmatter, body, end_line
    except yaml.YAMLError:
        return None, content, end_line


# =============================================================================
# FILE VALIDATORS
# =============================================================================


def validate_config(config_path: Path, result: ValidationResult) -> dict | None:
    """Validate config.yml file."""
    if not config_path.exists():
        result.add_error(str(config_path), "E001", "config.yml not found")
        return None

    try:
        with open(config_path) as f:
            config = yaml.safe_load(f) or {}
    except yaml.YAMLError as e:
        result.add_error(str(config_path), "E002", f"Invalid YAML: {e}")
        return None

    # Required fields - project_name is required, task_prefix is optional
    if "project_name" not in config:
        result.add_error(
            str(config_path), "E003", "Missing required field: project_name"
        )

    # Derive task_prefix from project_name if not provided
    if "task_prefix" not in config and "project_name" in config:
        # Use first letters of words: "My Project" -> "mp"
        words = config["project_name"].lower().replace("-", " ").replace("_", " ").split()
        config["task_prefix"] = "".join(w[0] for w in words if w) or "task"

    # task_prefix should be lowercase letters/hyphens
    if "task_prefix" in config:
        prefix = config["task_prefix"]
        if not re.match(r"^[a-z]+(-[a-z]+)*$", str(prefix)):
            result.add_warning(
                str(config_path),
                "W004",
                f"task_prefix '{prefix}' should be lowercase letters/hyphens",
            )

    return config


def validate_task_file(
    file_path: Path,
    result: ValidationResult,
    config: dict,
    known_milestones: set[str],
    known_tasks: set[str],
) -> str | None:
    """Validate a task markdown file.

    Returns:
        Task ID if valid, None otherwise
    """
    result.files_checked += 1
    content = file_path.read_text()
    frontmatter, body, fm_end_line = parse_frontmatter(content)

    if frontmatter is None:
        result.add_error(str(file_path), "E010", "Missing or invalid frontmatter")
        return None

    # Required frontmatter fields
    required = ["id", "title", "status"]
    for field_name in required:
        if field_name not in frontmatter:
            result.add_error(
                str(file_path), "E011", f"Missing required field: {field_name}"
            )

    task_id = frontmatter.get("id", "")

    # Validate ID format (flexible: supports various patterns)
    is_subtask = "." in task_id

    # General ID pattern: alphanumeric with hyphens, optionally ending with .NN for subtasks
    general_id_pattern = re.compile(r"^[a-zA-Z0-9-]+(\.\d+)?$")

    if not general_id_pattern.match(task_id):
        result.add_error(
            str(file_path),
            "E012",
            f"Invalid task ID '{task_id}': must be alphanumeric with hyphens",
        )

    if is_subtask:
        # Check parent exists
        parent_id = frontmatter.get("parent_task_id")
        if not parent_id:
            result.add_warning(
                str(file_path),
                "W011",
                f"Subtask '{task_id}' missing parent_task_id field",
            )
        elif parent_id not in known_tasks:
            result.add_warning(
                str(file_path),
                "W012",
                f"Parent task '{parent_id}' not found",
            )

    # Validate status
    status = frontmatter.get("status", "")
    if status and status not in VALID_TASK_STATUSES:
        result.add_error(
            str(file_path),
            "E013",
            f"Invalid status '{status}': must be one of {VALID_TASK_STATUSES}",
        )

    # Validate priority if present
    priority = frontmatter.get("priority", "medium")
    if priority not in VALID_PRIORITIES:
        result.add_warning(
            str(file_path),
            "W013",
            f"Invalid priority '{priority}': should be one of {VALID_PRIORITIES}",
        )

    # Validate milestone reference
    milestone = frontmatter.get("milestone")
    if milestone and milestone not in known_milestones:
        result.add_warning(
            str(file_path),
            "W014",
            f"Milestone '{milestone}' not found",
        )

    # Check acceptance criteria format
    ac_pattern = re.compile(r"^-\s*\[([ xX])\]\s*#(\d+)\s+(.+)$", re.MULTILINE)
    ac_matches = list(ac_pattern.finditer(body))
    if ac_matches:
        # Check for sequential numbering
        numbers = [int(m.group(2)) for m in ac_matches]
        expected = list(range(1, len(numbers) + 1))
        if numbers != expected:
            result.add_warning(
                str(file_path),
                "W015",
                f"Acceptance criteria not sequentially numbered: {numbers}",
            )

    result.tasks_count += 1
    return task_id


def validate_milestone_file(
    file_path: Path, result: ValidationResult
) -> str | None:
    """Validate a milestone markdown file.

    Returns:
        Milestone ID if valid, None otherwise
    """
    result.files_checked += 1
    content = file_path.read_text()
    frontmatter, body, fm_end_line = parse_frontmatter(content)

    if frontmatter is None:
        result.add_error(str(file_path), "E020", "Missing or invalid frontmatter")
        return None

    # Required fields
    required = ["id", "title"]
    for field_name in required:
        if field_name not in frontmatter:
            result.add_error(
                str(file_path), "E021", f"Missing required field: {field_name}"
            )

    milestone_id = frontmatter.get("id", "")

    # Validate ID format
    if not MILESTONE_ID_PATTERN.match(milestone_id):
        result.add_error(
            str(file_path),
            "E022",
            f"Invalid milestone ID '{milestone_id}': expected m-N format",
        )

    # Validate status
    status = frontmatter.get("status", "active")
    if status not in VALID_MILESTONE_STATUSES:
        result.add_warning(
            str(file_path),
            "W021",
            f"Invalid status '{status}': should be one of {VALID_MILESTONE_STATUSES}",
        )

    result.milestones_count += 1
    return milestone_id


def validate_decision_file(file_path: Path, result: ValidationResult) -> str | None:
    """Validate a decision markdown file.

    Returns:
        Decision ID if valid, None otherwise
    """
    result.files_checked += 1
    content = file_path.read_text()
    frontmatter, body, fm_end_line = parse_frontmatter(content)

    if frontmatter is None:
        result.add_error(str(file_path), "E030", "Missing or invalid frontmatter")
        return None

    # Required fields
    required = ["id", "title", "status"]
    for field_name in required:
        if field_name not in frontmatter:
            result.add_error(
                str(file_path), "E031", f"Missing required field: {field_name}"
            )

    decision_id = frontmatter.get("id", "")

    # Validate ID format
    if not DECISION_ID_PATTERN.match(decision_id):
        result.add_warning(
            str(file_path),
            "W031",
            f"Decision ID '{decision_id}' doesn't match expected decision-N format",
        )

    # Validate status
    status = frontmatter.get("status", "")
    if status and status not in VALID_DECISION_STATUSES:
        result.add_warning(
            str(file_path),
            "W032",
            f"Invalid status '{status}': should be one of {VALID_DECISION_STATUSES}",
        )

    # Check for expected sections
    expected_sections = ["Context", "Decision"]
    for section in expected_sections:
        if f"## {section}" not in body:
            result.add_warning(
                str(file_path),
                "W033",
                f"Missing expected section: ## {section}",
            )

    result.decisions_count += 1
    return decision_id


def validate_risk_file(file_path: Path, result: ValidationResult) -> list[str]:
    """Validate a risk document markdown file.

    Returns:
        List of risk IDs if valid
    """
    result.files_checked += 1
    content = file_path.read_text()
    frontmatter, body, fm_end_line = parse_frontmatter(content)

    if frontmatter is None:
        result.add_error(str(file_path), "E040", "Missing or invalid frontmatter")
        return []

    risk_ids = []

    # Check if it's a risk cluster (RC-*) file
    if "RC-" in file_path.name:
        # Risk cluster: look for ## RISK-XXX-NNN: Title
        risk_pattern = re.compile(r"^##\s+(RISK-[A-Z]+-\d+):", re.MULTILINE)
        matches = risk_pattern.findall(body)

        if not matches:
            result.add_warning(
                str(file_path),
                "W041",
                "Risk cluster file has no RISK-XXX-NNN entries",
            )
        else:
            for risk_id in matches:
                risk_ids.append(risk_id.lower())
                result.risks_count += 1

        # Check for required labels
        labels = frontmatter.get("labels", [])
        has_rc_label = any(lbl.startswith("RC-") for lbl in labels)
        if not has_rc_label:
            result.add_warning(
                str(file_path),
                "W042",
                "Risk cluster missing RC-* label",
            )
    else:
        # Single risk document
        required = ["id", "title"]
        for field_name in required:
            if field_name not in frontmatter:
                result.add_error(
                    str(file_path), "E041", f"Missing required field: {field_name}"
                )

        risk_id = frontmatter.get("id", "")
        if risk_id:
            risk_ids.append(risk_id)
            result.risks_count += 1

    return risk_ids


# =============================================================================
# SCHEMA VALIDATION (using actual Pydantic models)
# =============================================================================


def _handle_validation_error(e: Exception, file_path: str, code: str, result: ValidationResult) -> None:
    """Handle validation errors from Pydantic."""
    if isinstance(e, PydanticValidationError):
        for err in e.errors():
            loc = ".".join(str(x) for x in err["loc"])
            result.add_error(file_path, code, f"Schema error at {loc}: {err['msg']}")
    else:
        result.add_error(file_path, code, f"Parse error: {e}")


def validate_task_schema(file_path: Path, result: ValidationResult) -> Task | None:
    """Validate task file against Pydantic schema used by DuckDB sync.

    Returns:
        Task object if valid, None if schema validation fails
    """
    try:
        task = parse_task(file_path)
        # Check for empty/missing required fields (parser returns defaults for invalid YAML)
        if not task.id:
            result.add_error(str(file_path), "E100", "Missing or invalid 'id' field in frontmatter")
            return None
        if not task.title:
            result.add_error(str(file_path), "E100", "Missing or invalid 'title' field in frontmatter")
            return None
        result.tasks_count += 1
        return task
    except Exception as e:
        _handle_validation_error(e, str(file_path), "E100", result)
        return None


def validate_milestone_schema(file_path: Path, result: ValidationResult) -> Milestone | None:
    """Validate milestone file against Pydantic schema."""
    try:
        milestone = parse_milestone(file_path)
        result.milestones_count += 1
        return milestone
    except Exception as e:
        _handle_validation_error(e, str(file_path), "E110", result)
        return None


def validate_decision_schema(file_path: Path, result: ValidationResult) -> Decision | None:
    """Validate decision file against Pydantic schema."""
    try:
        decision = parse_decision(file_path)
        result.decisions_count += 1
        return decision
    except Exception as e:
        _handle_validation_error(e, str(file_path), "E120", result)
        return None


def validate_risk_cluster_schema(file_path: Path, result: ValidationResult) -> list[RiskDoc]:
    """Validate risk cluster file against Pydantic schema."""
    try:
        risks = parse_risk_cluster(file_path)
        result.risks_count += len(risks)
        return risks
    except Exception as e:
        _handle_validation_error(e, str(file_path), "E130", result)
        return []


def validate_config_schema(config_path: Path, result: ValidationResult) -> BacklogConfig | None:
    """Validate config.yml against Pydantic schema."""
    if not config_path.exists():
        result.add_error(str(config_path), "E001", "config.yml not found")
        return None

    try:
        config = parse_config(config_path)
        return config
    except Exception as e:
        _handle_validation_error(e, str(config_path), "E002", result)
        return None


# =============================================================================
# MAIN VALIDATION
# =============================================================================


def validate_backlog(backlog_dir: Path, strict: bool = False) -> ValidationResult:
    """Validate all files in a Backlog.md directory against DuckDB schema.

    Uses the same Pydantic models and parsers as `rdm story sync` to ensure
    files will sync correctly to DuckDB.

    Args:
        backlog_dir: Path to backlog directory
        strict: If True, treat warnings as errors

    Returns:
        ValidationResult with all errors and warnings
    """
    result = ValidationResult()

    # Validate config against schema
    validate_config_schema(backlog_dir / "config.yml", result)

    # First pass: collect milestone IDs using schema validation
    known_milestones: set[str] = set()
    milestones_dir = backlog_dir / "milestones"
    if milestones_dir.exists():
        for md_file in sorted(milestones_dir.glob("*.md")):
            result.files_checked += 1
            milestone = validate_milestone_schema(md_file, result)
            if milestone:
                known_milestones.add(milestone.id)

    # First pass: collect parent task IDs
    known_tasks: set[str] = set()
    all_tasks: list[tuple[Task, str]] = []  # (task, file) for validation

    for tasks_dir in [backlog_dir / "tasks", backlog_dir / "completed"]:
        if not tasks_dir.exists():
            continue
        for md_file in sorted(tasks_dir.glob("*.md")):
            result.files_checked += 1
            task = validate_task_schema(md_file, result)
            if task:
                all_tasks.append((task, str(md_file)))
                if not task.is_subtask:
                    known_tasks.add(task.id)

    # Second pass: validate references
    seen_ids: dict[str, str] = {}
    for task, file_path in all_tasks:
        # Check for duplicate IDs
        if task.id in seen_ids:
            result.add_error(
                file_path,
                "E050",
                f"Duplicate task ID '{task.id}' (also in {seen_ids[task.id]})",
            )
        else:
            seen_ids[task.id] = file_path

        # Check milestone reference
        if task.milestone and task.milestone not in known_milestones:
            result.add_warning(
                file_path,
                "W014",
                f"Milestone '{task.milestone}' not found",
            )

        # Check parent task reference for subtasks
        if task.is_subtask and task.parent_task_id not in known_tasks:
            result.add_warning(
                file_path,
                "W012",
                f"Parent task '{task.parent_task_id}' not found",
            )

    # Validate decisions using schema
    decisions_dir = backlog_dir / "decisions"
    if decisions_dir.exists():
        for md_file in sorted(decisions_dir.glob("*.md")):
            result.files_checked += 1
            validate_decision_schema(md_file, result)

    # Validate risks using schema
    docs_dir = backlog_dir / "docs"
    if docs_dir.exists():
        for md_file in sorted(docs_dir.glob("*RC-*.md")):
            result.files_checked += 1
            validate_risk_cluster_schema(md_file, result)

    # In strict mode, promote warnings to errors
    if strict:
        result.errors.extend(result.warnings)
        result.warnings = []

    return result


# =============================================================================
# OUTPUT FORMATTING
# =============================================================================


def print_result(result: ValidationResult, verbose: bool = False) -> None:
    """Print validation results."""
    if result.errors:
        print("\nErrors:")
        for error in result.errors:
            print(f"  {error}")

    if result.warnings and verbose:
        print("\nWarnings:")
        for warning in result.warnings:
            print(f"  {warning}")

    print("\n" + "=" * 60)
    print("BACKLOG VALIDATION SUMMARY")
    print("=" * 60)
    print(f"""
  Schema Version:   v{SCHEMA_VERSION}
  Files checked:    {result.files_checked}
  Milestones:       {result.milestones_count}
  Tasks:            {result.tasks_count}
  Risks:            {result.risks_count}
  Decisions:        {result.decisions_count}

  Errors:           {len(result.errors)}
  Warnings:         {len(result.warnings)}
""")

    if result.is_valid:
        print("All validations passed!")
    else:
        print(f"Found {len(result.errors)} error(s)")

    print("=" * 60)


# =============================================================================
# CLI ENTRY POINT
# =============================================================================


def story_backlog_validate_command(
    backlog_dir: Path | None = None,
    file_path: Path | None = None,
    strict: bool = False,
    verbose: bool = False,
    quiet: bool = False,
) -> int:
    """Run backlog validation command.

    Args:
        backlog_dir: Path to backlog directory
        file_path: Single file to validate
        strict: Treat warnings as errors
        verbose: Show warnings
        quiet: Only show summary

    Returns:
        0 if valid, 1 if errors, 2 if not found
    """
    # Single file validation
    if file_path:
        if not file_path.exists():
            print(f"Error: File not found: {file_path}")
            return 2

        result = ValidationResult()
        content = file_path.read_text()
        fm, _, _ = parse_frontmatter(content)

        if fm is None:
            result.add_error(str(file_path), "E001", "Invalid frontmatter")
        else:
            # Determine file type and validate
            if "milestone" in str(file_path) or fm.get("id", "").startswith("m-"):
                validate_milestone_file(file_path, result)
            elif "decision" in str(file_path):
                validate_decision_file(file_path, result)
            elif "risk" in str(file_path).lower() or "RC-" in str(file_path):
                validate_risk_file(file_path, result)
            else:
                # Assume task
                validate_task_file(file_path, result, {}, set(), set())

        if not quiet:
            print_result(result, verbose)

        return 0 if result.is_valid else 1

    # Directory validation
    backlog_path = (backlog_dir or Path("backlog")).resolve()
    if not backlog_path.exists():
        print(f"Error: Backlog directory not found: {backlog_path}")
        return 2

    print(f"Validating backlog: {backlog_path}\n")

    result = validate_backlog(backlog_path, strict=strict)

    if not quiet:
        print_result(result, verbose)

    return 0 if result.is_valid else 1


def main() -> None:
    """CLI entry point."""
    import argparse

    parser = argparse.ArgumentParser(
        description="Validate Backlog.md markdown files"
    )
    parser.add_argument(
        "backlog_dir",
        nargs="?",
        type=Path,
        default=Path("backlog"),
        help="Path to backlog directory (default: ./backlog)",
    )
    parser.add_argument(
        "--file", "-f", type=Path, help="Validate a single file"
    )
    parser.add_argument(
        "--strict", "-s", action="store_true", help="Treat warnings as errors"
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Show warnings"
    )
    parser.add_argument(
        "--quiet", "-q", action="store_true", help="Only show summary"
    )
    args = parser.parse_args()

    sys.exit(
        story_backlog_validate_command(
            backlog_dir=args.backlog_dir,
            file_path=args.file,
            strict=args.strict,
            verbose=args.verbose,
            quiet=args.quiet,
        )
    )


if __name__ == "__main__":
    main()
