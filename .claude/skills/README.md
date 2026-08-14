# Skills for this repo live in scope-impact/agent-skills

`backlog`, `story-audit`, `test-faithfulness` and `usability-persona` used to be vendored here. They now live in [scope-impact/agent-skills](https://github.com/scope-impact/agent-skills), which is their single home — this directory intentionally holds no copies.

Copies in both places diverged silently for four months (rdm's `story-audit` reached 568 lines against agent-skills' 217, from a common ancestor) because nothing detected drift. One home removes the failure mode rather than trying to police it.

## Getting them

```bash
claude plugin marketplace add scope-impact/agent-skills
claude plugin install scope-impact-skills@scope-impact
```

Or copy a single skill directory into this folder — each one is self-contained. Don't commit it back: an uncommitted local copy is a convenience, a committed one restarts the divergence.

## The boundary

Ownership of a *skill* is separate from authority over a *tool*. **This repo remains authoritative for how the `rdm` CLI behaves.** A skill that drives `rdm` reads `rdm <command> --help` and this repo's docs rather than restating flags and exit codes — restating them is precisely what drifted.

So a change to `rdm story`'s interface needs no edit to any skill here; it needs the skills to keep reading `--help`. Where a skill has cached something it should have looked up, fix it in agent-skills.
