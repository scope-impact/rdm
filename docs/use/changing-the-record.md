# Changing the record

Every change that alters what the product does reaches the record the same
way: in one pull request, reviewed by someone other than its author. That
review is the approval; the merge records it.

```
 WHY the product exists      WHAT it must do           PROOF it does it
 User need  UN-nnn  ◄──────  Design input  DI-n  ◄───  Acceptance test
 (V&V plan)         traces_to (design document)  verify @allure.story("DI-n")
                                                            │ executed
 Traceability matrix ◄────── verification.yml ◄──────── Allure results
```

## Do I need a design input?

- The change alters behaviour, an interface or an output: it is governed by
  a design input. If one covers it (`rdm story trace DI-n --dhf dhf`), keep
  its test passing; if none does, declare one.
- A refactor, documentation, comments or CI plumbing: no design-input work.
  The gates still run and should stay green.

## The steps

1. **The need.** Find the user need the change serves in the V&V plan, or
   register a new one (`user_needs:`).
2. **The design input.** Declare it in the design document of the context
   that owns it (`design_inputs:`, with `traces_to`):
   [Design inputs and tests](design-inputs-and-tests.md).
3. **The test.** Write the acceptance test that verifies it, tagged
   `@allure.story("DI-n")`, and the code that makes it pass.
4. **The gates.** Run the acceptance suite and the gates as CI does:

    ```bash
    pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
    rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
    rdm story release-gate --dhf dhf --allure-results dhf/allure-results
    ```

5. **The review.** Open the pull request. The reviewer reads each changed
   design input beside its test and judges whether the test proves it.

Planning artifacts (tasks, issues, boards) coordinate the work; they are
never evidence ([Plan vs. record](../about/plan-vs-record.md)).
