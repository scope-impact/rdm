import re

from rdm.md_extensions.base import RdmExtension
from rdm.md_extensions.code import fenced

_HEADING = re.compile(r"^(#{1,6})[ \t]+\S")


def section_number_filter(generator):
    section_list = []
    for line, code in fenced(generator):
        section_depth = 0 if code else section_number_depth(line)
        if section_depth == 0:
            yield line
        else:
            if section_depth > len(section_list):
                while section_depth > len(section_list):
                    section_list.append(1)
            else:
                while section_depth < len(section_list):
                    section_list.pop()
                section_list[section_depth - 1] += 1
            formatted_section_number = '.'.join([
                str(section_number) for section_number in section_list])
            yield line[0:section_depth] + ' ' + formatted_section_number + line[section_depth:]


def section_number_depth(line):
    """The level of an ATX heading (``#`` to ``######``, then a space), else 0."""
    match = _HEADING.match(line)
    return len(match.group(1)) if match else 0


class SectionNumberExtension(RdmExtension):

    def post_process_filter(self, generator):
        yield from section_number_filter(generator)
