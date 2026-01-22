"""
Story Audit - Requirements Traceability for RDM.

This module provides:
- Backlog.md sync to DuckDB for analytics
- Traceability auditing across code, tests, and docs
- Duplicate ID detection

Usage:
    rdm story audit [repo_path]
    rdm story validate [--strict]
    rdm story sync /path/to/backlog [-o out.duckdb]
    rdm story check-ids [files...]
"""

# Backlog.md schema (v2.0.0)
from rdm.story_audit.backlog_schema import (
    SCHEMA_VERSION,
    BacklogConfig,
    BacklogData,
    Task,
    Milestone,
    RiskDoc,
    Decision,
    AcceptanceCriterion,
)

# Backlog parser
from rdm.story_audit.backlog_parser import (
    extract_backlog_data,
    parse_config,
    parse_task,
    parse_milestone,
    parse_risk,
    parse_decision,
)

__all__ = [
    # Schema
    "SCHEMA_VERSION",
    "BacklogConfig",
    "BacklogData",
    "Task",
    "Milestone",
    "RiskDoc",
    "Decision",
    "AcceptanceCriterion",
    # Parser
    "extract_backlog_data",
    "parse_config",
    "parse_task",
    "parse_milestone",
    "parse_risk",
    "parse_decision",
]
