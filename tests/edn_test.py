"""Unit tests for the EDN reader (rdm.kernel.edn)."""

from __future__ import annotations

import pytest

from rdm.kernel.edn import Keyword, Symbol, loads, loads_all


def test_scalars() -> None:
    assert loads("nil") is None and loads("true") is True and loads("false") is False
    assert loads("42") == 42 and loads("-3") == -3 and loads("1.5") == 1.5 and loads("7N") == 7
    assert loads('"a \\"quoted\\" line\\n"') == 'a "quoted" line\n'
    assert loads('#"^[0-9]+$"') == "^[0-9]+$"  # a regex literal reads as its pattern


def test_keywords_and_symbols_keep_apart_from_strings() -> None:
    value = loads(":measurement/checklist")
    assert isinstance(value, Keyword) and value == "measurement/checklist" and repr(value) == ":measurement/checklist"
    assert isinstance(loads("derive"), Symbol) and not isinstance(loads('"derive"'), Keyword)


def test_collections() -> None:
    data = loads('{:a [1 2 (3 4)] :b #{:x :y} "k" {:nested true}}')
    assert data[Keyword("a")] == [1, 2, [3, 4]]
    assert data[Keyword("b")] == frozenset({Keyword("x"), Keyword("y")})
    assert data["k"] == {Keyword("nested"): True}


def test_vector_keys_are_hashable() -> None:
    assert loads("{[:a 1] :v}") == {(Keyword("a"), 1): Keyword("v")}


def test_comments_commas_and_discards_are_skipped() -> None:
    text = ";; a comment\n{:a 1, ;; trailing\n #_ :dropped :b 2}\n"
    assert loads(text) == {Keyword("a"): 1, Keyword("b"): 2}


def test_every_top_level_value() -> None:
    assert loads_all(":a 1 [2]") == [Keyword("a"), 1, [2]] and loads_all("  ; nothing\n") == []


@pytest.mark.parametrize("text", ["{:a", "[1 2", '"open', "{:a}", ")", "#inst \"2020\"", ""])
def test_malformed_input_is_a_value_error(text: str) -> None:
    with pytest.raises(ValueError):
        loads(text)
