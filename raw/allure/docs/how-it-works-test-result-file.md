---
title: Test result file
description: Explore the format of Allure's result.json file for storing test results, including identifiers, metadata, execution details, and step information.
---

# Test result file

Warning:
This page describes a data format used by Allure and its integrations for test frameworks. Understanding this format is **not necessary** for using Allure in your project. For a more high-level description of the process of building a report, see [How it works](/docs/how-it-works/).

A test result file stores data related to a single run of a single test.

The data is stored as a JSON object. Each file is named `{uuid}-result.json`, where `{uuid}` is a randomly generated identifier.

This article describes the properties that can be found in the JSON object. Some of these properties can also be found in the objects describing test steps and sub-steps (see the [steps](#steps-array) section).

Example:

```json
{
  "uuid": "9d95e6e7-9cf6-4ca5-91b4-9b69ce0971f8",
  "historyId": "2b35e31882061875031701ba05a3cd67",
  "testCaseId": "43f8868a367ff70177a99838d39c5b33",
  "fullName": "com.example.web.essentials.AuthenticationTest.testAuthentication",
  "name": "testAuthentication()",
  "links": [
    {
      "type": "link",
      "name": "Allure Examples",
      "url": "https://examples.com/"
    },
    {
      "type": "issue",
      "name": "BUG-123",
      "url": "https://bugs.example.com/BUG-123"
    }
  ],
  "labels": [
    {
      "name": "host",
      "value": "machine-1"
    },
    {
      "name": "thread",
      "value": "306681-MainThread"
    },
    {
      "name": "language",
      "value": "java"
    },
    {
      "name": "framework",
      "value": "junit-platform"
    },
    {
      "name": "epic",
      "value": "Web interface"
    },
    {
      "name": "feature",
      "value": "Essential features"
    },
    {
      "name": "story",
      "value": "Authentication"
    }
  ],
  "status": "passed",
  "start": 1682358426014,
  "stop": 1682358426014,
  "steps": [
    {
      "name": "Step 1",
      "status": "passed",
      "start": 1682358426014,
      "stop": 1682358426014
    },
    {
      "name": "Step 2",
      "status": "passed",
      "start": 1682358426014,
      "stop": 1682358426014
    }
  ]
}
```

## Identifiers

### uuid (string)

A unique identifier of the test result.

### historyId (string)

An identifier used by Allure Report. Two runs of the same test with the same set of parameters will always have the same `historyId`. Refer to the [`historyId` section](/docs/how-it-works-test-identifiers/#historyid) in the test identifiers reference to find out how this identifier works.

### testCaseId (string)

An identifier used by Allure TestOps. Two runs of the same test will always have the same `testCaseId`. Refer to the [`testCaseId` section](/docs/how-it-works-test-identifiers/#testsaseid) in the test identifiers reference to find out how this identifier works.

## Metadata

### name (string)

The [title](/docs/v3/readability/#title) of the test or the name of the step.

### fullName (string)

A unique identifier based on the file name and the test name. Different Allure plugins can use different notations to generate this identifier. Refer to the [`fullName` section](/docs/how-it-works-test-identifiers/#fullname) in the test identifiers reference to find out how this identifier works.

### description (string)

The [description](/docs/v3/readability/#description) of the test or step in Markdown format.

### descriptionHtml (string)

The [description](/docs/v3/readability/#description) of the test or step in HTML format.

### links (array)

An array of [links](/docs/v3/readability/#links) added to the test or step.

Each object in the array has the following properties:

- `name` (string) — the link's name that will be displayed in the test report,
- `url` (string) — the full URL of the link,
- `type` (string) — the type of the link, e.g., “issue” or “tms”.

### labels (array)

An array of various labels added to the test or step.

Each object in the array has the following properties:

- `name` (string) — the name of the label.
- `value` (string) — the value of the label.

A test result can have multiple labels with the same name. For example, this is often the case when a test result has multiple tags.

Many metadata fields and navigation-related fields are represented in the file as labels:

| Label name    | Link to the field description                              |
| ------------- | ---------------------------------------------------------- |
| `tag`         | [Tags](/docs/v3/readability/#tags)                         |
| `severity`    | [Severity](/docs/v3/readability/#severity)                 |
| `owner`       | [Owner](/docs/v3/readability/#owner)                       |
| `epic`        | [Epic](/docs/v3/navigation/#behavior-based-hierarchy)      |
| `feature`     | [Feature](/docs/v3/navigation/#behavior-based-hierarchy)   |
| `story`       | [Story](/docs/v3/navigation/#behavior-based-hierarchy)     |
| `parentSuite` | [Parent suite](/docs/v3/navigation/#suite-based-hierarchy) |
| `suite`       | [Suite](/docs/v3/navigation/#suite-based-hierarchy)        |
| `subSuite`    | [Sub-suite](/docs/v3/navigation/#suite-based-hierarchy)    |
| `package`     | [Package](/docs/v3/navigation/#package-based-hierarchy)    |

Additionally, most Allure integrations set the following labels for every test:

| Label name  | Description                                             |
| ----------- | ------------------------------------------------------- |
| `host`      | Hostname of the machine on which the test was run.      |
| `thread`    | The identifier of the thread in which the test was run. |
| `language`  | The programming language of the test.                   |
| `framework` | The test framework of the test.                         |

The test author can set additional labels with arbitrary names, see [Other labels](/docs/v3/readability/#other-labels).

### parameters (array)

An array of [parameters](/docs/v3/readability/#parametrized-tests) added to the test or step.

Each object in the array has the following properties:

- `name` (string) — the name of the parameter.
- `value` (string) — the value of the parameter.
- `excluded` (boolean) — if true, Allure will not use the parameter when comparing the current result with the previous one in the history.
- `mode` (string) — how the parameter will be shown in the report:
  - `default` — the parameter and its value will be shown in a table along with other parameters.
  - `masked` — the parameter will be shown in the table, but its value will be hidden.
  - `hidden` — the parameter and its value will not be shown in the test report.

### attachments (array)

An array of [attachments](/docs/attachments/) added to the test or step.

The content of each attachment is stored as a file in the results directory. The file is named `{uuid}-attachment.{ext}`, where `{uuid}` is a randomly generated identifier, and `{ext}` is the filename extension.

Each object in the array has the following properties:

- `name` (string) — the human-readable name of the attachment.
- `source` (string) — the name of the file with the attachment's content.
- `type` (string) — the [media type](https://en.wikipedia.org/wiki/Media_type) of the content.

## Execution

### status (string)

The status with which the test or step finished. Possible values are: “failed”, “broken”, “passed”, “skipped”, “unknown”. See [Test statuses](/docs/test-statuses/).

### statusDetails (object)

An object having the following properties:

- `known` (boolean) — indicates that the test fails because of a known bug.
- `muted` (boolean) — indicates that the result must not affect the statistics. See [Visual analytics](/docs/visual-analytics/).
- `flaky` (boolean) — indicates that this test or step is known to be unstable and can may not succeed every time. See [Flaky tests](/docs/test-stability/#flaky-tests).
- `message` (string) — the short text message to display in the test details, such as a name of the exception that led to a failure.
- `trace` (string) — the full stack trace to display in the test details.

### stage (string)

The stage in the lifecycle of the test or step.

Possible values are: “scheduled”, “running”, “finished”, “pending”, “interrupted”.

### start (integer)

The time when the execution of the test ot step started, in the [UNIX timestamp](https://en.wikipedia.org/wiki/Unix_time) format.

Together with [`stop`](#stop-integer), this field is used to place the test on the [Timeline tab](/docs/timeline/) and to calculate its [duration](/docs/visual-analytics/#duration-charts).

### stop (integer)

The time when the execution of the test ot step finished, in the [UNIX timestamp](https://en.wikipedia.org/wiki/Unix_time) format.

Together with [`start`](#start-integer), this field is used to place the test on the [Timeline tab](/docs/timeline/) and to calculate its [duration](/docs/visual-analytics/#duration-charts).

### steps (array)

An array of [test steps](/docs/steps/).

Each step may contain a array of `steps`, too, which allows to represent a nested array of steps and sub-steps.

Objects in the array have the following properties, each having the same format as the corresponding property of a test result:

- Metadata
  - [`name`](#name-string)
  - [`parameters`](#parameters-array)
  - [`attachments`](#attachments-array)
- Execution
  - [`status`](#status-string)
  - [`statusDetails`](#statusdetails-object)
  - [`stage`](#stage-string)
  - [`start`](#start-integer)
  - [`stop`](#stop-integer)
  - [`steps`](#steps-array)
