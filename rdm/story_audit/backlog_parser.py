"""
Markdown parser for Backlog.md format.

Parses markdown files with YAML frontmatter from:
- config.yml
- milestones/*.md
- tasks/*.md
- completed/*.md
- docs/doc-risk-*.md
- decisions/*.md
"""

from __future__ import annotations

import re
from collections.abc import Sequence
from pathlib import Path

try:
    import yaml
except ImportError:
    raise ImportError("pyyaml is required. Install with: pip install pyyaml")

from rdm.story_audit.schema import RISK_HEADING_PATTERN
from rdm.story_audit.backlog_schema import (
    AcceptanceCriterion,
    BacklogConfig,
    BacklogData,
    Decision,
    Milestone,
    RiskDoc,
    Task,
)


# =============================================================================
# FRONTMATTER PARSING
# =============================================================================


def parse_frontmatter(content: str) -> tuple[dict, str]:
    """Extract YAML frontmatter and body from markdown content.

    Args:
        content: Raw markdown file content

    Returns:
        Tuple of (frontmatter dict, body string)
    """
    if not content.startswith("---"):
        return {}, content

    # Find the closing ---
    end_match = re.search(r"\n---\s*\n", content[3:])
    if not end_match:
        return {}, content

    yaml_str = content[3 : end_match.start() + 3]
    body = content[end_match.end() + 3 :].strip()

    try:
        frontmatter = yaml.safe_load(yaml_str) or {}
    except yaml.YAMLError:
        return {}, content

    return frontmatter, body


# =============================================================================
# ACCEPTANCE CRITERIA PARSING
# =============================================================================

# Pattern: - [x] #1 text or - [ ] #2 text
AC_PATTERN = re.compile(r"^-\s*\[([ xX])\]\s*#(\d+)\s+(.+)$", re.MULTILINE)


def parse_acceptance_criteria(body: str) -> list[AcceptanceCriterion]:
    """Parse acceptance criteria from markdown body.

    Looks for pattern: `- [x] #1 AC text` or `- [ ] #2 AC text`
    within <!-- AC:BEGIN --> and <!-- AC:END --> markers.

    Args:
        body: Markdown body content

    Returns:
        List of AcceptanceCriterion objects
    """
    criteria = []

    # Extract AC section if markers exist
    ac_match = re.search(r"<!-- AC:BEGIN -->(.*?)<!-- AC:END -->", body, re.DOTALL)
    ac_section = ac_match.group(1) if ac_match else body

    for match in AC_PATTERN.finditer(ac_section):
        checkbox, number, text = match.groups()
        criteria.append(
            AcceptanceCriterion(
                number=int(number),
                text=text.strip(),
                completed=checkbox.lower() == "x",
            )
        )

    return criteria


# =============================================================================
# SECTION PARSING
# =============================================================================


_section_pattern_cache: dict[str, re.Pattern[str]] = {}
_SECTION_MARKER_RE = re.compile(r"<!--\s*SECTION:\w+:(?:BEGIN|END)\s*-->")


def extract_section(body: str, heading: str) -> str:
    """Extract content under a markdown heading.

    Args:
        body: Markdown body content
        heading: Heading text (without ##)

    Returns:
        Content under the heading until next heading or end
    """
    # Cache compiled regex per heading to avoid recompilation
    if heading not in _section_pattern_cache:
        pattern = rf"^##\s+{re.escape(heading)}\s*\n(.*?)(?=^##\s|\Z)"
        _section_pattern_cache[heading] = re.compile(pattern, re.MULTILINE | re.DOTALL)
    match = _section_pattern_cache[heading].search(body)
    if match:
        content = match.group(1).strip()
        # Strip backlog CLI section markers (<!-- SECTION:*:BEGIN/END -->)
        content = _SECTION_MARKER_RE.sub("", content).strip()
        return content
    return ""


def extract_list_items(body: str, heading: str) -> list[str]:
    """Extract list items under a markdown heading.

    Args:
        body: Markdown body content
        heading: Heading text (without ##)

    Returns:
        List of item texts
    """
    section = extract_section(body, heading)
    if not section:
        return []

    items = []
    for line in section.split("\n"):
        line = line.strip()
        if line.startswith("- "):
            items.append(line[2:].strip())
    return items


