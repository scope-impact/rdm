"""
Sync requirements YAML files to DuckDB for analytics.

Uses Pydantic schema from schema.py as single source of truth.

Usage:
    rdm story sync [--repo name] [--output path]
    rdm story sync --validate-only

Requires: pip install rdm[analytics]
"""

from __future__ import annotations

import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    raise ImportError("pyyaml is required. Install with: pip install pyyaml")

from rdm.story_audit.schema import (
    SCHEMA_VERSION,
    Feature,
    RequirementsIndex,
    RiskRegister,
)


# =============================================================================
# HELPERS
# =============================================================================


def _count_dod_items(dod: list[str] | dict[str, list[str]]) -> int:
    """Count definition of done items (handles both list and dict formats)."""
    if isinstance(dod, dict):
        return sum(len(items) for items in dod.values())
    return len(dod)


# =============================================================================
# YAML PARSING WITH SCHEMA VALIDATION
# =============================================================================


def parse_index_yaml(yaml_dir: Path) -> RequirementsIndex:
    """Parse _index.yaml with schema validation."""
    index_path = yaml_dir / "_index.yaml"
    with open(index_path) as f:
        data = yaml.safe_load(f)
    return RequirementsIndex(**data)


def parse_feature_yaml(feature_path: Path) -> Feature:
    """Parse a feature YAML file with schema validation."""
    with open(feature_path) as f:
        data = yaml.safe_load(f)
    return Feature(**data)


def parse_risk_yaml(risk_path: Path) -> RiskRegister:
    """Parse a risk YAML file with schema validation."""
    with open(risk_path) as f:
        data = yaml.safe_load(f)
    return RiskRegister(**data)


def build_feature_phase_map(index: RequirementsIndex) -> dict[str, str]:
    """Build mapping of feature_id -> phase_id from index."""
    feature_phase_map = {}
    for phase_id, phase in index.phases.items():
        for feature_id in phase.features:
            feature_phase_map[feature_id] = phase_id
    return feature_phase_map


def get_epic_title(index: RequirementsIndex, epic_id: str | None) -> str | None:
    """Get epic title by ID."""
    if not epic_id:
        return None
    for epic in index.epics:
        if epic.id == epic_id:
            return epic.title
    return None


# =============================================================================
# DATA EXTRACTION
# =============================================================================


