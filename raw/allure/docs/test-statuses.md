---
title: Test statuses
description: Test statuses in Allure Report | Passed tests | Failed tests | Skipped tests | Broken tests | Unknown test status | Product defects | Test defects
---

# Test statuses

Every test in an Allure test report has one of the five statuses: [Passed](#passed), [Failed](#failed), [Skipped](#skipped), [Broken](#broken), [Unknown](#unknown). Allure uses colors to represent the statuses in the filters, lists and charts throughout the test report.

## Passed

A passed test (green) is a test that finished successfully. This usually means that the tested scenario works as expected.

Note that some Allure integrations allow you to indicate different statuses for different [test steps](/docs/steps/). Depending on how the test is written, a failure of a step does not necessarily cause a failure of the test as a whole.

## Failed

A failed test (red) is a test that encountered an unexpected behavior in the system under test. This means that the test itself seems to be valid (not [Broken](#broken)), but its execution ended with a false assertion.

## Skipped

A skipped test (gray) is a test that was included in the test plan but then not executed.

This status is reported by the test framework itself and may be caused by various reasons. For example, some frameworks skip tests if they have a special annotation or similar construction, such as the `@Disabled` annotation in JUnit 5 or the `@pytest.mark.skip` decorator in pytest.

Info:
If you use Allure TestOps, you can unselect a test when creating a test plan. In such a case, the test will not be shown as **Skipped**, but instead will be excluded from the report entirely.

## Broken

A broken test (yellow) is a test that failed because of a test defect. Unlike [Failed](#failed), this status means that the test was unable to check the product's behavior as it intended to, therefore, the failure may or may not indicate an actual product defect.

For different test frameworks, the Allure integrations implement different criteria for differentiating between broken and failed tests. As a general rule, a test is considered broken if it causes an exception unrelated to the normal test framework behavior — for example, when it tries and fails to read a configuration file.

Notably, some JavaScript frameworks make it harder to check the reason of a failure because they don't always provide the full name of an exception that occurred. The Allure integrations [make their best to deduce it](https://github.com/allure-framework/allure-js/blob/main/packages/allure-js-commons/src/sdk/utils.ts#L5-L25) based on the information available. Consider opening an issue in the integration's bug tracker if some of your failed test are accidentally marked as broken or vice versa.

## Unknown

A test gets the “Unknown” status (violet) when the Allure integration did not explicitly set any other status for it, not even the [Skipped](#skipped) status. This may be caused by a bug or an incorrect usage of the Allure integration.

Consider opening an issue in the integration's bug tracker if you encounter tests with an unknown status in your reports.

## Beyond the test statuses

While the set of test statuses is fixed and cannot be extended, there are additional ways to sort and filter test results.

- Keep the history from previous test reports to automatically add special marks to [newly passed, newly failed and newly broken tests](/docs/sorting-and-filtering/#filter-tests-by-marks).

- Configure [custom categories](/docs/categories/#custom-categories) to differentiate tests based on their error messages.

- Consider using [Allure TestOps](https://qameta.io/) to perform other action on test results, for example: mute a test for a period of time, retry a test in a new run, map a failure to an issue in a bug tracker, and more.