# =============================================================================
# CONFIG PARSING
# =============================================================================


def parse_config(config_path: Path) -> BacklogConfig:
    """Parse config.yml from backlog root.

    Args:
        config_path: Path to config.yml

    Returns:
        BacklogConfig object
    """
    with open(config_path) as f:
        data = yaml.safe_load(f) or {}

    # Handle missing project_id by deriving from repository or task_prefix
    if "project_id" not in data:
        if "repository" in data and data["repository"]:
            # Extract initials from an "owner/repo" slug -> a short prefix
            repo = data["repository"].split("/")[-1]
            # Take the first letter of each hyphenated word
            data["project_id"] = "".join(
                word[0] for word in repo.replace("-", " ").replace("_", " ").split() if word
            )
        else:
            # Fallback to task_prefix
            data["project_id"] = data.get("task_prefix", "unknown")

    return BacklogConfig(**data)


# =============================================================================
# MILESTONE PARSING
# =============================================================================


def parse_milestone(file_path: Path) -> Milestone:
    """Parse a milestone markdown file.

    Args:
        file_path: Path to milestone .md file

    Returns:
        Milestone object
    """
    content = file_path.read_text()
    frontmatter, body = parse_frontmatter(content)

    # Extract description
    description = extract_section(body, "Description")

    # Extract features list
    features_section = extract_section(body, "Features")
    features = []
    for line in features_section.split("\n"):
        # Pattern: - FT-001: Bootstrap Infrastructure... (case-insensitive)
        match = re.match(r"^-\s+([\w-]+-\d+):", line.strip())
        if match:
            features.append(match.group(1))

    return Milestone(
        id=frontmatter.get("id", ""),
        title=frontmatter.get("title", ""),
        status=frontmatter.get("status", "active"),
        created_date=frontmatter.get("created_date"),
        labels=frontmatter.get("labels", []),
        description=description,
        features=features,
    )


# =============================================================================
# TASK PARSING
# =============================================================================


def parse_task(file_path: Path) -> Task:
    """Parse a task or subtask markdown file.

    Args:
        file_path: Path to task .md file

    Returns:
        Task object
    """
    content = file_path.read_text()
    frontmatter, body = parse_frontmatter(content)

    # Extract description
    description = extract_section(body, "Description")

    # Extract business value
    business_value = extract_section(body, "Business Value")

    # Extract acceptance criteria
    acceptance_criteria = parse_acceptance_criteria(body)

    # Extract subtask IDs from Subtasks section
    subtasks_section = extract_section(body, "Subtasks")
    subtask_ids = []
    for line in subtasks_section.split("\n"):
        # Pattern: - FT-003.01: K3s cluster... (any task prefix)
        match = re.match(r"^-\s+([\w-]+-\d+\.\d+):", line.strip())
        if match:
            subtask_ids.append(match.group(1))

    return Task(
        id=frontmatter.get("id", ""),
        title=frontmatter.get("title", ""),
        status=frontmatter.get("status", "To Do"),
        parent_task_id=frontmatter.get("parent_task_id"),
        labels=frontmatter.get("labels", []),
        milestone=frontmatter.get("milestone"),
        priority=frontmatter.get("priority", "medium"),
        created_date=frontmatter.get("created_date"),
        description=description,
        business_value=business_value,
        acceptance_criteria=acceptance_criteria,
        subtask_ids=subtask_ids,
        source_file=file_path.name,
    )


# =============================================================================
# RISK PARSING
# =============================================================================


# Table labels that mean the same field. The key is what consumers read; the
# values are spellings seen in real risk clusters. One register writing
# `**STRIDE Category**` and another `**STRIDE**` is enough to break this: the
# label is lowercased into the lookup key, so the second spelling lands under
# `stride`, every consumer reading `stride_category` sees None, and a whole
# register loses its classification silently -- a missing category and a risk
# nobody classified are the same value downstream.
RISK_TABLE_ALIASES = {
    "stride": "stride_category",
    "stride_type": "stride_category",
    "risk_level": "risk_level",
    "hazard_category": "hazard_category",
    # A residual re-scored from a post-control severity and probability, rather
    # than asserted as a bare level. Parenthesised labels are the spelling in
    # use; without these the rows parse to keys nothing reads.
    "severity_(post-control)": "severity_post_control",
    "severity_post-control": "severity_post_control",
    "post-control_severity": "severity_post_control",
    "probability_(post-control)": "probability_post_control",
    "probability_post-control": "probability_post_control",
    "post-control_probability": "probability_post_control",
}


