Feature: Test evidence
  RDM's own requirements in this context: each stamped scenario is one requirement,
  verified by the acceptance tests that claim it with @allure.tag("item-NN-SSS").

  @allium-guarantee.TraceVerification @allium-guarantee.ReleaseBlocked
  @item-03-001
  Scenario: RDM shall read Allure results so that a result it cannot read is never counted as a pass: a file that is not JSON or not an object, not UTF-8, a symbolic link, nested too deep, with a status Allure does not write or labels that are not name and value text blocks release and is named by verify; a byte-order mark is read; a run whose step failed or broke has failed; and a run tagged with an id no requirement declares is warned about.

  @allium-guarantee.TraceVerification @allium-guarantee.ReleaseBlocked
  @item-03-002
  Scenario: rdm.pytest_plugin shall label each run of a test that claims a requirement of the spec (@allure.tag("item-NN-SSS")) with the commit under test, and mark the run when the working tree had uncommitted changes.

  @allium-guarantee.TraceVerification @allium-guarantee.ReleaseEvidence
  @item-03-003
  Scenario: rdm.pytest_plugin shall record, in the Allure results of an acceptance run, who or what ran the tests and where: Allure's executor.json naming the CI system, the run and its URL, or a local run with its user and host; and environment.properties with the operating system, the Python, pytest, allure-pytest and RDM versions, the commit under test, whether the worktree had uncommitted changes (unknown when the commit is), and the CI actor, workflow, event and runner (the user, for a local run).
