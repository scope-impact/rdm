//! What a step definition is given: the scenario's context, its fixtures, the
//! last command it ran, and helpers. A step text has one meaning everywhere
//! (dhf/spec/README.md), so each is defined once, by the step file of its
//! owner: the kernel's (kernel-command/steps.rs) for a text the features of
//! more than one context use, else its context's.

use crate::gherkin::{Feature, Scenario};
use regex::Regex;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

/// How a step went wrong: Fail is a Then whose assertion did not hold
/// (failed); Broken is anything else (broken).
#[derive(Debug)]
pub enum Wrong {
    Fail(String),
    Broken(String),
}

pub type StepResult = Result<(), Wrong>;

pub fn fail<T>(msg: impl Into<String>) -> Result<T, Wrong> {
    Err(Wrong::Fail(msg.into()))
}

pub fn broken<T>(msg: impl Into<String>) -> Result<T, Wrong> {
    Err(Wrong::Broken(msg.into()))
}

/// Holds, or fails naming what was expected.
pub fn check(cond: bool, msg: impl FnOnce() -> String) -> StepResult {
    if cond { Ok(()) } else { Err(Wrong::Fail(msg())) }
}

pub trait OrBroken<T> {
    fn or_broken(self, what: &str) -> Result<T, Wrong>;
}

impl<T, E: std::fmt::Display> OrBroken<T> for Result<T, E> {
    fn or_broken(self, what: &str) -> Result<T, Wrong> {
        self.map_err(|e| Wrong::Broken(format!("{what}: {e}")))
    }
}

/// One run of a command.
#[derive(Clone, Debug, Default)]
pub struct Ran {
    pub argv: Vec<String>,
    pub cwd: PathBuf,
    pub exit: Option<i32>,
    pub stdout: String,
    pub stderr: String,
}

impl Ran {
    pub fn summary(&self) -> String {
        format!(
            "`{}` in {} exited {:?}; stdout: {:?}; stderr: {:?}",
            self.argv.join(" "),
            self.cwd.display(),
            self.exit,
            truncate(&self.stdout),
            truncate(&self.stderr)
        )
    }

    pub fn expect_exit(&self, code: i32) -> StepResult {
        check(self.exit == Some(code), || format!("expected exit {code}: {}", self.summary()))
    }
}

fn truncate(s: &str) -> String {
    if s.len() > 1500 { format!("{}...", &s[..s.char_indices().nth(1500).map(|x| x.0).unwrap_or(s.len())]) } else { s.to_string() }
}

pub type StepFn = fn(&mut World, &[String]) -> StepResult;

pub struct Def {
    pub pattern: Regex,
    pub source: String,
    pub run: StepFn,
}

/// Every step definition, from every step file.
#[derive(Default)]
pub struct Steps {
    pub defs: Vec<Def>,
}

impl Steps {
    pub fn def(&mut self, source: &str, pattern: &str, run: StepFn) {
        let pattern = Regex::new(&format!("^{pattern}$")).expect("a step pattern");
        self.defs.push(Def { pattern, source: source.to_string(), run });
    }
}

/// The context one scenario's steps share; it starts empty for each.
pub struct World {
    /// The root of the project the tests run in: where dhf/spec is.
    pub repo: PathBuf,
    /// Every feature file the run found, for steps that read the spec's tables.
    pub features: std::rc::Rc<Vec<Feature>>,
    /// The scenario being run.
    pub scenario: Scenario,
    /// This scenario's own directory, removed after it: every fixture is in it.
    pub dir: PathBuf,
    /// Values steps hand to later steps, by name.
    pub vars: BTreeMap<String, String>,
    /// Paths steps hand to later steps, by name.
    pub paths: BTreeMap<String, PathBuf>,
    /// Environment the scenario's steps set for its commands.
    pub env: BTreeMap<String, Option<String>>,
    /// The folder the scenario's commands run in.
    pub cwd: Option<PathBuf>,
    pub last: Option<Ran>,
    pub attachments: Vec<(String, String)>,
    /// Snapshots of folders (see snapshot), by name, for "unchanged" checks.
    pub snaps: BTreeMap<String, BTreeMap<String, Vec<u8>>>,
}

impl World {
    pub fn new(repo: &Path, features: std::rc::Rc<Vec<Feature>>, scenario: Scenario, dir: PathBuf) -> World {
        World {
            repo: repo.to_path_buf(),
            features,
            scenario,
            dir,
            vars: BTreeMap::new(),
            paths: BTreeMap::new(),
            env: BTreeMap::new(),
            cwd: None,
            last: None,
            attachments: Vec::new(),
            snaps: BTreeMap::new(),
        }
    }

    /// A new directory inside the scenario's own, for a fixture or a ROOM.
    pub fn fresh_dir(&mut self, name: &str) -> Result<PathBuf, Wrong> {
        let mut n = 0;
        loop {
            let p = self.dir.join(if n == 0 { name.to_string() } else { format!("{name}-{n}") });
            if !p.exists() {
                std::fs::create_dir_all(&p).or_broken("making a fixture directory")?;
                return Ok(p);
            }
            n += 1;
        }
    }

    /// The folder commands run in: the one a step chose, else an empty one
    /// that is no project at all.
    pub fn workdir(&mut self) -> Result<PathBuf, Wrong> {
        if let Some(c) = &self.cwd {
            return Ok(c.clone());
        }
        let d = self.fresh_dir("nowhere")?;
        self.cwd = Some(d.clone());
        Ok(d)
    }

