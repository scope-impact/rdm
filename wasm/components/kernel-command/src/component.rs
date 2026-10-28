//! kernel/command built to its component, kernel-command-component
//! (dhf/wit/kernel-command.wit): the command's entry point (wasi:cli/run),
//! slice 01's tasks (help, init, adopt), and the dispatch of every other task
//! to the later slice whose tasks name it (dhf/wit/tasks.wit; ED-02).

use rdm_kernel::command::{self, Plan};
use rdm_kernel::edn::{self, Edn};
use rdm_kernel::report;
use rdm_kernel::sys;
use rdm_kernel::table::TABLE;
use alloc::format;
use alloc::string::String;
use alloc::vec;
use alloc::vec::Vec;

rdm_kernel::bindings!("rdm:spec/kernel-command-component", with_host);

use exports::rdm::spec::tasks::{Entry, Outcome, Request};
use rdm::spec::{project_adopt, project_init};

struct Command;

export!(Command);


/// Slice 01's rows of the table: help, the command's own, and init and adopt.
const OWN: &[&str] = &["help", "init", "adopt"];

impl exports::rdm::spec::tasks::Guest for Command {
    fn entries() -> Vec<Entry> {
        TABLE
            .iter()
            .filter(|r| OWN.contains(&r.task))
            .map(|r| Entry { task: r.task.into(), usage: r.usage.into(), does: r.does.into() })
            .collect()
    }

    async fn run(request: Request) -> Option<Outcome> {
        own(&request).await
    }
}

impl exports::wasi::cli::run::Guest for Command {
    async fn run() -> Result<(), ()> {
        let code = main().await;
        if code != 0 {
            sys::exit(code);
        }
        Ok(())
    }
}

fn refused(why: &str) -> Outcome {
    Outcome { exit: 2, stdout: String::new(), errors: vec![report::cannot_run(why)] }
}

async fn main() -> u8 {
    let argv = sys::arguments();
    let args: Vec<String> = argv.into_iter().skip(1).collect();
    let outcome = match command::plan(&args) {
        Plan::Help => Outcome { exit: 0, stdout: format!("{}\n", command::help()), errors: Vec::new() },
        Plan::Refuse(why) => refused(&why),
        Plan::Task { task, args, root, results, cut } => match settle(task, args, root, results, cut).await {
            Ok(request) => dispatch(request).await,
            Err(why) => refused(&why),
        },
    };
    sys::print(&outcome.stdout).await;
    sys::eprint(&command::error_stream(&outcome.errors)).await;
    outcome.exit
}

/// What the command settles before it runs a task: the project's root, the
/// results directory, and the project's folder within its repository
/// (tickets 04 and 05; ED-06, ED-16).
async fn settle(task: String, args: Vec<String>, root: bool, results: bool, cut: bool) -> Result<Request, String> {
    let cwd = sys::cwd();
    let mut configured: Option<String> = None;
    if root {
        let config = rdm_kernel::path::join(&cwd, "dhf/harness/config.edn");
        if !sys::exists(&config).await? {
            return Err(format!(
                "{task} runs in a project's root, the folder holding dhf/harness/config.edn, and {cwd} holds none"
            ));
        }
        let text = sys::read_string(&config).await.map_err(|e| format!("dhf/harness/config.edn cannot be read: {e}"))?;
        let value = edn::read(&text).map_err(|e| format!("dhf/harness/config.edn cannot be read: {e}"))?;
        if !matches!(value, Edn::Map(_)) {
            return Err("dhf/harness/config.edn cannot be read: it is not a map".into());
        }
        configured = value.get("results-dir").and_then(|v| v.as_str()).map(String::from);
    }
    // ED-16, as the spec owner narrowed it: see command::results_for.
    let results_dir = if results {
        let env = sys::var("RESULTS_DIR");
        let dir = command::results_dir(&args, env.as_deref(), configured.as_deref())?;
        let exists = sys::is_dir(&dir).await;
        command::results_for(&task, &args, env.as_deref(), configured.as_deref(), &|p| p == dir && exists)?
    } else {
        None
    };
    let folder = if cut {
        match sys::git(&cwd, &["rev-parse", "--show-prefix"]).await {
            Ok(prefix) => Some(command::folder(&prefix)),
            Err(_) => None,
        }
    } else {
        None
    };
    Ok(Request { task, args, results_dir, folder })
}

/// Runs the task with the slice whose tasks name it; a task no built slice
/// names is not built (ED-02).
async fn dispatch(request: Request) -> Outcome {
    if let Some(outcome) = own(&request).await {
        return outcome;
    }
    macro_rules! slice {
        ($m:ident) => {
            if $m::entries().iter().any(|e| e.task == request.task) {
                let r = $m::Request {
                    task: request.task.clone(),
                    args: request.args.clone(),
                    results_dir: request.results_dir.clone(),
                    folder: request.folder.clone(),
                };
                if let Some(o) = $m::run(r).await {
                    return Outcome { exit: o.exit, stdout: o.stdout, errors: o.errors };
                }
            }
        };
    }
    slice!(write_the_spec);
    slice!(ground_the_spec);
    slice!(model_the_architecture);
    slice!(verify_by_results);
    slice!(regenerate);
    slice!(measure);
    slice!(plugins);
    slice!(publish);
    slice!(regulated_profile);
    refused(&command::not_built(&request.task))
}

/// Slice 01's own tasks: help, init and adopt.
async fn own(request: &Request) -> Option<Outcome> {
    match request.task.as_str() {
        "help" => Some(Outcome { exit: 0, stdout: format!("{}\n", command::help()), errors: Vec::new() }),
        "init" => Some(init(&request.args).await),
        "adopt" => Some(adopt(&request.args).await),
        _ => None,
    }
}

async fn init(args: &[String]) -> Outcome {
    let (path, prefix) = match command::init_args(args) {
        Ok(x) => x,
        Err(why) => return refused(&why),
    };
    match project_init::init(path.clone(), prefix).await {
        Ok(done) => Outcome {
            exit: 0,
            stdout: format!(
                "{}\n",
                edn::print(&Edn::map(vec![(
                    "init",
                    Edn::map(vec![
                        ("path", Edn::str(&done.path)),
                        ("wrote", Edn::strs(&done.wrote)),
                        ("next", Edn::strs(&done.next_steps)),
                    ])
                )]))
            ),
            errors: Vec::new(),
        },
        Err(why) => refused(&why),
    }
}

async fn adopt(args: &[String]) -> Outcome {
    if let Some(extra) = args.first() {
        return refused(&format!("adopt takes no argument: {extra}"));
    }
    match project_adopt::adopt(sys::cwd()).await {
        Ok(done) => {
            let mut errors = Vec::new();
            for k in &done.kept {
                errors.push(report::line(Some(k), None, "adopt-kept", &format!("{k} was already there; kept as it was"), true));
            }
            if !done.ignore_lines.is_empty() {
                errors.push(report::line(
                    Some(".gitignore"),
                    None,
                    "adopt-gitignore",
                    &format!("add the lines rdm-spec init writes: {}", done.ignore_lines.join(", ")),
                    true,
                ));
            }
            let result = Edn::map(vec![(
                "adopt",
                Edn::map(vec![
                    ("wrote", Edn::strs(&done.wrote)),
                    ("kept", Edn::strs(&done.kept)),
                    ("next", Edn::strs(command::ADOPT_NEXT)),
                    ("gitignore-lines", Edn::strs(&done.ignore_lines)),
                ]),
            )]);
            Outcome { exit: 0, stdout: format!("{}\n", edn::print(&result)), errors }
        }
        Err(why) => refused(&why),
    }
}
