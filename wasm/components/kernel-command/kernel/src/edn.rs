//! EDN, the format of every record and result rdm-spec reads and prints
//! (dhf/spec/README.md, "How every task reports"): a reader for what the
//! configuration and the spec hold, and a printer for results.

use alloc::boxed::Box;
use alloc::format;
use alloc::string::{String, ToString};
use alloc::vec::Vec;

#[derive(Clone, Debug, PartialEq)]
pub enum Edn {
    Nil,
    Bool(bool),
    Int(i64),
    /// A number that is not an integer, kept as written.
    Number(String),
    Str(String),
    Char(char),
    /// Without its colon.
    Keyword(String),
    Symbol(String),
    List(Vec<Edn>),
    Vector(Vec<Edn>),
    Map(Vec<(Edn, Edn)>),
    Set(Vec<Edn>),
    Tagged(String, Box<Edn>),
}

impl Edn {
    pub fn kw(name: &str) -> Edn {
        Edn::Keyword(name.to_string())
    }

    pub fn str(s: &str) -> Edn {
        Edn::Str(s.to_string())
    }

    pub fn strs<S: AsRef<str>>(items: &[S]) -> Edn {
        Edn::Vector(items.iter().map(|s| Edn::str(s.as_ref())).collect())
    }

    /// A map with keyword keys, in the order given.
    pub fn map(entries: Vec<(&str, Edn)>) -> Edn {
        Edn::Map(entries.into_iter().map(|(k, v)| (Edn::kw(k), v)).collect())
    }

    /// The value of a keyword key of a map.
    pub fn get(&self, key: &str) -> Option<&Edn> {
        match self {
            Edn::Map(entries) => entries
                .iter()
                .find(|(k, _)| matches!(k, Edn::Keyword(n) if n == key))
                .map(|(_, v)| v),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Edn::Str(s) => Some(s),
            _ => None,
        }
    }

    pub fn items(&self) -> &[Edn] {
        match self {
            Edn::Vector(v) | Edn::List(v) | Edn::Set(v) => v,
            _ => &[],
        }
    }
}

/// Reads one EDN value from text, ignoring what follows it but whitespace and
/// comments; an error says what is wrong and on which line.
pub fn read(text: &str) -> Result<Edn, String> {
    let mut r = Reader { s: text.as_bytes(), i: 0, text };
    r.skip();
    if r.i >= r.s.len() {
        return Err("no value".into());
    }
    let v = r.value()?;
    r.skip();
    if r.i < r.s.len() {
        return Err(format!("line {}: more than one value", r.line()));
    }
    Ok(v)
}

struct Reader<'a> {
    s: &'a [u8],
    i: usize,
    text: &'a str,
}

fn is_delim(c: u8) -> bool {
    c.is_ascii_whitespace() || matches!(c, b',' | b'(' | b')' | b'[' | b']' | b'{' | b'}' | b'"' | b';')
}

