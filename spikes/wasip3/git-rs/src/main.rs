// Spike: the two git questions RDM's gate asks, answered by gitoxide inside WASI.
//   gixprobe <repo> <path>...   ->  per path: tracked? and changed since HEAD/index?
use std::env;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<String> = env::args().skip(1).collect();
    let repo = gix::open(&args[0])?;
    let head = repo.head_commit()?;
    println!("HEAD {} {}", head.id, head.message_raw().map_err(|e| format!("{e:?}"))?.to_string().lines().next().unwrap_or(""));
    for (i, c) in head.ancestors().all()?.take(3).enumerate() {
        let c = c?.object()?;
        println!("log{} {} {}", i, c.id, c.message_raw().map_err(|e| format!("{e:?}"))?.to_string().lines().next().unwrap_or(""));
    }
    let tags: Vec<String> = repo.references()?.tags()?.filter_map(Result::ok)
        .map(|r| r.name().shorten().to_string()).collect();
    println!("tags {}", tags.join(" "));

    let index = repo.index()?;
    // Changed = differs between HEAD and index, or between index and worktree, or untracked.
    let mut changed = std::collections::BTreeSet::new();
    let status = repo.status(gix::progress::Discard)?.untracked_files(gix::status::UntrackedFiles::Files);
    for item in status.into_iter(None)? {
        let item = item?;
        changed.insert(item.location().to_string());
    }
    for path in &args[1..] {
        let tracked = index.entry_by_path(path.as_str().into()).is_some();
        println!("{path}\ttracked={tracked}\tchanged={}", changed.contains(path.as_str()));
    }
    Ok(())
}
