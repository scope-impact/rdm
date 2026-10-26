# Component Domain model, bounded context architecture of the rdm-spec container: dhf/spec/model.edn.
#
# What an event storm finds, kept as data: the aggregates, their commands and
# invariants, the events, the read models they feed and the policies they
# trigger, each distilled from the raw sources like a user need. The storm
# itself is a way of working and never part of the spec; its result, when a
# project keeps one, is dhf/spec/domain.edn (the configuration's :domain), and
# it is optional (spec owner, spec-22). Where it exists it says why the bounded
# contexts are where they are: a context ends where an aggregate's invariants
# stop, and contexts meet through events.

@UN-005
Feature: Domain model
  Holds a project's domain model, when it keeps one, to the rules of
  domain-driven design and to its sources.

  Rule: The domain model is optional

    @spec-0334 @e2e
    Scenario: A project without a domain model is not asked for one
      Given a project set up by rdm-spec with no dhf/spec/domain.edn
      When "rdm-spec check" is run
      Then it exits 0 and names no finding about a domain model

    @spec-0335 @contract
    Scenario: The domain model is one EDN file of this shape
      Given a project whose dhf/spec/domain.edn holds one context with one aggregate
      When "rdm-spec check" is run
      Then the file is read as exactly this shape, and a key it does not declare is a finding domain-key-undeclared:
        """
        {:contexts
         {:booking
          {:aggregates   {:standing-slot {:commands   {:RequestStandingSlot {:by :resident :emits [:SlotConfirmed] :refuses [:closed-day :held] :sources [citation]}}
                                          :invariants {:one-holder {:text "A slot is held by at most one household" :verified-by ["spec-0003"] :sources [citation]}}
                                          :reads      [:open-days]
                                          :sources    [citation]}}
           :read-models  {:open-days {:fed-by [:DaysConfigured] :sources [citation]}}
           :policies     {:charge {:when :SlotConfirmed :then "raise an invoicing signal" :sources [citation]}}
           :repositories {:booking-record {:stores [:standing-slot]}}}}
         :events {:SlotConfirmed {:sources [citation]}}}
        """
      And a context's id is a bounded context of model.edn, and every event is declared in the event catalogue

  # A domain model is distilled from the sources as a need is, so no rule in
  # it is invented (loyly:docs/wow/invariants.md@17d68d2, INV-1).
  @UN-011
  Rule: Every element of the domain model rests on a source

    @spec-0336 @e2e
    Scenario Outline: An element with no source, or a source that is not recorded, is a finding
      Given a project whose dhf/spec/domain.edn holds <element>
      When "rdm-spec check" is run
      Then it exits 1 with the finding <rule>, naming the element

      Examples:
        | element                                                                    | rule                 |
        | an aggregate with no :sources                                              | domain-unsourced     |
        | an invariant with no :sources                                              | domain-unsourced     |
        | a command citing loyly:raw/x.md@0000000, a pin dhf/raw/sources.edn lacks   | domain-source-unknown |

  # The rules of the storm, where a machine can judge them.
  Rule: The domain model keeps the rules of domain-driven design

    @spec-0337 @e2e
    Scenario Outline: A domain model that breaks a rule is a finding naming the element
      Given a project whose dhf/spec/domain.edn holds <wrong>
      When "rdm-spec check" is run
      Then it exits 1 with the finding <rule>, naming <named>

      Examples:
        | wrong                                                                         | rule                        | named                  |
        | the command RequestStandingSlot in two aggregates                             | domain-command-handlers     | the command and both   |
        | a command that emits no event and names no refusal                            | domain-command-silent       | the command            |
        | an event in :events that no command emits                                     | domain-event-orphan         | the event              |
        | a read model fed by :RequestStandingSlot, a command                           | domain-read-model-fed       | the read model         |
        | an invariant whose :verified-by is empty                                      | domain-invariant-unverified | the invariant          |
        | an invariant verified by spec-9999, which no stamped scenario carries         | domain-invariant-unverified | the invariant and id   |
        | an aggregate that :reads an aggregate of another context                      | domain-contexts-coupled     | both aggregates        |
        | an event a command emits that the event catalogue does not declare            | domain-event-uncatalogued   | the event              |

    @spec-0338 @e2e
    Scenario: A domain model that keeps every rule passes
      Given a project whose dhf/spec/domain.edn holds two contexts, each command emitting a catalogued event, each invariant verified by a stamped scenario, each read model fed by events, and every element sourced
      When "rdm-spec check" is run
      Then it names no finding about the domain model
