"""Tests for the Allure results ingester (system-of-record verification)."""

from __future__ import annotations

import json
from pathlib import Path

from rdm.record.allure import (
    FAILED,
    UNTESTED,
    VERIFIED,
    parse_results,
    reconcile,
)
from tests.util import write_allure_result as _result


def _result_with_output(results_dir: Path, name: str, status: str, story: str, output: str) -> None:
    """Write a result tagging a design input (story) and a design output (label)."""
    results_dir.mkdir(parents=True, exist_ok=True)
    labels = [{"name": "story", "value": story}, {"name": "output", "value": output}]
    (results_dir / f"{name}-result.json").write_text(
        json.dumps({"name": name, "status": status, "labels": labels})
    )


class TestParseResults:
    def test_parses_status_and_user_need_labels(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "passed", "UN-001", "UN-002")
        results = parse_results(tmp_path)
        assert len(results) == 1
        assert results[0].status == "passed"
        assert set(results[0].user_need_ids) == {"UN-001", "UN-002"}

    def test_missing_dir_returns_empty(self, tmp_path: Path) -> None:
        assert parse_results(tmp_path / "nope") == []

    def test_skips_malformed_json(self, tmp_path: Path) -> None:
        (tmp_path / "bad-result.json").write_text("{not json")
        _result(tmp_path, "ok", "passed", "UN-001")
        assert len(parse_results(tmp_path)) == 1

    def test_parses_output_label(self, tmp_path: Path) -> None:
        _result_with_output(tmp_path, "t1", "passed", "DI-1", "SDS-core")
        results = parse_results(tmp_path)
        assert results[0].user_need_ids == ["DI-1"]
        assert results[0].outputs == ["SDS-core"]


class TestReconcile:
    def test_passing_test_verifies_user_need(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "passed", "UN-001")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.by_id["UN-001"].status == VERIFIED
        assert report.verified == ["UN-001"]

    def test_failing_test_fails_user_need(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "failed", "UN-001")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.by_id["UN-001"].status == FAILED

    def test_failure_dominates_a_passing_test(self, tmp_path: Path) -> None:
        _result(tmp_path, "ok", "passed", "UN-001")
        _result(tmp_path, "bad", "broken", "UN-001")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.by_id["UN-001"].status == FAILED

    def test_declared_need_with_no_test_is_untested(self, tmp_path: Path) -> None:
        report = reconcile({"UN-001"}, tmp_path)
        assert report.untested == ["UN-001"]

    def test_skipped_only_is_untested(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "skipped", "UN-001")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.by_id["UN-001"].status == UNTESTED

    def test_orphan_tag_reported(self, tmp_path: Path) -> None:
        _result(tmp_path, "t1", "passed", "UN-001")
        _result(tmp_path, "t2", "passed", "UN-999")
        report = reconcile({"UN-001"}, tmp_path)
        assert report.orphan_ids == ["UN-999"]

    def test_outputs_aggregated_onto_verification(self, tmp_path: Path) -> None:
        # Design outputs from the `output` label surface on the design input's
        # verification, deduped across covering tests.
        _result_with_output(tmp_path, "t1", "passed", "DI-1", "SDS-core")
        _result_with_output(tmp_path, "t2", "passed", "DI-1", "SDS-core")
        report = reconcile({"DI-1"}, tmp_path)
        assert report.by_id["DI-1"].outputs == ["SDS-core"]


