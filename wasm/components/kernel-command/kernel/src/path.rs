//! Paths as the command is given them: relative to the folder it runs in, or
//! absolute (spec-0272), normalised without touching the file system.

use alloc::string::String;
use alloc::vec::Vec;

/// The path made absolute against cwd, with "." and ".." resolved.
pub fn absolute(cwd: &str, p: &str) -> String {
    let joined = if p.starts_with('/') { String::from(p) } else { alloc::format!("{cwd}/{p}") };
    let mut parts: Vec<&str> = Vec::new();
    for part in joined.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                parts.pop();
            }
            other => parts.push(other),
        }
    }
    let mut out = String::new();
    for part in parts {
        out.push('/');
        out.push_str(part);
    }
    if out.is_empty() { String::from("/") } else { out }
}

/// A path under a folder: folder/rel.
pub fn join(dir: &str, rel: &str) -> String {
    if dir.ends_with('/') { alloc::format!("{dir}{rel}") } else { alloc::format!("{dir}/{rel}") }
}

/// The folder holding a path ("/" for the root).
pub fn parent(p: &str) -> String {
    match p.trim_end_matches('/').rsplit_once('/') {
        Some(("", _)) | None => String::from("/"),
        Some((dir, _)) => String::from(dir),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn resolves_relative_and_absolute_paths() {
        assert_eq!(absolute("/w/room", "sauna"), "/w/room/sauna");
        assert_eq!(absolute("/w/room", "./a/../sauna/"), "/w/room/sauna");
        assert_eq!(absolute("/w/room", "/abs/sauna"), "/abs/sauna");
        assert_eq!(absolute("/", ".."), "/");
        assert_eq!(join("/a", "b/c"), "/a/b/c");
        assert_eq!(parent("/a/b/c"), "/a/b");
        assert_eq!(parent("/a"), "/");
    }
}
