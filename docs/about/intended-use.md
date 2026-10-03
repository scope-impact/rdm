# Intended use

## Intended purpose

RDM keeps the design record of regulated software in git and checks it. A
team uses it to:

- declare user needs, design inputs, risks and their controls as Markdown
  in its repository;
- tie each design input to the acceptance test that verifies it, and each
  test run to the commit it tested;
- stop implementation before its design is approved, and a release before
  every design input is verified and every risk controlled (the gates);
- render the regulatory documents from the record, and keep the release
  evidence with each release;
- query the whole record as one read-only graph, by person or by agent.

It is written for medical-device software under IEC 62304 first, with risk
management under ISO 14971 and health software under IEC 82304-1; its
checklists also cover 21 CFR Part 11 and ISO 13485 document control.

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

A team that uses RDM within its quality system validates it for that use
(ISO 13485 §4.1.6). RDM's own design history file, with its verification
evidence, is published with this manual to help.
