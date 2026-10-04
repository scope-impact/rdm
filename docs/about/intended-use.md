# What RDM is for

RDM keeps the design record of regulated software — medical-device software
first — next to the code, in git, and checks it on every commit. With it a
team:

- writes user needs, design inputs (the requirements), risks and their
  controls as Markdown in the repository;
- tags the acceptance test that verifies each design input, and keeps each
  test run tied to the commit it tested;
- blocks code that lands before its design is approved, and a release while
  any design input is unverified or any risk control fails (the gates);
- keeps its architecture as C4 diagrams in the repository, and is warned
  where the code, the tests and the diagrams disagree;
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
| a developer | declare design inputs, tag acceptance tests, run the gates |
| a reviewer | approve each change in its pull request, helped by `trace` and the mutation probe |
| a regulatory or quality person | write the record, render documents, read the gates and the graph |
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
- **Not a judge of your evidence.** It shows that each design input has a
  passing tagged test. Whether that test proves the input is your
  reviewer's call; whether the evidence is enough is your regulator's.
- **Not usability testing.** Its persona runs find problems early; they do
  not replace testing with real users.

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
   (a failing test, an uncommitted design document) and check they block it;
   `rdm story mutation-probe` does the same for your own tests.
5. **Keep the result,** and do steps 2 to 5 again when you upgrade RDM,
   change how you use it, or add an extra.
