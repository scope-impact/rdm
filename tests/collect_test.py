import pytest

from rdm.publishing.collect import collect_from_lines


def test_mismatched_start_stop_token_indents():
    with pytest.raises(ValueError):
        collect_from_lines(['RDOC test', 'Test', ' ENDRDOC'])


def test_reach_end_without_end_snippet():
    with pytest.raises(ValueError):
        collect_from_lines(['RDOC test', 'Test'])


def test_missing_key():
    with pytest.raises(ValueError):
        collect_from_lines(['RDOC', 'Test', 'ENDRDOC'])


def test_basic_snippet_w_offset():
    assert collect_from_lines(['# RDOC test', '# Test', '# ENDRDOC']) == {'test': 'Test'}


def test_multiple_rdocs_in_file():
    with pytest.raises(ValueError):
        collect_from_lines(2 * ['# RDOC test', '# Test', '# ENDRDOC'])
