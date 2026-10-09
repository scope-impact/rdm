//! RDM's typeset provider (DI-88): one implementation, built as the native
//! program `rdm-typst` for the command line and, with the `component` feature,
//! as the WASI component that exports `rdm:component/typeset`. The layout, its
//! data and attachments are compiled to PDF by Typst's own crates, with the
//! fonts the report's layout names embedded (fonts/, OFL), so the PDF never
//! depends on the fonts of the machine that makes it.
use std::collections::HashMap;

use typst::diag::{FileError, FileResult};
use typst::foundations::{Bytes, Datetime};
use typst::syntax::{FileId, Source, VirtualPath};
use typst::text::{Font, FontBook};
use typst::utils::LazyHash;
use typst::{Library, LibraryExt, World};

/// The fonts the verification report's layout names, embedded.
const FONTS: &[&[u8]] = &[
    include_bytes!("../fonts/NunitoSans-400.ttf"),
    include_bytes!("../fonts/NunitoSans-400-italic.ttf"),
    include_bytes!("../fonts/NunitoSans-600.ttf"),
    include_bytes!("../fonts/NunitoSans-700.ttf"),
    include_bytes!("../fonts/NunitoSans-700-italic.ttf"),
    include_bytes!("../fonts/JetBrainsMono-Regular.ttf"),
    include_bytes!("../fonts/JetBrainsMono-Medium.ttf"),
    include_bytes!("../fonts/JetBrainsMono-Bold.ttf"),
    include_bytes!("../fonts/JetBrainsMono-Italic.ttf"),
];

struct Files {
    library: LazyHash<Library>,
    book: LazyHash<FontBook>,
    fonts: Vec<Font>,
    main: FileId,
    files: HashMap<FileId, Bytes>,
}

impl World for Files {
    fn library(&self) -> &LazyHash<Library> {
        &self.library
    }
    fn book(&self) -> &LazyHash<FontBook> {
        &self.book
    }
    fn main(&self) -> FileId {
        self.main
    }
    fn source(&self, id: FileId) -> FileResult<Source> {
        let bytes = self.file(id)?;
        let text = String::from_utf8(bytes.to_vec()).map_err(|_| FileError::InvalidUtf8)?;
        Ok(Source::new(id, text))
    }
    fn file(&self, id: FileId) -> FileResult<Bytes> {
        self.files.get(&id).cloned().ok_or_else(|| FileError::NotFound(id.vpath().as_rootless_path().into()))
    }
    fn font(&self, index: usize) -> Option<Font> {
        self.fonts.get(index).cloned()
    }
    fn today(&self, _offset: Option<i64>) -> Option<Datetime> {
        None // the report carries its own dates; the PDF is the same whenever it is made
    }
}

/// Compile `main` (a path among `files`, each path relative to the layout's root) to a PDF.
pub fn typeset(main: &str, files: Vec<(String, Vec<u8>)>) -> Result<Vec<u8>, String> {
    let mut book = FontBook::new();
    let mut fonts = Vec::new();
    for data in FONTS {
        for font in Font::iter(Bytes::new(*data)) {
            book.push(font.info().clone());
            fonts.push(font);
        }
    }
    let files: HashMap<FileId, Bytes> = files
        .into_iter()
        .map(|(path, data)| (FileId::new(None, VirtualPath::new(&path)), Bytes::new(data)))
        .collect();
    let main = FileId::new(None, VirtualPath::new(main));
    if !files.contains_key(&main) {
        return Err(format!("no file {} among the files given", main.vpath().as_rootless_path().display()));
    }
    let world = Files { library: LazyHash::new(Library::default()), book: LazyHash::new(book), fonts, main, files };
    let document = typst::compile::<typst::layout::PagedDocument>(&world)
        .output
        .map_err(|errors| errors.iter().map(|e| e.message.to_string()).collect::<Vec<_>>().join("\n"))?;
    typst_pdf::pdf(&document, &typst_pdf::PdfOptions::default())
        .map_err(|errors| errors.iter().map(|e| e.message.to_string()).collect::<Vec<_>>().join("\n"))
}

#[cfg(feature = "component")]
mod component {
    wit_bindgen::generate!({ path: "../../wit", world: "rdm:component/typesetter" });
    use exports::rdm::component::typeset::{File, Guest};

    struct Typeset;

    impl Guest for Typeset {
        fn typeset(main: String, files: Vec<File>) -> Result<Vec<u8>, String> {
            crate::typeset(&main, files.into_iter().map(|f| (f.path, f.data)).collect())
        }
    }

    export!(Typeset);
}
