//! `rdm-typst <directory> <main>`: every file under the directory (paths relative
//! to it) compiled by Typst from `main`, the PDF on stdout. A layout Typst
//! rejects exits 1 with its messages on stderr.
use std::io::Write;
use std::path::Path;

fn files_under(root: &Path, dir: &Path, out: &mut Vec<(String, Vec<u8>)>) -> std::io::Result<()> {
    for entry in std::fs::read_dir(dir)? {
        let path = entry?.path();
        if path.is_dir() {
            files_under(root, &path, out)?;
        } else {
            let rel = path.strip_prefix(root).expect("under root").to_string_lossy().replace('\\', "/");
            out.push((rel, std::fs::read(&path)?));
        }
    }
    Ok(())
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 3 {
        eprintln!("usage: rdm-typst <directory> <main>");
        std::process::exit(2);
    }
    let root = Path::new(&args[1]);
    let mut files = Vec::new();
    if let Err(error) = files_under(root, root, &mut files) {
        eprintln!("{}: {error}", root.display());
        std::process::exit(2);
    }
    match rdm_typst::typeset(&args[2], files) {
        Ok(pdf) => std::io::stdout().lock().write_all(&pdf).expect("stdout"),
        Err(error) => {
            eprintln!("{error}");
            std::process::exit(1);
        }
    }
}
