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
    AC_PATTERN,
    parse_frontmatter as _parse_frontmatter,
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
    """A single validation error with actionable fix guidance.

    Messages follow the pattern: what's wrong → what was expected → how to fix.
    This makes errors parseable by AI agents (Claude, Copilot) so they can
    self-correct without needing to look up documentation.
    """

    file: str
    line: int | None
    code: str
    message: str
    fix_hint: str = ""

    def __str__(self) -> str:
        loc = f"{self.file}:{self.line}" if self.line else self.file
        base = f"[{self.code}] {loc}: {self.message}"
        if self.fix_hint:
            return f"{base}\n         Fix: {self.fix_hint}"
        return base


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
        self, file: str, code: str, message: str, line: int | None = None,
        fix_hint: str = "",
    ) -> None:
        self.errors.append(ValidationError(file, line, code, message, fix_hint))

    def add_warning(
        self, file: str, code: str, message: str, line: int | None = None,
        fix_hint: str = "",
    ) -> None:
        self.warnings.append(ValidationError(file, line, code, message, fix_hint))


# =============================================================================
# VALIDATION RULES
# =============================================================================

# Valid status values for tasks (canonical forms)
VALID_TASK_STATUSES = {"To Do", "In Progress", "Done", "Blocked", "Cancelled", "Review", "In Review"}

# Aliases: map common variants to canonical forms (only non-canonical entries)
_TASK_STATUS_ALIASES: dict[str, str] = {
    "todo": "To Do",
    "in_progress": "In Progress",
    "completed": "Done",
    "canceled": "Cancelled",
    "in_review": "In Review",
    "backlog": "To Do",
    "draft": "To Do",
}

# Valid status values for milestones
VALID_MILESTONE_STATUSES = {"active", "completed", "planned"}

_MILESTONE_STATUS_ALIASES: dict[str, str] = {
    "in_progress": "active",
    "in progress": "active",
    "open": "active",
    "done": "completed",
    "complete": "completed",
    "to do": "planned",
}

# Valid decision statuses
VALID_DECISION_STATUSES = {"proposed", "accepted", "deprecated", "superseded"}

_DECISION_STATUS_ALIASES: dict[str, str] = {
    "draft": "proposed",
}

# Valid priority values
VALID_PRIORITIES = {"low", "medium", "high", "critical"}

_PRIORITY_ALIASES: dict[str, str] = {
    "normal": "medium",
}


def _find_suggestion(value: str, aliases: dict[str, str], valid: set[str]) -> str | None:
    """Find a canonical suggestion for a non-standard value.

    Returns:
        Suggested canonical value, or None if no match found.
    """
    if value in valid:
        return None
    lower = value.lower().strip()
    if lower in aliases:
        return aliases[lower]
    # Check if it's a case-insensitive match of a valid value
    for v in valid:
        if lower == v.lower():
            return v
    return None


def _check_enum_field(
    value: str,
    valid: set[str],
    aliases: dict[str, str],
    file_path: str,
    field_label: str,
    error_code: str,
    warning_code: str,
    result: ValidationResult,
    *,
    use_error_for_unknown: bool = False,
) -> None:
    """Validate an enum field with alias-based suggestions.

    Emits a warning with suggestion if alias matches, or an error/warning
    for completely unknown values.
    """
    if not value or value in valid:
        return
    suggestion = _find_suggestion(value, aliases, valid)
    if suggestion:
        result.add_warning(
            file_path, warning_code,
            f"Non-standard {field_label} '{value}' — did you mean '{suggestion}'?",
            fix_hint=f"Change {field_label.split()[0]} to: {suggestion}",
        )
    elif use_error_for_unknown:
        result.add_error(
            file_path, error_code,
            f"Invalid {field_label} '{value}'",
            fix_hint=f"Change {field_label.split()[0]} to one of: {', '.join(sorted(valid))}",
        )
    else:
        result.add_warning(
            file_path, warning_code,
            f"Invalid {field_label} '{value}'",
            fix_hint=f"Change {field_label.split()[0]} to one of: {', '.join(sorted(valid))}",
        )


# Frontmatter field examples (for error hints)
_TASK_FIELD_EXAMPLES = {"id": "FT-001", "title": "\"Task description\"", "status": "To Do"}
_MILESTONE_FIELD_EXAMPLES = {"id": "m-1", "title": "\"Milestone Title\""}
_DECISION_FIELD_EXAMPLES = {"id": "decision-1", "title": "\"ADR Title\"", "status": "proposed"}

