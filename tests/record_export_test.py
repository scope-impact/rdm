"""`rdm story export` — the DHF as data, so a view is not a bespoke parser."""

from pathlib import Path
from textwrap import dedent

from rdm.record.export import export_dhf


def _write(root: Path, rel: str, text: str) -> Path:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(dedent(text).lstrip(), encoding="utf-8")
    return path


def _dhf(tmp_path: Path) -> Path:
    dhf = tmp_path / "dhf"
    _write(dhf, "documents/vv_plan.md", """
        ---
        id: VV-001
        user_needs:
          - id: UN-PROD-1
            summary: A user can do the thing.
            audience: consumer
        ---

        # V&V plan
        """)
    _write(dhf, "documents/design/thing.md", """
        ---
        kind: design
        context: thing
        title: The Thing
        status: Draft
        satisfies: [UN-PROD-1]
        design_inputs:
          - {id: DI-PROD-1, text: "The thing responds.", traces_to: [UN-PROD-1]}
        ---

        # Design
        """)
    _write(dhf, "risk/RC-DATA.md", """
        ---
        id: rc-data
        type: risk-cluster
        labels: [risk-cluster, RC-DATA]
        ---

        ## RISK-PROD-DATA-001: Something leaks

        | Attribute | Value |
        |-----------|-------|
        | **STRIDE** | Information Disclosure |
        | **Severity** | Serious |
        | **Probability** | Possible |
        | **Risk Level** | High |

        ### Hazard

        A store is readable.

        ### Situation

        Someone reads it.

        ### Harm

        Personal data is exposed.

        ### Mitigation

        **Status:** Partial

        #### Controls

        - Access is scoped

        **Residual Risk:** Medium
        """)
    return dhf


def test_export_carries_needs_inputs_documents_and_risks(tmp_path):
    data = export_dhf(_dhf(tmp_path))
    assert data["totals"] == {
        "user_needs": 1, "design_inputs": 1, "design_documents": 1,
        "risks": 1, "tagged_ids": 0,
    }
    assert data["user_needs"][0]["summary"] == "A user can do the thing."
    assert data["design_inputs"][0]["id"] == "DI-PROD-1"
    assert data["design_documents"][0]["satisfies"] == ["UN-PROD-1"]


def test_export_reads_a_namespaced_risk_id(tmp_path):
    """A namespaced register must not read as empty.

    The cluster parser matched `RISK-<one segment>-NNN` only, so a register that
    namespaces its ids per product parsed to zero risks while the files were
    found — the register looked documented and scored as though it held nothing.
    """
    risks = export_dhf(_dhf(tmp_path))["risks"]
    assert len(risks) == 1
    risk = risks[0]
    assert risk["title"].startswith("RISK-PROD-DATA-001")
    assert risk["severity"] == "Serious"
    assert risk["residual_risk"] == "Medium"
    assert risk["stride_category"] == "Information Disclosure"


def test_export_derives_the_cluster_from_the_id(tmp_path):
    """Cluster comes from the id, so a project's own cluster names just work."""
    from rdm.record.export import _cluster_of

    assert _cluster_of("RISK-DATA-001") == "DATA"
    assert _cluster_of("RISK-PROD-SAFETY-002") == "SAFETY"
    assert _cluster_of("RISK") is None


def test_export_names_test_files_per_tagged_id(tmp_path):
    dhf = _dhf(tmp_path)
    tests = tmp_path / "tests"
    _write(tests, "acceptance/thing_test.yml", """
        - name: the thing responds
          tags: [DI-PROD-1]
        """)
    data = export_dhf(dhf, tests_dir=tests)
    assert data["tests"]["tags"]["DI-PROD-1"] == ["acceptance/thing_test.yml"]
    assert data["totals"]["tagged_ids"] == 1


def test_export_holds_no_scoring_policy(tmp_path):
    """An acceptability matrix is the project's, not the tool's.

    It belongs in the `config.yml` that `rdm render` already loads, so the
    export stays valid whatever a project's scoring policy is.
    """
    data = export_dhf(_dhf(tmp_path))
    assert "matrix" not in data
    assert not any("matrix" in str(k).lower() for k in data)
