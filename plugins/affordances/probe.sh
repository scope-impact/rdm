#!/bin/sh
# The probe of verify's :serves check. It lives in a kept path, so the builder
# never writes the judge. Usage: probe.sh URL
# 1. Identity: the thing answering on URL is this application, by what only it serves.
# 2. Conformance: the served design holds to the affordance contract
#    (dhf/spec/affordances.yaml) through the design system's binding
#    (dhf/harness/binding.yaml): dhf/harness/conformance.cjs, black box, from "/".
URL="${1:?usage: probe.sh URL}"
PAGE="$(curl -s "$URL")" || { echo "nothing answered at $URL"; exit 1; }
for word in sauna pukuhuone; do
  printf '%s' "$PAGE" | grep -qi "$word" || { echo "the served page does not show '$word'"; exit 1; }
done
echo "the served page shows sauna and pukuhuone: the Löyly booking app"
as_json() { bb -e "(println (cheshire.core/generate-string (clj-yaml.core/parse-string (slurp \"$1\") :keywords false)))"; }
CONTRACT="$(as_json dhf/spec/affordances.yaml)" || exit 2
BINDING="$(as_json dhf/harness/binding.yaml)" || exit 2
export CONTRACT BINDING
exec node dhf/harness/conformance.cjs "$URL"
