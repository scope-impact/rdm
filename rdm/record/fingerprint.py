"""
Normalized fingerprints of requirement text and verifying-test source.

A faithfulness pin (``faithfulness.hash_for``) is deliberately exact: any byte
change re-opens the review. These fingerprints are the *semantic* counterpart,
used to classify a stale verdict (DI-36) and to anchor a design input's wording
(DI-37) -- they ignore what cannot change meaning:

- requirement text: whitespace (runs collapsed, ends stripped);
- Python test source: everything the AST does not keep (comments, formatting)
  plus docstrings;
- other test sources: whole-line comments and whitespace.

Stdlib only, like the rest of the record layer.
"""

from __future__ import annotations

import ast
import hashlib
import re
import textwrap

# Whole-line comments in the non-Python languages tag discovery reads (DI-31):
# JS/TS/Java/Go ``//`` and ``/* … */`` continuation lines, YAML/shell ``#``.
_LINE_COMMENT = re.compile(r"^\s*(//|#|/\*|\*)")


def normalize_text(text: str) -> str:
    """Requirement text with every whitespace run collapsed to one space."""
    return " ".join(str(text).split())


def _sha(value: str) -> str:
    return "sha256:" + hashlib.sha256(value.encode("utf-8")).hexdigest()


def text_fingerprint(text: str) -> str:
    """Fingerprint of a requirement's wording, blind to whitespace."""
    return _sha(normalize_text(text))


def _strip_docstrings(tree: ast.AST) -> None:
    for node in ast.walk(tree):
        if not isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        body = node.body
        if (
            body
            and isinstance(body[0], ast.Expr)
            and isinstance(body[0].value, ast.Constant)
            and isinstance(body[0].value.value, str)
        ):
            # Drop it (adding a docstring must not change the dump); keep the
            # body non-empty so the tree stays a valid shape.
            node.body = body[1:] or [ast.Pass()]


def normalize_source(source: str) -> str:
    """Test source reduced to what can change its behavior.

    Python (anything ``ast`` parses, after dedenting a function segment) is
    reduced to its AST dump without docstrings or positions; anything else
    drops whole-line comments and collapses whitespace.
    """
    try:
        tree = ast.parse(textwrap.dedent(source))
    except (SyntaxError, ValueError):
        kept = [line for line in source.splitlines() if not _LINE_COMMENT.match(line)]
        return " ".join(" ".join(kept).split())
    _strip_docstrings(tree)
    return ast.dump(tree, include_attributes=False)


def source_fingerprint(sources: list[str]) -> str:
    """Fingerprint of a design input's verifying-test source(s), order-blind."""
    return _sha("\n--\n".join(sorted(normalize_source(s) for s in sources)))
