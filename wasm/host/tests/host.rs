//! The host's tests (written after its code, in ticket 01): the exit code the
//! command gives reaches the shell (ED-03), and rdm:host/process runs, times
//! out and refuses as host.wit says. They run the composed command, so
//! wasm/test.sh runs them after it composes wasm/build/rdm-spec.wasm.

use std::path::PathBuf;
use std::process::Command;

fn build() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../build/rdm-spec.wasm")
}

fn host(args: &[&str], cwd: &std::path::Path) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_rdm-spec-host"))
        .arg(build())
        .args(args)
        .current_dir(cwd)
        .output()
        .expect("the host runs")
}

fn scratch(name: &str) -> PathBuf {
    let d = std::env::temp_dir().join(format!("rdm-spec-host-test-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&d);
    std::fs::create_dir_all(&d).unwrap();
    d
}

#[test]
fn exit_codes_0_and_2_reach_the_shell() {
    if !build().exists() {
        panic!("wasm/build/rdm-spec.wasm is not composed: run wasm/test.sh");
    }
    let d = scratch("exit");
    let help = host(&["help"], &d);
    assert_eq!(help.status.code(), Some(0));
    assert!(String::from_utf8_lossy(&help.stdout).starts_with("{:help"));
    let unknown = host(&["frobnicate"], &d);
    assert_eq!(unknown.status.code(), Some(2));
    assert!(unknown.stdout.is_empty());
    std::fs::remove_dir_all(&d).unwrap();
}

#[test]
fn a_component_that_cannot_be_read_is_cannot_run() {
    let d = scratch("missing");
    let out = Command::new(env!("CARGO_BIN_EXE_rdm-spec-host")).arg(d.join("none.wasm")).arg("help").output().unwrap();
    assert_eq!(out.status.code(), Some(2));
    assert!(String::from_utf8_lossy(&out.stderr).starts_with("cannot-run:"));
    std::fs::remove_dir_all(&d).unwrap();
}

#[test]
fn processes_run_in_the_folder_given_with_the_project_preopened() {
    // init runs git through rdm:host/process in a folder it names by an
    // absolute path (ED-05).
    let d = scratch("process");
    let target = d.join("deep/sauna");
    let out = host(&["init", target.to_str().unwrap()], &d);
    assert_eq!(out.status.code(), Some(0), "{}", String::from_utf8_lossy(&out.stderr));
    assert!(target.join(".git").is_dir());
    let mode = std::os::unix::fs::PermissionsExt::mode(&std::fs::metadata(target.join(".githooks/pre-commit")).unwrap().permissions());
    assert!(mode & 0o111 != 0);
    std::fs::remove_dir_all(&d).unwrap();
}