# ID patterns
MILESTONE_ID_PATTERN = re.compile(r"^m-\d+$")  # e.g., m-1, m-2
DECISION_ID_PATTERN = re.compile(r"^decision-\d+$")

# General task ID pattern
_TASK_ID_PATTERN = re.compile(r"^[a-zA-Z0-9-]+(\.\d+)?$")


# =============================================================================
# FILE VALIDATORS
# =============================================================================


def validate_config(config_path: Path, result: ValidationResult) -> dict | None:
    """Validate config.yml file."""
    if not config_path.exists():
        result.add_error(
            str(config_path), "E001", "config.yml not found",
            fix_hint="Create backlog/config.yml with required fields:\n"
            "         project_id: \"my-proj\"\n"
            "         task_prefix: \"ft\"\n"
            "         project_name: \"My Project\"",
        )
        return None

    try:
        with open(config_path) as f:
            config = yaml.safe_load(f) or {}
    except yaml.YAMLError as e:
        result.add_error(
            str(config_path), "E002", f"Invalid YAML syntax: {e}",
            fix_hint="Check for indentation errors, missing colons, or unquoted special characters in config.yml",
        )
        return None

    # Required fields - project_name is required, task_prefix is optional
    if "project_name" not in config:
        result.add_error(
            str(config_path), "E003", "Missing required field: project_name",
            fix_hint="Add to config.yml: project_name: \"My Project Name\"",
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
                f"task_prefix '{prefix}' should be lowercase letters/hyphens (e.g., 'ft', 'hh-infra')",
                fix_hint=f"Change task_prefix to lowercase: task_prefix: \"{str(prefix).lower()}\"",
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
    frontmatter, body = _parse_frontmatter(content)

    if not frontmatter:
        result.add_error(
            str(file_path), "E010",
            "Missing or invalid YAML frontmatter",
            fix_hint="File must start with '---', followed by YAML fields, then '---'. Example:\n"
            "         ---\n"
            "         id: FT-001\n"
            "         title: My Task\n"
            "         status: To Do\n"
            "         ---",
        )
        return None

    # Required frontmatter fields
    for field_name in ("id", "title", "status"):
        if field_name not in frontmatter:
            result.add_error(
                str(file_path), "E011", f"Missing required field: {field_name}",
                fix_hint=f"Add to frontmatter: {field_name}: {_TASK_FIELD_EXAMPLES.get(field_name, '...')}",
            )

    task_id = frontmatter.get("id", "")

    # Validate ID format (flexible: supports various patterns)
    is_subtask = "." in task_id

    if not _TASK_ID_PATTERN.match(task_id):
        result.add_error(
            str(file_path),
            "E012",
            f"Invalid task ID '{task_id}': must be alphanumeric with hyphens (e.g., FT-001 or FT-001.01)",
            fix_hint="Change id to match pattern: "
            "id: {task_prefix}-NNN (parent) or {task_prefix}-NNN.NN (subtask)",
        )

    if is_subtask:
        # Check parent exists
        parent_id = frontmatter.get("parent_task_id")
        if not parent_id:
            parent_guess = task_id.rsplit(".", 1)[0]
            result.add_warning(
                str(file_path),
                "W011",
                f"Subtask '{task_id}' missing parent_task_id field",
                fix_hint=f"Add to frontmatter: parent_task_id: {parent_guess}",
            )
        elif parent_id not in known_tasks:
            result.add_warning(
                str(file_path),
                "W012",
                f"Parent task '{parent_id}' not found in tasks/ directory",
                fix_hint="Create the parent task file first, or fix parent_task_id "
                f"to an existing task ID. Known tasks: "
                f"{sorted(known_tasks)[:5]}{'...' if len(known_tasks) > 5 else ''}",
            )

    # Validate status
    _check_enum_field(
        frontmatter.get("status", ""), VALID_TASK_STATUSES, _TASK_STATUS_ALIASES,
        str(file_path), "status", "E013", "W016", result, use_error_for_unknown=True,
    )

    # Validate priority if present
    _check_enum_field(
        frontmatter.get("priority", "medium"), VALID_PRIORITIES, _PRIORITY_ALIASES,
        str(file_path), "priority", "W013", "W013", result,
    )

    # Validate milestone reference
    milestone = frontmatter.get("milestone")
    if milestone and milestone not in known_milestones:
        result.add_warning(
            str(file_path),
            "W014",
            f"Milestone '{milestone}' not found in milestones/ directory",
            fix_hint=f"Create milestones/{milestone} - Title.md, or use "
            f"an existing milestone: "
            f"{sorted(known_milestones) if known_milestones else '(none defined)'}",
        )

    # Check acceptance criteria format
    ac_matches = list(AC_PATTERN.finditer(body))
    if ac_matches:
        # Check for sequential numbering
        numbers = [int(m.group(2)) for m in ac_matches]
        expected = list(range(1, len(numbers) + 1))
        if numbers != expected:
            result.add_warning(
                str(file_path),
                "W015",
                f"Acceptance criteria not sequentially numbered: found {numbers}, expected {expected}",
                fix_hint="Renumber AC items sequentially: - [ ] #1 ..., - [ ] #2 ..., etc.",
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
    frontmatter, body = _parse_frontmatter(content)

    if not frontmatter:
        result.add_error(
            str(file_path), "E020",
            "Missing or invalid YAML frontmatter in milestone file",
            fix_hint="Milestone files must start with:\n"
            "         ---\n"
            "         id: m-1\n"
            "         title: \"Milestone Title\"\n"
            "         status: active\n"
            "         ---",
        )
        return None

    # Required fields
    for field_name in ("id", "title"):
        if field_name not in frontmatter:
            result.add_error(
                str(file_path), "E021", f"Missing required field: {field_name}",
                fix_hint=f"Add to frontmatter: {field_name}: {_MILESTONE_FIELD_EXAMPLES.get(field_name, '...')}",
            )

    milestone_id = frontmatter.get("id", "")

    # Validate ID format
    if not MILESTONE_ID_PATTERN.match(milestone_id):
        result.add_error(
            str(file_path),
            "E022",
            f"Invalid milestone ID '{milestone_id}': must match pattern m-N (e.g., m-1, m-2)",
            fix_hint="Change id to: m-{number} (e.g., m-1)",
        )

    # Validate status
    _check_enum_field(
        frontmatter.get("status", "active"), VALID_MILESTONE_STATUSES, _MILESTONE_STATUS_ALIASES,
        str(file_path), "milestone status", "W021", "W021", result,
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
    frontmatter, body = _parse_frontmatter(content)

    if not frontmatter:
        result.add_error(
            str(file_path), "E030",
            "Missing or invalid YAML frontmatter in decision file",
            fix_hint="Decision files must start with:\n"
            "         ---\n"
            "         id: decision-1\n"
            "         title: \"ADR Title\"\n"
            "         date: '2026-01-01'\n"
            "         status: proposed\n"
            "         ---",
        )
        return None

    # Required fields
    for field_name in ("id", "title", "status"):
        if field_name not in frontmatter:
            result.add_error(
                str(file_path), "E031", f"Missing required field: {field_name}",
                fix_hint=f"Add to frontmatter: {field_name}: {_DECISION_FIELD_EXAMPLES.get(field_name, '...')}",
            )

    decision_id = frontmatter.get("id", "")

    # Validate ID format
    if not DECISION_ID_PATTERN.match(decision_id):
        result.add_warning(
            str(file_path),
            "W031",
            f"Decision ID '{decision_id}' doesn't match expected pattern",
            fix_hint="Use format: decision-N (e.g., decision-1, decision-2)",
        )

    # Validate status
    _check_enum_field(
        frontmatter.get("status", ""), VALID_DECISION_STATUSES, _DECISION_STATUS_ALIASES,
        str(file_path), "decision status", "W032", "W032", result,
    )

    # Check for expected sections
    for section in ("Context", "Decision"):
        if f"## {section}" not in body:
            result.add_warning(
                str(file_path),
                "W033",
                f"Missing expected section: ## {section}",
                fix_hint=f"Add section to markdown body:\n         ## {section}\n\n         Description here.",
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
    frontmatter, body = _parse_frontmatter(content)

    if not frontmatter:
        result.add_error(
            str(file_path), "E040",
            "Missing or invalid YAML frontmatter in risk file",
            fix_hint="Risk files must start with:\n"
            "         ---\n"
            "         id: vp-risks-001\n"
            "         title: \"RC-MEAS: Measurement Risks\"\n"
            "         type: risk\n"
            "         labels: [risk, RC-MEAS]\n"
            "         ---",
        )
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
                fix_hint="Add risk entries as ## headings:\n"
                "         ## RISK-MEAS-001: Risk Title\n"
                "         ### Hazard\n"
                "         ...\n"
                "         ### Mitigation\n"
                "         ...",
            )
        else:
            for risk_id in matches:
                risk_ids.append(risk_id.lower())
                result.risks_count += 1

        # Check for required labels
        labels = frontmatter.get("labels", [])
        has_rc_label = any(lbl.startswith("RC-") for lbl in labels)
        if not has_rc_label:
            rc_name = file_path.stem.split("RC-")[-1] if "RC-" in file_path.stem else "XXX"
            result.add_warning(
                str(file_path),
                "W042",
                "Risk cluster missing RC-* label",
                fix_hint=f"Add to frontmatter labels: labels: [risk, RC-{rc_name}]",
            )
    else:
        # Single risk document
        for field_name in ("id", "title"):
            if field_name not in frontmatter:
                result.add_error(
                    str(file_path), "E041", f"Missing required field: {field_name}",
                    fix_hint=f"Add to frontmatter: {field_name}: ...",
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
            result.add_error(
                file_path, code,
                f"Schema error at field '{loc}': {err['msg']}",
                fix_hint=f"Check the '{loc}' field in frontmatter — see "
                "backlog SKILL.md for expected types and values",
            )
    else:
        result.add_error(
            file_path, code, f"Parse error: {e}",
            fix_hint="The file could not be parsed. Check YAML frontmatter "
            "syntax (--- delimiters, proper indentation, quoted strings)",
        )


def validate_task_schema(file_path: Path, result: ValidationResult) -> Task | None:
    """Validate task file against Pydantic schema used by DuckDB sync.

    Returns:
        Task object if valid, None if schema validation fails
    """
    try:
        task = parse_task(file_path)
        # Check for empty/missing required fields (parser returns defaults for invalid YAML)
        if not task.id:
            result.add_error(
                str(file_path), "E100", "Missing or empty 'id' field in frontmatter",
                fix_hint="Add to frontmatter: id: FT-001 (or {task_prefix}-NNN for subtask: FT-001.01)",
            )
            return None
        if not task.title:
            result.add_error(
                str(file_path), "E100", "Missing or empty 'title' field in frontmatter",
                fix_hint="Add to frontmatter: title: \"Descriptive task title\"",
            )
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
        result.add_error(
            str(config_path), "E001", "config.yml not found",
            fix_hint="Run 'backlog init' to create the project, then add project_id and project_name to config.yml",
        )
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

    # Validate config (schema + field-level)
    validate_config_schema(backlog_dir / "config.yml", result)
    config_data = validate_config(backlog_dir / "config.yml", result)
    task_prefix = (config_data or {}).get("task_prefix", "")

    # First pass: collect milestone IDs using schema + field validation
    known_milestones: set[str] = set()
    milestones_dir = backlog_dir / "milestones"
    if milestones_dir.exists():
        for md_file in sorted(milestones_dir.glob("*.md")):
            result.files_checked += 1
            milestone = validate_milestone_schema(md_file, result)
            if milestone:
                known_milestones.add(milestone.id)
                # Field-level checks on parsed milestone
                if not MILESTONE_ID_PATTERN.match(milestone.id):
                    result.add_error(
                        str(md_file), "E022",
                        f"Invalid milestone ID '{milestone.id}': must match pattern m-N (e.g., m-1, m-2)",
                        fix_hint="Change id to: m-{{number}} (e.g., m-1)",
                    )
                _check_enum_field(
                    milestone.status, VALID_MILESTONE_STATUSES, _MILESTONE_STATUS_ALIASES,
                    str(md_file), "milestone status", "W021", "W021", result,
                )

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

    # Pre-compute for fix hints (avoid sorting inside loop)
    known_tasks_sample = sorted(known_tasks)[:5]
    known_tasks_hint = f"{known_tasks_sample}{'...' if len(known_tasks) > 5 else ''}"

    # Second pass: validate field values and references
    seen_ids: dict[str, str] = {}
    for task, file_path in all_tasks:
        # Check for duplicate IDs
        if task.id in seen_ids:
            result.add_error(
                file_path,
                "E050",
                f"Duplicate task ID '{task.id}' — conflicts with {seen_ids[task.id]}",
                fix_hint="Rename the id in one of the files. Each task must have a unique ID.",
            )
        else:
            seen_ids[task.id] = file_path

        # Check task ID starts with expected prefix
        if task_prefix:
            base_id = task.id.split(".")[0]  # strip subtask suffix
            expected = task_prefix.upper() + "-"
            if not base_id.upper().startswith(expected):
                result.add_warning(
                    file_path, "W017",
                    f"Task ID '{task.id}' doesn't start with "
                    f"expected prefix '{task_prefix}'",
                    fix_hint=f"Rename id to start with "
                    f"{task_prefix.upper()}- "
                    f"(e.g., {task_prefix.upper()}-001)",
                )

        # Validate status
        _check_enum_field(
            task.status, VALID_TASK_STATUSES, _TASK_STATUS_ALIASES,
            file_path, "status", "E013", "W016", result, use_error_for_unknown=True,
        )

        # Validate priority
        _check_enum_field(
            task.priority, VALID_PRIORITIES, _PRIORITY_ALIASES,
            file_path, "priority", "W013", "W013", result,
        )

        # Validate subtask parent reference
        if task.is_subtask:
            if not task.parent_task_id:
                parent_guess = task.id.rsplit(".", 1)[0]
                result.add_warning(
                    file_path,
                    "W011",
                    f"Subtask '{task.id}' missing parent_task_id field",
                    fix_hint=f"Add to frontmatter: parent_task_id: {parent_guess}",
                )
            elif task.parent_task_id not in known_tasks:
                result.add_warning(
                    file_path,
                    "W012",
                    f"Parent task '{task.parent_task_id}' not found in tasks/",
                    fix_hint="Create the parent task first, or fix "
                    f"parent_task_id. Known parent tasks: {known_tasks_hint}",
                )

        # Validate milestone reference
        if task.milestone and task.milestone not in known_milestones:
            result.add_warning(
                file_path,
                "W014",
                f"Milestone '{task.milestone}' not found in milestones/ directory",
                fix_hint=f"Create milestones/{task.milestone} - Title.md, "
                f"or use an existing milestone: "
                f"{sorted(known_milestones) if known_milestones else '(none defined)'}",
            )

        # Validate AC numbering
        if task.acceptance_criteria:
            numbers = [ac.number for ac in task.acceptance_criteria]
            expected = list(range(1, len(numbers) + 1))
            if numbers != expected:
                result.add_warning(
                    file_path,
                    "W015",
                    f"Acceptance criteria not sequentially numbered: found {numbers}, expected {expected}",
                    fix_hint="Renumber AC items sequentially: - [ ] #1 ..., - [ ] #2 ..., etc.",
                )

    # Validate decisions using schema + field-level checks
    decisions_dir = backlog_dir / "decisions"
    if decisions_dir.exists():
        for md_file in sorted(decisions_dir.glob("*.md")):
            result.files_checked += 1
            decision = validate_decision_schema(md_file, result)
            if decision:
                if not DECISION_ID_PATTERN.match(decision.id):
                    result.add_warning(
                        str(md_file), "W031",
                        f"Decision ID '{decision.id}' doesn't match expected pattern",
                        fix_hint="Use format: decision-N (e.g., decision-1, decision-2)",
                    )
                _check_enum_field(
                    decision.status, VALID_DECISION_STATUSES, _DECISION_STATUS_ALIASES,
                    str(md_file), "decision status", "W032", "W032", result,
                )
                # Check for expected body sections using already-parsed fields
                if not decision.context:
                    result.add_warning(
                        str(md_file), "W033",
                        "Missing expected section: ## Context",
                        fix_hint="Add section to markdown body:\n         ## Context\n\n         Description here.",
                    )
                if not decision.decision:
                    result.add_warning(
                        str(md_file), "W033",
                        "Missing expected section: ## Decision",
                        fix_hint="Add section to markdown body:\n         ## Decision\n\n         Description here.",
                    )

    # Validate risks using schema
    docs_dir = backlog_dir / "docs"
    if docs_dir.exists():
        for md_file in sorted(docs_dir.glob("**/*RC-*.md")):
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
        fm, _ = _parse_frontmatter(content)

        if not fm:
            result.add_error(
                str(file_path), "E001",
                "Could not parse YAML frontmatter",
                fix_hint="File must start with '---' on line 1, followed by "
                "YAML key: value pairs, then '---' on its own line",
            )
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
