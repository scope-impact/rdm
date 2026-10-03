"""Where Markdown code is, so post-processing leaves it as written: fenced
blocks (``` or ~~~, also inside a blockquote or an indented list item) and
inline code spans."""

import re

# A fence after any blockquote markers and indentation: a list item's fence
# is indented to the item's content.
_FENCE = re.compile(r"^(?:[ \t]*>)*[ \t]*(`{3,}|~{3,})")
_SPAN = re.compile(r"(`+)(?:(?!\1).)+?\1")


def fenced(lines):
    """Each line with whether it is code: inside a fenced block, or a fence."""
    fence = None
    for line in lines:
        match = _FENCE.match(line)
        if fence is None and match:
            fence = match.group(1)
            yield line, True
        elif fence is not None:
            if match and match.group(1)[0] == fence[0] and len(match.group(1)) >= len(fence) \
                    and not line[match.end():].strip():
                fence = None
            yield line, True
        else:
            yield line, False


def spans(text):
    """The text in pieces, each with whether it is an inline code span."""
    position = 0
    for match in _SPAN.finditer(text):
        if match.start() > position:
            yield text[position:match.start()], False
        yield match.group(0), True
        position = match.end()
    if position < len(text):
        yield text[position:], False
