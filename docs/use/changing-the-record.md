# Changing the record

Every change that alters what the product does reaches the record the same
way. The spec changes in its own process (a scenario written and tagged with
an id, by the team or by the factory that owns the spec); the tests,
documents and risks change in one pull request, reviewed by someone other
than its author. That
review is the approval; the merge records it.

```
 WHO needs it              WHAT it must do                PROOF it does it
 User need: a Feature ◄──── Requirement  @REQ-12  ◄────────── Acceptance test
                     under  (tagged scenario)        claims  @allure.tag("REQ-12")
                                                                   │ executed
 Traceability matrix ◄─────── verification.yml ◄────────────── Allure results
```

## Do I need a requirement?

- The change alters behaviour, an interface or an output: it is governed by
  a requirement. If one covers it (ask the graph: `trace REQ-12` in
  `rdm graph mcp`, or a `rdm graph query`), keep its test passing; if none
  does, the spec needs a new scenario.
- A refactor, documentation, comments or CI plumbing: no spec work. The gates
  still run and should stay green.

## The steps

1. **The spec.** The scenario is written under the Feature (the user need)
   it serves and tagged with a new id:
   [The spec and its tests](spec-and-tests.md).
2. **The test.** Write the acceptance test that verifies it, tagged
   `@allure.tag("REQ-12")` (a Gherkin runner writes the tag for you), and
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
