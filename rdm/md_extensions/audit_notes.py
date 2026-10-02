from rdm.md_extensions.base import RdmExtension
from rdm.md_extensions.code import fenced, spans


class AuditNoteExclusionExtension(RdmExtension):
    def post_process_filter(self, generator):
        yield from exclude_audit_notes(generator)


def exclude_audit_notes(lines):
    """Remove each ``[[…]]`` note, and the space before it, outside code. A
    note may run over lines of one paragraph; one still open at the end of
    the paragraph (a blank line, a code block, or the end) is kept as text."""
    held = []   # the lines since the open note began, as written
    kept = ""   # what is kept of them so far
    depth = 0
    for line, code in fenced(lines):
        if depth and (code or not line.strip()):
            yield from held  # an unclosed note: it was text after all
            held, kept, depth = [], "", 0
        if code:
            yield line
            continue
        if depth:
            held.append(line)
        out = []
        for piece, is_code in spans(line):
            if is_code:
                if not depth:
                    out.append(piece)
                continue
            i = 0
            while i < len(piece):
                if piece.startswith("[[", i):
                    if not depth and out and out[-1].endswith(" "):
                        out[-1] = out[-1][:-1]
                    if not depth and not held:
                        held = [line]
                        kept = "".join(out)
                        out = []
                    depth += 1
                    i += 2
                elif depth and piece.startswith("]]", i):
                    depth -= 1
                    i += 2
                else:
                    if not depth:
                        out.append(piece[i])
                    i += 1
        if depth:
            kept += "".join(out)
            continue
        if held:
            yield kept + "".join(out)
            held, kept = [], ""
        else:
            yield "".join(out)
    yield from held

