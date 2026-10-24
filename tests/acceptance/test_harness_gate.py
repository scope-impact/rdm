"""Slice 01, ticket 4: the gate (DI-95 to DI-99) and the harness's
configuration (DI-100), run as the harness's command (``bb harness``)."""

from pytest_bdd import scenario

from tests.acceptance.harness_steps import *  # noqa: F401,F403 (the steps)

SPEC = "../../dhf/plan/01-harness/spec.feature"


@scenario(SPEC, 'The instrument is checked before the suite runs')
def test_024_the_instrument_is_checked_before_the_suite_runs():
    pass


@scenario(SPEC, 'A wrong known answer stops the gate')
def test_025_a_wrong_known_answer_stops_the_gate():
    pass


@scenario(SPEC, 'A green suite passes this step')
def test_026_a_green_suite_passes_this_step():
    pass


@scenario(SPEC, 'A red suite fails the gate, whatever it prints')
def test_027_a_red_suite_fails_the_gate_whatever_it_prints():
    pass


@scenario(SPEC, 'No declared suite means nothing to ratify')
def test_028_no_declared_suite_means_nothing_to_ratify():
    pass


@scenario(SPEC, 'The served graph answers inside the budget, and the margin is reported')
def test_029_the_served_graph_answers_inside_the_budget_and_the_margin_is_reported():
    pass


@scenario(SPEC, 'A port already taken is refused, not reused')
def test_030_a_port_already_taken_is_refused_not_reused():
    pass


@scenario(SPEC, 'No answer inside the budget fails the gate')
def test_031_no_answer_inside_the_budget_fails_the_gate():
    pass


@scenario(SPEC, "The served graph gives the record's own text")
def test_032_the_served_graph_gives_the_record_s_own_text():
    pass


@scenario(SPEC, 'A served graph of another record fails the gate')
def test_033_a_served_graph_of_another_record_fails_the_gate():
    pass


@scenario(SPEC, 'A build round that changed a spec is refused')
def test_034_a_build_round_that_changed_a_spec_is_refused():
    pass


@scenario(SPEC, 'A build round that left the plan alone passes this step')
def test_035_a_build_round_that_left_the_plan_alone_passes_this_step():
    pass


@scenario(SPEC, 'The configuration is printed on every run')
def test_036_the_configuration_is_printed_on_every_run():
    pass


@scenario(SPEC, 'An unknown setting is refused')
def test_037_an_unknown_setting_is_refused():
    pass
