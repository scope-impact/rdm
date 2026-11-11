# What RDM is for

RDM audits the record of regulated software — medical-device software
first — next to the code, in git, on every commit. The spec is Gherkin,
written by the team or by a factory that regenerates code from it; RDM reads
it. With it a team:

- holds the spec (tagged Gherkin scenarios as requirements, their Features as
  user needs) to its tests and runs;
- keeps its risks and their controls as Markdown in the repository;
- tags the acceptance test that verifies each requirement, and keeps each
  test run tied to the commit it tested;
- blocks a release while any requirement is unverified or any risk
  control fails (the release gate);
- checks its documents against the standards' checklists (gap analysis);
- renders the regulatory documents from the record, and keeps each
  release's evidence with it;
- queries the whole record as one read-only graph, by person or by agent.

It ships checklists for the standards medical-device software is usually
held to — IEC 62304, ISO 14971, FDA software, cybersecurity and
human-factors guidance, 21 CFR Part 11 document control (`rdm gap --list`) —
and you can write your own.

## Who uses it

| You are | You use RDM to |
|---|---|
| a developer | tag acceptance tests with the requirements they verify, run the gates |
| a reviewer | approve each change in its pull request, helped by `trace` and the verification report |
| a regulatory or quality person | keep risks and documents, render documents, read the gates and the graph |
| an AI agent, working for one of the above | read the record through the read-only agent server, and propose changes as pull requests |

You need git and pull requests, Markdown, and a test suite you can run. You
do not need RDF or SPARQL to use the gates.

## Where it runs

- In a git repository on a forge with pull-request review and CI. GitHub is
  supported out of the box (a reusable workflow and actions).
- On Linux, macOS, or Windows with Git Bash or WSL.
- With Docker, or Pandoc and Typst, if you render PDFs.

## What RDM is not

- **Not a medical device, and not a quality system on its own.** It keeps
  one part of your evidence straight.
- **Not an author of your spec.** It reads the Gherkin and never assigns
  ids or edits it.
- **Not a judge of your evidence.** It shows that each requirement has a
  passing tagged test. Whether that test proves the requirement is your
  reviewer's call; whether the evidence is enough is your regulator's.

## Using RDM in a regulated quality system

If RDM's results go into your quality records, you need to show it works
for the way you use it. Keep it proportionate:

1. **Write down your use:** which gates you rely on, for which decisions,
   and which outputs (documents, verification report, evidence bundle) you
   keep.
2. **Look at what could go wrong:** start from RDM's own
   [known risks](safety.md#known-risks-and-what-you-do-about-them), and add
   any your use brings.
3. **Reuse RDM's own evidence:** every release's requirements are verified
   by tagged tests, and its design history is published beside these docs
   (the *Design history* tab).
4. **Try it on your record:** run the gates on a copy with a known defect
   (a failing test, an unreadable result) and check they block it.
5. **Keep the result,** and do steps 2 to 5 again when you upgrade RDM,
   change how you use it, or add an extra.
