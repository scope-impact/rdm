import pytest

from rdm.compliance.gaps import SectionalAnalysis, coverage_report


@pytest.fixture
def example_short_checklist_source():
    return [
        ('   include other_file\napple tempted Eve\nbanana tempted Curious George\n# commentary', 'yellow brick road')
    ]


@pytest.fixture
def example_short_checklist():
    return [
        {
            'reference': 'apple',
            'description': 'tempted Eve',
        },
        {
            'reference': 'banana',
            'description': 'tempted Curious George',
        },
    ]


@pytest.fixture
def example_long_checklist():
    return [
        {
            'reference': 'apple',
            'description': 'tempted Eve',
        },
        {
            'reference': 'banana',
            'description': 'tempted Curious George',
        },
        {
            'reference': 'cherry',
        },
        {
            'reference': 'dates',
        },
    ]


@pytest.fixture
def example_raw_checklist():
    return [
        {
            'include': 'other_file',
            'path': 'yellow brick road'
        },
        {
            'reference': 'apple',
            'description': 'tempted Eve',
        },
        {
            'reference': 'banana',
            'description': 'tempted Curious George',
        },
    ]


document_a = "We like [[apple]] pie."
document_b = "We hate [[banana]] splits."
document_ac = "We like [[apple]] pie and [[cherry]] pie."
document_ad = "Never put [[dates]] in [[apple]] pie."
























def test_sorting():
    original = [
        '62304:5.1.8.d Documentation Planning: procedures',
        '62304:5.2.1 Define and document software requirements from system requirements',
        '62304:5.1.10 Supporting items to be controlled',
        '62304:5.1.9.a Software Configuration Management Planning: controlled items',
        '62304:5.1.11 Software configuration item control before verification',
        '62304:5.1.9.b Software Configuration Management Planning: activities and tasks',
    ]
    properly_sorted = [
        '62304:5.1.8.d Documentation Planning: procedures',
        '62304:5.1.9.a Software Configuration Management Planning: controlled items',
        '62304:5.1.9.b Software Configuration Management Planning: activities and tasks',
        '62304:5.1.10 Supporting items to be controlled',
        '62304:5.1.11 Software configuration item control before verification',
        '62304:5.2.1 Define and document software requirements from system requirements',
    ]
    actual = sorted(original, key=SectionalAnalysis)
    assert properly_sorted == actual


def test_sorting_reversed():
    original = ['a:a.1', 'a:a.2', 'a:b', 'a:b.1', 'a:c.1', 'b:1', 'b:2', 'b:2.a']
    original.reverse()
    properly_sorted = ['a:a.1', 'a:a.2', 'a:b', 'a:b.1', 'a:c.1', 'b:1', 'b:2', 'b:2.a']
    actual = sorted(original, key=SectionalAnalysis)
    assert properly_sorted == actual










def test_coverage_report_no_checklists():
    result = coverage_report([], [])
    assert result == 1


def test_gap_coverage_cli_resolves_builtin_checklist_name(tmp_path, capsys):
    """Regression: `rdm gap --coverage <builtin-name> <sources>` must classify a
    built-in checklist NAME (no .txt suffix) as a checklist, not a source."""
    from rdm.main import cli

    source = tmp_path / "doc.md"
    source.write_text("Mentions [[62304:4.1]] only.")

    result = cli(["gap", "--coverage", "62304_2015_class_b", str(source)])
    captured = capsys.readouterr()

    assert "no checklists specified" not in captured.out
    assert "62304_2015_CLASS_B" in captured.out
    assert result == 3  # clauses are missing: coverage exits as the audit does (Design Review 55)
