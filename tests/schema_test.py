"""ID pattern guarantees in story_audit.schema (the single source of truth)."""
def test_risk_id_accepts_a_product_namespace_segment():
    """Two repos read in one traceability run need distinguishable risk ids.

    `documentation` passes both mirrored DHFs to a single `rdm gap` run, where
    RISK-DATA-001 meant "Database Credentials Exposure" in one and "Vertex
    context cache retains health conversation" in the other.
    """
    import re

    from rdm.story_audit.schema import RISK_ID_PATTERN

    for valid in ("RISK-DATA-001", "RISK-INFRA-DATA-001", "RISK-WALLET-DATA-001"):
        assert re.match(RISK_ID_PATTERN, valid), valid
    for invalid in ("RISK-001", "RISK-data-001", "RISK-DATA-"):
        assert not re.match(RISK_ID_PATTERN, invalid), invalid


def test_id_pattern_matches_a_namespaced_risk_in_prose():
    from rdm.story_audit.schema import ID_PATTERN

    found = ID_PATTERN.findall("mitigated per RISK-WALLET-DATA-003 and RISK-INFRA-IAM-001")
    assert [m[1] for m in found] == ["003", "001"]


def test_tag_pattern_accepts_multi_segment_ids():
    """Allure tags carry the same ids; UN-WALLET-FLUTTER-APP-5 has two segments."""
    from rdm.record.allure import TAG_ID_PATTERN

    for valid in ("DI-5", "DI-INFRA-7", "UN-WALLET-FLUTTER-APP-5", "RISK-WALLET-DATA-001"):
        assert TAG_ID_PATTERN.fullmatch(valid), valid
    assert not TAG_ID_PATTERN.fullmatch("lowercase-1")
