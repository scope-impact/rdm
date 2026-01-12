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

    # Create all tables and insert data
    _create_and_populate_tables(conn, data)

    conn.close()

    print(f"\nDone! Database saved to: {db_path}")
    return 0


def _create_and_populate_tables(conn: object, data: dict[str, list[dict]]) -> None:
    """Create all tables and populate with extracted data."""

    # === PHASES TABLE ===
    conn.execute("DROP TABLE IF EXISTS phases")
    conn.execute("""
        CREATE TABLE phases (
            phase_id VARCHAR,
            phase_name VARCHAR,
            description VARCHAR,
            sort_order INTEGER,
            feature_count INTEGER
        )
    """)
    for p in data["phases"]:
        conn.execute(
            "INSERT INTO phases VALUES (?, ?, ?, ?, ?)",
            [p["phase_id"], p["phase_name"], p["description"], p["sort_order"], p["feature_count"]],
        )

    # === EPICS TABLE ===
    conn.execute("DROP TABLE IF EXISTS epics")
    conn.execute("""
        CREATE TABLE epics (
            epic_id VARCHAR,
            title VARCHAR,
            status VARCHAR,
            phases VARCHAR[],
            feature_ids VARCHAR[]
        )
    """)
    for e in data["epics"]:
        conn.execute(
            "INSERT INTO epics VALUES (?, ?, ?, ?, ?)",
            [e["epic_id"], e["title"], e["status"], e.get("phases", []), e.get("feature_ids", [])],
        )

    # === LABELS TABLE ===
    conn.execute("DROP TABLE IF EXISTS labels")
    conn.execute("""
        CREATE TABLE labels (
            label_id INTEGER,
            label_name VARCHAR
        )
    """)
    for lbl in data["labels"]:
        conn.execute(
            "INSERT INTO labels VALUES (?, ?)",
            [lbl["label_id"], lbl["label_name"]],
        )

    # === FEATURES TABLE ===
    conn.execute("DROP TABLE IF EXISTS features")
    conn.execute("""
        CREATE TABLE features (
            feature_id VARCHAR,
            repo_name VARCHAR,
            global_id VARCHAR,
            title VARCHAR,
            description VARCHAR,
            business_value VARCHAR,
            epic_id VARCHAR,
            epic_title VARCHAR,
            phase_id VARCHAR,
            priority VARCHAR,
            status VARCHAR,
            labels VARCHAR[],
            user_story_count INTEGER,
            dod_item_count INTEGER,
            story_quality_core INTEGER,
            story_quality_acceptable INTEGER,
            story_quality_weak INTEGER,
            has_technical_spec BOOLEAN,
            has_existing_code BOOLEAN,
            has_business_value BOOLEAN,
            source_file VARCHAR,
            note VARCHAR
        )
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

    # === USER STORIES TABLE ===
    conn.execute("DROP TABLE IF EXISTS user_stories")
    conn.execute("""
        CREATE TABLE user_stories (
            story_id VARCHAR,
            repo_name VARCHAR,
            global_id VARCHAR,
            feature_id VARCHAR,
            feature_title VARCHAR,
            epic_id VARCHAR,
            phase_id VARCHAR,
            role VARCHAR,
            goal VARCHAR,
            benefit VARCHAR,
            full_story VARCHAR,
            priority VARCHAR,
            story_quality VARCHAR,
            status VARCHAR,
            acceptance_criteria_count INTEGER,
            note VARCHAR
        )
    """)
    for s in data["user_stories"]:
        conn.execute(
            "INSERT INTO user_stories VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            [
                s["story_id"], s["repo_name"], s["global_id"], s["feature_id"],
                s["feature_title"], s["epic_id"], s["phase_id"], s["role"],
                s["goal"], s["benefit"], s["full_story"], s["priority"],
                s["story_quality"], s["status"], s["acceptance_criteria_count"],
                s.get("note"),
            ],
        )

    # === ACCEPTANCE CRITERIA TABLE ===
    conn.execute("DROP TABLE IF EXISTS acceptance_criteria")
    conn.execute("""
        CREATE TABLE acceptance_criteria (
            criteria_id INTEGER,
            story_id VARCHAR,
            feature_id VARCHAR,
            criteria_text VARCHAR,
            sort_order INTEGER,
            story_role VARCHAR,
            feature_title VARCHAR
        )
    """)
    for ac in data["acceptance_criteria"]:
        conn.execute(
            "INSERT INTO acceptance_criteria VALUES (?, ?, ?, ?, ?, ?, ?)",
            [
                ac["criteria_id"], ac["story_id"], ac["feature_id"],
                ac["criteria_text"], ac["sort_order"], ac["story_role"],
                ac["feature_title"],
            ],
        )

    # === DEFINITION OF DONE TABLE ===
    conn.execute("DROP TABLE IF EXISTS definition_of_done")
    conn.execute("""
        CREATE TABLE definition_of_done (
            dod_id INTEGER,
            feature_id VARCHAR,
            item_text VARCHAR,
            sort_order INTEGER
        )
    """)
    for dod in data["definition_of_done"]:
        conn.execute(
            "INSERT INTO definition_of_done VALUES (?, ?, ?, ?)",
            [dod["dod_id"], dod["feature_id"], dod["item_text"], dod["sort_order"]],
        )

    # === RISKS TABLE ===
    conn.execute("DROP TABLE IF EXISTS risks")
    conn.execute("""
        CREATE TABLE risks (
            risk_id VARCHAR,
            repo_name VARCHAR,
            global_id VARCHAR,
            title VARCHAR,
            description VARCHAR,
            category VARCHAR,
            severity VARCHAR,
            probability VARCHAR,
            risk_level VARCHAR,
            residual_risk VARCHAR,
            status VARCHAR,
            control_count INTEGER,
            source_file VARCHAR
        )
    """)
    for r in data["risks"]:
        conn.execute(
            "INSERT INTO risks VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            [
                r["risk_id"], r["repo_name"], r["global_id"], r["title"],
                r["description"], r["category"], r["severity"], r["probability"],
                r["risk_level"], r["residual_risk"], r["status"],
                r["control_count"], r["source_file"],
            ],
        )

    # === RISK CONTROLS TABLE ===
    conn.execute("DROP TABLE IF EXISTS risk_controls")
    conn.execute("""
        CREATE TABLE risk_controls (
            control_id VARCHAR,
            repo_name VARCHAR,
            global_id VARCHAR,
            risk_id VARCHAR,
            description VARCHAR,
            implemented_by VARCHAR[],
            verification VARCHAR,
            status VARCHAR
        )
    """)
    for rc in data["risk_controls"]:
        conn.execute(
            "INSERT INTO risk_controls VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            [
                rc["control_id"], rc["repo_name"], rc["global_id"], rc["risk_id"],
                rc["description"], rc.get("implemented_by", []), rc.get("verification"),
                rc.get("status"),
            ],
        )


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
