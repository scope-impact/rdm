---
title: Test Identifiers
description: Find out how internal test identifiers work in Allure reporting tools and framework integrations, and what aspects of your test pipeline may be affected by changes in them.
---

# Test Identifiers

Allure integrations and Allure reporting tools use internal test identifiers to track tests across runs and correctly map historical results within reports.

This article explains how these internal identifiers work and what parts of your testing workflow may be affected by any changes to them.

Tip:
This article frequently refers to concepts such as test cases, tests and test results. Please refer to the [Glossary](/docs/how-it-works-glossary/) to learn what exactly these terms mean within Allure Report.

## Identifier Types

There are four main identifiers that Allure tools use to track test results.

- [`fullName`](#fullname)
- [`testCaseId`](#testcaseid)
- [`historyId`](#historyid)
- [`uuid`](#uuid)

Here is an example of an Allure test result file (typically found within the `allure-results` directory of your project) where all these identifiers are stored:

```json
{
  "name": "test_sum_keyword[sum_arguments1]",
  "status": "passed",
  "parameters": [
    {
      "name": "sum_arguments",
      "value": "[-1, 1, 0]"
    }
  ],
  "start": 1759304434025,
  "stop": 1759304434025,
  "uuid": "2f25f93b-9ef3-4e62-b4d7-e376db69e198",
  "historyId": "741e3c0379dae9c479f495d79cfc4a46",
  "testCaseId": "e3edbc90111b1e74614910797ec9e808",
  "fullName": "test.test_sum#test_sum_keyword",
  "labels": [
    {
      "name": "parentSuite",
      "value": "test"
    },
    {
      "name": "suite",
      "value": "test_sum"
    },
    {
      "name": "package",
      "value": "test.test_sum"
    }
  ]
}
```

All four are opaque identifiers that are calculated for each test result automatically and are not, usually, displayed in the generated reports.

However, as they are based on test framework metadata, they may change when you refactor your test code.

Three out of four of them (`fullName`, `testCaseId`, `historyId`) are used to map test results to tests, so changes in them may cause problems in your workflow.

Below we discuss how each identifier is calculated and what it affects.

### fullName

`fullName` identifies [test cases](/docs/how-it-works-glossary/#test-case) and is always calculated according to two rules:

- it must be unique for every test case in the project
- for parametrized test cases it must not depend on parameter values

It is typically a text string consisting of concatenated test metadata supplied by the testing framework (for example, package name + class + test name).

Different Allure integrations use different metadata combinations to define `fullName`. Refer to the [section](#test-identifiers-reference-by-framework) for details.

`fullName` is the identifier used by Allure reporting tools to do selective test runs:

- Allure Report 3 uses `fullName` to identify which test cases to rerun.

Tip: Example

You run your JavaScript tests like so: `npx allure run --rerun 5 -- npm test` . This means that if any test cases during the initial run fail, you want Allure to rerun them up to 5 times. When the first run completes, Allure collects the `fullName` identifiers of failed tests and creates a test plan with these identifiers, which it then reruns.

- Allure TestOps uses `fullName` to run selected test cases from a test plan or launch.

- You can also create a test plan file with `fullName` identifiers manually and run it via your test framework’s Allure integration.

In all three cases, you may encounter problems, if the `fullName` identifier of some test changes, that is, if you refactor your code (for example, rename some test functions, rearrange test files), between the moment the identifier was saved into a test plan, and the moment the test plan is used to rerun tests.

If this is something that may happen in your project, the best practice is to assign `ALLURE_ID` labels to affected tests: if that label is present it overrides `fullName` as the main test case identifier for test plans, and it doesn’t change when you rearrange your code in any way.

Tip:
The exact semantics of how each framework calculates `fullName` are subject to change. For example, the order in which strings are concatenated, or the characters used as separators may be revised at some point to account for some edge cases in naming. That's why this document goes into detail on what metadata is included into `fullName` for different frameworks, but not into how exactly it is constructed.

And for this same reason, we advise against building any custom integrations based on the exact structure of `fullName`.

### testCaseId

`testCaseId` identifies [test cases](/docs/how-it-works-glossary/#test-case) and is usually a hash of `fullName`. In some rare cases, it is a hash derived from similarly unique test metadata. These cases will be discussed below.

Like `fullName`, `testCaseId` is an opaque identifier not meant to be human readable.

In most cases, code refactoring that causes `fullName` for a test case to change will also change `testCaseId`.

Currently, it is only used to map test results to test cases in the Allure TesOps database, but in future releases its use will expand.

Tip:

Unlike `fullName`, you can set `testCaseId` via API in some framework integrations. For details, please consult the framework-specific documentation.

### historyId

`historyId` identifies individual [tests](/docs/how-it-works-glossary/#test).

It is used to track the history of test results for a specific test with the same set of parameters (and display previous results on the [History and Retries](/docs/history-and-retries/) tabs in Allure Report).

- it depends on all of the same metadata that `fullName` and `testCaseId` depend on
- unlike `fullName` and `testCaseId`, it additionally depends on parameters.

Where a parametrized test case will have the same `fullName` and `testCaseId` across all parameter variations, `historyId` will be unique for every unique combination of parameters. And whenever a test parameter value changes, `historyId` for this test changes as well.

Tip:
If you don't want a certain parameter to affect `historyId`, mark that parameter as excluded. Most Allure integrations support this feature.

### uuid

`uuid` is unique for each test result, but is not reused across different runs in any way. No existing integration relies, and no custom integration should rely on it to map results to tests, so it won't be discussed further in this document.

# Test Identifiers Reference by Framework

This section documents what the identifiers depend on for each framework integration.

Its purpose is to show what changes to a given test during refactoring may break the test history and/or its association with the test case.

If you find that some refactoring you're planning will affect the identifiers, you can set `testCaseId`/`historyId` to a previous value manually (this is supported by many integrations) and avoid these consequences.

## C#

### NUnit

- `fullName` and `testCaseId` depend on:
  - assembly name
  - class name (including namespace and generic type arguments)
  - test fixture arguments (if any)
  - method signature (name + generic arguments + parameter types)
- `historyId` depends on:
  - `fullName`
  - parameters ordered by name

### xUnit

- `fullName` and `testCaseId` depend on:
  - assembly name
  - class name (including namespace)
  - method signature (name + generic arguments + parameter types)
- `historyId` depends on:
  - `fullName`
  - parameters ordered by name

### Reqnroll/SpecFlow

- `fullName` and `testCaseId` depend on:
  - assembly name
  - folder path containing the feature file
  - `featureInfo.Title` - the feature title
  - `scenarioTitle` - the scenario name
- `historyId` depends on:
  - `fullName`
  - parameters ordered by name

## Java

Warning:
Allure Java 3.0 changed how test identifiers are calculated. After upgrading from Allure Java 2.x, the history of some tests starts over, and parameters added at runtime now affect the identifiers. The rules below describe Allure Java 2.x. See [Migrating to Allure Java 3](/docs/guides/allure-java-3-migration/#test-history) for details.

### Cucumber JVM

- `fullName` depends on:
  - feature file path (relative)
  - scenario line number
- `testCaseId` depends on:
  - feature file path
  - original scenario name
- `historyId` depends on:
  - feature file path (relative)
  - scenario line number

### JBehave

- `fullName` depends on:
  - story name
  - scenario title
- `testCaseId` is not set
- `historyId` depends on `fullName` and non-excluded parameter names and values

### JUnit4

- `fullName` depends on:
  - package name
  - class name
  - method name
  - for Cucumber tests via JUnit4: feature file path or URI
- `testCaseId` is not set
- `historyId` depends on `fullName` and non-excluded parameter names and values

### JUnit5

- `fullName` depends on:
  - methodSource: package, class name, method name
  - classSource: package, class name
  - classpathResourceSource: Resource path, optional line number
- `testCaseId` equals JUnit Platform's uniqueId (stable for test templates)
- `historyId` is a hash of JUnit Platform's `uniqueId`

### Karate

- `fullName` depends on:
  - feature file path (relative)
  - scenario line number
- `testCaseId` depends on:
  - feature file path
  - scenario name (or line number as fallback)
- `historyId` depends on:
  - Cucumber's internal scenario unique ID

### Scalatest

- `fullName`, `testCaseId` and `historyId` all depend on:
  - suite ID
  - test name

### Spock

- `fullName` depends on:
  - package name
  - class name
  - feature/test name (or iteration name for parameterized tests)
- `testCaseId` (Spock 2 only) depends on:
  - `fullName`
- `historyId` depends on:
  - qualified name
  - parameter names and values (sorted)

### TestNG

- `fullName` depends on:
  - fully qualified class name (includes package)
  - test method name
- `testCaseId` is not set
- `historyId` depends on `fullName` and non-excluded parameter names and values

## JavaScript

### Cucumber-JS

- `fullName` and `testCaseId` depend on:
  - relative path to the feature file
  - scenario name (as processed by Cucumber)
- `historyId` depends on `fullName` and non-excluded parameter names and values

### Cypress

- `fullName` and `testCaseId` depend on:

  - spec file path
  - array of suite names + test name

  **Excluded:**

  - metadata tags/annotations embedded in test names

- `historyId` depends on `fullName` and non-excluded parameter names and values

### Jasmine

- `fullName` and `testCaseId` depend on:

  - spec file path (relative, optional - can be undefined)
  - array of suite names + test name
  - the spec/test name

  **Excluded:**

  - metadata tags/annotations embedded in spec name

- `historyId` depends on `fullName` and non-excluded parameter names and values

### Jest

- `fullName` and `testCaseId` depend on:

  - test name
  - all parent `describe` block names (suite hierarchy)

  **Excluded:**

  - ROOT_DESCRIBE_BLOCK (Jest's internal root node)

- `historyId` depends on `fullName` and non-excluded parameter names and values

### Mocha / CodeceptJS

CodeceptJS uses Mocha under the hood, so identifier calculation rules are the same.

- `fullName` and `testCaseId` depend on:
  - relative path to the test file
  - suite names
  - test name
- `historyId` depends on `fullName` and non-excluded parameter names and values

### Newman

- `fullName`, `testCaseId` and `historyId` all depend on:
  - array of parent collection/folder names
  - request/test name

### Playwright

- `fullName` depends on:
  - test file path
  - line number of the test function in the source file
  - column number of the test function in the source file
- `testCaseId` depends on:
  - test file path
  - suite hierarchy
  - test name
- `historyId` depends on `testCaseId` and non-excluded parameter names and values

### Vitest

- `fullName` and `testCaseId` depend on:

  - test file path (via `relativeTestPath`)
  - array of parent suite names
  - test name

  **Excluded:**

  - metadata tags/annotations embedded in spec name

- `historyId` depends on `fullName` and non-excluded parameter names and values

### WebdriverIO (WDIO)

- `fullName` and `testCaseId` depend on:
  - test file path (relative, URL-aware, strips position suffixes)
  - test title
- `historyId` depends on `fullName` and non-excluded parameter names and values

## PHP

### Behat

- `fullName` depends on:
  - regular scenario: Feature title, scenario title
  - scenario outline: Feature file path, line number
- `testCaseId`:
  - not automatically set
  - can be manually provided via `@TestCaseId` annotations (stored as label)
- `historyId` is not set

### Codeception

- `fullName` depends on:
  - Cept: Codeception's built-in signature
  - Cest: Namespace, class name, method name
  - Unit: Namespace, class name, test name
  - Gherkin: Not set
- `testCaseId` depends on:
  - Codeception's built-in signature
  - parameter names

### PHPUnit

- `fullName` depends on:
  - fully qualified class name
  - test method name
- `testCaseId` depends on:
  - `fullName`
  - non-excluded parameter names
- `historyId` depends on:
  - `testCaseId`
  - test method name
  - parameter names and values

## Python

### Behave

- `fullName` and `testCaseId` depend on:

  - feature name
  - scenario name

  **Excluded:**

  - parameters (just the base scenario name used)

- `historyId` depends on:
  - feature name
  - scenario name
  - scenario outline parameters (the `Examples` section)

### pytest

- `fullName` and `testCaseId` depend on:

  - package/module path
  - test function name
  - class names - only if test is inside a class (can be nested classes)

  **Excluded:**

  - parameters

- `historyId` depends on:
  - `fullName`
  - parameters ordered by name:
    - pytest parameters (e.g., provided via `@pytest.mark.parametrize`)
    - Allure parameters (except those with `excluded` set to `True`)

### pytest-bdd

- `fullName` and `testCaseId` depend on:

  - relative file path to the `.feature` file
  - scenario name

  **Excluded:**

  - feature name (uses file path instead)
  - parameters (just the base scenario name)

- `historyId` depends on:
  - `testCaseId`
  - parameters ordered by name:
    - pytest parameters (e.g., provided via `@pytest.mark.parametrize`)
    - scenario outline parameters from the `Examples` section of the Gherkin file
    - Allure parameters (except when `excluded` is set to `False`)

### Robot Framework

- `fullName` equals Robot Framework's built-in `longname` attribute, which is the full hierarchical test name (suite path + test name)
- `testCaseId` depends on:
  - suite names
  - test case name
- `historyId` is a hash of `longname`

## Ruby

### Cucumber

- `fullName` equals built-in `scenario.name`
- `testCaseId`:
  - not automatically set
  - can be manually provided via `allure_id` labels for test plan support
- `historyId` equals built-in `scenario.id`

### RSpec

- `fullName` equals `example.full_description` (RSpec's built-in property that includes the nested describe block descriptions plus the test description)
- `testCaseId`:
  - not automatically set
  - can be manually provided via `allure_id` labels for test plan support
- `historyId` depends on RSpec’s built-in `example.id` and parameter names and values
