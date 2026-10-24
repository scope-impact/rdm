# Step definitions

The code that runs the spec's scenarios. It survives every clean room, so it is
written against the public surface only: the `rdm-spec` command, the files it
reads and writes, and the runner's step API (`:step-api` in
`dhf/harness/config.edn`). Anything else fails ratification
(`dhf/spec/ratification/black-box.feature`).

None is written yet. They have to exist before the first regeneration can be
ratified.
