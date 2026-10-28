//! The kernel's system calls, on WASI 0.3 and rdm:host/process only: files
//! through the root folder the host preopens ("/", ED-05), the environment,
//! the standard streams, the exit code (ED-03), and processes.

use crate::path;
use alloc::format;
use alloc::string::{String, ToString};
use alloc::vec::Vec;
use wasip3::filesystem::preopens;
use wasip3::filesystem::types::{Descriptor, DescriptorFlags, DescriptorType, ErrorCode, OpenFlags, PathFlags};

/// rdm:host/process (dhf/wit/deps/rdm-host/host.wit).
pub mod host {
    wit_bindgen::generate!({
        path: "../../../../dhf/wit/deps/rdm-host",
        world: "rdm:host/host",
    });
    pub use self::rdm::host::process;
}

pub use host::process::{Command, Output};

// ---- the environment ----

pub fn arguments() -> Vec<String> {
    wasip3::cli::environment::get_arguments()
}

pub fn var(name: &str) -> Option<String> {
    wasip3::cli::environment::get_environment().into_iter().find(|(k, _)| k == name).map(|(_, v)| v)
}

/// The folder rdm-spec was run in, absolute.
pub fn cwd() -> String {
    wasip3::cli::environment::get_initial_cwd().unwrap_or_else(|| "/".into())
}

/// A path as given, made absolute against the folder rdm-spec was run in.
pub fn absolute(p: &str) -> String {
    path::absolute(&cwd(), p)
}

// ---- the standard streams and the exit ----

async fn write_stream(bytes: Vec<u8>, err: bool) {
    if bytes.is_empty() {
        return;
    }
    let (mut tx, rx) = wasip3::wit_stream::new();
    let done = if err { wasip3::cli::stderr::write_via_stream(rx) } else { wasip3::cli::stdout::write_via_stream(rx) };
    let _ = tx.write_all(bytes).await;
    drop(tx);
    let _ = done.await;
}

pub async fn print(text: &str) {
    write_stream(text.as_bytes().to_vec(), false).await
}

pub async fn eprint(text: &str) {
    write_stream(text.as_bytes().to_vec(), true).await
}

/// Ends the run with the code: 0, 1 or 2 (ED-03).
pub fn exit(code: u8) {
    wasip3::cli::exit::exit_with_code(code);
}

// ---- files ----

fn root() -> Result<Descriptor, String> {
    preopens::get_directories()
        .into_iter()
        .find(|(_, name)| name == "/")
        .map(|(d, _)| d)
        .ok_or_else(|| "the host gave no root folder".to_string())
}

/// The path relative to the root preopen, as WASI wants it.
fn rel(p: &str) -> String {
    let abs = absolute(p);
    let r = abs.trim_start_matches('/');
    if r.is_empty() { ".".into() } else { r.into() }
}

fn why(e: ErrorCode) -> String {
    match e {
        ErrorCode::NoEntry => "no such file or directory".into(),
        ErrorCode::Exist => "already exists".into(),
        ErrorCode::NotDirectory => "not a directory".into(),
        ErrorCode::IsDirectory => "is a directory".into(),
        ErrorCode::Access | ErrorCode::NotPermitted => "permission denied".into(),
        other => format!("{other:?}"),
    }
}

/// What is at a path, a symbolic link counted without following it; None
/// when nothing is.
pub async fn kind(p: &str) -> Result<Option<DescriptorType>, String> {
    match root()?.stat_at(PathFlags::empty(), rel(p)).await {
        Ok(stat) => Ok(Some(stat.type_)),
        Err(ErrorCode::NoEntry) => Ok(None),
        Err(e) => Err(format!("{p}: {}", why(e))),
    }
}

pub async fn exists(p: &str) -> Result<bool, String> {
    Ok(kind(p).await?.is_some())
}

/// Whether a folder is at the path, following a symbolic link.
pub async fn is_dir(p: &str) -> bool {
    let Ok(r) = root() else { return false };
    matches!(r.stat_at(PathFlags::SYMLINK_FOLLOW, rel(p)).await, Ok(s) if matches!(s.type_, DescriptorType::Directory))
}

pub async fn read(p: &str) -> Result<Vec<u8>, String> {
    let file = root()?
        .open_at(PathFlags::SYMLINK_FOLLOW, rel(p), OpenFlags::empty(), DescriptorFlags::READ)
        .await
        .map_err(|e| format!("{p}: {}", why(e)))?;
    let (rx, done) = file.read_via_stream(0);
    let bytes = rx.collect().await;
    done.await.map_err(|e| format!("{p}: {}", why(e)))?;
    Ok(bytes)
}

pub async fn read_string(p: &str) -> Result<String, String> {
    String::from_utf8(read(p).await?).map_err(|_| format!("{p}: not UTF-8 text"))
}

/// Writes a new file; refuses when anything is already at the path.
pub async fn write_new(p: &str, bytes: &[u8]) -> Result<(), String> {
    let file = root()?
        .open_at(PathFlags::empty(), rel(p), OpenFlags::CREATE | OpenFlags::EXCLUSIVE, DescriptorFlags::WRITE)
        .await
        .map_err(|e| format!("{p}: {}", why(e)))?;
    let (mut tx, rx) = wasip3::wit_stream::new();
    let done = file.write_via_stream(rx, 0);
    let _ = tx.write_all(bytes.to_vec()).await;
    drop(tx);
    done.await.map_err(|e| format!("{p}: {}", why(e)))
}

/// Makes a folder and every folder above it that is missing.
pub async fn create_dir_all(p: &str) -> Result<(), String> {
    let abs = absolute(p);
    let mut cur = String::new();
    for part in abs.split('/').filter(|s| !s.is_empty()) {
        cur.push('/');
        cur.push_str(part);
        if is_dir(&cur).await {
            continue;
        }
        root()?.create_directory_at(rel(&cur)).await.map_err(|e| format!("{cur}: {}", why(e)))?;
    }
    Ok(())
}

// ---- processes ----

/// Runs an argument vector in a folder through the host.
pub async fn run(argv: &[&str], cwd: &str) -> Result<Output, String> {
    let cmd = Command {
        argv: argv.iter().map(|s| s.to_string()).collect(),
        cwd: cwd.to_string(),
        env: Vec::new(),
        stdin: None,
        timeout_ms: None,
    };
    host::process::run(cmd).await.map_err(|e| match e {
        host::process::Error::CannotStart(why) => why,
    })
}

/// git, run in a folder: its standard output when it exits 0.
pub async fn git(cwd: &str, args: &[&str]) -> Result<String, String> {
    let mut argv = Vec::from(["git"]);
    argv.extend_from_slice(args);
    let out = run(&argv, cwd).await?;
    if out.exit == Some(0) {
        Ok(String::from_utf8_lossy(&out.stdout).into_owned())
    } else {
        let err = String::from_utf8_lossy(&out.stderr);
        Err(format!("git {} exited {:?}: {}", args.join(" "), out.exit, err.trim().replace('\n', " ")))
    }
}
