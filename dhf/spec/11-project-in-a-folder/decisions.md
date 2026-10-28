# Decisions: slice 11-project-in-a-folder

What the spec owner accepted from a cycle's builder decisions
(`dhf/cycles/NN/decisions.yaml`), and every divergence from this slice's plan,
each with the branch it pruned (ADR 0005), and why each rule of the slice
exists. The feature files say only what is true.

None yet.

## Why the rules are as they are

Moved from the comments of this slice's feature files (spec-34): the
reason each rule or scenario exists, and the incident that taught it.

### `command.feature`, Rule: A project may be one folder of a larger repository

A task runs its stages in order and every stage reports, so one run names
every finding. A gate is the exception: a stage the task table marks as a
gate stops the task when it has findings, and the stages after it never
run. The plugins answering :Gate are the gate before fence and verify, the
oracle among them (dhf/spec/08-plugins/plugins.feature; dhf/spec/measurement/
oracle.feature, spec-0232). A gate replaces an event the gated stages would
each have had to interpret (cycle 04).
Cycles 01 and 02 of halla-health's wallet (spec-27): the wallet is one
folder of a monorepo, and fence and verify read a cut by paths from the
repository's root, so they found no configuration at the cut; the cycles
ran on a copy of the folder as a repository of its own.

### `verify.feature`, Scenario: The room's implementation reaches the checkout as git would commit it (spec-0370)

Cycle 01 of halla-health's wallet (spec-27): verify copied the room's
node_modules, which .gitignore excludes, into the checkout, its links
copied as files, and the install in the checkout broke.

