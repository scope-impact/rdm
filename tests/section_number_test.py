
from rdm.md_extensions.section_numbers import section_number_filter




SECTION_NUMBER_INPUT = """preceding
# This is the first section
Hello from first section.
## This is the first subsection
Hello from first of top.
# This is the second section
Hello from second section.
## This is the second subsection
Hello from first of second.
#### This is a deeper section
More Stuff
## This is the third subsection
Hello from second of second.
"""

EXPECTED_SECTION_NUMBER_OUTPUT = """preceding
# 1 This is the first section
Hello from first section.
## 1.1 This is the first subsection
Hello from first of top.
# 2 This is the second section
Hello from second section.
## 2.1 This is the second subsection
Hello from first of second.
#### 2.1.1.1 This is a deeper section
More Stuff
## 2.2 This is the third subsection
Hello from second of second.
"""


def test_section_number_filter_direct():
    generator = (line for line in SECTION_NUMBER_INPUT.split('\n'))
    actual_output = '\n'.join([item for item in section_number_filter(generator)])
    assert actual_output == EXPECTED_SECTION_NUMBER_OUTPUT