def _unwrap(text: str) -> str:
    """Collapse a source-wrapped paragraph to one line.

    The line breaks are the markdown author's column width, not part of the
    sentence; leaving them in means every consumer re-collapses them.
    """
    return re.sub(r"\s+", " ", text or "").strip()


def parse_risk_table(body: str) -> dict[str, str]:
    """Parse risk details table from markdown.

    Format:
        | Attribute | Value |
        |-----------|-------|
        | **STRIDE Category** | Spoofing |
        | **Severity** | Critical |

    Known label spellings are normalised via RISK_TABLE_ALIASES, so a cluster
    writing `**STRIDE**` is read the same as one writing `**STRIDE Category**`.

    Returns:
        Dict of attribute -> value
    """
    result = {}
    table_pattern = re.compile(r"\|\s*\*\*([^*]+)\*\*\s*\|\s*([^|]+)\|")

    for match in table_pattern.finditer(body):
        key = match.group(1).strip().lower().replace(" ", "_")
        key = RISK_TABLE_ALIASES.get(key, key)
        # A value read out of a table should not carry the table's emphasis:
        # `**High**` and `High` are the same risk level, and a consumer that
        # compares them as strings sees two.
        value = re.sub(r"\*\*|`", "", match.group(2)).strip()
        result[key] = value

    return result


def _parse_control_line(line_text: str) -> tuple[str, list[str]]:
    """Parse a control line to extract description and refs.

    Handles both simple refs and markdown link refs:
    - Simple: (refs: task-id:AC-001, task-id:AC-002)
    - Markdown: (refs: [task-id](url):AC-001, [task-id2](url2):AC-002)

    Returns:
        Tuple of (description, list of refs)
    """
    # Match refs at end of line, handling nested parens in markdown links
    refs_match = re.search(r"\(refs?:\s*(.+)\)\s*$", line_text)
    if not refs_match:
        return line_text.strip(), []

    refs_text = refs_match.group(1)
    refs = []

    # Pattern handles markdown links [id](url):suffix or plain id:suffix
    for ref_match in re.finditer(r"\[([^\]]+)\]\([^)]+\)(:\S+)?|([^\s,\[\]]+:\S+)", refs_text):
        if ref_match.group(1):  # Markdown link
            task_id = ref_match.group(1)
            ac_suffix = ref_match.group(2) or ""
            refs.append(f"{task_id}{ac_suffix}")
        elif ref_match.group(3):  # Plain ref
            refs.append(ref_match.group(3))

    # Fallback to simple comma split if no refs matched
    if not refs:
        refs = [r.strip() for r in refs_text.split(",")]

    description = re.sub(r"\s*\(refs?:\s*.+\)\s*$", "", line_text).strip()
    return description, refs


def parse_risk_controls(body: str) -> tuple[list[str], list[list[str]]]:
    """Parse control descriptions and their AC refs from markdown.

    Format:
        ### Controls
        - Control description (refs: US-XXX:AC-001)
        - Another control (refs: US-XXX:AC-002, US-YYY:AC-003)

    Returns:
        Tuple of (control descriptions, list of refs per control)
    """
    controls_section = extract_section(body, "Mitigation")
    if not controls_section:
        return [], []

    controls = []
    control_refs = []

    # Look for lines starting with - within Controls subsection
    in_controls = False
    for line in controls_section.split("\n"):
        if "### Controls" in line:
            in_controls = True
            continue
        if line.startswith("###") or line.startswith("**Residual"):
            in_controls = False
            continue

        if in_controls and line.strip().startswith("- "):
            description, refs = _parse_control_line(line.strip()[2:])
            controls.append(description)
            control_refs.append(refs)

    return controls, control_refs


