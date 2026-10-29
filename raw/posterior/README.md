# raw/posterior: what the instrument's model rests on

Sources for `docs/wow/posterior.md`, found by a search of arXiv on
2026-10-29 for each part of the model (noisy graders with no gold standard,
item response theory, identifiability without a gold standard, judge
calibration, spec completeness and regeneration, correlated test
executions). Each file is a paper's title, authors and abstract as arXiv
shows them, fetched that day and never edited; the full text was read for
2405.19521 only (its section 2, the model).

| arXiv | Title | Why it is here |
|---|---|---|
| `2405.19521` | Hierarchical Bayesian Crowdsourcing with Item Difficulty | the closest model to ours: Dawid–Skene with item difficulty, discrimination and guessing (IRT-3PL plus sensitivity/specificity), adversarial raters excluded by reparameterisation, Stan; its reductions table maps onto our model |
| `2601.22336` | Dependence-Aware Label Aggregation for LLM-as-a-Judge via Ising Models | LLM judges violate conditional independence (shared data, architectures, prompts); Dawid–Skene stays suboptimal as judges grow |
| `2510.23874` | Quantifying and Correcting Measurement Error in LLM-Generated Classifications | repeated judgments of one item are correlated (ICC 0.50 to 0.93; ten ratings worth 1.07 to 1.81 independent ones); a beta-binomial latent class fix; Youden's J predicts recovery |
| `2606.15031` | Partial Identification from LLM Prompts | with arbitrarily dependent reporters the prevalence is unidentified; identifying power comes from externally calibrated scores and events |
| `2401.09376` | Online Performance Estimation with Unlabeled Data: Hui–Walter | the Hui–Walter paradigm applied to a classifier's error rates with Beta(1,1) priors |
| `2605.09227` | Two Ways to De-Bias an LLM-as-a-Judge | a hierarchical Bayesian linear corrector with partial pooling across rubrics; the slope's posterior as a judge-quality signal; the data-budget rule |
| `2605.06939` | Bias and Uncertainty in LLM-as-a-Judge Estimation | correction by Youden's J from a calibration set; when calibration makes a comparison worse |
| `2609.12002` | Can We Trust LLM Judges | judge accuracy tracks task accuracy; more capable examinees get lenient judgments; calibrated weighted majority voting with false-positive and false-negative rates estimated from disagreement, no labels |
| `2608.05086` | Item Response Theory for AI Safety | models as test-takers, prompts as items, 2PL fitted by marginal maximum likelihood |
| `2605.25232` | Specification-Based Code-Text-Code Reengineering | regeneration through a neutral textual specification; transformation loss estimated by graph similarity; DSPy for prompt tuning |
| `2608.21208` | Specification Portability Across LLM Development Agents | a specification is not agent-neutral: cross-agent transfer degrades sharply; specification size does not predict implementation quality |
| `2509.09917` | Enhancing LLM-based Specification Generation | completeness measured against behaviour-changing mutants, not logical completeness |
| `2607.01953` | Underspecification does not imply Incoherence | semantic collapse under underspecification in coding models |
| `2208.07935` | Demonstrating Software Reliability using Possibly Correlated Tests | conservative Bayesian inference when executions are not independent; observing no failures can give less confidence than observing some |

Not held: Dawid and Skene (1979), Hui and Walter (1980), Jones, Johnson,
Hanson and Christensen (2010, Biometrics) on identifiability of multiple-test
models, Gustafson on inference in non-identified models. Journal papers,
cited by name in `docs/wow/posterior.md`.
