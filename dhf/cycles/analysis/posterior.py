"""What the graded cycles say, as beliefs with a width rather than scores.

An analysis of the cycles' record, not product code. It reads, through bb
(the record is EDN): every cycle's checklist.json, verdicts.edn, cycle.edn and
runs.edn, and the model's component names; then answers, each with its
uncertainty, the questions a cycle faces:

  1. Can this build ship?               P(some graded item is wrong anyway)
  2. What should the next grading cover? expected information per item
  3. Is this fail real?                 P(real | the grader said fail)
  4. Which scenario should the spec fix? the lowest-belief scenarios
  5. Did a spec change help?            needs builder decisions per scenario
  6. How many more runs?                gradings until the 95% bound clears
  7. Is rdm-spec cheaper than Löyly's?  posterior on the token ratio
  8. Is the spec done?                  share of scenarios above the bar

The model:
  theta_s   P(a build passes scenario s), partially pooled: a context's
            posterior is its components' prior, a component's posterior its
            scenarios' prior, each at strength KAPPA.
  recency   a verdict on a component later rebuilt counts DISCOUNT per rebuild
            since (a power prior): it judged code that is gone.
  grader    a false-fail and a false-pass rate, each Beta(1, 19) (mean 5%):
            a prior only. No item has been graded twice, so it cannot be
            learned yet; double grading (two graders, same items) would.

Run: <analysis venv>/bin/python dhf/cycles/analysis/posterior.py [--json]
Stack: dhf/harness/stack.md, "Cycle analysis".
"""
import json
import math
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from scipy import stats

KAPPA = 10.0          # prior strength of a level on the one below
DISCOUNT = 0.5        # weight of a verdict per later rebuild of its component
GRADER = (1.0, 19.0)  # Beta prior of each grader error rate (false fail, false pass)
BAR = 0.95            # "done": a scenario's 95% lower bound clears this
TARGET_SHIP = 0.05    # an acceptable P(something is wrong) for shipping
RNG = np.random.default_rng(20261026)
DRAWS = 20000

ROOT = Path(__file__).resolve().parents[3]
CYCLES = ROOT / "dhf" / "cycles"

EXPORT = r"""
(require '[babashka.fs :as fs] '[cheshire.core :as json] '[clojure.edn :as edn])
(defn rd [f] (when (fs/exists? f) (edn/read-string (slurp (str f)))))
(defn scope [s] (cond (= :project s) "project"
                      (sequential? s) (vec (mapcat (fn [x] (cond (:context x) [{"context" (name (:context x))}]
                                                                (:component x) [{"component" (str (namespace (:component x)) "/" (name (:component x)))}]
                                                                (:kernel x) (for [k (:kernel x)] {"component" (str "kernel/" (name k))})
                                                                :else [])) s))
                      :else nil))
(let [dir (first *command-line-args*)
      model (rd (str (fs/parent (fs/parent dir)) "/dhf/spec/model.edn"))
      names (into {} (for [c (:containers model) x (:contexts c) k (:components x)]
                       [(str (name (:id x)) "/" (:name k)) (str (namespace (:id k)) "/" (name (:id k)))]))
      names (into names (for [k (:kernel model)] [(str "kernel/" (:name k)) (str (namespace (:id k)) "/" (name (:id k)))]))]
  (println (json/generate-string
   {"names" names
    "cycles" (vec (for [c (sort (map str (fs/list-dir dir))) :when (re-find #"/\d\d$" c)]
                    (let [cy (rd (str c "/cycle.edn"))]
                      {"cycle" (str (fs/file-name c))
                       "scope" (scope (:scope cy))
                       "checklist" (when (fs/exists? (str c "/checklist.json")) (json/parse-string (slurp (str c "/checklist.json"))))
                       "verdicts" (some->> (rd (str c "/verdicts.edn")) (mapv (fn [v] {"id" (:id v) "pass" (= :pass (:verdict v))})))
                       "decisions" (rd (str c "/decisions.edn"))
                       "runs" (some->> (rd (str c "/runs.edn")) (mapv (fn [r] {"role" (:role r) "minutes" (:minutes r) "tokens" (:tokens r)})))})))})))
"""


