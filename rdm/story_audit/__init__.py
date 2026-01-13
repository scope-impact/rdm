"""
Story Audit - Requirements Traceability for RDM.

This module provides:
- Schema validation for requirements YAML files
- Traceability auditing across code, tests, and docs
- DuckDB sync for analytics
- Duplicate ID detection
- Schema documentation
- Requirements structure initialization

Usage:
    rdm story audit [repo_path]
    rdm story validate [--strict] [--suggest-fixes]
    rdm story sync [--repo name]
    rdm story check-ids [files...] [--explain]
    rdm story schema [--model Feature|Epic|UserStory|Risk|All]
    rdm story init [--template infrastructure|application]
    rdm story docs
"""

from rdm.story_audit.schema import (
    SCHEMA_VERSION,
    Feature,
    UserStory,
    Epic,
    Phase,
    RequirementsIndex,
    Risk,
    RiskControl,
    RiskRegister,
)

__all__ = [
    "SCHEMA_VERSION",
    "Feature",
    "UserStory",
    "Epic",
    "Phase",
    "RequirementsIndex",
    "Risk",
    "RiskControl",
    "RiskRegister",
]
