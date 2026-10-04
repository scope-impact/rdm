# Intended use

## Intended purpose

RDM keeps the design record of regulated software in git and checks it. A
team uses it to:

- declare user needs, design inputs, risks and their controls as Markdown
  in its repository;
- tie each design input to the acceptance test that verifies it, and each
  test run to the commit it tested;
- stop implementation before its design is approved, and a release before
  every design input is verified and every risk control verified with an
  acceptable residual (the gates);
- keep its architecture as C4 diagrams in the record, and be warned where
  the code, the tests and the diagrams disagree;
- render the regulatory documents from the record, and keep the release
  evidence with each release;
- query the whole record as one read-only graph, by person or by agent.

It is written for medical-device software under IEC 62304 first, with risk
management under ISO 14971. Its built-in checklists cover IEC 62304 (2006,
2015 and the base text, per safety class), ISO 14971 (2007, 2019), FDA
software, cybersecurity and human-factors guidance, and 21 CFR Part 11
document control (`rdm gap --list`); other standards need a checklist you
write.

## Intended users

| User | Uses RDM to |
|---|---|
| Regulatory author, quality engineer | write the record, render documents, read the gates and the graph |
| Software engineer | declare design inputs, tag acceptance tests, run the gates |
| Reviewer | approve each change by reviewing its pull request, helped by the trace and the mutation probe |
| AI agent, working under a person's direction | read the record through the read-only agent server, and propose changes as pull requests |

Users know git and pull requests, Markdown, and how to run a test suite.
None of them needs to know RDF or SPARQL to use the gates.

## Use environment

- A git repository on a forge with pull-request review and CI (GitHub
  first: RDM ships a reusable workflow and actions for it).
- A workstation running Linux, macOS, or Windows with Git Bash or WSL.
- Docker, or Pandoc and Typst, to render PDFs.

## What RDM is not

- **Not a medical device,** and not a quality management system on its
  own. It keeps one part of a team's evidence straight.
- **Not a judge of evidence.** It shows that a design input has a passing
  tagged test; whether the test proves the input is the reviewer's call,
  and whether the evidence suffices is a regulator's.
- **Not a substitute for summative usability validation** with
  representative users; its persona runs are formative only.

## Validating RDM for your use

RDM is software in your quality system, so you validate it for the way you
use it (ISO 13485 §4.1.6), in proportion to the risk of that use:

1. **State your use.** Which gates you rely on, for which decisions, and
   which outputs (documents, verification report, evidence bundle) you put
   in your records.
2. **Assess the risk of that use.** Start from RDM's own residual risks
   ([Safety and limitations](safety.md)) and add any your use introduces.
3. **Reuse RDM's evidence.** Each release's design inputs are verified by
   tagged tests; its design history file and traceability are published
   beside this manual (the *Design history* tab). Read what covers your use.
4. **Check it on your record.** Run the gates on a copy of your record with
   a known defect (a failing test, an unapproved design document) and confirm
   they block it; `rdm story mutation-probe` checks your own tests the same way.
5. **Record the result** in your quality system, and repeat steps 2 to 5 when
   you upgrade RDM, change how you use it, or add an extra.
