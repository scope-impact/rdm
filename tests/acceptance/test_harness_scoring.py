"""Slice 01, ticket 3: scoring (DI-93) and the scoreboard (DI-94), run as the
harness's command (``bb harness``)."""

from pytest_bdd import scenario

from tests.acceptance.harness_steps import *  # noqa: F401,F403 (the steps)

SPEC = "../../dhf/plan/01-harness/spec.feature"


@scenario(SPEC, "Verdicts are filed against the checklist as the cycle's record")
def test_016_verdicts_are_filed_against_the_checklist_as_the_cycle_s_record():
    pass


@scenario(SPEC, 'Zero verdicts means the grading did not happen')
def test_017_zero_verdicts_means_the_grading_did_not_happen():
    pass


@scenario(SPEC, 'An item no verdict reached is a failure, never a smaller denominator')
def test_018_an_item_no_verdict_reached_is_a_failure_never_a_smaller_denominator():
    pass


@scenario(SPEC, 'A verdict for an item not on the checklist is refused')
def test_019_a_verdict_for_an_item_not_on_the_checklist_is_refused():
    pass


@scenario(SPEC, 'An acceptance run is a grader')
def test_020_an_acceptance_run_is_a_grader():
    pass


@scenario(SPEC, "The pass rate is over every item the cycle's checklist carried")
def test_021_the_pass_rate_is_over_every_item_the_cycle_s_checklist_carried():
    pass


@scenario(SPEC, 'Better and worse are counted apart')
def test_022_better_and_worse_are_counted_apart():
    pass


@scenario(SPEC, 'A moved denominator is said out loud, and new items are counted apart')
def test_023_a_moved_denominator_is_said_out_loud_and_new_items_are_counted_apart():
    pass