impl Reader<'_> {
    fn line(&self) -> usize {
        1 + self.s[..self.i.min(self.s.len())].iter().filter(|c| **c == b'\n').count()
    }

    fn err<T>(&self, what: &str) -> Result<T, String> {
        Err(format!("line {}: {what}", self.line()))
    }

    fn skip(&mut self) {
        loop {
            while self.i < self.s.len() && (self.s[self.i].is_ascii_whitespace() || self.s[self.i] == b',') {
                self.i += 1;
            }
            if self.i < self.s.len() && self.s[self.i] == b';' {
                while self.i < self.s.len() && self.s[self.i] != b'\n' {
                    self.i += 1;
                }
                continue;
            }
            if self.s[self.i..].starts_with(b"#_") {
                self.i += 2;
                self.skip();
                if self.value().is_err() {
                    return;
                }
                continue;
            }
            return;
        }
    }

    fn seq(&mut self, close: u8) -> Result<Vec<Edn>, String> {
        let mut items = Vec::new();
        loop {
            self.skip();
            if self.i >= self.s.len() {
                return self.err("unclosed collection");
            }
            if self.s[self.i] == close {
                self.i += 1;
                return Ok(items);
            }
            items.push(self.value()?);
        }
    }

    fn token(&mut self) -> &str {
        let start = self.i;
        while self.i < self.s.len() && !is_delim(self.s[self.i]) {
            self.i += 1;
        }
        &self.text[start..self.i]
    }

    fn value(&mut self) -> Result<Edn, String> {
        self.skip();
        let Some(&c) = self.s.get(self.i) else { return self.err("unexpected end") };
        match c {
            b'(' => {
                self.i += 1;
                Ok(Edn::List(self.seq(b')')?))
            }
            b'[' => {
                self.i += 1;
                Ok(Edn::Vector(self.seq(b']')?))
            }
            b'{' => {
                self.i += 1;
                let items = self.seq(b'}')?;
                if items.len() % 2 != 0 {
                    return self.err("a map with an odd number of forms");
                }
                let mut entries = Vec::new();
                let mut it = items.into_iter();
                while let (Some(k), Some(v)) = (it.next(), it.next()) {
                    entries.push((k, v));
                }
                Ok(Edn::Map(entries))
            }
            b'#' => {
                self.i += 1;
                match self.s.get(self.i) {
                    Some(b'{') => {
                        self.i += 1;
                        Ok(Edn::Set(self.seq(b'}')?))
                    }
                    Some(_) => {
                        let tag = self.token().to_string();
                        if tag.is_empty() {
                            return self.err("a # with no tag");
                        }
                        let v = self.value()?;
                        Ok(Edn::Tagged(tag, Box::new(v)))
                    }
                    None => self.err("unexpected end after #"),
                }
            }
            b'"' => self.string(),
            b'\\' => {
                self.i += 1;
                let start = self.i;
                // A character is at least one byte, even a delimiter.
                if self.i < self.s.len() {
                    self.i += 1;
                }
                while self.i < self.s.len() && !is_delim(self.s[self.i]) {
                    self.i += 1;
                }
                let t = &self.text[start..self.i];
                let ch = match t {
                    "newline" => '\n',
                    "space" => ' ',
                    "tab" => '\t',
                    "return" => '\r',
                    _ => t.chars().next().unwrap_or(' '),
                };
                Ok(Edn::Char(ch))
            }
            b')' | b']' | b'}' => self.err("an unmatched closing delimiter"),
            b':' => {
                self.i += 1;
                let t = self.token();
                if t.is_empty() {
                    return self.err("a keyword with no name");
                }
                Ok(Edn::Keyword(t.to_string()))
            }
            _ => {
                let t = self.token().to_string();
                if t.is_empty() {
                    return self.err("an unexpected character");
                }
                Ok(match t.as_str() {
                    "nil" => Edn::Nil,
                    "true" => Edn::Bool(true),
                    "false" => Edn::Bool(false),
                    _ => {
                        let first = t.as_bytes()[0];
                        let numeric = first.is_ascii_digit()
                            || ((first == b'-' || first == b'+') && t.len() > 1 && t.as_bytes()[1].is_ascii_digit());
                        if numeric {
                            match t.parse::<i64>() {
                                Ok(n) => Edn::Int(n),
                                Err(_) => Edn::Number(t),
                            }
                        } else {
                            Edn::Symbol(t)
                        }
                    }
                })
            }
        }
    }

    fn string(&mut self) -> Result<Edn, String> {
        self.i += 1;
        let mut out = String::new();
        let start_line = self.line();
        loop {
            let Some(&c) = self.s.get(self.i) else {
                return Err(format!("line {start_line}: an unterminated string"));
            };
            match c {
                b'"' => {
                    self.i += 1;
                    return Ok(Edn::Str(out));
                }
                b'\\' => {
                    let Some(&e) = self.s.get(self.i + 1) else { return self.err("an unterminated string") };
                    self.i += 2;
                    match e {
                        b'n' => out.push('\n'),
                        b't' => out.push('\t'),
                        b'r' => out.push('\r'),
                        b'"' => out.push('"'),
                        b'\\' => out.push('\\'),
                        b'u' => {
                            let hex = self.text.get(self.i..self.i + 4).unwrap_or("");
                            let ch = u32::from_str_radix(hex, 16).ok().and_then(char::from_u32);
                            match ch {
                                Some(ch) => out.push(ch),
                                None => return self.err("a bad \\u escape"),
                            }
                            self.i += 4;
                        }
                        _ => return self.err("an unknown escape in a string"),
                    }
                }
                _ => {
                    let rest = &self.text[self.i..];
                    let ch = rest.chars().next().unwrap_or(' ');
                    out.push(ch);
                    self.i += ch.len_utf8();
                }
            }
        }
    }
}

