//! The Gherkin subset the spec is written in (dhf/spec/02-write-the-spec/
//! parser.feature): Feature, Background, Rule, Scenario, Scenario Outline,
//! Examples, Given/When/Then/And/But, tags, comments, doc strings and data
//! tables.

#[derive(Clone, Debug, Default)]
pub struct Step {
    pub keyword: String,
    pub text: String,
    pub line: usize,
    pub table: Vec<Vec<String>>,
    pub doc: Option<String>,
}

#[derive(Clone, Debug, Default)]
pub struct Examples {
    pub header: Vec<String>,
    pub rows: Vec<Vec<String>>,
}

#[derive(Clone, Debug, Default)]
pub struct Scenario {
    pub name: String,
    pub line: usize,
    pub tags: Vec<String>,
    pub outline: bool,
    pub steps: Vec<Step>,
    pub examples: Vec<Examples>,
    pub rule: Option<String>,
    pub rule_tags: Vec<String>,
}

#[derive(Clone, Debug, Default)]
pub struct Feature {
    pub path: String,
    pub name: String,
    pub tags: Vec<String>,
    pub background: Vec<Step>,
    pub scenarios: Vec<Scenario>,
}

impl Scenario {
    /// The scenario's id, the one @spec-NNNN tag.
    pub fn id(&self) -> Option<String> {
        self.tags.iter().find(|t| t.starts_with("spec-")).cloned()
    }

    pub fn rows(&self) -> Vec<(Vec<String>, Vec<String>)> {
        self.examples
            .iter()
            .flat_map(|e| e.rows.iter().map(move |r| (e.header.clone(), r.clone())))
            .collect()
    }
}

fn cells(line: &str) -> Vec<String> {
    let t = line.trim();
    let inner = t.strip_prefix('|').unwrap_or(t);
    let inner = inner.strip_suffix('|').unwrap_or(inner);
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut chars = inner.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '\\' => match chars.next() {
                Some('|') => cur.push('|'),
                Some('n') => cur.push('\n'),
                Some('\\') => cur.push('\\'),
                Some(o) => {
                    cur.push('\\');
                    cur.push(o);
                }
                None => cur.push('\\'),
            },
            '|' => out.push(std::mem::take(&mut cur).trim().to_string()),
            c => cur.push(c),
        }
    }
    out.push(cur.trim().to_string());
    out
}

const STEP_KEYWORDS: [&str; 6] = ["Given", "When", "Then", "And", "But", "*"];

