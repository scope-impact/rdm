# Validation evidence

Verification asks whether each requirement is met; validation asks whether
each user need is. RDM keeps validation evidence as a record against each
user need.

## Human validation

A person's validation judgment is a file in the record,
`<dhf>/validation/<name>-validation.json`, with `user_need` (the need's id
as the spec gives it: a Feature's title, such as `Alarms sound`),
`disposition: "approved"`, `reviewer` (required: a person makes the
judgment) and `summary`. The release gate names every user need without one, as a warning: a machine cannot supply that
judgment, but its absence is never silent.
