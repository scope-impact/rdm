# Invariants

Rules that must hold, and **what enforces each one**. A principle without a
named enforcement point is a wish: the sentence stays in the document while
quietly ceasing to be true, and nobody notices. (Pattern borrowed from the
method's parent projects.)

Each entry: the rule, the enforcement point, and what breaks without it.

Cites: `docs/wow/way-of-working.md` (the gate, the firewall, the minimum
reference rule).

---

## INV-1: The app never invents domain rules

**Rule.** Every behavioural rule in the app traces to a page under
`docs/wiki/`, and every wiki page traces to a source under `raw/`. If a rule
cannot be traced, it does not go in; it goes back to the interview.

**Enforced by.** The minimum reference rule, checked at two gates: a slice
`plan.md` must list the wiki pages it rests on (review rejects a plan with
an empty references section), and each wiki page must cite its `raw/`
source. `raw/` itself is immutable: agents never edit it, so the chain
bottoms out in something no generation step can quietly rewrite.

**Without it.** The vibe-run failure mode returns: plausible rules
("bookings open at midnight", "one standing slot per apartment") that no
source ever stated, indistinguishable from real ones once they are in code.
This repo has already lived that failure once: its first domain corpus was
invented scaffolding plausible enough to fool a reader, and this invariant
plus the purge that followed are what removed it.

---

## INV-2: Specs never reference implementation

**Rule.** `docs/wiki/` and `docs/plan/` contain no language, framework,
database, storage, hosting, vendor, or UI-toolkit names. This is the
spec/implementation firewall.

**Enforced by.** A wordlist grep over `docs/wiki/` and `docs/plan/` for
known stack vocabulary (run it before every commit that touches docs), plus
review: any ticket or spec line that says *how* instead of *what* is sent
back. `impl/` may cite the docs; nothing under `docs/` cites `impl/`.

**Without it.** The docs silently become a description of one codebase.
Regeneration stops being a measurement (the "spec" already contains the
answers) and a second implementation becomes impossible to build honestly.

---

## INV-3: The standing-slot cap is enforced in exactly one place

**Rule.** The cap on standing slots per household is a configured policy
(at Soukanvuori: a second slot is allowed **when there is room**, see
`docs/wiki/pages/actors-and-fairness.md`), and its check lives in a single
domain-level decision point that every booking path goes through. No UI
layer, list view, or convenience path re-implements it.

**Enforced by.** Structure: there is one function/decider that accepts or
refuses standing-slot changes, and the test for the cap targets it
directly. A grep for the cap's guard condition must return exactly one
site in domain code. Note what the single site protects: the cap is
**conditional** (room remaining changes the answer), and a scattered check
is exactly how one copy learns the condition and another does not.

**Without it.** Two copies of the cap drift: one grants the second slot
while there is room, the other refuses it flat, and which rule a resident
hits depends on which screen they came from.

---

## INV-4: Slots are named calendar values, never floats or raw timestamps

**Rule.** A slot is identified by its domain coordinates: a date (or
weekday, for a standing slot), a bookable resource (the dressing room, not
merely the sauna), and a named start time. Slot length is configuration
(90 minutes at Soukanvuori). Slots are never stored or passed around as
fractional hours, epoch floats, or bare local timestamps without the
resource.

**Enforced by.** The contract: the slot schema in the implementation's
contract file declares `{date, resource, start}` with `start` a named
time-of-day value, and unit tests construct slots only through that shape.
A slot value that cannot say which dressing room it belongs to fails at
construction, loudly.

**Without it.** Arithmetic on 17.5, timezone drift around DST weekends, and
the corpus's own trap realised in code: pukuhuone 8 and pukuhuone 9 are two
bookable resources inside one sauna, so their Tuesday 17:00 slots collide
the moment the resource is not part of the identity.

---

## INV-5: Tests derive from spec.feature, not from code

**Rule.** Every e2e test names the `spec.feature` scenario it implements.
Tests are written from the spec while the code is still red; retroactive
tests written to match existing behaviour are forbidden.

**Enforced by.** The harness's RED/GREEN discipline (`docs/wow/harness.md`):
the loop refuses implementation while the state is GREEN and refuses new
tests while the state is RED, so a test always exists before the code that
passes it. Review rejects any test file whose scenarios cannot be matched
one-to-one against `spec.feature` lines.

**Without it.** Tests become a mirror of the code and pass by construction.
"Green" then measures nothing, and a regenerated implementation can differ
from the spec while every light stays on.
