// Spike: rdm-git, a library component exporting rdm:component/record-state
// (../wit/record-state.wit): what git would say of the record, read by gitoxide
// from the repository the host mounts at /. The host needs no git.
//
// The repository's status is read once per instance (the gate asks about each
// controlled document in turn) and kept: an RDM command is one short run.
use std::collections::{BTreeMap, BTreeSet};
use std::sync::OnceLock;

use exports::rdm::component::record_state::{FileState, Guest, HeadState, Tracking};

wit_bindgen::generate!({ path: "../wit", world: "rdm:component/git" });

const ROOT: &str = "/";

/// The repository as read once: each index entry's flags, and every path git status names.
struct Record {
    index: BTreeMap<String, Tracking>,
    changed: BTreeSet<String>,
    head: HeadState,
}

fn read() -> Result<Record, String> {
    let repo = gix::open(ROOT).map_err(|e| e.to_string())?;
    let mut index = BTreeMap::new();
    for entry in repo.index_or_empty().map_err(|e| e.to_string())?.entries_with_paths_by_filter_map(|_, e| Some(e.flags)) {
        let (path, flags) = entry;
        let tracking = if flags.contains(gix::index::entry::Flags::SKIP_WORKTREE) {
            Tracking::SkipWorktree
        } else if flags.contains(gix::index::entry::Flags::ASSUME_VALID) {
            Tracking::AssumeUnchanged
        } else {
            Tracking::Tracked
        };
        index.insert(path.to_string(), tracking);
    }
    let mut changed = BTreeSet::new();
    let status = repo
        .status(gix::progress::Discard)
        .map_err(|e| e.to_string())?
        .untracked_files(gix::status::UntrackedFiles::Files);
    for item in status.into_iter(None).map_err(|e| e.to_string())? {
        changed.insert(item.map_err(|e| e.to_string())?.location().to_string());
    }
    let commit = repo.head_id().ok().map(|id| id.to_string());
    let origin = repo
        .find_remote("origin")
        .ok()
        .and_then(|r| r.url(gix::remote::Direction::Fetch).map(|u| u.to_bstring().to_string()));
    let merging = repo.git_dir().join("MERGE_HEAD").exists();
    let dirty = !changed.is_empty();
    Ok(Record { index, changed, head: HeadState { commit, origin, dirty, merging } })
}

fn record() -> &'static Result<Record, String> {
    static RECORD: OnceLock<Result<Record, String>> = OnceLock::new();
    RECORD.get_or_init(read)
}

/// `path` itself, or everything under it when it names a directory.
fn under(candidate: &str, path: &str) -> bool {
    let path = path.trim_end_matches('/');
    path.is_empty() || path == "." || candidate == path || candidate.starts_with(&format!("{path}/"))
}

struct Git;

impl Guest for Git {
    fn head() -> HeadState {
        match record() {
            Ok(record) => record.head.clone(),
            Err(_) => HeadState { commit: None, origin: None, dirty: false, merging: false },
        }
    }

    fn files(paths: Vec<String>) -> Vec<FileState> {
        let Ok(record) = record() else { return Vec::new() }; // not a repository: nothing is known
        let mut out = BTreeMap::new();
        for path in &paths {
            for (file, tracking) in record.index.iter().filter(|(f, _)| under(f, path)) {
                out.insert(file.clone(), FileState { path: file.clone(), tracking: *tracking, changed: record.changed.contains(file) });
            }
            for file in record.changed.iter().filter(|f| under(f, path) && !record.index.contains_key(*f)) {
                out.insert(file.clone(), FileState { path: file.clone(), tracking: Tracking::Untracked, changed: true });
            }
            // A file on disk that neither the index nor status names is ignored: not committed either way.
            let on_disk = std::path::Path::new(ROOT).join(path);
            if on_disk.is_file() && !out.contains_key(path.as_str()) {
                out.insert(path.clone(), FileState { path: path.clone(), tracking: Tracking::Ignored, changed: true });
            }
        }
        out.into_values().collect()
    }
}

export!(Git);
