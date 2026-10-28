//! What adopt does with the skeleton, given what is already there
//! (spec-0079 to spec-0083): every destination with anything at it, a
//! symbolic link included, is kept; the rest is written; a kept .gitignore
//! names the lines init writes to it.

use alloc::string::String;
use alloc::vec::Vec;

#[derive(Debug, PartialEq)]
pub struct Plan {
    pub write: Vec<String>,
    pub keep: Vec<String>,
    pub ignore_lines: Vec<String>,
}

/// skeleton: the skeleton's paths, in order; exists: whether anything is at
/// a destination; ignore_lines: the lines init writes to .gitignore.
pub fn plan(skeleton: &[String], exists: &dyn Fn(&str) -> bool, ignore_lines: &[String]) -> Plan {
    let mut p = Plan { write: Vec::new(), keep: Vec::new(), ignore_lines: Vec::new() };
    for path in skeleton {
        if exists(path) {
            if path == ".gitignore" {
                p.ignore_lines = ignore_lines.to_vec();
            }
            p.keep.push(path.clone());
        } else {
            p.write.push(path.clone());
        }
    }
    p
}

#[cfg(test)]
mod tests {
    use super::*;
    use alloc::string::ToString;
    use alloc::vec;

    fn s(v: &[&str]) -> Vec<String> {
        v.iter().map(|x| x.to_string()).collect()
    }

    #[test]
    fn writes_what_is_missing_and_keeps_what_is_there() {
        let p = plan(&s(&["a", "dhf/spec/glossary.md", ".gitignore"]), &|p| p == "dhf/spec/glossary.md", &s(&["site/"]));
        assert_eq!(p, Plan { write: s(&["a", ".gitignore"]), keep: s(&["dhf/spec/glossary.md"]), ignore_lines: vec![] });
    }

    #[test]
    fn a_kept_ignore_file_names_the_lines_init_writes() {
        let p = plan(&s(&[".gitignore"]), &|_| true, &s(&["site/", "site-src/"]));
        assert_eq!(p.keep, s(&[".gitignore"]));
        assert_eq!(p.ignore_lines, s(&["site/", "site-src/"]));
        assert!(p.write.is_empty());
    }
}