def export(cycles_dir):
    out = subprocess.run(["bb", "-e", EXPORT, str(cycles_dir)], capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


def component_id(names, context, name):
    return names.get(f"{context}/{name}") or f"{context}/{name.lower().replace(' ', '-')}"


def rebuilt(scope, comp):
    if scope == "project":
        return True
    ctx = comp.split("/")[0]
    return any(s.get("component") == comp or s.get("context") == ctx for s in scope or [])


def observations(data):
    """Every graded item, with its weight after the rebuilds that followed it."""
    cycles = data["cycles"]
    obs = []
    for i, c in enumerate(cycles):
        if not (c["checklist"] and c["verdicts"]):
            continue
        items = {it["id"]: it for it in c["checklist"]["items"]}
        for v in c["verdicts"]:
            it = items.get(v["id"])
            if not it:
                continue
            comp = component_id(data["names"], it["context"], it["component"])
            later = sum(1 for d in cycles[i + 1:] if d["scope"] and rebuilt(d["scope"], comp))
            obs.append({"id": v["id"], "context": it["context"], "component": comp, "cycle": c["cycle"],
                        "pass": v["pass"], "weight": DISCOUNT ** later})
    return obs


def beta_post(a0, b0, os):
    p = sum(o["weight"] for o in os if o["pass"])
    f = sum(o["weight"] for o in os if not o["pass"])
    return a0 + p, b0 + f


def shrink(a, b):
    m = a / (a + b)
    return m * KAPPA, (1 - m) * KAPPA


def pool(obs):
    by = lambda keyf: _group(obs, keyf)
    ctx = {c: beta_post(1.0, 1.0, os) for c, os in by(lambda o: o["context"]).items()}
    comp = {k: beta_post(*shrink(*ctx[os[0]["context"]]), os) for k, os in by(lambda o: o["component"]).items()}
    scen = {}
    for s, os in by(lambda o: o["id"]).items():
        prior = shrink(*comp[os[-1]["component"]])
        a, b = beta_post(*prior, os)
        last = sorted(os, key=lambda o: o["cycle"])[-1]
        scen[s] = {"id": s, "component": os[-1]["component"], "prior": prior, "a": a, "b": b,
                   "graded": len(os), "fails": sum(1 for o in os if not o["pass"]),
                   "last_pass": last["pass"], "last_cycle": last["cycle"],
                   "mean": a / (a + b), "lo95": stats.beta.ppf(0.05, a, b)}
    return ctx, comp, scen


def _group(xs, keyf):
    g = defaultdict(list)
    for x in xs:
        g[keyf(x)].append(x)
    return g


def grader_draws():
    ff = RNG.beta(*GRADER, DRAWS)  # P(says fail | correct)
    fp = RNG.beta(*GRADER, DRAWS)  # P(says pass | wrong)
    return ff, fp


# ---------------------------------------------------------------- the questions

def q1_ship(scen, cycle):
    """P(at least one item of the cycle's slice is wrong, given its verdicts)."""
    ff, fp = grader_draws()
    p_all_right = np.ones(DRAWS)
    for v in cycle["verdicts"]:
        s = scen.get(v["id"])
        if not s:
            continue
        a, b = s["prior"]                         # belief before this build's verdict
        th = RNG.beta(a, b, DRAWS)
        if v["pass"]:
            right = th * (1 - ff) / (th * (1 - ff) + (1 - th) * fp)
        else:
            right = th * ff / (th * ff + (1 - th) * (1 - fp))
        p_all_right *= right
    p_wrong = 1 - p_all_right
    return float(p_wrong.mean()), [float(x) for x in np.percentile(p_wrong, [5, 95])]


def q2_next_grading(scen, n=12):
    """Items whose next verdict moves belief most: the expected drop in the
    Beta variance after one more grading."""
    rows = []
    for s in scen.values():
        a, b = s["a"], s["b"]
        var = a * b / ((a + b) ** 2 * (a + b + 1))
        m = a / (a + b)
        after = m * (a + 1) * b / ((a + b + 1) ** 2 * (a + b + 2)) + (1 - m) * a * (b + 1) / ((a + b + 1) ** 2 * (a + b + 2))
        rows.append((var - after, s))
    return [s | {"gain": g} for g, s in sorted(rows, key=lambda r: -r[0])[:n]]


def q3_fail_real(scen, cycles):
    """For each fail on record: P(the build really was wrong | the grader said fail)."""
    ff, fp = grader_draws()
    out = []
    for c in cycles:
        for v in c["verdicts"] or []:
            if v["pass"] or v["id"] not in scen:
                continue
            a, b = scen[v["id"]]["prior"]
            th = RNG.beta(a, b, DRAWS)
            real = (1 - th) * (1 - fp) / ((1 - th) * (1 - fp) + th * ff)
            out.append({"cycle": c["cycle"], "id": v["id"], "p_real": float(real.mean()),
                        "ci": [float(x) for x in np.percentile(real, [5, 95])]})
    return out


def q4_fix_next(scen, n=8):
    return sorted(scen.values(), key=lambda s: s["mean"])[:n]


def q5_change_helped(cycles):
    have = [c["cycle"] for c in cycles if c.get("decisions")]
    return have


def q6_runs_needed(scen, ids):
    """More all-pass gradings each scenario needs before its 95% lower bound clears BAR."""
    out = []
    for i in ids:
        s = scen[i]
        k = 0
        while stats.beta.ppf(0.05, s["a"] + k, s["b"]) < BAR and k < 500:
            k += 1
        out.append({"id": i, "more": k if k < 500 else None})
    return out


def q7_cost():
    """Log-normal cost per cycle, its spread from rdm-spec's own builder runs."""
    loyly = [359560]
    rdm = [210023, 250780]
    builds = [603427, 391417, 373289, 506027]            # rdm-spec builders, cycles 13-16, for the spread
    sigma = float(np.std(np.log(np.array(builds) / np.mean(builds)), ddof=1)) if len(builds) > 1 else 0.25
    diff = float(np.mean(np.log(rdm)) - np.mean(np.log(loyly)))
    se = sigma * math.sqrt(1 / len(rdm) + 1 / len(loyly))
    p_cheaper = float(stats.norm.cdf(-diff / se))
    ratio = math.exp(diff)
    ci = [math.exp(diff - 1.645 * se), math.exp(diff + 1.645 * se)]
    n = 1
    while stats.norm.cdf(-diff / (sigma * math.sqrt(2 / n))) < 0.95 and n < 200:
        n += 1
    return {"sigma": sigma, "ratio": ratio, "ci90": ci, "p_rdm_cheaper": p_cheaper, "runs_each_for_95": n}


def q8_done(scen):
    above = [s for s in scen.values() if s["lo95"] >= BAR]
    return len(above), len(scen)


# ---------------------------------------------------------------- report

def pct(x):
    return f"{100 * x:.1f}%"


def main():
    global GRADER
    if "--grader-error" in sys.argv:
        e = float(sys.argv[sys.argv.index("--grader-error") + 1])
        GRADER = (1.0, 1.0 / e - 1.0)
    data = export(CYCLES)
    cycles = [c for c in data["cycles"] if c["checklist"] and c["verdicts"]]
    obs = observations(data)
    ctx, comp, scen = pool(obs)
    last = cycles[-1]
    ship, ship_ci = q1_ship(scen, last)
    lines = [f"Graded cycles: {', '.join(c['cycle'] for c in cycles)}; {len(obs)} verdicts, "
             f"{sum(1 for o in obs if not o['pass'])} fails; {len(scen)} scenarios.", ""]

    lines += ["## 1. Can the latest build ship?", "",
              f"Cycle {last['cycle']}: P(some item of its slice is wrong, given its verdicts) = **{pct(ship)}** "
              f"(90% interval {pct(ship_ci[0])} to {pct(ship_ci[1])}). "
              f"{'Under' if ship <= TARGET_SHIP else 'Over'} the {pct(TARGET_SHIP)} bar.", "",
              "| Cycle | Graded | Fails | P(something wrong) |", "|---|---|---|---|"]
    for c in cycles:
        p, _ = q1_ship(scen, c)
        lines.append(f"| {c['cycle']} | {len(c['verdicts'])} | {sum(1 for v in c['verdicts'] if not v['pass'])} | {pct(p)} |")

    lines += ["", "## 2. What the next grading should cover", "",
              "| Scenario | Component | Graded | Fails | Last | Belief | 95% lower |", "|---|---|---|---|---|---|---|"]
    for s in q2_next_grading(scen):
        lines.append(f"| {s['id']} | {s['component']} | {s['graded']} | {s['fails']} | "
                     f"{'pass' if s['last_pass'] else 'fail'} ({s['last_cycle']}) | {pct(s['mean'])} | {pct(s['lo95'])} |")

    lines += ["", "## 3. Is each fail real?", "",
              f"With the grader prior only ({pct(GRADER[0] / sum(GRADER))} false fail, {pct(GRADER[0] / sum(GRADER))} false pass): how likely each fail was a real defect.", "",
              "| Cycle | Scenario | P(real) | 90% interval |", "|---|---|---|---|"]
    for f in q3_fail_real(scen, cycles):
        lines.append(f"| {f['cycle']} | {f['id']} | {pct(f['p_real'])} | {pct(f['ci'][0])} to {pct(f['ci'][1])} |")

    lines += ["", "## 4. Which scenarios the spec should look at first (lowest belief)", "",
              "| Scenario | Component | Graded | Fails | Belief | 95% lower |", "|---|---|---|---|---|---|"]
    for s in q4_fix_next(scen):
        lines.append(f"| {s['id']} | {s['component']} | {s['graded']} | {s['fails']} | {pct(s['mean'])} | {pct(s['lo95'])} |")

    have = q5_change_helped(data["cycles"])
    lines += ["", "## 5. Did a spec change help?", "",
              ("Cycles with builder decisions recorded: " + ", ".join(have)) if have else
              "No cycle records its builder's decisions per scenario (dhf/cycles/NN/decisions.edn), so this cannot "
              "be answered yet. Recording them each cycle (E6) gives a Poisson rate per scenario, and a spec change "
              "helped when that rate drops for the scenarios it touched."]

    worst = [s["id"] for s in q4_fix_next(scen, 5)]
    lines += ["", f"## 6. How many more gradings until the 95% lower bound clears {pct(BAR)}", "",
              "| Scenario | More all-pass gradings needed |", "|---|---|"]
    for r in q6_runs_needed(scen, worst):
        lines.append(f"| {r['id']} | {r['more'] if r['more'] is not None else 'over 500'} |")

    c = q7_cost()
    lines += ["", "## 7. Is rdm-spec cheaper than Löyly's harness (the Löyly app, tokens per cycle)?", "",
              f"Ratio rdm-spec / Löyly-harness = **{c['ratio']:.2f}** (90% interval {c['ci90'][0]:.2f} to {c['ci90'][1]:.2f}); "
              f"P(rdm-spec cheaper) = **{pct(c['p_rdm_cheaper'])}**. The spread is rdm-spec's own builders' "
              f"(log sd {c['sigma']:.2f}). Runs per side to know at 95%: about **{c['runs_each_for_95']}**."]

    done, total = q8_done(scen)
    lines += ["", "## 8. Is the spec done?", "",
              f"{done} of {total} scenarios ({pct(done / total)}) have a 95% lower bound at or above {pct(BAR)}. "
              "Done would be all of them, with builder decisions near zero (question 5)."]

    print("\n".join(lines))


if __name__ == "__main__":
    main()
