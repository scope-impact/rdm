"""A small reader for EDN, the data format of a Grain event model and an
event-storm board.

Enough of EDN for those files: maps, vectors, lists, sets, strings, regex
literals (read as strings), keywords, symbols, numbers, ``nil``, ``true`` and
``false``, comments and ``#_`` discards. A keyword reads as a :class:`Keyword`
(a ``str`` holding its name without the colon), so it stays apart from a
string with the same text. Anything else is a :class:`ValueError`.
"""

from __future__ import annotations

import re


class Keyword(str):
    """An EDN keyword, by its name: ``:measurement/checklist`` is
    ``Keyword("measurement/checklist")``."""

    def __repr__(self) -> str:
        return ":" + str(self)


class Symbol(str):
    """An EDN symbol, by its name."""


_DELIMS = set("()[]{}\"; \t\r\n,")
_NUMBER = re.compile(r"^[+-]?\d+(\.\d+)?([eE][+-]?\d+)?[MN]?$")
_ESCAPES = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\"}


def loads(text: str):
    """The first value of ``text``."""
    values = loads_all(text)
    if not values:
        raise ValueError("no EDN value")
    return values[0]


def loads_all(text: str) -> list:
    """Every top-level value of ``text``, in order."""
    reader = _Reader(text)
    values = []
    while True:
        reader.skip()
        if reader.done():
            return values
        values.append(reader.value())


class _Reader:
    def __init__(self, text: str):
        self.text, self.pos = text, 0

    def done(self) -> bool:
        return self.pos >= len(self.text)

    def skip(self) -> None:
        while not self.done():
            ch = self.text[self.pos]
            if ch in " \t\r\n,":
                self.pos += 1
            elif ch == ";":
                end = self.text.find("\n", self.pos)
                self.pos = len(self.text) if end < 0 else end + 1
            elif self.text.startswith("#_", self.pos):
                self.pos += 2
                self.skip()
                self.value()
            else:
                return

    def _expect_value(self):
        self.skip()
        if self.done():
            raise ValueError("unexpected end of EDN")
        return self.value()

    def _seq(self, close: str) -> list:
        items = []
        while True:
            self.skip()
            if self.done():
                raise ValueError(f"unclosed EDN collection, expected {close!r}")
            if self.text[self.pos] == close:
                self.pos += 1
                return items
            items.append(self.value())

    def _string(self) -> str:
        self.pos += 1  # the opening quote
        out = []
        while not self.done():
            ch = self.text[self.pos]
            if ch == '"':
                self.pos += 1
                return "".join(out)
            if ch == "\\":
                nxt = self.text[self.pos + 1:self.pos + 2]
                out.append(_ESCAPES.get(nxt, nxt))
                self.pos += 2
            else:
                out.append(ch)
                self.pos += 1
        raise ValueError("unclosed EDN string")

    def _token(self) -> str:
        start = self.pos
        while not self.done() and self.text[self.pos] not in _DELIMS:
            self.pos += 1
        return self.text[start:self.pos]

    def value(self):
        ch = self.text[self.pos]
        if ch == "{":
            self.pos += 1
            items = self._seq("}")
            if len(items) % 2:
                raise ValueError("an EDN map needs an even number of forms")
            return {_hashable(k): v for k, v in zip(items[::2], items[1::2])}
        if ch == "[":
            self.pos += 1
            return self._seq("]")
        if ch == "(":
            self.pos += 1
            return self._seq(")")
        if ch == '"':
            return self._string()
        if ch == "#":
            nxt = self.text[self.pos + 1:self.pos + 2]
            if nxt == "{":
                self.pos += 2
                return frozenset(_hashable(v) for v in self._seq("}"))
            if nxt == '"':
                self.pos += 1
                return self._string()
            raise ValueError(f"unsupported EDN dispatch #{nxt}")
        if ch in ")]}":
            raise ValueError(f"unexpected {ch!r} in EDN")
        token = self._token()
        if not token:
            raise ValueError(f"unexpected character {ch!r} in EDN")
        if token.startswith(":"):
            return Keyword(token[1:])
        if token == "nil":
            return None
        if token in ("true", "false"):
            return token == "true"
        if _NUMBER.match(token):
            token = token.rstrip("MN")
            return float(token) if any(c in token for c in ".eE") else int(token)
        if token.startswith("\\"):
            return token[1:]
        return Symbol(token)


def _hashable(value):
    if isinstance(value, list):
        return tuple(_hashable(v) for v in value)
    if isinstance(value, dict):
        return tuple(sorted((k, _hashable(v)) for k, v in value.items()))
    return value
