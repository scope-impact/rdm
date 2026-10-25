@UN-001
Feature: Till
  Takes the money.

  Rule: The till totals the cart

    @spec-0004 @e2e
    Scenario: The total is the sum of the prices
      Given a cart holding an apple at 2 and a pear at 3
      When the till totals it
      Then the total is 5

    @spec-0005 @contract
    Scenario Outline: A discount is taken off the total
      Given a cart totalling <total>
      When a discount of <discount> is applied
      Then the total is <after>

      Examples:
        | total | discount | after |
        | 10    | 1        | 9     |
        | 5     | 5        | 0     |

    @spec-0006 @e2e @wip
    Scenario: A refund returns the money
      Given a paid cart
      When it is refunded
      Then the money is returned