def extract_data(
    yaml_dir: Path,
    repo_name: str | None = None,
) -> dict[str, list[dict]]:
    """Extract all data from YAML files using schema validation."""
    index = parse_index_yaml(yaml_dir)
    feature_phase_map = build_feature_phase_map(index)

    # Initialize data containers
    data: dict[str, list[dict]] = {
        "phases": [],
        "epics": [],
        "labels": [],
        "features": [],
        "user_stories": [],
        "acceptance_criteria": [],
        "definition_of_done": [],
        "extra_fields_log": [],
        "risks": [],
        "risk_controls": [],
    }

    labels_set: set[str] = set()
    criteria_id = 0
    dod_id = 0

    # Extract phases
    for i, (phase_id, phase) in enumerate(index.phases.items()):
        data["phases"].append({
            "phase_id": phase_id,
            "phase_name": phase_id.replace("_", " ").title(),
            "description": phase.description,
            "sort_order": i,
            "feature_count": len(phase.features),
        })

    # Extract epics
    for epic in index.epics:
        data["epics"].append({
            "epic_id": epic.id,
            "title": epic.title,
            "status": epic.status,
            "phases": epic.phases,
            "feature_ids": epic.features,
        })

    # Extract features
    for feature_path in sorted(yaml_dir.glob("features/FT-*.yaml")):
        try:
            feature = parse_feature_yaml(feature_path)
        except Exception as e:
            print(f"Warning: Failed to parse {feature_path}: {e}")
            continue

        # Collect labels
        labels_set.update(feature.labels)

        # Get phase from file or index
        phase_id = feature.phase or feature_phase_map.get(feature.id, "unknown")

        # Get epic title for denormalization
        epic_title = get_epic_title(index, feature.epic_id)

        # Compute quality summary
        quality_summary = feature.compute_quality_summary()

        # Check for extra fields
        extra = feature.get_extra_fields()
        if extra:
            data["extra_fields_log"].append({
                "source": feature_path.name,
                "type": "feature",
                "id": feature.id,
                "extra_fields": list(extra.keys()),
            })

        feature_record = {
            "feature_id": feature.id,
            "repo_name": repo_name,
            "global_id": f"{repo_name}:{feature.id}" if repo_name else feature.id,
            "title": feature.title,
            "description": feature.description,
            "business_value": feature.business_value,
            "epic_id": feature.epic_id,
            "epic_title": epic_title,
            "phase_id": phase_id,
            "priority": feature.priority,
            "status": feature.status,
            "labels": feature.labels,
            "user_story_count": len(feature.user_stories),
            "dod_item_count": _count_dod_items(feature.definition_of_done),
            "story_quality_core": quality_summary.core,
            "story_quality_acceptable": quality_summary.acceptable,
            "story_quality_weak": quality_summary.weak,
            "has_technical_spec": feature.technical_spec is not None,
            "has_existing_code": feature.existing_code is not None,
            "has_business_value": bool(feature.business_value),
            "source_file": feature_path.name,
            "note": feature.note,
        }
        data["features"].append(feature_record)

        # Extract user stories
        for story in feature.user_stories:
            story_extra = story.get_extra_fields()
            if story_extra:
                data["extra_fields_log"].append({
                    "source": feature_path.name,
                    "type": "user_story",
                    "id": story.id,
                    "extra_fields": list(story_extra.keys()),
                })

            story_record = {
                "story_id": story.id,
                "repo_name": repo_name,
                "global_id": f"{repo_name}:{story.id}" if repo_name else story.id,
                "feature_id": feature.id,
                "feature_title": feature.title,
                "epic_id": feature.epic_id,
                "phase_id": phase_id,
                "role": story.as_a,
                "goal": story.i_want,
                "benefit": story.so_that,
                "full_story": story.full_story,
                "priority": story.priority,
                "story_quality": story.story_quality,
                "status": story.status,
                "acceptance_criteria_count": len(story.acceptance_criteria),
                "note": story.note,
            }
            data["user_stories"].append(story_record)

            # Extract acceptance criteria
            for i, ac_text in enumerate(story.acceptance_criteria):
                criteria_id += 1
                data["acceptance_criteria"].append({
                    "criteria_id": criteria_id,
                    "story_id": story.id,
                    "feature_id": feature.id,
                    "criteria_text": ac_text,
                    "sort_order": i,
                    "story_role": story.as_a,
                    "feature_title": feature.title,
                })

        # Extract definition of done
        dod_items = feature.definition_of_done
        if isinstance(dod_items, dict):
            flat_items = []
            for category, items in dod_items.items():
                for item in items:
                    flat_items.append(f"[{category}] {item}")
            dod_items = flat_items

        for i, dod_text in enumerate(dod_items):
            dod_id += 1
            data["definition_of_done"].append({
                "dod_id": dod_id,
                "feature_id": feature.id,
                "item_text": dod_text,
                "sort_order": i,
            })

    # Build labels dimension
    data["labels"] = [
        {"label_id": i, "label_name": label}
        for i, label in enumerate(sorted(labels_set))
    ]

    # Extract risks
    risks_dir = yaml_dir / "risks"
    if risks_dir.exists():
        for risk_path in sorted(risks_dir.glob("*.yaml")):
            try:
                register = parse_risk_yaml(risk_path)
            except Exception as e:
                print(f"Warning: Failed to parse {risk_path}: {e}")
                continue

            for risk in register.risks:
                risk_record = {
                    "risk_id": risk.id,
                    "repo_name": repo_name,
                    "global_id": f"{repo_name}:{risk.id}" if repo_name else risk.id,
                    "title": risk.title,
                    "description": risk.description,
                    "category": risk.category,
                    "severity": risk.severity,
                    "probability": risk.probability,
                    "risk_level": risk.risk_level,
                    "residual_risk": risk.residual_risk,
                    "status": risk.status,
                    "control_count": len(risk.controls),
                    "source_file": risk_path.name,
                }
                data["risks"].append(risk_record)

                for control in risk.controls:
                    control_record = {
                        "control_id": control.id,
                        "repo_name": repo_name,
                        "global_id": f"{repo_name}:{control.id}" if repo_name else control.id,
                        "risk_id": risk.id,
                        "description": control.description,
                        "implemented_by": control.implemented_by,
                        "verification": control.verification,
                        "status": control.status,
                    }
                    data["risk_controls"].append(control_record)

    return data