pub fn parse(path: &str, text: &str) -> Result<Feature, String> {
    let mut f = Feature { path: path.to_string(), ..Default::default() };
    let mut pending_tags: Vec<String> = Vec::new();
    let mut rule: Option<(String, Vec<String>)> = None;
    enum In {
        None,
        Background,
        Scenario,
        Examples,
    }
    let mut state = In::None;
    let lines: Vec<&str> = text.lines().collect();
    let mut i = 0;
    while i < lines.len() {
        let raw = lines[i];
        let line = raw.trim();
        let n = i + 1;
        i += 1;
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        if line.starts_with('@') {
            for t in line.split_whitespace() {
                if t.starts_with('#') {
                    break;
                }
                pending_tags.push(t.trim_start_matches('@').to_string());
            }
            continue;
        }
        if let Some(rest) = line.strip_prefix("Feature:") {
            f.name = rest.trim().to_string();
            f.tags = std::mem::take(&mut pending_tags);
            state = In::None;
            continue;
        }
        if line.starts_with("Background:") {
            state = In::Background;
            continue;
        }
        if let Some(rest) = line.strip_prefix("Rule:") {
            rule = Some((rest.trim().to_string(), std::mem::take(&mut pending_tags)));
            state = In::None;
            continue;
        }
        let outline = line.starts_with("Scenario Outline:") || line.starts_with("Scenario Template:");
        if outline || line.starts_with("Scenario:") || line.starts_with("Example:") {
            let name = line.split_once(':').map(|x| x.1).unwrap_or("").trim().to_string();
            f.scenarios.push(Scenario {
                name,
                line: n,
                tags: std::mem::take(&mut pending_tags),
                outline,
                rule: rule.as_ref().map(|r| r.0.clone()),
                rule_tags: rule.as_ref().map(|r| r.1.clone()).unwrap_or_default(),
                ..Default::default()
            });
            state = In::Scenario;
            continue;
        }
        if line.starts_with("Examples:") || line.starts_with("Scenarios:") {
            pending_tags.clear();
            if let Some(s) = f.scenarios.last_mut() {
                s.examples.push(Examples::default());
            }
            state = In::Examples;
            continue;
        }
        if line.starts_with('|') {
            match state {
                In::Examples => {
                    let ex = f.scenarios.last_mut().and_then(|s| s.examples.last_mut()).ok_or("a table outside Examples")?;
                    if ex.header.is_empty() {
                        ex.header = cells(line);
                    } else {
                        ex.rows.push(cells(line));
                    }
                }
                In::Scenario | In::Background => {
                    let step = match state {
                        In::Background => f.background.last_mut(),
                        _ => f.scenarios.last_mut().and_then(|s| s.steps.last_mut()),
                    };
                    step.ok_or(format!("{path}:{n}: a table with no step"))?.table.push(cells(line));
                }
                In::None => return Err(format!("{path}:{n}: a table outside a scenario")),
            }
            continue;
        }
        if line.starts_with("\"\"\"") || line.starts_with("```") {
            let fence = &line[..3];
            let indent = raw.len() - raw.trim_start().len();
            let mut doc = Vec::new();
            while i < lines.len() && !lines[i].trim().starts_with(fence) {
                let l = lines[i];
                let cut = l.len() - l.trim_start().len();
                doc.push(if cut >= indent { &l[indent..] } else { l.trim_start() }.to_string());
                i += 1;
            }
            i += 1;
            let step = match state {
                In::Background => f.background.last_mut(),
                _ => f.scenarios.last_mut().and_then(|s| s.steps.last_mut()),
            };
            step.ok_or(format!("{path}:{n}: a doc string with no step"))?.doc = Some(doc.join("\n"));
            continue;
        }
        if let Some(kw) = STEP_KEYWORDS.iter().find(|k| line.starts_with(*k) && line[k.len()..].starts_with(' ')) {
            let step = Step { keyword: kw.to_string(), text: line[kw.len()..].trim().to_string(), line: n, ..Default::default() };
            match state {
                In::Background => f.background.push(step),
                In::Scenario => f.scenarios.last_mut().ok_or("a step outside a scenario")?.steps.push(step),
                _ => return Err(format!("{path}:{n}: a step outside a scenario")),
            }
            continue;
        }
        // Description prose under a Feature, Rule or Scenario.
    }
    Ok(f)
}

/// The step's text with an outline row's values in place of its <names>.
pub fn substitute(text: &str, header: &[String], row: &[String]) -> String {
    let mut out = text.to_string();
    for (h, v) in header.iter().zip(row) {
        out = out.replace(&format!("<{h}>"), v);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_rules_outlines_tables_and_tags() {
        let text = "@UN-1\nFeature: F\n  prose\n  @UN-2\n  Rule: R\n    @spec-0001 @e2e\n    Scenario Outline: S <a>\n      Given x\n        | p | q |\n        | 1 | a \\| b |\n      Then <a> holds\n      Examples:\n        | a |\n        | one |\n        | two |\n";
        let f = parse("f.feature", text).unwrap();
        assert_eq!(f.tags, vec!["UN-1"]);
        let s = &f.scenarios[0];
        assert_eq!(s.rule.as_deref(), Some("R"));
        assert_eq!(s.rule_tags, vec!["UN-2"]);
        assert_eq!(s.id().as_deref(), Some("spec-0001"));
        assert!(s.outline);
        assert_eq!(s.steps[0].table[1], vec!["1", "a | b"]);
        assert_eq!(s.rows().len(), 2);
        assert_eq!(substitute(&s.steps[1].text, &s.examples[0].header, &s.examples[0].rows[1]), "two holds");
    }
}
