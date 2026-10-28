//! `rdm:host/process` (dhf/wit/deps/rdm-host/host.wit): runs an argument
//! vector in a folder, with an environment added to rdm-spec's own, a
//! standard input and a time limit, in its own process group, and returns its
//! exit, output and error. No rdm-spec logic.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::os::unix::process::{CommandExt, ExitStatusExt};
use std::process::{Child, Stdio};
use std::time::{Duration, Instant};

wasmtime::component::bindgen!({
    path: "../../dhf/wit/deps/rdm-host",
    world: "rdm:host/host",
});

use rdm::host::process::{Command, Error, Host, HostWithStore, Output};
use wasmtime::component::{Accessor, HasSelf};

pub use rdm::host::process::add_to_linker;

#[derive(Default)]
pub struct Processes {
    children: HashMap<u32, Child>,
}

impl Processes {
    pub fn stop_all(&mut self) {
        let pids: Vec<u32> = self.children.keys().copied().collect();
        for pid in pids {
            self.stop(pid);
        }
    }

    fn stop(&mut self, pid: u32) {
        if let Some(mut child) = self.children.remove(&pid) {
            kill_group(pid, libc::SIGTERM);
            let deadline = Instant::now() + Duration::from_secs(5);
            loop {
                match child.try_wait() {
                    Ok(Some(_)) | Err(_) => break,
                    Ok(None) if Instant::now() >= deadline => {
                        kill_group(pid, libc::SIGKILL);
                        let _ = child.wait();
                        break;
                    }
                    Ok(None) => std::thread::sleep(Duration::from_millis(20)),
                }
            }
            // The group may outlive its leader: make sure none of it is left.
            kill_group(pid, libc::SIGKILL);
        }
    }
}

fn kill_group(pid: u32, signal: i32) {
    unsafe {
        libc::kill(-(pid as i32), signal);
    }
}

fn build(cmd: &Command) -> Result<std::process::Command, Error> {
    let Some((program, rest)) = cmd.argv.split_first() else {
        return Err(Error::CannotStart("an empty argument vector".into()));
    };
    let mut c = std::process::Command::new(program);
    c.args(rest);
    if !cmd.cwd.is_empty() {
        c.current_dir(&cmd.cwd);
    }
    for (k, v) in &cmd.env {
        c.env(k, v);
    }
    c.process_group(0);
    Ok(c)
}

fn run(cmd: Command) -> Result<Output, Error> {
    let started = Instant::now();
    let mut c = build(&cmd)?;
    c.stdin(if cmd.stdin.is_some() { Stdio::piped() } else { Stdio::null() });
    c.stdout(Stdio::piped()).stderr(Stdio::piped());
    let mut child = c
        .spawn()
        .map_err(|e| Error::CannotStart(format!("{}: {e}", cmd.argv[0])))?;
    let pid = child.id();
    if let Some(input) = cmd.stdin {
        let mut stdin = child.stdin.take().expect("piped stdin");
        std::thread::spawn(move || {
            let _ = stdin.write_all(&input);
        });
    }
    let mut out = child.stdout.take().expect("piped stdout");
    let mut err = child.stderr.take().expect("piped stderr");
    let out_reader = std::thread::spawn(move || {
        let mut buf = Vec::new();
        let _ = out.read_to_end(&mut buf);
        buf
    });
    let err_reader = std::thread::spawn(move || {
        let mut buf = Vec::new();
        let _ = err.read_to_end(&mut buf);
        buf
    });
    let deadline = cmd.timeout_ms.map(|ms| started + Duration::from_millis(ms));
    let mut timed_out = false;
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Some(status),
            Ok(None) => {}
            Err(_) => break None,
        }
        if deadline.is_some_and(|d| Instant::now() >= d) {
            timed_out = true;
            kill_group(pid, libc::SIGKILL);
            break child.wait().ok();
        }
        std::thread::sleep(Duration::from_millis(2));
    };
    // Whatever it started in its group goes with it, so nothing is left behind.
    kill_group(pid, libc::SIGKILL);
    let stdout = out_reader.join().unwrap_or_default();
    let stderr = err_reader.join().unwrap_or_default();
    let exit = match status {
        Some(s) if !timed_out && s.signal().is_none() => s.code(),
        _ => None,
    };
    Ok(Output { exit, stdout, stderr, timed_out, millis: started.elapsed().as_millis() as u64 })
}

impl<T> HostWithStore<T> for HasSelf<crate::State> {
    async fn run(_: &Accessor<T, Self>, cmd: Command) -> Result<Output, Error> {
        tokio::task::spawn_blocking(move || run(cmd))
            .await
            .unwrap_or_else(|e| Err(Error::CannotStart(e.to_string())))
    }
}

impl Host for crate::State {
    fn spawn(&mut self, cmd: Command) -> Result<u32, Error> {
        let mut c = build(&cmd)?;
        c.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
        let child = c
            .spawn()
            .map_err(|e| Error::CannotStart(format!("{}: {e}", cmd.argv[0])))?;
        let pid = child.id();
        self.processes.children.insert(pid, child);
        Ok(pid)
    }

    fn stop(&mut self, pid: u32) {
        self.processes.stop(pid);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cmd(argv: &[&str], timeout_ms: Option<u64>) -> Command {
        Command { argv: argv.iter().map(|s| s.to_string()).collect(), cwd: String::new(), env: vec![("RDM_TEST".into(), "x".into())], stdin: None, timeout_ms }
    }

    #[test]
    fn returns_the_exit_and_both_streams() {
        let out = run(cmd(&["sh", "-c", "echo $RDM_TEST; echo e >&2; exit 3"], None)).unwrap();
        assert_eq!(out.exit, Some(3));
        assert_eq!(out.stdout, b"x\n");
        assert_eq!(out.stderr, b"e\n");
        assert!(!out.timed_out);
    }

    #[test]
    fn gives_its_standard_input() {
        let mut c = cmd(&["cat"], None);
        c.stdin = Some(b"in".to_vec());
        assert_eq!(run(c).unwrap().stdout, b"in");
    }

    #[test]
    fn stops_it_past_its_time_with_its_group() {
        let out = run(cmd(&["sh", "-c", "sleep 30 & sleep 30"], Some(200))).unwrap();
        assert!(out.timed_out);
        assert_eq!(out.exit, None);
        assert!(out.millis < 5000);
    }

    #[test]
    fn a_program_that_cannot_start_is_said_so() {
        assert!(matches!(run(cmd(&["no-such-program-rdm"], None)), Err(Error::CannotStart(_))));
        assert!(matches!(run(cmd(&[], None)), Err(Error::CannotStart(_))));
    }
}