# =============================================================================
# CLI ENTRY POINT
# =============================================================================


def story_sync_command(
    requirements_dir: Path | None = None,
    output_path: Path | None = None,
    repo_name: str | None = None,
    validate_only: bool = False,
) -> int:
    """Run story sync command."""
    yaml_dir = (requirements_dir or Path("requirements")).resolve()

    if not yaml_dir.exists():
        print(f"Error: Requirements directory not found: {yaml_dir}")
        return 1

    print(f"YAML source:  {yaml_dir}")
    print(f"Schema:       v{SCHEMA_VERSION}")
    if repo_name:
        print(f"Repo name:    {repo_name}")

    # Extract data with validation
    print("\nParsing YAML files with schema validation...")
    try:
        data = extract_data(yaml_dir, repo_name=repo_name)
    except Exception as e:
        print(f"\nError: Schema validation failed: {e}")
        return 1

    print(f"  Parsed {len(data['features'])} features, {len(data['user_stories'])} user stories")
    if data["risks"]:
        print(f"  Parsed {len(data['risks'])} risks, {len(data['risk_controls'])} controls")

    if validate_only:
        print("\nValidation complete. No database created (--validate-only).")
        return 0

    # Create database (requires duckdb)
    try:
        import duckdb
    except ImportError:
        print("\nError: duckdb is required for sync. Install with: pip install rdm[analytics]")
        return 1

    db_path = (output_path or Path("requirements.duckdb")).resolve()
    print(f"Database:     {db_path}")

    conn = duckdb.connect(str(db_path))

    # Create tables and insert data
    conn.execute("DROP TABLE IF EXISTS features")
    conn.execute("""
        CREATE TABLE features AS SELECT * FROM (
            VALUES (NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                    NULL, 0, 0, 0, 0, 0, false, false, false, NULL, NULL)
        ) AS t(
            feature_id, repo_name, global_id, title, description, business_value,
            epic_id, epic_title, phase_id, priority, status, labels,
            user_story_count, dod_item_count, story_quality_core, story_quality_acceptable,
            story_quality_weak, has_technical_spec, has_existing_code, has_business_value,
            source_file, note
        ) WHERE false
    """)

    for f in data["features"]:
        conn.execute(
            "INSERT INTO features VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            [
                f["feature_id"], f["repo_name"], f["global_id"], f["title"],
                f["description"], f["business_value"], f["epic_id"], f["epic_title"],
                f["phase_id"], f["priority"], f["status"], f["labels"],
                f["user_story_count"], f["dod_item_count"], f["story_quality_core"],
                f["story_quality_acceptable"], f["story_quality_weak"],
                f["has_technical_spec"], f["has_existing_code"], f["has_business_value"],
                f["source_file"], f.get("note"),
            ],
        )

    conn.close()

    print(f"\nDone! Database saved to: {db_path}")
    return 0


def main() -> None:
    """CLI entry point."""
    import argparse

    parser = argparse.ArgumentParser(description="Sync requirements to DuckDB")
    parser.add_argument("-r", "--requirements", type=Path)
    parser.add_argument("-o", "--output", type=Path)
    parser.add_argument("--repo", type=str)
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()

    sys.exit(story_sync_command(
        requirements_dir=args.requirements,
        output_path=args.output,
        repo_name=args.repo,
        validate_only=args.validate_only,
    ))


if __name__ == "__main__":
    main()
