//! How a task's outcome is reported (dhf/spec/README.md, "How every task
//! reports"): a finding or a warning as one line of the error stream.

use alloc::format;
use alloc::string::String;

/// "FILE:LINE: RULE: what", FILE and LINE left out where none applies; with
/// "warning: " before RULE for a warning.
pub fn line(file: Option<&str>, line: Option<u32>, rule: &str, what: &str, warning: bool) -> String {
    let w = if warning { "warning: " } else { "" };
    let what = what.replace('\n', " ");
    match (file, line) {
        (Some(f), Some(l)) => format!("{f}:{l}: {w}{rule}: {what}"),
        (Some(f), None) => format!("{f}: {w}{rule}: {what}"),
        _ => format!("{w}{rule}: {what}"),
    }
}

/// "cannot-run: why", one line.
pub fn cannot_run(why: &str) -> String {
    format!("cannot-run: {}", why.replace('\n', " "))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_each_shape_of_line() {
        assert_eq!(line(Some("a.feature"), Some(3), "missing-level", "no level", false), "a.feature:3: missing-level: no level");
        assert_eq!(line(Some(".gitignore"), None, "adopt-kept", "kept", true), ".gitignore: warning: adopt-kept: kept");
        assert_eq!(line(None, None, "r", "a\nb", true), "warning: r: a b");
        assert_eq!(cannot_run("x\ny"), "cannot-run: x y");
    }
}