    /// Runs a program with the tests' environment (RESULTS_DIR removed, as
    /// dhf/spec/README.md says), the scenario's own variables, and git fenced
    /// from any repository around the scenario's directory.
    pub fn exec(&mut self, argv: &[String], cwd: &Path, stdin: Option<&str>) -> Result<Ran, Wrong> {
        let mut c = Command::new(&argv[0]);
        c.args(&argv[1..]).current_dir(cwd);
        c.env_remove("RESULTS_DIR");
        c.env("GIT_CEILING_DIRECTORIES", &self.dir);
        c.env("GIT_CONFIG_NOSYSTEM", "1");
        c.env("GIT_AUTHOR_NAME", "Scenario").env("GIT_AUTHOR_EMAIL", "scenario@example.invalid");
        c.env("GIT_COMMITTER_NAME", "Scenario").env("GIT_COMMITTER_EMAIL", "scenario@example.invalid");
        for (k, v) in &self.env {
            match v {
                Some(v) => c.env(k, v),
                None => c.env_remove(k),
            };
        }
        c.stdin(if stdin.is_some() { Stdio::piped() } else { Stdio::null() });
        c.stdout(Stdio::piped()).stderr(Stdio::piped());
        let mut child = c.spawn().or_broken(&format!("starting {}", argv[0]))?;
        if let Some(input) = stdin {
            use std::io::Write;
            let mut si = child.stdin.take().unwrap();
            let input = input.to_string();
            std::thread::spawn(move || {
                let _ = si.write_all(input.as_bytes());
            });
        }
        let out = child.wait_with_output().or_broken(&format!("running {}", argv[0]))?;
        Ok(Ran {
            argv: argv.to_vec(),
            cwd: cwd.to_path_buf(),
            exit: out.status.code(),
            stdout: String::from_utf8_lossy(&out.stdout).into_owned(),
            stderr: String::from_utf8_lossy(&out.stderr).into_owned(),
        })
    }

    /// Runs "rdm-spec ARGS" (the rdm-spec on PATH, this build's: ED-11) in a
    /// folder, keeping it as the scenario's last run and its evidence.
    pub fn rdm_spec(&mut self, args: &str, cwd: &Path) -> Result<Ran, Wrong> {
        let mut argv = vec!["rdm-spec".to_string()];
        argv.extend(args.split_whitespace().map(String::from));
        let ran = self.exec(&argv, cwd, None)?;
        self.attachments.push((format!("rdm-spec {args}"), ran.summary()));
        self.last = Some(ran.clone());
        Ok(ran)
    }

    /// git in a folder; a git that fails is broken: it only sets things up.
    pub fn git(&mut self, cwd: &Path, args: &[&str]) -> Result<String, Wrong> {
        let mut argv = vec!["git".to_string(), "-c".into(), "core.hooksPath=/dev/null".into()];
        argv.extend(args.iter().map(|s| s.to_string()));
        let ran = self.exec(&argv, cwd, None)?;
        if ran.exit != Some(0) {
            return broken(format!("git {} failed: {}", args.join(" "), ran.stderr.trim()));
        }
        Ok(ran.stdout)
    }

    pub fn last(&self) -> Result<&Ran, Wrong> {
        self.last.as_ref().ok_or(Wrong::Broken("no command was run".into()))
    }

    pub fn path(&self, name: &str) -> Result<PathBuf, Wrong> {
        self.paths.get(name).cloned().ok_or_else(|| Wrong::Broken(format!("no {name} was set up")))
    }

    /// The examples of the scenario outline tagged with the id, as maps of
    /// heading to value: the spec's own table, for steps that read it.
    pub fn outline_rows(&self, id: &str) -> Result<Vec<BTreeMap<String, String>>, Wrong> {
        for f in self.features.iter() {
            for s in &f.scenarios {
                if s.tags.iter().any(|t| t == id) {
                    return Ok(s
                        .rows()
                        .into_iter()
                        .map(|(h, r)| h.into_iter().zip(r).collect())
                        .collect());
                }
            }
        }
        broken(format!("no scenario {id} in the spec"))
    }
}

/// Every file under a folder, relative to it, with its bytes; symbolic links
/// as their target's text, never followed. For "nothing changed" checks.
pub fn snapshot(root: &Path) -> BTreeMap<String, Vec<u8>> {
    let mut out = BTreeMap::new();
    fn walk(root: &Path, dir: &Path, out: &mut BTreeMap<String, Vec<u8>>) {
        let Ok(entries) = std::fs::read_dir(dir) else { return };
        for e in entries.flatten() {
            let p = e.path();
            let rel = p.strip_prefix(root).unwrap().to_string_lossy().into_owned();
            let Ok(meta) = std::fs::symlink_metadata(&p) else { continue };
            if meta.file_type().is_symlink() {
                let t = std::fs::read_link(&p).map(|t| t.to_string_lossy().into_owned()).unwrap_or_default();
                out.insert(rel, format!("-> {t}").into_bytes());
            } else if meta.is_dir() {
                out.insert(format!("{rel}/"), Vec::new());
                walk(root, &p, out);
            } else {
                use std::os::unix::fs::PermissionsExt;
                let mut bytes = std::fs::read(&p).unwrap_or_default();
                bytes.extend(format!("\nmode {:o}", meta.permissions().mode()).into_bytes());
                out.insert(rel, bytes);
            }
        }
    }
    walk(root, root, &mut out);
    out
}

/// Every file under a folder, relative to it, sorted, leaving out .git.
pub fn files(root: &Path) -> Vec<String> {
    snapshot(root)
        .into_keys()
        .filter(|k| !k.ends_with('/') && !k.starts_with(".git/"))
        .collect()
}
