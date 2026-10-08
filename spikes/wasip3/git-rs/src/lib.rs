// Spike: rdm-git, a library component exporting rdm:component/record-state
// (../wit/record-state.wit): what git would say of the record, read by gitoxide
// from the repository the host mounts (at RDM_REPO, else /). The host needs no git.
//
// The repository's status is read once per instance (the gate asks about each
// controlled document in turn) and kept: an RDM command is one short run.
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::sync::OnceLock;

use exports::rdm::component::record_state::{CommitInfo, FileState, Guest, HeadState, Reference, Tracking};

wit_bindgen::generate!({ path: "../wit", world: "rdm:component/git" });

fn root() -> &'static str {
    static ROOT: OnceLock<String> = OnceLock::new();
    ROOT.get_or_init(|| std::env::var("RDM_REPO").unwrap_or_else(|_| "/".into()))
}

/// The repository, opened once per instance (a component instance is one thread).
fn repo() -> Option<&'static gix::Repository> {
    thread_local! {
        static REPO: &'static Option<gix::Repository> = Box::leak(Box::new(gix::open(root()).ok()));
    }
    REPO.with(|r| r.as_ref())
}

/// The repository as read once: each index entry's flags, and every path git status names.
struct Record {
    index: BTreeMap<String, Tracking>,
    changed: BTreeSet<String>,
    head: HeadState,
}

fn read() -> Result<Record, String> {
    let repo = repo().ok_or("not a repository")?;
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

fn files(paths: Vec<String>) -> Vec<FileState> {
    let Ok(record) = record() else { return Vec::new() }; // not a repository: nothing is known
    {
        let mut out = BTreeMap::new();
        for path in &paths {
            for (file, tracking) in record.index.iter().filter(|(f, _)| under(f, path)) {
                out.insert(file.clone(), FileState { path: file.clone(), tracking: *tracking, changed: record.changed.contains(file) });
            }
            for file in record.changed.iter().filter(|f| under(f, path) && !record.index.contains_key(*f)) {
                out.insert(file.clone(), FileState { path: file.clone(), tracking: Tracking::Untracked, changed: true });
            }
            // A file on disk that neither the index nor status names is ignored: not committed either way.
            let on_disk = std::path::Path::new(root()).join(path);
            if on_disk.is_file() && !out.contains_key(path.as_str()) {
                out.insert(path.clone(), FileState { path: path.clone(), tracking: Tracking::Ignored, changed: true });
            }
        }
        out.into_values().collect()
    }
}

// History

fn commit_of(rev: &str) -> Option<gix::Commit<'static>> {
    repo()?.rev_parse_single(rev).ok()?.object().ok()?.try_into_commit().ok()
}

/// A commit's parents that are in the repository: at a shallow clone's boundary, none, as git sees it.
fn parents(commit: &gix::Commit<'static>) -> Vec<gix::Commit<'static>> {
    let repo = repo().expect("a commit implies a repository");
    commit.parent_ids().filter_map(|id| repo.find_object(id).ok()?.try_into_commit().ok()).collect()
}

fn blob_at(commit: &gix::Commit<'static>, path: &str) -> Option<gix::ObjectId> {
    let mut tree = commit.tree().ok()?;
    tree.peel_to_entry_by_path(path).ok()?.map(|entry| entry.object_id())
}

/// What `git log -1 -- <path>` finds: from HEAD, follow a parent the path is the same in
/// (history simplification), until a commit changed it.
fn latest_commit(path: &str) -> Option<String> {
    let mut commit = commit_of("HEAD")?;
    loop {
        let here = blob_at(&commit, path);
        let parents = parents(&commit);
        if parents.is_empty() {
            return here.map(|_| commit.id.to_string());
        }
        match parents.into_iter().find(|p| blob_at(p, path) == here) {
            Some(same) => commit = same,
            None => return Some(commit.id.to_string()),
        }
    }
}

fn commit_time(commit: &gix::Commit<'static>) -> i64 {
    commit.time().map(|t| t.seconds).unwrap_or(0)
}

/// Every commit reachable from `start`, newest first by committer time, as rev-list orders them.
fn reachable(start: gix::Commit<'static>) -> Vec<gix::Commit<'static>> {
    let (mut seen, mut stack, mut all) = (HashSet::new(), vec![start], Vec::new());
    while let Some(commit) = stack.pop() {
        if seen.insert(commit.id) {
            stack.extend(parents(&commit));
            all.push(commit);
        }
    }
    all.sort_by_key(|c| std::cmp::Reverse(commit_time(c)));
    all
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
        files(paths)
    }

    fn latest_commits(paths: Vec<String>) -> Vec<(String, String)> {
        paths.into_iter().filter_map(|p| latest_commit(&p).map(|c| (p, c))).collect()
    }

    fn commit(revision: String) -> Option<CommitInfo> {
        let commit = commit_of(&revision)?;
        let author = commit.author().ok()?;
        let time = author.time().ok()?.format(gix::date::time::format::ISO8601_STRICT).ok()?;
        let subject = commit.message().ok()?.summary().to_string();
        Some(CommitInfo { id: commit.id.to_string(), author: author.name.to_string(), time, subject })
    }

    fn resolve(name: String) -> Option<Reference> {
        let reference = repo()?.find_reference(name.as_str()).ok()?;
        let symbolic = reference.target().try_name().map(|n| n.as_bstr().to_string());
        let target = reference.into_fully_peeled_id().ok()?.to_string();
        Some(Reference { target, symbolic })
    }

    fn branches() -> Vec<String> {
        let Some(repo) = repo() else { return Vec::new() };
        let Ok(platform) = repo.references() else { return Vec::new() };
        let Ok(locals) = platform.local_branches() else { return Vec::new() };
        let mut names: Vec<String> = locals.filter_map(Result::ok).map(|r| r.name().shorten().to_string()).collect();
        names.sort();
        names
    }

    fn config(key: String) -> Option<String> {
        repo()?.config_snapshot().string(key.as_str()).map(|v| v.to_string())
    }

    fn first_parents(revision: String) -> Vec<String> {
        let mut out = Vec::new();
        let mut next = commit_of(&revision);
        while let Some(commit) = next {
            out.push(commit.id.to_string());
            next = parents(&commit).into_iter().next();
        }
        out
    }

    fn ancestry_path(ancestor: String, revision: String) -> Vec<String> {
        let (Some(start), Ok(from)) = (commit_of(&revision), gix::ObjectId::from_hex(ancestor.as_bytes())) else {
            return Vec::new();
        };
        let commits = reachable(start);
        let parents_of: HashMap<gix::ObjectId, Vec<gix::ObjectId>> =
            commits.iter().map(|c| (c.id, parents(c).into_iter().map(|p| p.id).collect())).collect();
        let mut descends: HashMap<gix::ObjectId, bool> = HashMap::new();
        fn reaches(id: gix::ObjectId, from: gix::ObjectId, parents: &HashMap<gix::ObjectId, Vec<gix::ObjectId>>,
                   memo: &mut HashMap<gix::ObjectId, bool>) -> bool {
            if let Some(known) = memo.get(&id) {
                return *known;
            }
            memo.insert(id, false); // history has no cycles; this only stops a revisit
            let answer = parents.get(&id).into_iter().flatten().any(|p| *p == from || reaches(*p, from, parents, memo));
            memo.insert(id, answer);
            answer
        }
        commits.iter().filter(|c| c.id != from && reaches(c.id, from, &parents_of, &mut descends))
            .map(|c| c.id.to_string()).collect()
    }
}

export!(Git);
