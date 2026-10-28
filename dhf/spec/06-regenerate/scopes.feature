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

    @spec-0238 @e2e
    Scenario: A changed file no component owns is named for a person to judge
      Given the cuts spec-3 and spec-4, between which only dhf/spec/glossary.md changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists no scope, and names glossary.md as a changed shared file whose readers a person must judge

  Rule: A changed event reaches its publisher and every subscriber

    @spec-0298 @e2e
    Scenario: A changed event's publisher and subscribers are scopes
      Given the cuts spec-3 and spec-4, between which only the shape of one carried key of one event in dhf/spec/events.edn changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists as scopes the event's publisher, then every subscriber the model's relationships name for that event, in the model's order

    @spec-0348 @e2e
    Scenario: A changed publisher reaches both publishers and every subscriber
      Given the cuts spec-3 and spec-4, between which only an event's :published-by changed from shop/cart to shop/till
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists as scopes shop/cart, shop/till and every subscriber the model's relationships name for that event, in the order the next rule gives

    @spec-0340 @e2e
    Scenario Outline: A change that leaves an event's contract unchanged reaches no component
      Given the cuts spec-3 and spec-4, between which only <change>
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists no scope

      Examples:
        | change                                                                 |
        | an event's :description changed                                        |
        | an event lost a key its catalogue entry does not declare, as :read-by was |

  Rule: Scopes are listed in the order a builder builds them

    @spec-0299 @e2e
    Scenario: The kernel first, then each publisher before its subscribers
      Given the cuts spec-3 and spec-4, between which the kernel's command, the spec parser and the status component changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists kernel/command, then spec/parser, then verification/status
      And components with no order between them come in the model's order, the subscribers of one event among them (cycles 18 and 19 read spec-0298's "the order they name them" two ways; spec-24 settles on the model's)