def parse_risk(file_path: Path) -> RiskDoc:
    """Parse a risk document markdown file.

    Args:
        file_path: Path to risk .md file

    Returns:
        RiskDoc object
    """
    content = file_path.read_text()
    frontmatter, body = parse_frontmatter(content)

    # Parse risk table
    table_data = parse_risk_table(body)

    # Parse controls
    controls, control_refs = parse_risk_controls(body)

    # Extract mitigation status
    mitigation_section = extract_section(body, "Mitigation")
    mitigation_status = None
    residual_risk = None

    if mitigation_section:
        # Look for **Status:** Mitigated
        status_match = re.search(r"\*\*Status:\*\*\s*(\w+)", mitigation_section)
        if status_match:
            mitigation_status = status_match.group(1)

        # Look for **Residual Risk:** Low
        residual_match = re.search(r"\*\*Residual Risk:\*\*\s*(\w+)", mitigation_section)
        if residual_match:
            residual_risk = residual_match.group(1)

    # Extract affected requirements
    affected_section = extract_section(body, "Affected Requirements")
    affected_requirements = []
    for line in affected_section.split("\n"):
        # Match any task prefix (e.g., vp-001.01) or legacy US-XXX-NNN
        match = re.match(r"^-\s+([\w-]+-\d+(?:\.\d+)?|US-[A-Z]+-\d+)", line.strip())
        if match:
            affected_requirements.append(match.group(1))

    return RiskDoc(
        id=frontmatter.get("id", ""),
        title=frontmatter.get("title", ""),
        type=frontmatter.get("type", "risk"),
        created_date=frontmatter.get("created_date"),
        labels=frontmatter.get("labels", []),
        # From table
        stride_category=table_data.get("stride_category"),
        severity=table_data.get("severity"),
        probability=table_data.get("probability"),
        risk_level=table_data.get("risk_level"),
        cluster=table_data.get("cluster"),
        # Hazard-Situation-Harm
        hazard=extract_section(body, "Hazard"),
        situation=extract_section(body, "Situation"),
        harm=extract_section(body, "Harm"),
        description=extract_section(body, "Description"),
        # Traceability
        affected_requirements=affected_requirements,
        # Mitigation
        mitigation_status=mitigation_status,
        residual_risk=residual_risk,
        controls=controls,
        control_refs=control_refs,
        source_file=file_path.name,
    )


def _extract_risk_section(section: str, heading: str) -> str:
    """Extract content under a ### heading within a risk section."""
    pattern = rf"^###\s+{re.escape(heading)}\s*\n(.*?)(?=^###\s|^##\s|\Z)"
    match = re.search(pattern, section, re.MULTILINE | re.DOTALL)
    return match.group(1).strip() if match else ""


def _extract_risk_controls_from_section(section: str) -> tuple[list[str], list[list[str]]]:
    """Parse controls from a risk section's Mitigation."""
    mitigation = _extract_risk_section(section, "Mitigation")
    if not mitigation:
        return [], []

    controls = []
    control_refs = []
    in_controls = False

    for line in mitigation.split("\n"):
        if "#### Controls" in line or "### Controls" in line:
            in_controls = True
            continue
        if line.startswith("####") or line.startswith("###") or line.startswith("**Residual"):
            in_controls = False
            continue

        if in_controls and line.strip().startswith("- "):
            description, refs = _parse_control_line(line.strip()[2:])
            controls.append(description)
            control_refs.append(refs)

    return controls, control_refs


def _extract_affected_requirements_from_section(section: str) -> list[str]:
    """Extract affected requirements from a risk section."""
    affected = _extract_risk_section(section, "Affected Requirements")
    if not affected:
        return []

    requirements = []
    for line in affected.split("\n"):
        line = line.strip()
        if line.startswith("- "):
            # Handle markdown link: [hh-infra-001.02](../tasks/...)
            link_match = re.match(r"-\s*\[([^\]]+)\]", line)
            if link_match:
                requirements.append(link_match.group(1))
            else:
                match = re.match(r"-\s+([\w-]+(?:\.\d+)?)", line)
                if match:
                    requirements.append(match.group(1))
    return requirements


