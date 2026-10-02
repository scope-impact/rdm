import pytest

from tests.util import render_from_string


@pytest.mark.parametrize('input_string, expected_result', [
    (
        "apple\nbanana\ncherry\n{% for vocab in first_pass_output.words | sort %}[{{ vocab }}]{% "
        "endfor %}",
        "apple\nbanana\ncherry\n[apple][banana][cherry]\n"
    ),
    (
        "apple banana cherry\n{% if first_pass_output.has('banana') %}banana: yellow fruit\n{% "
        "endif %}",
        "apple banana cherry\nbanana: yellow fruit\n"
    ),
    (
        "apple Banana cherry\n{% if first_pass_output.has_ignore_case('banana') %}banana: yellow "
        "fruit\n{% endif %}",
        "apple Banana cherry\nbanana: yellow fruit\n"
    ),
])
def test_vocabulary(input_string, expected_result):
    config = {
        'md_extensions': ['rdm.md_extensions.VocabularyExtension'],
    }

    actual_result = render_from_string(input_string, config=config)
    assert actual_result == expected_result


def test_filtering():
    config = {
        'md_extensions': ['rdm.md_extensions.VocabularyExtension'],
    }
    context = {
        'stuff': ['apple', 'cherry', 'egg']
    }
    input_string = 'apple, banana, cherry, plum\n{% for thing in stuff | present_in(first_pass_output.source) %} ' \
                   '--->{{thing}}{% endfor %}'
    expected_result = 'apple, banana, cherry, plum\n --->apple --->cherry\n'
    actual_result = render_from_string(input_string, context, config=config)
    assert actual_result == expected_result


