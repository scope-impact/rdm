from rdm.kernel.util import write_yaml
from rdm.evidence.test_formatters.xml_util import (
    flattened_gtest_results,
    xml_load,
    flattened_qttest_results,
    auto_translator,
    has_test_results,
)

XML_TRANSLATORS = {
    'auto': auto_translator,
    'gtest': flattened_gtest_results,
    'qttest': flattened_qttest_results,
    'xunit': flattened_gtest_results,  # this flattener also handles xunit.
}
XML_FORMATS = list(XML_TRANSLATORS)


def translate_test_results(format, input, output):
    xml_translator = XML_TRANSLATORS.get(format)
    if xml_translator is None:
        raise ValueError("Unknown translation format: " + format)
    results = xml_load(input)
    if not has_test_results(results):
        raise ValueError(f"{input} holds no test results (not gtest, xunit or qttest XML)")
    with open(output, 'w', encoding='utf-8') as out_file:
        write_yaml(xml_translator(results), out_file)