def parse_risk_cluster(file_path: Path) -> list[RiskDoc]:
    """Parse a risk cluster document (RC-*) containing multiple risks.

    File naming: {task_prefix}-doc-NNN - RC-*.md
    Example: hh-infra-doc-003 - RC-IAM.md

    Contains multiple risks:
        ## RISK-IAM-001: Title
        | Attribute | Value |
        ...

    Args:
        file_path: Path to risk cluster .md file

    Returns:
        List of RiskDoc objects
    """
    content = file_path.read_text()
    frontmatter, body = parse_frontmatter(content)

    cluster_labels = frontmatter.get("labels", [])
    created_date = frontmatter.get("created_date")

    # Derive cluster name from labels (e.g., RC-IAM)
    cluster_name = next((lbl for lbl in cluster_labels if lbl.startswith("RC-")), None)

    matches = list(RISK_HEADING_PATTERN.finditer(body))

    risks = []
    for i, match in enumerate(matches):
        risk_id = match.group(1).lower()
        risk_title = match.group(2).strip()

        start = match.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(body)
        section = body[start:end].strip()

        table_data = parse_risk_table(section)
        if not (table_data.get("stride_category") or table_data.get("hazard_category")):
            # Say so rather than storing None: an unreadable label and a risk
            # nobody classified are the same value downstream, and the first is
            # a documentation bug worth fixing at source. A safety cluster is
            # not the bug -- a patient-safety hazard has no STRIDE category and
            # classifies by hazard_category instead -- so either satisfies this.
            print(
                f"Warning: {match.group(1)} in {file_path.name} carries no risk "
                f"classification (labels found: "
                f"{', '.join(sorted(table_data)) or 'none'})"
            )
        controls, control_refs = _extract_risk_controls_from_section(section)

        mitigation = _extract_risk_section(section, "Mitigation")
        mitigation_status = None
        residual_risk = None
        if mitigation:
            status_match = re.search(r"\*\*Status:\*\*\s*(\w+)", mitigation)
            if status_match:
                mitigation_status = status_match.group(1)
            residual_match = re.search(r"\*\*Residual Risk:\*\*\s*(\w+)", mitigation)
            if residual_match:
                residual_risk = residual_match.group(1)

        risks.append(
            RiskDoc(
                id=risk_id,
                title=f"{match.group(1)}: {risk_title}",
                type="risk",
                created_date=created_date,
                labels=cluster_labels.copy(),
                risk_id=match.group(1),
                stride_category=table_data.get("stride_category"),
                hazard_category=table_data.get("hazard_category"),
                severity=table_data.get("severity"),
                probability=table_data.get("probability"),
                risk_level=table_data.get("risk_level"),
                severity_post_control=table_data.get("severity_post_control"),
                probability_post_control=table_data.get("probability_post_control"),
                attributes=table_data,
                cluster=cluster_name,
                hazard=_unwrap(_extract_risk_section(section, "Hazard")),
                situation=_unwrap(_extract_risk_section(section, "Situation")),
                harm=_unwrap(_extract_risk_section(section, "Harm")),
                description=_extract_risk_section(section, "Description"),
                affected_requirements=_extract_affected_requirements_from_section(section),
                mitigation_status=mitigation_status,
                residual_risk=residual_risk,
                controls=controls,
                control_refs=control_refs,
                source_file=file_path.name,
            )
        )

    return risks


# =============================================================================
# DECISION PARSING
# =============================================================================


def parse_decision(file_path: Path) -> Decision:
    """Parse a decision/ADR markdown file.

    Args:
        file_path: Path to decision .md file

    Returns:
        Decision object
    """
    content = file_path.read_text()
    frontmatter, body = parse_frontmatter(content)

    return Decision(
        id=frontmatter.get("id", ""),
        title=frontmatter.get("title", ""),
        date=frontmatter.get("date"),
        status=frontmatter.get("status", "accepted"),
        labels=frontmatter.get("labels", []),
        context=extract_section(body, "Context"),
        decision=extract_section(body, "Decision"),
        rationale=extract_section(body, "Rationale"),
        consequences=extract_section(body, "Consequences"),
        source_file=file_path.name,
    )


# =============================================================================
# BACKLOG EXTRACTION
# =============================================================================


# Directories never searched for controlled records.
RISK_SEARCH_SKIP_DIRS = frozenset(
    {"node_modules", ".git", ".venv", "venv", "site", "build", "dist", "mutants"}
)


