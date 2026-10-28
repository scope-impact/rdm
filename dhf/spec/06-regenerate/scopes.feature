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

  Rule: Scopes prints what it listed, and names each shared file

    @spec-0489 @contract
    Scenario: Scopes prints the cuts, the scopes and the shared files
      Given the cuts spec-3 and spec-4, between which kernel/bus.feature and dhf/spec/glossary.md changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it exits 0, and its standard output, read as EDN, is exactly {:scopes {:from "spec-3" :to "spec-4" :scopes [{:component :kernel/bus}] :shared ["dhf/spec/glossary.md"]}}
      And its error stream holds one warning line about dhf/spec/glossary.md, as FILE: warning: RULE: what

    @spec-0490 @e2e
    Scenario Outline: A shared file is a changed kept path that is no component's and not the event catalogue
      Given the cuts spec-3 and spec-4, between which only <path> changed
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it exits 0, lists no scope, and its :shared is <shared>

      Examples:
        | path                                                       | shared                    |
        | dhf/spec/model.edn                                         | ["dhf/spec/model.edn"]    |
        | dhf/spec/ids.edn                                           | ["dhf/spec/ids.edn"]      |
        | dhf/spec/events.edn, an event's :description only           | []                        |
        | an implementation file, a path the configuration denies    | []                        |
        | dhf/cycles/03/score.edn, a path the configuration denies   | []                        |

    @spec-0491 @e2e
    Scenario Outline: An event added or removed reaches the subscribers each cut's model names
      Given the cuts spec-3 and spec-4, between which only the event :Paid <change>, published by shop/till
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists as scopes shop/till and <subscribers>

      Examples:
        | change                                                                                    | subscribers                                              |
        | was added, with spec-4's model naming shop/ledger as its subscriber                       | shop/ledger                                              |
        | was removed, with spec-3's model naming shop/ledger as its subscriber                     | shop/ledger                                              |
        | changed the shape of a key, spec-3's model naming shop/ledger and spec-4's shop/mail as its subscribers | shop/ledger and shop/mail                  |

    @spec-0492 @e2e
    Scenario Outline: Two cuts are read as given, the change from the first to the second
      Given the cuts spec-3 and spec-4, between which only kernel/bus.feature changed
      When "rdm-spec scopes <args>" is run
      Then it <outcome>

      Examples:
        | args             | outcome                                                                                          |
        | spec-4 spec-3    | exits 0, its :from "spec-4" and :to "spec-3", and lists the scope {:component :kernel/bus}        |
        | spec-4 spec-4    | exits 0, and lists no scope and no shared file                                                     |
        | spec-3 HEAD      | exits 2 and names HEAD as no cut                                                                   |

  Rule: Under the slices layout, slices first, then the components no listed slice holds

    @spec-0493 @e2e
    Scenario: A changed event's publisher and subscribers no listed slice holds are scopes after the slices
      Given a configuration with :spec-layout :slices, and the cuts spec-3 and spec-4, between which a feature file of 12-several-specs and the shape of a key of :SpecParsed changed, the slice reaching :spec/parser and not :verification/status
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists the scope {:slice "12-several-specs" :components [...]}, then {:component :verification/status} and each other subscriber of :SpecParsed the slice does not reach, in the order a builder builds them
      And it lists :spec/parser, the publisher, in the slice only

    @spec-0494 @e2e
    Scenario: A touched slice whose every component an earlier slice holds is still listed
      Given a configuration with :spec-layout :slices, and the cuts spec-3 and spec-4, between which a feature file of 11-project-in-a-folder and the plan of 12-several-specs changed, every component 12-several-specs reaches also reached by 11-project-in-a-folder
      When "rdm-spec scopes spec-3 spec-4" is run
      Then it lists both slices as scopes, 12-several-specs with :components []
