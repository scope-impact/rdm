//! Component Adopt of the project context (dhf/spec/01-start-a-project/
//! adopt.feature): adds the skeleton to an existing repository, never
//! overwriting a file.
#![cfg_attr(target_arch = "wasm32", no_std)]
extern crate alloc;

pub mod plan;


#[cfg(target_arch = "wasm32")]
mod component {
    use crate::plan::plan;
    use alloc::string::String;
    use alloc::vec::Vec;
    use rdm_kernel::path::{join, parent};
    use rdm_kernel::sys;
    rdm_kernel::bindings!("rdm:spec/project-adopt-component");
    use exports::rdm::spec::project_adopt::{Adopted, Guest};
    use rdm::spec::{project_hooks, project_init};

    struct Adopt;
    export!(Adopt);

    impl Guest for Adopt {
        async fn adopt(root: String) -> Result<Adopted, String> {
            let root = sys::absolute(&root);
            // The hook first: it refuses, writing nothing, outside a git
            // repository (spec-0084), before any file of the skeleton.
            let hook = project_hooks::install(root.clone()).await?;
            let skeleton = project_init::skeleton("spec");
            let paths: Vec<String> = skeleton.iter().map(|f| f.path.clone()).collect();
            let mut there = Vec::new();
            for p in &paths {
                if sys::exists(&join(&root, p)).await? {
                    there.push(p.clone());
                }
            }
            let ignore: Vec<String> = skeleton
                .iter()
                .find(|f| f.path == ".gitignore")
                .map(|f| f.content.lines().filter(|l| !l.is_empty() && !l.starts_with('#')).map(String::from).collect())
                .unwrap_or_default();
            let todo = plan(&paths, &|p| there.iter().any(|t| t == p), &ignore);
            for p in &todo.write {
                let file = join(&root, p);
                let content = &skeleton.iter().find(|f| &f.path == p).expect("a skeleton file").content;
                sys::create_dir_all(&parent(&file)).await?;
                sys::write_new(&file, content.as_bytes()).await?;
            }
            let mut wrote = todo.write;
            wrote.extend(hook.wrote);
            let mut kept = todo.keep;
            kept.extend(hook.kept);
            wrote.sort();
            kept.sort();
            Ok(Adopted { wrote, kept, ignore_lines: todo.ignore_lines })
        }
    }
}
