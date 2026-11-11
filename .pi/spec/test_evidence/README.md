# Test evidence

Slice 03. Executed Allure results read as test runs, and what an acceptance run records
about the version it tested and who ran it.

- `spec.feature`: the accepted scenarios (`@item-03-001` to `@item-03-003`).
- `allium/test-evidence.allium`: entities TestRun, Step, Attachment and Executor; rules
  ReadAllureResult, LabelRun and RecordRunEnvironment. No surface and no screen: this
  context is home to no need; its requirements address `release/trace-verification`
  through guarantees on release's TraceVerification surface.
