//! `rdm-c4 [workspace.dsl]`: the workspace's DSL text (the file, or stdin when no
//! file or `-` is given; includes inlined) exported and drawn, as one JSON object
//! on stdout: `{"workspace": <the exported model>, "views": {<key>: <svg>}}`.
//! A workspace structurizrx rejects exits 1 with the reason on stderr.
use std::io::{Read, Write};

fn main() {
    let dsl = match std::env::args().nth(1).filter(|a| a != "-") {
        Some(path) => std::fs::read_to_string(&path).unwrap_or_else(|e| {
            eprintln!("{path}: {e}");
            std::process::exit(2);
        }),
        None => {
            let mut text = String::new();
            std::io::stdin().read_to_string(&mut text).expect("stdin");
            text
        }
    };
    match rdm_c4::draw(&dsl) {
        Ok(drawing) => {
            let workspace: serde_json::Value = serde_json::from_str(&drawing.workspace_json).expect("own JSON");
            let views: serde_json::Map<String, serde_json::Value> =
                drawing.views.into_iter().map(|v| (v.key, serde_json::Value::String(v.svg))).collect();
            let out = serde_json::json!({ "workspace": workspace, "views": views });
            let mut stdout = std::io::stdout().lock();
            serde_json::to_writer(&mut stdout, &out).expect("stdout");
            stdout.write_all(b"\n").expect("stdout");
        }
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(1);
        }
    }
}
