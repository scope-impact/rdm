# Component Scopes, bounded context regeneration of the rdm-spec container: dhf/spec/model.edn.
#
# Which scopes a cycle regenerates is read from the spec, never remembered:
# cycle 04 missed a changed component when its scopes were listed by hand
# (dhf/cycles/04/proposed-spec-changes.md, D).

@UN-006
Feature: Scopes
  Lists what a spec change touched between two cuts, so a cycle's scopes come
  from the spec.

  Rule: A cycle's scopes come from the spec change

    @spec-0237 @e2e
    Scenario: The components whose feature files changed are listed as scopes
      Given the cuts spec-3 and spec-4, between which architecture/views.feature and kernel/bus.feature changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists the scopes {:component :kernel/bus}, then {:component :architecture/views}, and nothing else

    @spec-0241 @e2e
    Scenario: A component's design prose is its own
      Given the cuts spec-3 and spec-4, between which only architecture/views.md, design prose that spec-3 did not have, was added
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists the scope {:component :architecture/views}

    # A shared file is a changed, kept path that no component owns; it is
    # named as a warning, and the task exits 0.
    @spec-0238 @e2e
    Scenario: A changed file no component owns is named for a person to judge
      Given the cuts spec-3 and spec-4, between which only dhf/spec/glossary.md changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists no scope, and names glossary.md as a changed shared file whose readers a person must judge

  # A changed event reaches its publisher and every subscriber the model
  # names for it. Reading :read-by per key, and per key of a map, saved a few
  # scopes and broke twice: cycle 14 read its first appearance as every key
  # added, cycle 16 regressed on a flat list under a nested key. The spec
  # owner chose Löyly's simplicity over the saving (spec-22; about 5 to 15%
  # more build cost): :read-by is gone, and a whole-project cycle every few
  # cycles catches what scoping hides (README, "A whole-project cycle").
  Rule: A changed event reaches its publisher and every subscriber

    @spec-0298 @e2e
    Scenario: A changed event's publisher and subscribers are scopes
      Given the cuts spec-3 and spec-4, between which only the shape of one carried key of one event in dhf/spec/events.edn changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists as scopes the event's publisher, then every subscriber the model's relationships name for that event, in the order they name them

    # Removing :read-by from every event reached every subscriber of every
    # event: spec-21 to spec-22 listed 15 scopes (cycle 18, integrator). An
    # event's contract is what it carries and their shape.
    @spec-0340 @e2e
    Scenario Outline: A change that leaves an event's contract unchanged reaches no component
      Given the cuts spec-3 and spec-4, between which only <change>
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists no scope

      Examples:
        | change                                                                 |
        | an event's :description changed                                        |
        | an event lost a key its catalogue entry does not declare, as :read-by was |

  # One builder rebuilds a cycle's scopes one after another (spec owner, cycle
  # 13), so the list is in the order they can be built.
  Rule: Scopes are listed in the order a builder builds them

    @spec-0299 @e2e
    Scenario: The kernel first, then each publisher before its subscribers
      Given the cuts spec-3 and spec-4, between which the kernel's command, the spec parser and the status component changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists kernel/command, then spec/parser, then verification/status
      And components with no order between them come in the model's order
