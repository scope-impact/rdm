---
id: RMF-001
title: "Risk register — git/GitHub document control"
kind: risk
status: proposed
references: [RMP-001, SOP-DC-001]
risks:
  - id: RISK-DC-001
    category: safety
    status: proposed
    hazard: "An unapproved change reaches the controlled document set."
    situation: "An author merges a procedure change without a current, independent code-owner approval, or pushes to the default branch directly."
    harm: "Staff follow a procedure no authorized signer approved, and product is built or released from wrong instructions."
    severity: Serious
    probability: Possible
    controls: [DI-1, DI-9]
    residual: {probability: Rare}
  - id: RISK-DC-002
    category: security
    stride: Tampering
    linked: [RISK-DC-001]
    status: proposed
    hazard: "The approval path is weakened on the service provider, outside the reviewed configuration."
    situation: "An administrator disables the ruleset or adds a bypass actor in GitHub's settings, merges a change, and restores the setting."
    harm: "An unapproved change reaches the controlled set while the checked-in configuration still reads as enforced."
    severity: Serious
    probability: Unlikely
    controls: [DI-11, DI-6]
    residual: {probability: Rare}
  - id: RISK-DC-003
    category: security
    stride: Repudiation
    status: proposed
    hazard: "An approval cannot be tied to the revision it approved."
    situation: "A pull request is squashed or rebased on merge, so the commit in history is not the commit the approver reviewed."
    harm: "An approver can deny signing the released revision, and an audit finds the signature not linked to its record (§11.70)."
    severity: Serious
    probability: Possible
    controls: [DI-6, DI-1]
    residual: {probability: Rare}
  - id: RISK-DC-004
    category: safety
    status: proposed
    hazard: "A release is published from a commit whose verification failed."
    situation: "A release tag is pushed on a commit where a design input's test fails or never ran."
    harm: "A document set whose controls are not verified is distributed as released, and staff rely on it."
    severity: Serious
    probability: Possible
    controls: [DI-10]
    residual: {probability: Rare}
  - id: RISK-DC-005
    category: safety
    status: proposed
    hazard: "A released copy differs from the approved content."
    situation: "The rendered copies or the archive are produced from a revision other than the tagged one, or the release records no revision."
    harm: "Users work from a copy that no one approved, and no record shows which revision it was."
    severity: Serious
    probability: Unlikely
    controls: [DI-3, DI-8]
    residual: {probability: Rare}
  - id: RISK-DC-006
    category: security
    stride: Spoofing
    linked: [RISK-DC-001]
    status: proposed
    hazard: "A change is approved under another person's account."
    situation: "An approver's GitHub account is compromised and used to approve a change."
    harm: "An unapproved change reaches the controlled set under a genuine signer's name."
    severity: Serious
    probability: Possible
    controls: [DI-1]
    residual: {probability: Unlikely}
    acceptance:
      by: "the quality owner, by approving the pull request that records this register"
      rationale: "The ruleset limits what one account can do (the last pusher cannot approve, commits must be signed), but account security itself (SSO, 2FA, one account per person, §11.100) is the organization's identity controls, outside this system and its tests. Monitored through the identity provider's audit log."
---

# Risk register

**Proposed, not yet approved.** These ratings, and the acceptance of
RISK-DC-006, are a proposal written for this example; the quality owner
approves each by reviewing it and setting `status: approved` (or changing it).

The risks are the ways this document control system could let an unapproved,
unverified or misidentified document reach the people who use it. Each risk
control is a design input of this record, verified by its tagged test; the
release gate holds the register to RDM's rules: every risk scored against the
policy (RMP-001), every control a verified design input, and every residual
acceptable or accepted with a reason.

The two security threats that lead to the same harm as RISK-DC-001 are linked
to it. RISK-DC-006 stays at Medium after its control and is accepted above.
