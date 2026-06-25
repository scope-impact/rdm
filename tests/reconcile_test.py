"""Tests for the generic reconciliation core (rdm.record.reconcile)."""

from __future__ import annotations

from dataclasses import dataclass, field

from rdm.record.reconcile import aggregate_by_id, ids_with_status, relevant_orphans


@dataclass
class _Agg:
    uid: str
    status: str = "none"
    hits: list = field(default_factory=list)


def _new(uid: str) -> _Agg:
    return _Agg(uid)


def _fold(agg: _Agg, obs) -> None:
    agg.hits.append(obs[1])


def _status(agg: _Agg) -> str:
    return "hit" if agg.hits else "miss"


class TestAggregateById:
    def test_buckets_observations_and_derives_status(self) -> None:
        observations = [("UN-001", "a"), ("UN-001", "b"), ("UN-002", "c")]
        by_id, orphans = aggregate_by_id(
            {"UN-001", "UN-002", "UN-003"},
            observations,
            ids_of=lambda o: [o[0]],
            new=_new,
            fold=_fold,
            status=_status,
        )
        assert by_id["UN-001"].hits == ["a", "b"]
        assert by_id["UN-001"].status == "hit"
        assert by_id["UN-003"].status == "miss"  # declared, no observation
        assert orphans == []

    def test_orphans_are_referenced_but_undeclared_and_sorted(self) -> None:
        observations = [("UN-001", "a"), ("UN-009", "x"), ("UN-005", "y")]
        _, orphans = aggregate_by_id(
            {"UN-001"},
            observations,
            ids_of=lambda o: [o[0]],
            new=_new,
            fold=_fold,
            status=_status,
        )
        assert orphans == ["UN-005", "UN-009"]

    def test_multi_id_observations(self) -> None:
        by_id, orphans = aggregate_by_id(
            {"UN-001", "UN-002"},
            [(("UN-001", "UN-002"), "shared")],
            ids_of=lambda o: o[0],
            new=_new,
            fold=lambda agg, o: agg.hits.append(o[1]),
            status=_status,
        )
        assert by_id["UN-001"].hits == ["shared"]
        assert by_id["UN-002"].hits == ["shared"]
        assert orphans == []


def test_ids_with_status() -> None:
    by_id = {"UN-002": _Agg("UN-002", "hit"), "UN-001": _Agg("UN-001", "hit"),
             "UN-003": _Agg("UN-003", "miss")}
    assert ids_with_status(by_id, "hit") == ["UN-001", "UN-002"]  # sorted
    assert ids_with_status(by_id, "miss") == ["UN-003"]


def test_relevant_orphans_filters_by_prefix() -> None:
    # UN-999 shares the UN- prefix; FT-001 does not.
    assert relevant_orphans(["UN-999", "FT-001"], {"UN-001"}) == ["UN-999"]
