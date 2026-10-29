---
title: Container file
description: Learn the structure and purpose of the container.json file in Allure, describing test fixtures and initialization/finalization steps for test results.
---

# Container file

Warning:
This page describes a data format used by Allure and its integrations for test frameworks. Understanding this format is **not necessary** for using Allure in your project. For a more high-level description of the process of building a report, see [How it works](/docs/how-it-works/).

A container file describes a set of initialization and finalization steps related to certain tests. It is used by the test frameworks that implement the concept of **test fixtures**, with one container normally describing one fixture. The container may refer to multiple [test results](/docs/how-it-works-test-result-file/) via the [children](#children-array) property, thus adding the initialization and finalization steps to each of them in the test report.

The data is stored as a JSON object. Each file is named `{uuid}-container.json`, where `{uuid}` is a randomly generated identifier. This article describes the properties that can be found in the JSON object.

Example:

```json
{
  "uuid": "987b1a5d-fbab-4707-acff-ddfd41f9e511",
  "start": 1682502818632,
  "stop": 1682502818712,
  "children": [
    "f82da25c-3565-4eb4-a1db-4a502fe35eea",
    "fc9e5cee-f4af-475c-94f2-5902ee7c9d2d",
    "e3592f29-c828-4816-8d3d-3759c6663b27"
  ],
  "befores": [
    {
      "name": "Create temporary directory",
      "status": "passed",
      "start": 1682502818632,
      "stop": 1682502818632
    },
    {
      "name": "Create temporary files",
      "status": "passed",
      "start": 1682502818632,
      "stop": 1682502818632
    }
  ],
  "afters": [
    {
      "name": "Remove temporary files",
      "status": "passed",
      "start": 1682502818712,
      "stop": 1682502818712
    },
    {
      "name": "Remove temporary directory",
      "status": "passed",
      "start": 1682502818712,
      "stop": 1682502818712
    }
  ]
}
```

### uuid (string)

A unique identifier of the test result.

### start (integer)

The time when the execution of the initialization steps started, in the [UNIX timestamp](https://en.wikipedia.org/wiki/Unix_time) format. Normally, it is equal or close to the earliest `start` time from the [`befores`](#befores-array).

### stop (integer)

The time when the execution of the finalization steps finished, in the [UNIX timestamp](https://en.wikipedia.org/wiki/Unix_time) format. Normally, it is equal or close to the latest `stop` time from the [`afters`](#afters-array).

### children (array)

The [`uuid`](/docs/how-it-works-test-result-file/#uuid-string) values of the test results for which this fixture was added.

### befores (array)

An array of initialization steps that were executed before the tests. Note that the test report does not necessarily display these steps in the same order as defined in the file.

Objects in the array have the following properties, each having the same format as the corresponding property of a test result:

- Metadata
  - [`name`](/docs/how-it-works-test-result-file/#name-string)
  - [`parameters`](/docs/how-it-works-test-result-file/#parameters-array)
  - [`attachments`](/docs/how-it-works-test-result-file/#attachments-array)
- Execution
  - [`status`](/docs/how-it-works-test-result-file/#status-string)
  - [`statusDetails`](/docs/how-it-works-test-result-file/#statusdetails-object)
  - [`stage`](/docs/how-it-works-test-result-file/#stage-string)
  - [`start`](/docs/how-it-works-test-result-file/#start-integer)
  - [`stop`](/docs/how-it-works-test-result-file/#stop-integer)
  - [`steps`](/docs/how-it-works-test-result-file/#steps-array)

### afters (array)

An array of finalization steps that were executed after the tests. Note that the test report does not necessarily display these steps in the same order as defined in the file.

Objects in the array have the following properties, each having the same format as the corresponding property of a test result:

- Metadata
  - [`name`](/docs/how-it-works-test-result-file/#name-string)
  - [`parameters`](/docs/how-it-works-test-result-file/#parameters-array)
  - [`attachments`](/docs/how-it-works-test-result-file/#attachments-array)
- Execution
  - [`status`](/docs/how-it-works-test-result-file/#status-string)
  - [`statusDetails`](/docs/how-it-works-test-result-file/#statusdetails-object)
  - [`stage`](/docs/how-it-works-test-result-file/#stage-string)
  - [`start`](/docs/how-it-works-test-result-file/#start-integer)
  - [`stop`](/docs/how-it-works-test-result-file/#stop-integer)
  - [`steps`](/docs/how-it-works-test-result-file/#steps-array)