class TestYamlTestDiscovery:
    """Ansible task files used as acceptance tests must be discoverable.

    An estate can carry its entire design-input acceptance suite as tagged
    Ansible tasks. While TEST_FILE_GLOBS omitted YAML, every one of those tags
    scanned as zero: coverage read 0% and each test file read as an orphan --
    the same wrong answer an unaudited repo gives, only inverted.
    """

    def _suite(self, tests_dir: Path) -> Path:
        tests_dir.mkdir(parents=True, exist_ok=True)
        (tests_dir / "foundation_dns_test.yml").write_text(
            "---\n"
            "- name: read the DNS layer sources\n"
            "  ansible.builtin.set_fact:\n"
            "    zone: \"{{ lookup('file', 'main.tf') }}\"\n"
            "  tags: [DI-5]\n"
            "\n"
            '- name: "DI-5 step 1: the module provisions a public zone"\n'
            "  ansible.builtin.assert:\n"
            "    that:\n"
            "      - zone is regex('aws_route53_zone')\n"
            "  tags: [DI-5, dns]\n"
        )
        return tests_dir

    def test_yaml_test_file_is_discovered(self, tmp_path: Path) -> None:
        """A *_test.yml file counts as a test file."""
        from rdm.record.allure import iter_test_files

        found = {p.name for p in iter_test_files(self._suite(tmp_path / "tests"))}
        assert "foundation_dns_test.yml" in found

    def test_ansible_task_tags_resolve_to_design_inputs(self, tmp_path: Path) -> None:
        """`tags: [DI-5]` is the tag syntax, so DI-5 is the claimed id."""
        from rdm.record.allure import scan_source_tags

        refs = scan_source_tags(self._suite(tmp_path / "tests"))
        assert "DI-5" in refs

    def test_ordinary_ansible_tags_are_not_mistaken_for_ids(self, tmp_path: Path) -> None:
        """`dns` is a plain Ansible tag and must not enter the id universe."""
        from rdm.record.allure import scan_source_tags

        refs = scan_source_tags(self._suite(tmp_path / "tests"))
        assert "dns" not in refs
        assert set(refs) == {"DI-5"}

    def test_a_file_claiming_one_id_many_times_counts_once(self, tmp_path: Path) -> None:
        """An Ansible suite tags every task in a context with the same design input.

        Callers report these as a file count ("tagged (n file(s))"), so counting
        occurrences made one file with thirteen DI-1 tags read as thirteen files.
        """
        from rdm.record.allure import scan_source_tags

        refs = scan_source_tags(self._suite(tmp_path / "tests"))
        assert refs["DI-5"] == [str(tmp_path / "tests" / "foundation_dns_test.yml")]

    def test_plural_tests_suffix_is_discovered(self, tmp_path: Path) -> None:
        """`ft-004.01-flux-bootstrap-tests.yml` is a test file too.

        halla-health-infra's live-tier suite -- 26 Ansible task files verified
        against the running estate -- is named `*-tests.yml`, so the singular
        globs alone found none of them and the live tier could not be
        discovered at all.
        """
        from rdm.record.allure import iter_test_files

        tests_dir = tmp_path / "tests" / "roles" / "gitops" / "tasks"
        tests_dir.mkdir(parents=True)
        for name in (
            "ft-004.01-flux-bootstrap-tests.yml",
            "ft-004.02-kubeconfig-ssm-tests.yml",
            "some_tests.yaml",
        ):
            (tests_dir / name).write_text("---\n- name: a check\n  tags: [DI-16]\n")

        found = {p.name for p in iter_test_files(tmp_path / "tests")}
        assert found == {
            "ft-004.01-flux-bootstrap-tests.yml",
            "ft-004.02-kubeconfig-ssm-tests.yml",
            "some_tests.yaml",
        }

    def test_singular_test_directory_is_found(self, tmp_path: Path) -> None:
        """Dart, Flutter and Maven put the suite in `test/`, not `tests/`.

        halla_health_app carries 162 Dart test files under `test/`; looking only
        for the plural made the whole suite read as absent.
        """
        from rdm.record.allure import find_tests_dir

        (tmp_path / "dhf").mkdir()
        (tmp_path / "test").mkdir()
        assert find_tests_dir(tmp_path / "dhf") == tmp_path / "test"

    def test_plural_test_directory_still_wins(self, tmp_path: Path) -> None:
        """Where both exist, `tests/` is preferred — it is rdm's own convention."""
        from rdm.record.allure import find_tests_dir

        (tmp_path / "dhf").mkdir()
        (tmp_path / "test").mkdir()
        (tmp_path / "tests").mkdir()
        assert find_tests_dir(tmp_path / "dhf") == tmp_path / "tests"

    def test_dart_test_files_are_discovered(self, tmp_path: Path) -> None:
        """`*_test.dart` is a test file."""
        from rdm.record.allure import iter_test_files

        d = tmp_path / "test" / "providers"
        d.mkdir(parents=True)
        (d / "clear_local_data_test.dart").write_text("void main() {}\n")
        (d / "helpers.dart").write_text("// not a test\n")

        assert {p.name for p in iter_test_files(tmp_path / "test")} == {
            "clear_local_data_test.dart"
        }
