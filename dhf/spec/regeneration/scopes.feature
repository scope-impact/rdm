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
      Then it lists the scopes {:component :architecture/views} and {:component :kernel/bus}, and nothing else

    @spec-0241 @e2e
    Scenario: A component's design prose is its own
      Given the cuts spec-3 and spec-4, between which only architecture/views.md changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists the scope {:component :architecture/views}

    # A shared file is one on the clean room's allow-list that no component
    # owns; it is named as a warning, and the task exits 0.
    @spec-0238 @e2e
    Scenario: A changed file no component owns is named for a person to judge
      Given the cuts spec-3 and spec-4, between which only dhf/spec/glossary.md changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists no scope, and names glossary.md as a changed shared file whose readers a person must judge

  # A context is rebuilt when its own spec changed, or when an event it
  # subscribes to changed shape: that event is its contract. A context whose
  # only change is upstream code is not rebuilt; the event's shape holds
  # (spec owner, cycle 13).
  Rule: A changed event reaches its publisher and its subscribers

    @spec-0298 @e2e
    Scenario: A changed event's publisher and subscribers are scopes
      Given the cuts spec-3 and spec-4, between which only the shape of one event in dhf/spec/events.edn changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists as scopes the component the event catalogue names as its publisher and every component the model draws subscribing to it

  # One builder rebuilds a cycle's scopes one after another (spec owner, cycle
  # 13), so the list is in the order they can be built.
  Rule: Scopes are listed in the order a builder builds them

    @spec-0299 @e2e
    Scenario: The kernel first, then each publisher before its subscribers
      Given the cuts spec-3 and spec-4, between which the kernel's command, the spec parser and the status component changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists kernel/command, then spec/parser, then verification/status
