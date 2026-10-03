import os

import pytest

from rdm.evidence.test_formatters.xml_util import (
    auto_translator, xml_load,
)


def _full_path_of_test_file(name):
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), "test_data", name)


XML_PATHS = [_full_path_of_test_file(leaf_name)
             for leaf_name in ["integration.xml", "test_detail.xml", "xunit_result.xml"]]


@pytest.mark.parametrize('xml_path', XML_PATHS)
def test_auto_flattener(xml_path):
    test_results = xml_load(xml_path)
    flattened_results = auto_translator(test_results)
    assert flattened_results is not None
    assert len(flattened_results) in {4, 15, 18}
