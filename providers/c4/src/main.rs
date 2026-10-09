//! `rdm-c4 <workspace.dsl>`: the workspace exported and drawn, as one JSON
//! object on stdout: `{"workspace": <the exported model>, "views": {<key>: <svg>}}`.
//! A workspace structurizrx rejects exits 1 with the reason on stderr.
use std::io::Write;

fn main() {
    let path = match std::env::args().nth(1) {
        Some(p) => p,
        None => {
            eprintln!("usage: rdm-c4 <workspace.dsl>");
            std::process::exit(2);
        }
    };
    match rdm_c4::draw(&path) {
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
