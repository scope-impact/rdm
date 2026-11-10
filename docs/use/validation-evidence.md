# Validation evidence

Verification asks whether each design input is met; validation asks whether
each user need is. RDM keeps validation evidence as a record against each
user need.

## Human validation

A person's validation judgment is a file in the record,
`<dhf>/validation/UN-…-validation.json`, with `user_need`,
`disposition: "approved"`, `reviewer` (required: a person makes the
judgment) and `summary`. The release gate names every user need without one, as a warning: a machine cannot supply that
judgment, but its absence is never silent.
