# Changing the record

Every change that alters what the product does reaches the record the same
way. The spec changes in the factory that owns it (Löyly: a scenario drafted,
accepted by the product owner, stamped); the tests, documents and risks
change in one pull request, reviewed by someone other than its author. That
review is the approval; the merge records it.

```
 WHO needs it                  WHAT it must do                PROOF it does it
 User need: screen + surface ◄─ Requirement  item-NN-SSS  ◄── Acceptance test
 (event model, Allium)  covers   (stamped scenario)   claims  @allure.tag("item-NN-SSS")
                                                                   │ executed
 Traceability matrix ◄─────── verification.yml ◄────────────── Allure results
```

## Do I need a requirement?

- The change alters behaviour, an interface or an output: it is governed by
  a requirement. If one covers it (ask the graph: `trace item-NN-SSS` in
  `rdm graph mcp`, or a `rdm graph query`), keep its test passing; if none
  does, the spec needs a new scenario, from the factory.
- A refactor, documentation, comments or CI plumbing: no spec work. The gates
  still run and should stay green.

## The steps

1. **The spec.** The scenario is accepted and stamped `@item-NN-SSS` in its
   context's `spec.feature`, tagged with the Allium rules or guarantees it
   covers, so it addresses its user need:
   [The spec and its tests](spec-and-tests.md).
2. **The test.** Write the acceptance test that verifies it, tagged
   `@allure.tag("item-NN-SSS")` (a Gherkin runner writes the tag for you), and
   the code that makes it pass.
3. **Risks.** If the requirement controls a risk, name it in the risk's
   `controls:` ([Risk register](risk-register.md)).
4. **The gates.** Run the acceptance suite and the gates as CI does:

    ```bash
    pytest tests/acceptance --clean-alluredir --alluredir=dhf/allure-results
    rdm story verify --dhf dhf --allure-results dhf/allure-results -o dhf/data/verification.yml
    rdm story release-gate --dhf dhf --allure-results dhf/allure-results
    ```

5. **The review.** Open the pull request. The reviewer reads each changed
   scenario beside its test and judges whether the test proves it.

Planning artifacts (tasks, issues, boards) coordinate the work; they are
never evidence ([Plan vs. record](../about/plan-vs-record.md)).