def find_risk_clusters(
    backlog_dir: Path, risk_roots: Sequence[Path] | None = None
) -> list[Path]:
    """Every risk-cluster document reachable from a backlog directory.

    Discovery used to glob ``backlog/docs/**/*RC-*.md`` and nothing else, but a
    project under design controls commonly keeps its clusters in the DHF instead
    -- ``dhf/risk/`` or ``dhf/documents/risk/``. A repository with 20 documented
    risks then reported 0, and the risk tooling scored an empty set, which
    before the audit scoring was fixed read as full marks.

    Searches ``backlog/docs`` and the repository containing the backlog, unless
    ``risk_roots`` names the roots explicitly. Results are deduplicated by
    resolved path, so overlapping roots cannot double-count a cluster.
    """
    roots = (
        [Path(r) for r in risk_roots]
        if risk_roots is not None
        else [backlog_dir / "docs", backlog_dir.parent]
    )
    found: dict[Path, None] = {}
    for root in roots:
        if not root.exists():
            continue
        for path in sorted(root.rglob("*RC-*.md")):
            if RISK_SEARCH_SKIP_DIRS.intersection(path.parts):
                continue
            try:
                key = path.resolve()
            except OSError:
                key = path
            found.setdefault(key, None)
    return list(found)


def extract_backlog_data(
    backlog_dir: Path, risk_roots: Sequence[Path] | None = None
) -> BacklogData:
    """Extract all data from a Backlog.md directory.

    Args:
        backlog_dir: Path to backlog directory containing config.yml
        risk_roots: Where to look for risk clusters. Defaults to
            ``backlog/docs`` plus the repository containing the backlog.

    Returns:
        BacklogData object with all parsed content
    """
    # Parse config
    config = parse_config(backlog_dir / "config.yml")

    # Parse milestones
    milestones = []
    milestones_dir = backlog_dir / "milestones"
    if milestones_dir.exists():
        for md_file in sorted(milestones_dir.glob("*.md")):
            try:
                milestones.append(parse_milestone(md_file))
            except Exception as e:
                print(f"Warning: Failed to parse milestone {md_file}: {e}")

    # Parse tasks and subtasks
    tasks = []
    subtasks = []

    for tasks_dir in [backlog_dir / "tasks", backlog_dir / "completed"]:
        if not tasks_dir.exists():
            continue

        for md_file in sorted(tasks_dir.glob("*.md")):
            try:
                task = parse_task(md_file)
                if task.is_subtask:
                    subtasks.append(task)
                else:
                    tasks.append(task)
            except Exception as e:
                print(f"Warning: Failed to parse task {md_file}: {e}")

    # Parse risks from RC-* (risk cluster) files, wherever the repository keeps
    # them -- see find_risk_clusters for why backlog/docs alone was not enough.
    risks = []
    seen_risk_ids: dict[str, Path] = {}
    for md_file in find_risk_clusters(backlog_dir, risk_roots):
        try:
            cluster_risks = parse_risk_cluster(md_file)
        except Exception as e:
            print(f"Warning: Failed to parse risk cluster {md_file}: {e}")
            continue
        for risk in cluster_risks:
            first = seen_risk_ids.get(risk.id)
            if first is not None:
                # Two clusters claiming one id would collide on insert. Keep the
                # first and name both files: a duplicate id is a real conflict,
                # not something for a sync to resolve quietly.
                print(
                    f"Warning: duplicate risk id {risk.id.upper()} in "
                    f"{md_file.name}; already defined in {first.name} -- keeping "
                    "the first"
                )
                continue
            seen_risk_ids[risk.id] = md_file
            risks.append(risk)

    # Parse decisions
    decisions = []
    decisions_dir = backlog_dir / "decisions"
    if decisions_dir.exists():
        for md_file in sorted(decisions_dir.glob("*.md")):
            try:
                decisions.append(parse_decision(md_file))
            except Exception as e:
                print(f"Warning: Failed to parse decision {md_file}: {e}")

    return BacklogData(
        config=config,
        milestones=milestones,
        tasks=tasks,
        subtasks=subtasks,
        risks=risks,
        decisions=decisions,
    )
