"""Slice 01, ticket 1: the record reads the spec (DI-90).

Each test is one scenario of the spec, bound by its title; its design input
comes from the Rule's tag (RDM's pytest plugin turns it into the story).
"""

from pytest_bdd import scenario

from tests.acceptance.harness_steps import *  # noqa: F401,F403 (the steps)

SPEC = "../../dhf/plan/01-harness/spec.feature"


@scenario(SPEC, "A tagged Rule is a design input of the record")
def test_a_tagged_rule_is_a_design_input():
    pass


@scenario(SPEC, "A design input declared in a spec and in a design document is a duplicate")
def test_a_design_input_declared_twice_is_a_duplicate():
    pass


@scenario(SPEC, "A Rule in a slice's spec with no design-input id is refused")
def test_a_rule_with_no_id_is_refused():
    pass


@scenario(SPEC, "A Rule with no owner or no traced need is refused")
def test_a_rule_with_no_owner_is_refused():
    pass


@scenario(SPEC, "A spec that cannot be read is refused at its line")
def test_an_unreadable_spec_is_refused():
    pass
