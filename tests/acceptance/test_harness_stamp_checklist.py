"""Slice 01, ticket 2: the stamper (DI-91) and the checklist (DI-92), run as
the harness's command (``bb harness``)."""

from pytest_bdd import scenario

from tests.acceptance.harness_steps import *  # noqa: F401,F403 (the steps)

SPEC = "../../dhf/plan/01-harness/spec.feature"


@scenario(SPEC, 'An unstamped scenario is given the next free id in its slice')
def test_006_an_unstamped_scenario_is_given_the_next_free_id_in_its_slice():
    pass


@scenario(SPEC, 'The next free id is one past the highest the spec carries')
def test_007_the_next_free_id_is_one_past_the_highest_the_spec_carries():
    pass


@scenario(SPEC, 'Stamping again changes nothing')
def test_008_stamping_again_changes_nothing():
    pass


@scenario(SPEC, 'Rewording or reordering scenarios keeps their ids')
def test_009_rewording_or_reordering_scenarios_keeps_their_ids():
    pass


@scenario(SPEC, 'The stamper says where it looked when it finds no spec')
def test_010_the_stamper_says_where_it_looked_when_it_finds_no_spec():
    pass


@scenario(SPEC, 'The checklist has one item per stamped scenario')
def test_011_the_checklist_has_one_item_per_stamped_scenario():
    pass


@scenario(SPEC, 'An unstamped scenario stops the checklist rather than shrinking it')
def test_012_an_unstamped_scenario_stops_the_checklist_rather_than_shrinking_it():
    pass


@scenario(SPEC, 'A spec with no scenario stops the checklist rather than producing an empty one')
def test_013_a_spec_with_no_scenario_stops_the_checklist_rather_than_producing_an_empty_one():
    pass


@scenario(SPEC, 'A slice still planned is scope, and is skipped out loud')
def test_014_a_slice_still_planned_is_scope_and_is_skipped_out_loud():
    pass


@scenario(SPEC, 'The checklist is the same bytes from the same spec')
def test_015_the_checklist_is_the_same_bytes_from_the_same_spec():
    pass