/// Prints a value as Clojure's `pr-str` would.
pub fn print(v: &Edn) -> String {
    let mut out = String::new();
    write(v, &mut out);
    out
}

pub fn quote(s: &str) -> String {
    let mut out = String::new();
    write_str(s, &mut out);
    out
}

fn write_str(s: &str, out: &mut String) {
    out.push('"');
    for ch in s.chars() {
        match ch {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\t' => out.push_str("\\t"),
            '\r' => out.push_str("\\r"),
            _ => out.push(ch),
        }
    }
    out.push('"');
}

fn write_all(items: &[Edn], sep: &str, out: &mut String) {
    for (n, item) in items.iter().enumerate() {
        if n > 0 {
            out.push_str(sep);
        }
        write(item, out);
    }
}

fn write(v: &Edn, out: &mut String) {
    match v {
        Edn::Nil => out.push_str("nil"),
        Edn::Bool(b) => out.push_str(if *b { "true" } else { "false" }),
        Edn::Int(n) => out.push_str(&n.to_string()),
        Edn::Number(n) => out.push_str(n),
        Edn::Str(s) => write_str(s, out),
        Edn::Char(c) => match c {
            '\n' => out.push_str("\\newline"),
            ' ' => out.push_str("\\space"),
            '\t' => out.push_str("\\tab"),
            '\r' => out.push_str("\\return"),
            c => {
                out.push('\\');
                out.push(*c);
            }
        },
        Edn::Keyword(k) => {
            out.push(':');
            out.push_str(k);
        }
        Edn::Symbol(s) => out.push_str(s),
        Edn::List(items) => {
            out.push('(');
            write_all(items, " ", out);
            out.push(')');
        }
        Edn::Vector(items) => {
            out.push('[');
            write_all(items, " ", out);
            out.push(']');
        }
        Edn::Set(items) => {
            out.push_str("#{");
            write_all(items, " ", out);
            out.push('}');
        }
        Edn::Map(entries) => {
            out.push('{');
            for (n, (k, v)) in entries.iter().enumerate() {
                if n > 0 {
                    out.push_str(", ");
                }
                write(k, out);
                out.push(' ');
                write(v, out);
            }
            out.push('}');
        }
        Edn::Tagged(tag, v) => {
            out.push('#');
            out.push_str(tag);
            out.push(' ');
            write(v, out);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use alloc::vec;

    #[test]
    fn reads_a_configuration_with_comments_and_nesting() {
        let v = read(";; c\n{:id-prefix \"spec\" ; x\n :verify [{:kind :tests :command [\"sh\" \"t.sh\"]}]\n :n 3 #_ :gone :s #{1 2}}").unwrap();
        assert_eq!(v.get("id-prefix"), Some(&Edn::str("spec")));
        assert_eq!(v.get("n"), Some(&Edn::Int(3)));
        assert_eq!(v.get("gone"), None);
        let check = &v.get("verify").unwrap().items()[0];
        assert_eq!(check.get("kind"), Some(&Edn::kw("tests")));
        assert_eq!(v.get("s"), Some(&Edn::Set(vec![Edn::Int(1), Edn::Int(2)])));
    }

    #[test]
    fn refuses_what_is_not_edn() {
        assert!(read("{:a").is_err());
        assert!(read("{:a 1 :b}").is_err());
        assert!(read("\"open").is_err());
        assert!(read("").is_err());
        assert!(read("1 2").is_err());
    }

    #[test]
    fn prints_what_it_reads() {
        let text = "{:help [{:task \"help\", :usage \"a \\\"q\\\"\\n\"} nil true -3 :k/w #{1} (x)]}";
        let v = read(text).unwrap();
        assert_eq!(print(&v), text);
        assert_eq!(read(&print(&v)).unwrap(), v);
    }

    #[test]
    fn reads_strings_with_unicode() {
        assert_eq!(read("\"Löyly \\u00e4\"").unwrap(), Edn::str("Löyly ä"));
    }
}
