import pytest

from rdm.md_extensions.audit_notes import _find_trailing_space, _find_end_marker


class TestAuditPreprocess:
    @pytest.mark.parametrize('arg, expected_lead, expected_tail', [
        ('', '', ''),
        ('abc', 'abc', ''),
        (' xyz', ' xyz', ''),
        ('xyz ', 'xyz', ' '),
        ('xyz  ', 'xyz ', ' '),
        ('apple banana  ', 'apple banana ', ' '),
    ])
    def test_find_trailing_space(self, arg, expected_lead, expected_tail):
        actual_lead, actual_tail = _find_trailing_space(arg)
        assert actual_lead == expected_lead
        assert actual_tail == expected_tail

    @pytest.mark.parametrize('arg, expected_lead, expected_tail', [
        ('', '', None),
        ('abc]', 'abc]', None),
        ('abc]]', 'abc', ''),
        ('abc]]xyz', 'abc', 'xyz'),
    ])
    def test_find_end_marker(self, arg, expected_lead, expected_tail):
        actual_lead, actual_tail = _find_end_marker(arg)
        assert actual_lead == expected_lead
        assert actual_tail == expected_tail


