"""Tests for the Allure results ingester (system-of-record verification)."""

from __future__ import annotations

from pathlib import Path

from rdm.evidence.allure import parse_results
from tests.util import write_allure_result as _result


class TestParseResults:


    def test_skips_malformed_json(self, tmp_path: Path) -> None:
        (tmp_path / "bad-result.json").write_text("{not json")
        _result(tmp_path, "ok", "passed", "UN-001")
        assert len(parse_results(tmp_path)) == 1


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


    def test_ordinary_ansible_tags_are_not_mistaken_for_ids(self, tmp_path: Path) -> None:
        """`dns` is a plain Ansible tag and must not enter the id universe."""
        from rdm.specification.tags import scan_source_tags

        refs = scan_source_tags(self._suite(tmp_path / "tests"))
        assert "dns" not in refs
        assert set(refs) == {"DI-5"}


    def test_singular_test_directory_is_found(self, tmp_path: Path) -> None:
        """Dart, Flutter and Maven put the suite in `test/`, not `tests/`.

        halla_health_app carries 162 Dart test files under `test/`; looking only
        for the plural made the whole suite read as absent.
        """
        from rdm.specification.tags import find_tests_dir

        (tmp_path / "dhf").mkdir()
        (tmp_path / "test").mkdir()
        assert find_tests_dir(tmp_path / "dhf") == tmp_path / "test"


    def test_dart_test_files_are_discovered(self, tmp_path: Path) -> None:
        """`*_test.dart` is a test file."""
        from rdm.specification.tags import iter_test_files

        d = tmp_path / "test" / "providers"
        d.mkdir(parents=True)
        (d / "clear_local_data_test.dart").write_text("void main() {}\n")
        (d / "helpers.dart").write_text("// not a test\n")

        assert {p.name for p in iter_test_files(tmp_path / "test")} == {
            "clear_local_data_test.dart"
        }
