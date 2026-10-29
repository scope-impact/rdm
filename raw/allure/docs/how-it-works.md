---
title: How it works
description: Understand how Allure Report works, from test execution to report generation. Learn about result files, history data, and how to configure and share reports.
---

# How it works

Allure Report consists of a framework integration and the `allure` command-line utility. After a simple initial configuration, creating a test report for your project takes no more than three steps.

![How Allure Report works, Allure Results, additional data, history data, serve vs generate](/images/diagrams/how-it-works.png "How Allure Report works, Allure Results, additional data, history data, serve vs generate")

## 1. Data Collection During Test Execution

When you run tests normally - whether from command line, IDE, or CI pipeline - Allure captures detailed execution data through framework-specific integrations. These integrations hook into your testing [framework's lifecycle](/docs/frameworks/) naturally.

The integration saves structured data files to a results directory (`allure-results` by default), building a complete record of test execution including steps, timings, attachments, and metadata

Tip:
Multiple test runs can accumulate data into the same results directory, making it easy to aggregate results from different environments, configurations, or test suites into a single **launch** that will become a report.

## 2. Metadata Enrichment

Optionally, you can enrich your results with additional context through supplementary files, such as:

- **Environment data** to capture global test conditions and configurations
- **CI integration details** to link reports back to specific builds or jobs
- **Historical data** to enable trend analysis and stability tracking
- **Custom categorization rules** to automatically classify and group test failures

## 3. Report Generation

Allure's report generator processes the collected data, analyzes test results, applies categorization rules, calculates trends, and builds an interactive HTML report. Static generation (`allure generate`) creates a portable HTML report that you can archive, share, or host anywhere, while local preview (`allure serve` in Allure 2 or `allure open` in Allure 3) sets up a local server and displays the report in your browser.

The generator handles data aggregation across multiple test runs, automatically detects retries, and creates visual analytics.

To share the report, you can use the following approaches.

- You may download and archive your report, then send it via file hosting, email, Slack, or other available method.
- If you have integrated the report with a CI tool, you may share a link to the job results page that embeds the report.
- You may host the report on a web server and share the link to it.
- In the following cases, we recommend considering [Allure TestOps](https://qameta.io/):
  - If the report needs to be available 24/7 in a centralized and secure way,
  - If the report needs to be provided to non-technical team members or management who do not have access to the CI tool,
  - If the archive is too large and difficult to send.
