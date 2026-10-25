@UN-001
Feature: Cart
  Holds what is bought.

  Rule: A cart holds items

    @spec-0001 @e2e
    Scenario: An item added is in the cart
      Given an empty cart
      When an apple is added
      Then the cart holds an apple

    @spec-0002 @e2e
    Scenario: An item removed is gone
      Given a cart holding an apple
      When the apple is removed
      Then the cart is empty

    @spec-0003 @contract
    Scenario: A cart lists its items in the order added
      Given an empty cart
      When an apple and a pear are added
      Then the cart lists the apple, then the pear
